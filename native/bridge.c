/*
 * Link Play native core, shared by the Android app and the desktop app (macOS, Windows): one GBA
 * (mGBA) on its own thread, the NetLink driver (netlink.c) for link play over the network, and
 * TouchNav (touchnav.c) turning taps / drags on the picture into menu moves.
 *   Android (__ANDROID__): audio through AAudio, video through a small GLES3 renderer
 *     (android/app/src/main/cpp/render.c, which calls the bridgeFrame* functions at the bottom).
 *   Desktop: no audio device and no GL in here. The Java side pulls PCM with audioRead and the
 *     picture with copyFrameRgb. Windows and macOS differ only in the small helpers under
 *     "portability helpers" (clock, sleep, file replace); desktop never reads the user's own
 *     mGBA config.
 * Built by android/app/src/main/cpp/CMakeLists.txt (libgbalink.so) and desktop/native/CMakeLists.txt
 * (libgbalink.dylib / gbalink.dll). Everything Java-facing is in com.linkplay.app.Native.
 */
#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <android/api-level.h>
#include "render.h"
#endif

// First of the mGBA headers on purpose: on Windows it includes winsock2.h before anything can pull in
// windows.h (the other order breaks with redefinition errors).
#include <mgba-util/common.h>

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifndef _WIN32
#include <unistd.h>
#endif

#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/serialize.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/savedata.h>
#include <mgba-util/audio-buffer.h>
#include <mgba-util/audio-resampler.h>
#include <mgba-util/vfs.h>

#include "netlink.h"
#include "touchnav.h"
#ifdef _WIN32
#include <windows.h> // already in via winsock2.h (mgba-util/common.h); explicit for what is called here
#include <mmsystem.h> // timeBeginPeriod / timeEndPeriod (winmm)
#endif

#define TAG "LinkPlay"
#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
// Desktop: stderr (where that ends up is up to the launcher).
static void _logf(const char* level, const char* format, ...) {
	va_list args;
	va_start(args, format);
	fprintf(stderr, "%s/%s: ", level, TAG);
	vfprintf(stderr, format, args);
	fputc('\n', stderr);
	va_end(args);
}
#define LOGI(...) _logf("I", __VA_ARGS__)
#define LOGE(...) _logf("E", __VA_ARGS__)
#endif

#define FRAME_NS 16742706LL /* 280896 cycles at 16.78 MHz: 59.73 fps */
#define AUDIO_RATE 48000
#define AUDIO_RING_FRAMES 16384
#define AUDIO_MAX_LATENCY (AUDIO_RATE * 12 / 100) /* keep at most 120 ms queued */
#define CABLE_NONE (-1)
#define CABLE_DETACH (-2)
#if defined(__ANDROID__) || defined(__linux__)
#define OUT_COND_MONOTONIC /* pthread_condattr_setclock exists (not on macOS; winpthreads ignores it) */
#endif

static struct {
	struct mCore* core;
	mColor* video;
	unsigned width, height;
	pthread_t thread;
	bool threadStarted;
	atomic_bool paused, quit, flushRequested;

	// Savestate requests from other threads, carried out on the emulation thread between frames.
	pthread_mutex_t stateMutex;
	pthread_cond_t stateCond;
	int stateOp;         // STATE_NONE / STATE_SAVE / STATE_LOAD
	int stateResult;     // -1 pending, 0 failed, 1 done
	char statePath[512];
	atomic_uint keys, latched;

	pthread_mutex_t frameMutex;
	uint32_t* frame;
	bool frameNew;
	atomic_ullong frameCount;

	struct mAudioBuffer audioOut;
	struct mAudioResampler resampler;
	unsigned sourceRate;
#ifdef __ANDROID__
	AAudioStream* stream;
#endif
	pthread_mutex_t audioMutex;
	int16_t ring[AUDIO_RING_FRAMES * 2];
	unsigned ringHead, ringCount;
	_Atomic float volume;
	atomic_int speed;

	struct NetLink net;
	bool netAttached;
	atomic_int pendingCable;    // CABLE_NONE, CABLE_DETACH, or the link id to attach as
	atomic_int pendingLinkId;   // -1 = none
	atomic_bool pendingUnplug;
	bool syncInit;              // mutexes/conds below are set up once, never re-initialised
	pthread_mutex_t outMutex;
	pthread_cond_t outCond;     // signalled by _queueOut
	uint8_t out[64 * 1024];
	size_t outLen;

	struct TouchNav touch;
	atomic_bool touchReady;

} G = { .volume = 0.8f, .speed = 1 };

// ---------------------------------------------------------------- logging

static void _logCallback(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
	(void) logger;
	if (!(level & (mLOG_FATAL | mLOG_ERROR))) {
		return;
	}
	char buf[512];
	vsnprintf(buf, sizeof(buf), format, args);
	LOGE("[%s] %s", mLogCategoryName(category), buf);
}
static struct mLogger logger = { .log = _logCallback };

// ---------------------------------------------------------------- NetLink plumbing

static void _queueOut(uint8_t kind, const uint16_t* words, int count) {
	pthread_mutex_lock(&G.outMutex);
	size_t need = 2 + count * 2;
	if (G.outLen + need <= sizeof(G.out)) {
		G.out[G.outLen] = kind;
		G.out[G.outLen + 1] = 0;
		memcpy(&G.out[G.outLen + 2], words, count * 2);
		G.outLen += need;
		pthread_cond_signal(&G.outCond);
	}
	pthread_mutex_unlock(&G.outMutex);
}

static void _sendHandshake(struct NetLink* nl, uint16_t word) {
	(void) nl;
	_queueOut(NETLINK_EV_HANDSHAKE, &word, 1);
}

static void _sendCommand(struct NetLink* nl, const uint16_t cmd[NETLINK_CMD_WORDS]) {
	(void) nl;
	_queueOut(NETLINK_EV_ENTRY, cmd, NETLINK_CMD_WORDS);
}

static void _dropOutput(void) {
	pthread_mutex_lock(&G.outMutex);
	G.outLen = 0;
	pthread_mutex_unlock(&G.outMutex);
}

// Runs on the emulation thread between frames, where touching the link port is safe.
static void _applyNetCommands(void) {
	// Last request wins: leaving one room and joining another while the game is
	// paused in the lobby queues both, and the new room needs a fresh cable.
	int cable = atomic_exchange(&G.pendingCable, CABLE_NONE);
	if (cable != CABLE_NONE) {
		_dropOutput(); // queued by the old cable for the old room
	}
	if (cable != CABLE_NONE && G.netAttached) {
		G.core->setPeripheral(G.core, mPERIPH_GBA_LINK_PORT, NULL);
		NetLinkDestroy(&G.net);
		G.netAttached = false;
		LOGI("link cable detached");
	}
	if (cable >= 0) {
		NetLinkCreate(&G.net, cable);
		G.net.sendHandshake = _sendHandshake;
		G.net.sendCommand = _sendCommand;
		G.core->setPeripheral(G.core, mPERIPH_GBA_LINK_PORT, &G.net.d);
		G.netAttached = true;
		LOGI("link cable attached as player %d", cable + 1);
	}
	int id = atomic_exchange(&G.pendingLinkId, -1);
	if (id >= 0 && G.netAttached) {
		NetLinkSetLocalId(&G.net, id);
	}
	if (atomic_exchange(&G.pendingUnplug, false) && G.netAttached) {
		NetLinkDisconnect(&G.net);
	}
}

// ---------------------------------------------------------------- audio

// Fills [frames] stereo frames from the ring (volume applied), the rest with silence; returns how
// many were real audio. The Android audio callback and the desktop Native.audioRead both call this.
static int32_t _audioPull(int16_t* out, int32_t frames) {
	float vol = atomic_load(&G.volume);
	pthread_mutex_lock(&G.audioMutex);
	int32_t i = 0;
	for (; i < frames && G.ringCount; ++i, --G.ringCount) {
		out[i * 2] = (int16_t) (G.ring[G.ringHead * 2] * vol);
		out[i * 2 + 1] = (int16_t) (G.ring[G.ringHead * 2 + 1] * vol);
		G.ringHead = (G.ringHead + 1) % AUDIO_RING_FRAMES;
	}
	pthread_mutex_unlock(&G.audioMutex);
	memset(&out[i * 2], 0, (frames - i) * 4); // underrun: silence
	return i;
}

#ifdef __ANDROID__
static aaudio_data_callback_result_t _audioCallback(AAudioStream* stream, void* user, void* data, int32_t frames) {
	(void) stream;
	(void) user;
	_audioPull(data, frames);
	return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void _openAudio(void) {
	AAudioStreamBuilder* builder;
	if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
		return;
	}
	AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
	AAudioStreamBuilder_setChannelCount(builder, 2);
	AAudioStreamBuilder_setSampleRate(builder, AUDIO_RATE);
	AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
	AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
	AAudioStreamBuilder_setDataCallback(builder, _audioCallback, NULL);
	if (AAudioStreamBuilder_openStream(builder, &G.stream) == AAUDIO_OK) {
		AAudioStream_requestStart(G.stream);
	} else {
		G.stream = NULL;
		LOGE("could not open audio");
	}
	AAudioStreamBuilder_delete(builder);
}
#endif

static void _pushAudio(void) {
	unsigned rate = G.core->audioSampleRate(G.core);
	if (rate != G.sourceRate) {
		G.sourceRate = rate;
		mAudioResamplerSetSource(&G.resampler, G.core->getAudioBuffer(G.core), rate, true);
	}
	mAudioResamplerProcess(&G.resampler);
	int16_t buf[2048 * 2];
	size_t n;
	while ((n = mAudioBufferRead(&G.audioOut, buf, 2048))) {
		pthread_mutex_lock(&G.audioMutex);
		for (size_t i = 0; i < n; ++i) {
			if (G.ringCount >= AUDIO_MAX_LATENCY) { // drop the oldest to keep latency low
				G.ringHead = (G.ringHead + 1) % AUDIO_RING_FRAMES;
				--G.ringCount;
			}
			unsigned tail = (G.ringHead + G.ringCount) % AUDIO_RING_FRAMES;
			G.ring[tail * 2] = buf[i * 2];
			G.ring[tail * 2 + 1] = buf[i * 2 + 1];
			++G.ringCount;
		}
		pthread_mutex_unlock(&G.audioMutex);
	}
}

// ---------------------------------------------------------------- emulation thread

static void _flushSave(void) {
	struct GBA* gba = G.core->board;
	struct GBASavedata* sd = &gba->memory.savedata;
	if (sd->vf && sd->data && (sd->mapMode & MAP_WRITE)) {
		sd->vf->sync(sd->vf, sd->data, GBASavedataSize(sd));
		GBASavedataRTCWrite(sd);
	}
}

// ---------------------------------------------------------------- portability helpers

#ifdef _WIN32
static long long _nowNs(void) {
	static LARGE_INTEGER freq;
	LARGE_INTEGER c;
	if (!freq.QuadPart) {
		QueryPerformanceFrequency(&freq);
	}
	QueryPerformanceCounter(&c);
	return (c.QuadPart / freq.QuadPart) * 1000000000LL + (c.QuadPart % freq.QuadPart) * 1000000000LL / freq.QuadPart;
}

// The default timer tick is 15.6 ms, almost as long as a whole frame, so a plain sleep wakes late and
// frames arrive in uneven pairs. Ask for 1 ms ticks while the emulation thread runs, and wait on a
// high-resolution timer where there is one (Windows 10 1803+; recent Windows may ignore the 1 ms request
// for a hidden window, the timer is the main path). Without it: sleep most of the wait, spin the rest.
#ifndef CREATE_WAITABLE_TIMER_HIGH_RESOLUTION
#define CREATE_WAITABLE_TIMER_HIGH_RESOLUTION 0x2 // older headers
#endif
static HANDLE _tickTimer; // owned by the emulation thread

static void _timerBegin(void) {
	timeBeginPeriod(1);
	_tickTimer = CreateWaitableTimerExW(NULL, NULL, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
}

static void _timerEnd(void) {
	if (_tickTimer) {
		CloseHandle(_tickTimer);
		_tickTimer = NULL;
	}
	timeEndPeriod(1);
}

// Emulation thread only (the timer handle is not shared).
static void _sleepNs(long long ns) {
	if (ns <= 0) {
		return;
	}
	if (_tickTimer) {
		LARGE_INTEGER due;
		due.QuadPart = -(ns / 100); // negative: relative, in 100 ns units
		if (SetWaitableTimer(_tickTimer, &due, 0, NULL, NULL, FALSE)) {
			WaitForSingleObject(_tickTimer, INFINITE);
			return;
		}
	}
	long long until = _nowNs() + ns;
	for (long long left = ns; left > 0; left = until - _nowNs()) {
		if (left > 2000000LL) {
			Sleep((DWORD) ((left - 1000000LL) / 1000000LL));
		} else {
			YieldProcessor();
		}
	}
}

// Any other thread: a plain sleep is precise enough.
static void _idleSleepMs(int ms) {
	Sleep(ms > 0 ? (DWORD) ms : 0);
}

// Absolute CLOCK_REALTIME-style deadline [ms] from now, for pthread_cond_timedwait on winpthreads
// (which has no pthread_condattr_setclock and reads the deadline as wall-clock time).
static void _deadlineIn(struct timespec* t, long long ms) {
	FILETIME ft;
	GetSystemTimeAsFileTime(&ft);
	unsigned long long u = ((unsigned long long) ft.dwHighDateTime << 32 | ft.dwLowDateTime) - 116444736000000000ULL;
	long long ns = (long long) (u % 10000000ULL) * 100 + ms * 1000000LL;
	t->tv_sec = (time_t) (u / 10000000ULL + ns / 1000000000LL);
	t->tv_nsec = (long) (ns % 1000000000LL);
}

static wchar_t* _wide(const char* utf8) {
	int n = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, NULL, 0);
	wchar_t* w = n > 0 ? malloc(n * sizeof(*w)) : NULL;
	if (w && !MultiByteToWideChar(CP_UTF8, 0, utf8, -1, w, n)) {
		free(w);
		w = NULL;
	}
	return w;
}

// rename() onto an existing file fails on Windows, and it only knows the ANSI code page.
static bool _replaceFile(const char* from, const char* to) {
	wchar_t* wf = _wide(from);
	wchar_t* wt = _wide(to);
	bool ok = wf && wt && MoveFileExW(wf, wt, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
	free(wf);
	free(wt);
	return ok;
}

static void _removeFile(const char* path) {
	wchar_t* w = _wide(path);
	if (w) {
		DeleteFileW(w);
		free(w);
	}
}
#else
static long long _nowNs(void) {
	struct timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return t.tv_sec * 1000000000LL + t.tv_nsec;
}

#ifdef __APPLE__
// The emulation thread produces a frame every 16.7 ms: tell the scheduler it is user-facing work, so it
// stays off the efficiency cores and its timers are not coalesced.
static void _timerBegin(void) {
	pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE, 0);
}
#else
static void _timerBegin(void) {}
#endif
static void _timerEnd(void) {}

static void _sleepNs(long long ns) {
	if (ns <= 0) {
		return;
	}
	struct timespec d = { ns / 1000000000LL, ns % 1000000000LL };
	while (nanosleep(&d, &d) && errno == EINTR);
}

static void _idleSleepMs(int ms) {
	_sleepNs((ms > 0 ? ms : 0) * 1000000LL);
}

// Absolute deadline [ms] from now. Android and Linux wait on a CLOCK_MONOTONIC condition
// variable (outCond, see load()); macOS has no pthread_condattr_setclock, so it waits on the
// default (realtime) clock.
static void _deadlineIn(struct timespec* t, long long ms) {
#ifdef OUT_COND_MONOTONIC
	clock_gettime(CLOCK_MONOTONIC, t);
#else
	clock_gettime(CLOCK_REALTIME, t);
#endif
	long long ns = t->tv_nsec + ms * 1000000LL;
	t->tv_sec += ns / 1000000000LL;
	t->tv_nsec = ns % 1000000000LL;
}

static bool _replaceFile(const char* from, const char* to) {
	return rename(from, to) == 0;
}

static void _removeFile(const char* path) {
	unlink(path);
}
#endif

// ---------------------------------------------------------------- savestates (autosave)

enum { STATE_NONE, STATE_SAVE, STATE_LOAD };

// Written next to the target and renamed into place, so quitting mid-write never
// leaves a broken snapshot. The cartridge save is not part of it: restoring an older
// snapshot must never roll back a newer in-game save.
static bool _saveStateTo(const char* path) {
	// Put the in-game save on disk first: the app fingerprints it next to the snapshot, so a later
	// resume can tell whether the player saved in the game after this snapshot was taken.
	_flushSave();
	char tmp[sizeof(G.statePath) + 8];
	snprintf(tmp, sizeof(tmp), "%s.tmp", path);
	struct VFile* vf = VFileOpen(tmp, O_CREAT | O_TRUNC | O_RDWR); // RDWR: mGBA writes states through mmap
	if (!vf) {
		return false;
	}
	bool ok = mCoreSaveStateNamed(G.core, vf, SAVESTATE_RTC | SAVESTATE_METADATA);
	ok = ok && vf->sync(vf, NULL, 0);
	vf->close(vf);
	if (!ok || !_replaceFile(tmp, path)) {
		_removeFile(tmp);
		return false;
	}
	return true;
}

static bool _loadStateFrom(const char* path) {
	struct VFile* vf = VFileOpen(path, O_RDONLY);
	if (!vf) {
		return false;
	}
	bool ok = mCoreLoadStateNamed(G.core, vf, SAVESTATE_RTC);
	vf->close(vf);
	return ok;
}

static bool _runStateOp(int op, const char* path) {
	if (!G.core) {
		return false; // stopped meanwhile (Native.stop holds stateMutex while it tears the core down)
	}
	bool ok = op == STATE_SAVE ? _saveStateTo(path) : _loadStateFrom(path);
	LOGI("%s %s: %s", op == STATE_SAVE ? "snapshot saved to" : "snapshot loaded from", path, ok ? "ok" : "failed");
	return ok;
}

// Emulation thread: carry out a pending request.
static void _serviceState(void) {
	pthread_mutex_lock(&G.stateMutex);
	if (G.stateOp != STATE_NONE && G.stateResult < 0) {
		G.stateResult = _runStateOp(G.stateOp, G.statePath) ? 1 : 0;
		pthread_cond_broadcast(&G.stateCond);
	}
	pthread_mutex_unlock(&G.stateMutex);
}

// ---------------------------------------------------------------- ADPF hint session (API 33+, resolved at runtime)

#ifdef __ANDROID__
typedef void* (*PfnGetMgr)(void);
typedef void* (*PfnCreate)(void*, const int32_t*, size_t, int64_t);
typedef int (*PfnReport)(void*, int64_t);
typedef void (*PfnClose)(void*);

static struct { void* sess; PfnReport report; PfnClose close; } Hint;

static void _hintOpen(void) {
	void* lib = dlopen("libandroid.so", RTLD_NOW);
	if (!lib || android_get_device_api_level() < 33) {
		return;
	}
	PfnGetMgr getMgr = (PfnGetMgr) dlsym(lib, "APerformanceHint_getManager");
	PfnCreate create = (PfnCreate) dlsym(lib, "APerformanceHint_createSession");
	Hint.report = (PfnReport) dlsym(lib, "APerformanceHint_reportActualWorkDuration");
	Hint.close = (PfnClose) dlsym(lib, "APerformanceHint_closeSession");
	if (!getMgr || !create || !Hint.report || !Hint.close) {
		return;
	}
	void* mgr = getMgr();
	int32_t tid = (int32_t) gettid();
	if (mgr) {
		Hint.sess = create(mgr, &tid, 1, FRAME_NS);
	}
}

static void _hintClose(void) {
	if (Hint.sess) {
		Hint.close(Hint.sess);
		Hint.sess = NULL;
	}
}

static void _hintReport(long long workNs) {
	if (Hint.sess) {
		Hint.report(Hint.sess, workNs);
	}
}
#else
static void _hintOpen(void) {}
static void _hintClose(void) {}
static void _hintReport(long long workNs) { (void) workNs; }
#endif

static void* _emuLoop(void* unused) {
	(void) unused;
	long long next = _nowNs();
	_hintOpen();
	_timerBegin();
	bool catchUp = false;
	int skipped = 0;
	long long catchSince = 0, noCatchUntil = 0;
	while (!atomic_load(&G.quit)) {
		if (atomic_exchange(&G.flushRequested, false)) {
			_flushSave();
		}
		// Cable changes first, also while paused: a snapshot loaded right after leaving a room must
		// see the cable already detached.
		_applyNetCommands();
		_serviceState(); // also while paused: the app snapshots when it goes to the background
		if (atomic_load(&G.paused)) {
			_sleepNs(10000000LL);
			next = _nowNs();
			continue;
		}
		uint16_t keys = atomic_load(&G.keys) | atomic_exchange(&G.latched, 0);
		if (atomic_load(&G.touchReady)) {
			// G.video still holds the frame rendered last time round.
			TouchNavStep(&G.touch, (const uint32_t*) G.video, &keys);
		}
		long long workStart = _nowNs();
		G.core->setKeys(G.core, keys);
		G.core->runFrame(G.core);

		// Link traffic piling up (the shared stream is ahead of this GBA): run frames back to back
		// until the backlog is down to one, at most 4x real time, without audio, and showing only
		// every 4th frame, so the picture stays alive. Otherwise pace at the real frame rate.
		bool catchingUp = false;
		if (G.netAttached) {
			unsigned backlog = NetLinkBacklog(&G.net);
			catchingUp = catchUp ? backlog > 1 : backlog >= 2;
		}
		if (catchingUp) {
			// A burst is bounded: if the backlog won't go down (the game isn't consuming link
			// events, or the peer is persistently ahead) play normally for a while, then retry.
			long long t = _nowNs();
			if (t < noCatchUntil) {
				catchingUp = false;
			} else if (!catchUp) {
				catchSince = t;
			} else if (t - catchSince > 1000000000LL) {
				catchingUp = false;
				noCatchUntil = t + 3000000000LL;
			}
		}
		catchUp = catchingUp;
		if (catchingUp && ++skipped < 4) {
			mAudioBufferClear(G.core->getAudioBuffer(G.core)); // muted while catching up
			next += FRAME_NS / 4;
			long long now = _nowNs();
			if (next > now) {
				_sleepNs(next - now);
			} else if (next < now - FRAME_NS) {
				next = now;
			}
			continue;
		}
		skipped = 0;
		pthread_mutex_lock(&G.frameMutex);
		memcpy(G.frame, G.video, G.width * G.height * 4);
		G.frameNew = true;
		pthread_mutex_unlock(&G.frameMutex);
		atomic_fetch_add(&G.frameCount, 1);
		int speed = G.netAttached ? 1 : atomic_load(&G.speed);
		if (catchingUp || speed > 1) {
			mAudioBufferClear(G.core->getAudioBuffer(G.core)); // muted while fast-forwarding
		} else {
			_pushAudio();
		}
		_hintReport(_nowNs() - workStart);

		next += catchingUp ? FRAME_NS / 4 : FRAME_NS / speed;
		long long now = _nowNs();
		if (next < now - FRAME_NS * 4) {
			next = now; // fell behind (e.g. app was stalled): don't burst
		} else if (next > now) {
			_sleepNs(next - now);
		}
	}
	_hintClose();
	_timerEnd();
	_flushSave();
	return NULL;
}

// ---------------------------------------------------------------- JNI: lifecycle

// Takes the core and everything hanging off it down; used by stop() and by a load() that fails half way.
// The caller holds stateMutex (no snapshot op may touch the core while it goes) and the emulation thread
// is not running. Requires G.core.
static void _closeCore(void) {
	if (G.netAttached) {
		G.core->setPeripheral(G.core, mPERIPH_GBA_LINK_PORT, NULL);
		NetLinkDestroy(&G.net);
		G.netAttached = false;
	}
	if (atomic_exchange(&G.touchReady, false)) {
		TouchNavDeinit(&G.touch);
	}
	mCoreConfigDeinit(&G.core->config); // lives inside the core, so before the core is freed
	G.core->deinit(G.core);
	G.core = NULL;
	// A display thread may be copying the picture this very moment: take the buffer away under its lock.
	pthread_mutex_lock(&G.frameMutex);
	free(G.frame);
	G.frame = NULL;
	pthread_mutex_unlock(&G.frameMutex);
	free(G.video);
	G.video = NULL;
}

JNIEXPORT jboolean JNICALL Java_com_linkplay_app_Native_load(JNIEnv* env, jclass cls, jstring jrom, jstring jsave) {
	(void) cls;
	if (G.core) {
		return JNI_TRUE;
	}
	const char* rom = (*env)->GetStringUTFChars(env, jrom, NULL);
	const char* save = (*env)->GetStringUTFChars(env, jsave, NULL);
	bool ok = false;
	if (!rom || !save) {
		goto done;
	}
	mLogSetDefaultLogger(&logger);
	if (!G.syncInit) { // a later load must not re-initialise primitives a polling thread may be waiting on
		pthread_mutex_init(&G.frameMutex, NULL);
		pthread_mutex_init(&G.stateMutex, NULL);
		pthread_cond_init(&G.stateCond, NULL);
		pthread_mutex_init(&G.audioMutex, NULL);
		pthread_mutex_init(&G.outMutex, NULL);
#ifdef OUT_COND_MONOTONIC
		pthread_condattr_t ca;
		pthread_condattr_init(&ca);
		pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
		pthread_cond_init(&G.outCond, &ca);
		pthread_condattr_destroy(&ca);
#else
		pthread_cond_init(&G.outCond, NULL); // _deadlineIn uses the realtime clock here
#endif
		G.syncInit = true;
	}
	atomic_store(&G.pendingCable, CABLE_NONE);
	atomic_store(&G.pendingLinkId, -1);

	G.core = mCoreFind(rom);
	if (!G.core || !G.core->init(G.core)) {
		LOGE("no core for %s", rom);
		free(G.core); // mCoreFind's allocation; init failed before the core owned anything else
		G.core = NULL;
		goto done;
	}
	mCoreInitConfig(G.core, NULL);
	mCoreConfigSetValue(&G.core->config, "idleOptimization", "remove");
	mCoreConfigSetDefaultIntValue(&G.core->config, "volume", 0x100);
	mCoreConfigSetDefaultIntValue(&G.core->config, "mute", 0);
#ifdef __ANDROID__
	mCoreLoadConfig(G.core);
#else
	// Not mCoreLoadConfig (reads the user's own mGBA config) and not mCoreLoadForeignConfig (its
	// mDirectorySetMapOptions creates mGBA's config folder). Do the same steps minus the directory set.
	mCoreConfigMap(&G.core->config, &G.core->opts);
	if (G.core->opts.audioBuffers) {
		G.core->setAudioBufferSize(G.core, G.core->opts.audioBuffers);
	}
	G.core->loadConfig(G.core, &G.core->config);
#endif
	G.core->baseVideoSize(G.core, &G.width, &G.height);
	G.video = calloc(G.width * G.height, sizeof(mColor));
	G.frame = calloc(G.width * G.height, 4);
	if (!G.video || !G.frame) {
		LOGE("out of memory");
		goto fail;
	}
	G.core->setVideoBuffer(G.core, G.video, G.width);
	G.core->setAudioBufferSize(G.core, 2048);
	if (!mCoreLoadFile(G.core, rom)) {
		LOGE("could not load %s", rom);
		goto fail;
	}
	struct VFile* vf = VFileOpen(save, O_CREAT | O_RDWR);
	if (!vf || !G.core->loadSave(G.core, vf)) {
		LOGE("could not open save %s", save);
		goto fail;
	}
	G.core->reset(G.core);
	TouchNavInit(&G.touch, G.core);
	atomic_store(&G.touchReady, true);
	mAudioBufferInit(&G.audioOut, 8192, 2);
	mAudioResamplerInit(&G.resampler, mINTERPOLATOR_COSINE);
	mAudioResamplerSetDestination(&G.resampler, &G.audioOut, AUDIO_RATE);
	// The resampler above is new and has no source yet; _pushAudio only sets one when the rate changes,
	// and a second game reports the same 32768 Hz, so forget the old rate (else: crash in the resampler).
	G.sourceRate = 0;
	// Nothing of the previous game's sound or held buttons may carry over.
	pthread_mutex_lock(&G.audioMutex);
	G.ringHead = G.ringCount = 0;
	pthread_mutex_unlock(&G.audioMutex);
	atomic_store(&G.keys, 0);
	atomic_store(&G.latched, 0);
	ok = true;
	LOGI("loaded %s (%ux%u)", rom, G.width, G.height);
	goto done;
fail: // leave nothing half set up: a retry must start from scratch, not find a core without a game
	pthread_mutex_lock(&G.stateMutex);
	_closeCore();
	pthread_mutex_unlock(&G.stateMutex);
done:
	if (rom) {
		(*env)->ReleaseStringUTFChars(env, jrom, rom);
	}
	if (save) {
		(*env)->ReleaseStringUTFChars(env, jsave, save);
	}
	return ok;
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_start(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	if (!G.core || G.threadStarted) {
		return;
	}
	atomic_store(&G.quit, false);
	atomic_store(&G.paused, false);
#ifdef __ANDROID__
	_openAudio();
#endif
	pthread_create(&G.thread, NULL, _emuLoop, NULL);
	G.threadStarted = true;
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_setPaused(JNIEnv* env, jclass cls, jboolean paused) {
	(void) env;
	(void) cls;
	atomic_store(&G.paused, paused);
	if (paused) {
		atomic_store(&G.flushRequested, true); // write the save when the app goes to the background
	}
#ifdef __ANDROID__
	if (G.stream) {
		if (paused) {
			AAudioStream_requestPause(G.stream);
		} else {
			AAudioStream_requestStart(G.stream);
		}
	}
#endif
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_stop(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	if (G.threadStarted) {
		atomic_store(&G.quit, true);
		pthread_join(G.thread, NULL);
		G.threadStarted = false;
	}
#ifdef __ANDROID__
	if (G.stream) {
		AAudioStream_requestStop(G.stream);
		AAudioStream_close(G.stream);
		G.stream = NULL;
	}
#endif
	if (G.syncInit) pthread_mutex_lock(&G.stateMutex); // no snapshot op may touch the core while it goes
	if (G.core) { // only a fully loaded game gets here, so the audio side exists too
		_closeCore();
		mAudioResamplerDeinit(&G.resampler);
		mAudioBufferDeinit(&G.audioOut);
	}
	if (G.syncInit) pthread_mutex_unlock(&G.stateMutex);
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_setKeys(JNIEnv* env, jclass cls, jint keys) {
	(void) env;
	(void) cls;
	atomic_store(&G.keys, keys & 0x3FF);
	atomic_fetch_or(&G.latched, keys & 0x3FF); // a tap shorter than a frame still registers
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_setSpeed(JNIEnv* env, jclass cls, jint factor) {
	(void) env;
	(void) cls;
	atomic_store(&G.speed, factor < 1 ? 1 : factor > 8 ? 8 : factor);
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_setVolume(JNIEnv* env, jclass cls, jfloat volume) {
	(void) env;
	(void) cls;
	atomic_store(&G.volume, volume);
}

JNIEXPORT jlong JNICALL Java_com_linkplay_app_Native_frameCount(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	return (jlong) atomic_load(&G.frameCount);
}

// Latest frame as RGB565 (240x160), e.g. for screenshots.
JNIEXPORT jboolean JNICALL Java_com_linkplay_app_Native_copyFrame(JNIEnv* env, jclass cls, jshortArray out) {
	(void) cls;
	if (!G.frame || (*env)->GetArrayLength(env, out) < (jsize) (G.width * G.height)) {
		return JNI_FALSE;
	}
	jshort* dst = (*env)->GetShortArrayElements(env, out, NULL);
	if (!dst) {
		return JNI_FALSE;
	}
	pthread_mutex_lock(&G.frameMutex);
	bool ok = G.frame != NULL; // stop() may have taken the buffer since the check above
	for (unsigned i = 0; ok && i < G.width * G.height; ++i) {
		uint32_t c = G.frame[i];
		uint32_t r = c & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
		dst[i] = (jshort) (((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
	}
	pthread_mutex_unlock(&G.frameMutex);
	(*env)->ReleaseShortArrayElements(env, out, dst, ok ? 0 : JNI_ABORT);
	return ok ? JNI_TRUE : JNI_FALSE;
}

#ifndef __ANDROID__
// Latest frame as 0xFFRRGGBB ints (240x160), ready for a BufferedImage.TYPE_INT_RGB / TYPE_INT_ARGB.
JNIEXPORT jboolean JNICALL Java_com_linkplay_app_Native_copyFrameRgb(JNIEnv* env, jclass cls, jintArray out) {
	(void) cls;
	if (!G.frame || (*env)->GetArrayLength(env, out) < (jsize) (G.width * G.height)) {
		return JNI_FALSE;
	}
	jint* dst = (*env)->GetIntArrayElements(env, out, NULL);
	if (!dst) {
		return JNI_FALSE;
	}
	pthread_mutex_lock(&G.frameMutex);
	bool ok = G.frame != NULL; // stop() may have taken the buffer since the check above
	for (unsigned i = 0; ok && i < G.width * G.height; ++i) {
		uint32_t c = G.frame[i]; // mColor: 0x??BBGGRR
		dst[i] = (jint) (0xFF000000u | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF));
	}
	pthread_mutex_unlock(&G.frameMutex);
	(*env)->ReleaseIntArrayElements(env, out, dst, ok ? 0 : JNI_ABORT);
	return ok ? JNI_TRUE : JNI_FALSE;
}

// Pull model for the host's audio device: always fills [frames] interleaved stereo frames (48000 Hz,
// signed 16-bit, volume applied), zero-padded on underrun; returns how many frames were real audio.
JNIEXPORT jint JNICALL Java_com_linkplay_app_Native_audioRead(JNIEnv* env, jclass cls, jshortArray out, jint frames) {
	(void) cls;
	jsize cap = (*env)->GetArrayLength(env, out) / 2;
	if (frames <= 0 || frames > cap) {
		frames = cap;
	}
	jshort* dst = (*env)->GetShortArrayElements(env, out, NULL);
	if (!dst) {
		return 0;
	}
	int32_t real = G.syncInit ? _audioPull((int16_t*) dst, frames) : 0;
	if (!G.syncInit) {
		memset(dst, 0, (size_t) frames * 4);
	}
	(*env)->ReleaseShortArrayElements(env, out, dst, 0);
	return real;
}
#endif

// ---------------------------------------------------------------- JNI: touch navigation

// Tap on the picture (GBA pixels): steers the menu's cursor there and presses A, etc.
JNIEXPORT void JNICALL Java_com_linkplay_app_Native_touchTap(JNIEnv* env, jclass cls, jint x, jint y) {
	(void) env;
	(void) cls;
	if (atomic_load(&G.touchReady)) {
		TouchNavTap(&G.touch, x, y);
	}
}

// dir: 0 up, 1 down, 2 left, 3 right; that many D-pad steps (repeated swipes add up).
JNIEXPORT void JNICALL Java_com_linkplay_app_Native_touchSwipe(JNIEnv* env, jclass cls, jint dir, jint steps) {
	(void) env;
	(void) cls;
	if (atomic_load(&G.touchReady)) {
		TouchNavSwipe(&G.touch, dir, steps);
	}
}

JNIEXPORT jboolean JNICALL Java_com_linkplay_app_Native_touchBusy(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	return atomic_load(&G.touchReady) && TouchNavBusy(&G.touch);
}

// ---------------------------------------------------------------- JNI: savestates

// Blocks the calling (non-UI) thread until the emulation thread has done it, at most ~3 s.
static jboolean _stateRequest(JNIEnv* env, jstring jpath, int op) {
	if (!G.core) {
		return JNI_FALSE;
	}
	const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
	bool ok = false;
	if (strlen(path) >= sizeof(G.statePath)) {
		goto done;
	}
	pthread_mutex_lock(&G.stateMutex);
	if (!G.threadStarted) { // no emulation thread to race with
		ok = _runStateOp(op, path);
		pthread_mutex_unlock(&G.stateMutex);
		goto done;
	}
	struct timespec until; // the state condition variable has the default (realtime) clock everywhere
#ifdef _WIN32
	_deadlineIn(&until, 3000);
#else
	clock_gettime(CLOCK_REALTIME, &until);
	until.tv_sec += 3;
#endif
	while (G.stateOp != STATE_NONE) { // someone else's request is still running
		if (pthread_cond_timedwait(&G.stateCond, &G.stateMutex, &until) == ETIMEDOUT) {
			pthread_mutex_unlock(&G.stateMutex);
			goto done;
		}
	}
	strcpy(G.statePath, path);
	G.stateOp = op;
	G.stateResult = -1;
	while (G.stateResult < 0) {
		if (pthread_cond_timedwait(&G.stateCond, &G.stateMutex, &until) == ETIMEDOUT) {
			break;
		}
	}
	ok = G.stateResult == 1;
	G.stateOp = STATE_NONE; // a request the thread never got to is dropped
	G.stateResult = 0;
	pthread_cond_broadcast(&G.stateCond);
	pthread_mutex_unlock(&G.stateMutex);
done:
	(*env)->ReleaseStringUTFChars(env, jpath, path);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_linkplay_app_Native_saveState(JNIEnv* env, jclass cls, jstring path) {
	(void) cls;
	return _stateRequest(env, path, STATE_SAVE);
}

JNIEXPORT jboolean JNICALL Java_com_linkplay_app_Native_loadState(JNIEnv* env, jclass cls, jstring path) {
	(void) cls;
	return _stateRequest(env, path, STATE_LOAD);
}

// ---------------------------------------------------------------- JNI: link play

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_netAttach(JNIEnv* env, jclass cls, jint linkId) {
	(void) env;
	(void) cls;
	atomic_store(&G.pendingUnplug, false); // an unplug for the old room must not hit the new cable
	_dropOutput();
	atomic_store(&G.pendingCable, linkId);
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_netDetach(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	atomic_store(&G.pendingUnplug, false);
	_dropOutput();
	atomic_store(&G.pendingCable, CABLE_DETACH);
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_netSetLinkId(JNIEnv* env, jclass cls, jint linkId) {
	(void) env;
	(void) cls;
	atomic_store(&G.pendingLinkId, linkId);
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_netUnplug(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	atomic_store(&G.pendingUnplug, true);
}

// Incoming relay frame without its sequence number: u8 kind, u8 player, u16 words[].
JNIEXPORT void JNICALL Java_com_linkplay_app_Native_netPush(JNIEnv* env, jclass cls, jbyteArray data) {
	(void) cls;
	if (!G.netAttached) {
		return;
	}
	jsize len = (*env)->GetArrayLength(env, data);
	if (len < 2 || len > 2 + NETLINK_MAX_PLAYERS * NETLINK_CMD_WORDS * 2) {
		return;
	}
	uint8_t buf[2 + NETLINK_MAX_PLAYERS * NETLINK_CMD_WORDS * 2];
	(*env)->GetByteArrayRegion(env, data, 0, len, (jbyte*) buf);
	struct NetLinkEvent ev = { .kind = buf[0], .player = buf[1] };
	memcpy(ev.words, buf + 2, ((len - 2) / 2) * 2);
	NetLinkPushEvent(&G.net, &ev);
}

// Outgoing link traffic since the last poll: concatenated (u8 kind, u8 0, u16 words[1 | 8]).
JNIEXPORT jbyteArray JNICALL Java_com_linkplay_app_Native_netPoll(JNIEnv* env, jclass cls) {
	(void) cls;
	if (!G.syncInit) {
		return NULL;
	}
	pthread_mutex_lock(&G.outMutex);
	jbyteArray result = NULL;
	if (G.outLen) {
		result = (*env)->NewByteArray(env, (jsize) G.outLen);
		(*env)->SetByteArrayRegion(env, result, 0, (jsize) G.outLen, (const jbyte*) G.out);
		G.outLen = 0;
	}
	pthread_mutex_unlock(&G.outMutex);
	return result;
}

// Blocks until link output is queued or [timeoutMs] passes; true if there is output to poll.
JNIEXPORT jboolean JNICALL Java_com_linkplay_app_Native_netWait(JNIEnv* env, jclass cls, jint timeoutMs) {
	(void) env;
	(void) cls;
	if (!G.syncInit) { // nothing loaded yet: the primitives don't exist
		_idleSleepMs(timeoutMs);
		return JNI_FALSE;
	}
	struct timespec t;
	_deadlineIn(&t, timeoutMs > 0 ? timeoutMs : 0);
	pthread_mutex_lock(&G.outMutex);
	while (G.outLen == 0) {
		if (pthread_cond_timedwait(&G.outCond, &G.outMutex, &t) != 0) {
			break; // timed out
		}
	}
	bool has = G.outLen != 0;
	pthread_mutex_unlock(&G.outMutex);
	return has ? JNI_TRUE : JNI_FALSE;
}

#ifdef __ANDROID__
// ---------------------------------------------------------------- JNI: rendering

bool bridgeFrameAcquire(const uint32_t** px, int* w, int* h, bool* isNew) {
	if (!G.frame || !G.width) {
		return false;
	}
	pthread_mutex_lock(&G.frameMutex);
	if (!G.frame) { // stop() took the buffer away since the check above
		pthread_mutex_unlock(&G.frameMutex);
		return false;
	}
	*px = G.frame;
	*w = G.width;
	*h = G.height;
	*isNew = G.frameNew;
	return true;
}

void bridgeFrameRelease(bool consumedNew) {
	if (consumedNew) {
		G.frameNew = false;
	}
	pthread_mutex_unlock(&G.frameMutex);
}

void bridgeFrameForceNew(void) {
	G.frameNew = true;
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_glInit(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	renderInit();
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_glResize(JNIEnv* env, jclass cls, jint w, jint h) {
	(void) env;
	(void) cls;
	renderResize(w, h);
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_glDraw(JNIEnv* env, jclass cls, jint filter) {
	(void) env;
	(void) cls;
	renderDraw(filter);
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_glSetPictureRect(JNIEnv* env, jclass cls, jint l, jint t, jint w, jint h) {
	(void) env;
	(void) cls;
	renderSetPictureRect(l, t, w, h);
}

JNIEXPORT void JNICALL Java_com_linkplay_app_Native_glSetAmbient(JNIEnv* env, jclass cls, jboolean on) {
	(void) env;
	(void) cls;
	renderSetAmbient(on != JNI_FALSE);
}
#endif // __ANDROID__
