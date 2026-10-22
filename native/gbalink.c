/*
 * gbalink — runs up to 4 GBA cores in one process, connected by an emulated
 * link cable (mGBA lockstep), and streams their video/audio over stdout.
 *
 * Usage: gbalink <rom> <save-dir> <players 1-4>
 *
 * stdout (binary, little-endian), one message = 8-byte header + payload:
 *   u8 type, u8 player, u16 reserved, u32 length
 *   type 0 INFO  : u16 width, u16 height, u32 audioRate          (once per player)
 *   type 1 VIDEO : width*height RGB565 pixels
 *   type 2 AUDIO : int16 mono samples at audioRate
 *   type 3 LINK  : u8 active mask, u8 plugged mask                (after every change)
 *   type 4 NET   : NetLink message for the relay (GBALINK_NETLINK=stdio):
 *                  u8 kind (1 handshake, 2 command), u8 0, u16 words[1 or 8]
 * stdin: 4-byte commands: u8 cmd, u8 player, u16 value
 *   cmd 1 KEYS   : value = GBA key bitmask (A,B,Select,Start,Right,Left,Up,Down,R,L)
 *   cmd 2 ACTIVE : value = bitmask of GBAs someone is playing. Active GBAs run;
 *                  the rest are paused. With 2+ active, exactly those are plugged
 *                  into the link cable, hot-plugged without resetting anyone.
 *   cmd 5 NET    : value = payload length, followed by that many bytes:
 *                  u8 kind (1 handshake, 2 entry, 3 player left), u8 player, u16 words[0, 1 or 32]
 *   cmd 6 SETID  : value = new link player id (the relay renumbered players)
 *   cmd 7 UNPLUG : pull the link cable (relay session lost); the game sees a disconnect
 *   cmd 9 QUIT   : flush saves and exit
 *   cmd 10 TAP   : value = x | y << 8 (GBA pixels): pick that menu item (touchnav.c)
 *   cmd 11 SWIPE : value = dir | steps << 8, dir 0 up 1 down 2 left 3 right: that many D-pad steps.
 *                  While a tap/swipe is being carried out, its keys replace the player's.
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/lockstep.h>
#include <mgba/core/log.h>
#include <mgba/core/thread.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/sio/lockstep.h>
#include <mgba-util/audio-buffer.h>
#include <mgba-util/audio-resampler.h>
#include <mgba-util/vfs.h>

#include "netlink.h"
#include "touchnav.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define OUT_AUDIO_RATE 24000
#define FRAME_NS 16742706LL /* 280896 cycles per frame at 16.78 MHz */

struct Player {
	int id;
	struct mCore* core;
	struct mCoreThread thread;
	struct GBASIOLockstepDriver driver;
	struct mLockstepThreadUser user;
	mColor* video;
	unsigned width, height;
	uint16_t* rgb565;
	struct mAudioBuffer audioOut;
	struct mAudioResampler resampler;
	unsigned sourceRate;
	int16_t audioScratch[4096 * 2];
	int16_t monoScratch[4096];
	bool plugged;
	struct NetLink net;
	atomic_uint keys;
	atomic_uint frames;
	unsigned debugCounter;
	atomic_uint latched; // pressed since last frame, so a quick tap is never lost
	struct timespec nextFrame;
	struct TouchNav touch;
};

static struct Player players[4];
static int nPlayers;
static struct GBASIOLockstepCoordinator coordinator;
static pthread_mutex_t outMutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_bool quitting;
static bool debugLink;

// GBALINK_TRACE=<file>: record every multiplayer transfer for protocol analysis.
// Record (24 bytes LE): u64 global cycle, u32 frame, u8 player, u8 baud, u16 siocnt, u16 data[4]
static FILE* traceFile;
static pthread_mutex_t traceMutex = PTHREAD_MUTEX_INITIALIZER;
static void (*origFinishMultiplayer)(struct GBASIODriver*, uint16_t data[4]);
static int outFd = STDOUT_FILENO;

static void writeAll(const void* data, size_t size) {
	const uint8_t* p = data;
	while (size) {
		ssize_t w = write(outFd, p, size);
		if (w < 0) {
			if (errno == EINTR) {
				continue;
			}
			atomic_store(&quitting, true);
			return;
		}
		p += w;
		size -= w;
	}
}

static void emit(uint8_t type, uint8_t player, const void* payload, uint32_t length) {
	uint8_t header[8] = { type, player, 0, 0,
		length & 0xFF, (length >> 8) & 0xFF, (length >> 16) & 0xFF, length >> 24 };
	pthread_mutex_lock(&outMutex);
	writeAll(header, sizeof(header));
	writeAll(payload, length);
	pthread_mutex_unlock(&outMutex);
}

static void _log(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
	(void) logger;
	if (!(level & (mLOG_FATAL | mLOG_ERROR))) {
		return;
	}
	fprintf(stderr, "gbalink: [%s] ", mLogCategoryName(category));
	vfprintf(stderr, format, args);
	fputc('\n', stderr);
}

static struct mLogger logger = { .log = _log };

static int _requestedId(struct mLockstepUser* user) {
	struct mLockstepThreadUser* tuser = (struct mLockstepThreadUser*) user;
	return ((struct Player*) tuser->thread->userData)->id;
}

static bool _netMode(void);

static void _throttle(struct Player* p) {
	// Link traffic piling up means this GBA runs a hair slower than the others:
	// run 10% faster until it has caught up, so the shared stream never drifts.
	long long frameNs = FRAME_NS;
	if (_netMode() && p->net.d.p && NetLinkBacklog(&p->net) > NETLINK_MAX_BACKLOG) {
		frameNs = FRAME_NS * 9 / 10;
	}
	struct timespec now;
	clock_gettime(CLOCK_MONOTONIC, &now);
	long long nowNs = now.tv_sec * 1000000000LL + now.tv_nsec;
	long long target = p->nextFrame.tv_sec * 1000000000LL + p->nextFrame.tv_nsec + frameNs;
	if (target < nowNs - FRAME_NS * 4) {
		// Fell far behind (e.g. waiting on a linked core); don't try to catch up in a burst.
		target = nowNs;
	} else if (target > nowNs) {
		struct timespec delay = { (target - nowNs) / 1000000000LL, (target - nowNs) % 1000000000LL };
		while (nanosleep(&delay, &delay) && errno == EINTR);
	}
	p->nextFrame.tv_sec = target / 1000000000LL;
	p->nextFrame.tv_nsec = target % 1000000000LL;
}

static void _frame(struct mCoreThread* thread) {
	struct Player* p = thread->userData;
	struct mCore* core = p->core;

	size_t n = p->width * p->height;
	for (size_t i = 0; i < n; ++i) {
		uint32_t c = p->video[i]; // XBGR8888
		uint32_t r = c & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
		p->rgb565[i] = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
	}
	emit(1, p->id, p->rgb565, n * 2);

	unsigned rate = core->audioSampleRate(core);
	if (rate != p->sourceRate) {
		p->sourceRate = rate;
		mAudioResamplerSetSource(&p->resampler, core->getAudioBuffer(core), rate, true);
	}
	mAudioResamplerProcess(&p->resampler);
	size_t frames = mAudioBufferRead(&p->audioOut, p->audioScratch, 4096);
	if (frames) {
		for (size_t i = 0; i < frames; ++i) {
			p->monoScratch[i] = (p->audioScratch[i * 2] + p->audioScratch[i * 2 + 1]) / 2;
		}
		emit(2, p->id, p->monoScratch, frames * 2);
	}

	uint16_t keys = atomic_load(&p->keys) | atomic_exchange(&p->latched, 0);
	TouchNavStep(&p->touch, (const uint32_t*) p->video, &keys); // taps / swipes steering a menu
	core->setKeys(core, keys);
	atomic_fetch_add(&p->frames, 1);

	if (debugLink && ++p->debugCounter % 60 == 0) {
		// SIOCNT bits: 0-1 baud, 2 SI (all ready), 3 SD, 4-5 my multiplayer id, 6 error, 7 busy, 12-13 mode
		uint16_t siocnt = core->busRead16(core, 0x04000128);
		uint16_t rcnt = core->busRead16(core, 0x04000134);
		fprintf(stderr, "link[%d] plugged=%d RCNT=%04X SIOCNT=%04X id=%d mode=%d err=%d multi=[%04X %04X %04X %04X] send=%04X\n",
			p->id, p->plugged, rcnt, siocnt, (siocnt >> 4) & 3, (siocnt >> 12) & 3, (siocnt >> 6) & 1,
			core->busRead16(core, 0x04000120), core->busRead16(core, 0x04000122),
			core->busRead16(core, 0x04000124), core->busRead16(core, 0x04000126),
			core->busRead16(core, 0x0400012A));
	}
	_throttle(p);
}

static void _start(struct mCoreThread* thread) {
	// We pace frames ourselves; never block waiting on a frontend.
	thread->impl->sync.videoFrameWait = false;
	thread->impl->sync.audioWait = false;
	struct Player* p = thread->userData;
	clock_gettime(CLOCK_MONOTONIC, &p->nextFrame);
}

static bool setupPlayer(struct Player* p, const char* rom, const char* saveDir) {
	p->core = mCoreFind(rom);
	if (!p->core || !p->core->init(p->core)) {
		fprintf(stderr, "gbalink: could not create core for %s\n", rom);
		return false;
	}
	mCoreInitConfig(p->core, NULL);
	mCoreConfigSetValue(&p->core->config, "idleOptimization", "remove");
	// Frontends must set the volume themselves; without it mGBA loads 0 and every GBA is muted.
	mCoreConfigSetDefaultIntValue(&p->core->config, "volume", 0x100);
	mCoreConfigSetDefaultIntValue(&p->core->config, "mute", 0);
	mCoreLoadConfig(p->core);

	p->core->baseVideoSize(p->core, &p->width, &p->height);
	p->video = calloc(p->width * p->height, sizeof(mColor));
	p->rgb565 = calloc(p->width * p->height, sizeof(uint16_t));
	p->core->setVideoBuffer(p->core, p->video, p->width);
	p->core->setAudioBufferSize(p->core, 2048);

	if (!mCoreLoadFile(p->core, rom)) {
		fprintf(stderr, "gbalink: could not load %s\n", rom);
		return false;
	}

	char savePath[1024];
	snprintf(savePath, sizeof(savePath), "%s/player%d.sav", saveDir, p->id + 1);
	struct VFile* save = VFileOpen(savePath, O_CREAT | O_RDWR);
	if (!save || !p->core->loadSave(p->core, save)) {
		fprintf(stderr, "gbalink: could not open save %s\n", savePath);
		return false;
	}

	mAudioBufferInit(&p->audioOut, 8192, 2);
	mAudioResamplerInit(&p->resampler, mINTERPOLATOR_SINC);
	mAudioResamplerSetDestination(&p->resampler, &p->audioOut, OUT_AUDIO_RATE);
	p->sourceRate = 0;

	memset(&p->thread, 0, sizeof(p->thread));
	p->thread.core = p->core;
	p->thread.userData = p;
	p->thread.startCallback = _start;
	p->thread.frameCallback = _frame;
	p->thread.logger.logger = &logger;
	return true;
}

static void _traceFinishMultiplayer(struct GBASIODriver* driver, uint16_t data[4]) {
	origFinishMultiplayer(driver, data);
	struct GBA* gba = driver->p->p;
	struct Player* p = NULL;
	for (int i = 0; i < nPlayers; ++i) {
		if (&players[i].driver.d == driver) {
			p = &players[i];
		}
	}
	if (!p) {
		return;
	}
	uint8_t rec[24];
	uint64_t cycle = mTimingGlobalTime(&gba->timing);
	uint32_t frame = atomic_load(&p->frames);
	uint16_t siocnt = driver->p->siocnt;
	memcpy(rec, &cycle, 8);
	memcpy(rec + 8, &frame, 4);
	rec[12] = p->id;
	rec[13] = siocnt & 3;
	memcpy(rec + 14, &siocnt, 2);
	memcpy(rec + 16, data, 8);
	pthread_mutex_lock(&traceMutex);
	fwrite(rec, sizeof(rec), 1, traceFile);
	pthread_mutex_unlock(&traceMutex);
}

static void createDriver(struct Player* p) {
	GBASIOLockstepDriverCreate(&p->driver, &p->user.d);
	if (traceFile) {
		origFinishMultiplayer = p->driver.d.finishMultiplayer;
		p->driver.d.finishMultiplayer = _traceFinishMultiplayer;
	}
}

// ---- Simulated network for NetLink (GBALINK_NETSIM=<round trip ms>) ----
// Every GBA talks only through this: uplink delay -> sequencer -> downlink delay.

struct SimMsg {
	uint64_t due;
	int target;
	struct NetLinkEvent ev;
	struct SimMsg* next;
};

static int netSimMs = -1;
static bool netLog;
// GBALINK_NETLINK=stdio: one local GBA whose link traffic goes through the parent process.
static bool netStdio;
static int netLinkId;

static bool _netMode(void) {
	return netSimMs >= 0 || netStdio;
}

static void _stdioSend(uint8_t kind, const uint16_t* words, int count) {
	uint8_t buf[2 + NETLINK_CMD_WORDS * 2];
	buf[0] = kind;
	buf[1] = 0;
	memcpy(buf + 2, words, count * 2);
	emit(4, 0, buf, 2 + count * 2);
}

static void _stdioSendHandshake(struct NetLink* nl, uint16_t word) {
	(void) nl;
	_stdioSend(NETLINK_EV_HANDSHAKE, &word, 1);
}

static void _stdioSendCommand(struct NetLink* nl, const uint16_t cmd[NETLINK_CMD_WORDS]) {
	(void) nl;
	_stdioSend(NETLINK_EV_ENTRY, cmd, NETLINK_CMD_WORDS);
}
static pthread_mutex_t simMutex = PTHREAD_MUTEX_INITIALIZER;
static struct SimMsg* simUp;   // to the sequencer
static struct SimMsg* simDown; // to the GBAs

static uint64_t _nowMs(void) {
	struct timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return t.tv_sec * 1000ULL + t.tv_nsec / 1000000;
}

static void _simAppend(struct SimMsg** list, struct SimMsg* msg) {
	msg->next = NULL;
	while (*list) {
		list = &(*list)->next;
	}
	*list = msg;
}

static void _simSend(int target, const struct NetLinkEvent* ev, struct SimMsg** list) {
	struct SimMsg* msg = calloc(1, sizeof(*msg));
	msg->due = _nowMs() + netSimMs / 2;
	msg->target = target;
	msg->ev = *ev;
	pthread_mutex_lock(&simMutex);
	_simAppend(list, msg);
	pthread_mutex_unlock(&simMutex);
}

static void _netSendHandshake(struct NetLink* nl, uint16_t word) {
	struct NetLinkEvent ev = { .kind = NETLINK_EV_HANDSHAKE, .player = nl->localId };
	ev.words[0] = word;
	_simSend(-1, &ev, &simUp);
}

static void _netSendCommand(struct NetLink* nl, const uint16_t cmd[NETLINK_CMD_WORDS]) {
	struct NetLinkEvent ev = { .kind = NETLINK_EV_ENTRY, .player = nl->localId };
	memcpy(ev.words, cmd, NETLINK_CMD_WORDS * sizeof(uint16_t));
	_simSend(-1, &ev, &simUp);
}

static void _broadcast(const struct NetLinkEvent* ev) {
	for (int i = 0; i < nPlayers; ++i) {
		_simSend(i, ev, &simDown);
	}
}

static void* sequencerLoop(void* unused) {
	(void) unused;
	// Per-player FIFOs of commands waiting to be put into an entry.
	static uint16_t fifo[NETLINK_MAX_PLAYERS][256][NETLINK_CMD_WORDS];
	unsigned fifoHead[NETLINK_MAX_PLAYERS] = {0}, fifoCount[NETLINK_MAX_PLAYERS] = {0};
	struct timespec t0;
	clock_gettime(CLOCK_MONOTONIC, &t0);
	long long nextTickNs = t0.tv_sec * 1000000000LL + t0.tv_nsec;
	uint64_t nextReport = _nowMs() + 5000;
	while (!atomic_load(&quitting)) {
		usleep(1000);
		uint64_t now = _nowMs();

		pthread_mutex_lock(&simMutex);
		struct SimMsg* arrived = NULL;
		while (simUp && simUp->due <= now) {
			struct SimMsg* m = simUp;
			simUp = m->next;
			_simAppend(&arrived, m);
		}
		struct SimMsg* deliver = NULL;
		struct SimMsg** link = &simDown;
		while (*link) {
			if ((*link)->due <= now) {
				struct SimMsg* m = *link;
				*link = m->next;
				_simAppend(&deliver, m);
			} else {
				link = &(*link)->next;
			}
		}
		pthread_mutex_unlock(&simMutex);

		while (arrived) {
			struct SimMsg* m = arrived;
			arrived = m->next;
			int p = m->ev.player;
			if (netLog) {
				static uint16_t lastHs[NETLINK_MAX_PLAYERS] = { 1, 1, 1, 1 };
				if (m->ev.kind == NETLINK_EV_HANDSHAKE && m->ev.words[0] != lastHs[p]) {
					lastHs[p] = m->ev.words[0];
					fprintf(stderr, "net: P%d handshake %04X\n", p + 1, m->ev.words[0]);
				} else if (m->ev.kind == NETLINK_EV_ENTRY && m->ev.words[0] != 0xBEBE) {
					fprintf(stderr, "net: P%d cmd", p + 1);
					for (int w = 0; w < NETLINK_CMD_WORDS; ++w) {
						fprintf(stderr, " %04X", m->ev.words[w]);
					}
					fputc('\n', stderr);
				}
			}
			if (m->ev.kind == NETLINK_EV_HANDSHAKE) {
				_broadcast(&m->ev);
			} else if (fifoCount[p] >= 2 &&
			           NetLinkStateReplaceable(fifo[p][(fifoHead[p] + fifoCount[p] - 1) % 256], m->ev.words)) {
				// Backed up: a newer position packet replaces the queued one.
				memcpy(fifo[p][(fifoHead[p] + fifoCount[p] - 1) % 256], m->ev.words, sizeof(fifo[p][0]));
			} else if (fifoCount[p] < 256) {
				memcpy(fifo[p][(fifoHead[p] + fifoCount[p]) % 256], m->ev.words, sizeof(fifo[p][0]));
				++fifoCount[p];
			}
			free(m);
		}

		struct timespec tn;
		clock_gettime(CLOCK_MONOTONIC, &tn);
		long long nowNs = tn.tv_sec * 1000000000LL + tn.tv_nsec;
		if (netLog && now >= nextReport) {
			nextReport = now + 5000;
			fprintf(stderr, "net: backlog");
			for (int p = 0; p < nPlayers; ++p) {
				fprintf(stderr, " P%d=%u/%u", p + 1, fifoCount[p], NetLinkBacklog(&players[p].net));
			}
			fputc('\n', stderr);
		}
		if (nowNs >= nextTickNs) {
			// One tick per GBA frame (59.73 Hz), so entries never outpace the GBAs.
			nextTickNs += FRAME_NS;
			// One entry per tick, at most one command per player, in arrival order.
			struct NetLinkEvent entry = { .kind = NETLINK_EV_ENTRY };
			bool any = false;
			for (int p = 0; p < nPlayers; ++p) {
				if (fifoCount[p]) {
					memcpy(&entry.words[p * NETLINK_CMD_WORDS], fifo[p][fifoHead[p]], sizeof(fifo[p][0]));
					fifoHead[p] = (fifoHead[p] + 1) % 256;
					--fifoCount[p];
					any = true;
				}
			}
			if (any) {
				_broadcast(&entry);
			}
		}

		while (deliver) {
			struct SimMsg* m = deliver;
			deliver = m->next;
			NetLinkPushEvent(&players[m->target].net, &m->ev);
			free(m);
		}
	}
	return NULL;
}

static unsigned activeMask;
static pthread_mutex_t linkMutex = PTHREAD_MUTEX_INITIALIZER;

static void setActiveLocked(unsigned mask);

static void setActive(unsigned mask) {
	pthread_mutex_lock(&linkMutex);
	setActiveLocked(mask);
	pthread_mutex_unlock(&linkMutex);
}

// Last resort if linked cores ever stop making progress: pull the cable and plug it back in.
static void replugLink(void) {
	pthread_mutex_lock(&linkMutex);
	unsigned mask = activeMask;
	fprintf(stderr, "gbalink: linked GBAs stalled, re-plugging the link cable\n");
	for (int i = 0; i < nPlayers; ++i) {
		if (players[i].plugged) {
			mCoreThreadStopWaiting(&players[i].thread);
		}
	}
	setActiveLocked(mask & ~((1u << nPlayers) - 1)); // unplug + pause everyone
	setActiveLocked(mask);
	pthread_mutex_unlock(&linkMutex);
}

static void setActiveLocked(unsigned mask) {
	mask &= (1u << nPlayers) - 1;
	if (_netMode()) {
		// NetLink GBAs are always "plugged in"; only run the ones being played.
		for (int i = 0; i < nPlayers; ++i) {
			if ((mask & ~activeMask) & (1u << i)) {
				mCoreThreadUnpause(&players[i].thread);
			} else if ((activeMask & ~mask) & (1u << i)) {
				mCoreThreadPause(&players[i].thread);
			}
		}
		activeMask = mask;
		uint8_t status[2] = { mask, mask };
		emit(3, 0, status, sizeof(status));
		if (netStdio) {
			fprintf(stderr, "gbalink: playing mask %X (netlink over relay, link id %d)\n", mask, netLinkId);
		} else {
			fprintf(stderr, "gbalink: playing mask %X (netlink, %d ms simulated round trip)\n", mask, netSimMs);
		}
		return;
	}
	unsigned plugMask = __builtin_popcount(mask) >= 2 ? mask : 0;
	unsigned toPlug = 0, toUnplug = 0, pluggedNow = 0;
	for (int i = 0; i < nPlayers; ++i) {
		bool want = plugMask & (1u << i);
		if (players[i].plugged) {
			pluggedNow |= 1u << i;
		}
		if (want && !players[i].plugged) {
			toPlug |= 1u << i;
		} else if (!want && players[i].plugged) {
			toUnplug |= 1u << i;
		}
	}
	if (toPlug | toUnplug) {
		// Hot-adding or removing one GBA in a running group can deadlock mGBA's
		// lockstep, while forming a group from scratch is reliable. The player count
		// changes anyway, so re-form the whole group: unplug everyone, plug the rest in.
		toUnplug = pluggedNow;
		toPlug = plugMask;
	}

	for (int i = 0; i < nPlayers; ++i) {
		if ((mask & ~activeMask) & (1u << i)) {
			mCoreThreadUnpause(&players[i].thread);
		}
	}

	if (toPlug | toUnplug) {
		// Same sequence as mGBA's Qt multiplayer controller: stop every core,
		// swap link drivers, then resume.
		for (int i = 0; i < nPlayers; ++i) {
			mCoreThreadInterrupt(&players[i].thread);
		}
		for (int i = 0; i < nPlayers; ++i) {
			struct Player* p = &players[i];
			if (toUnplug & (1u << i)) {
				p->core->setPeripheral(p->core, mPERIPH_GBA_LINK_PORT, NULL);
				GBASIOLockstepCoordinatorDetach(&coordinator, &p->driver);
				createDriver(p);
				p->plugged = false;
				// A core unplugged while asleep waiting for its link partner would never wake up.
				mCoreThreadStopWaiting(&p->thread);
			}
		}
		for (int i = 0; i < nPlayers; ++i) {
			struct Player* p = &players[i];
			if (toPlug & (1u << i)) {
				GBASIOLockstepCoordinatorAttach(&coordinator, &p->driver);
				p->core->setPeripheral(p->core, mPERIPH_GBA_LINK_PORT, &p->driver.d);
				p->plugged = true;
			}
		}
		for (int i = nPlayers - 1; i >= 0; --i) {
			mCoreThreadContinue(&players[i].thread);
		}
	}

	for (int i = 0; i < nPlayers; ++i) {
		if ((activeMask & ~mask) & (1u << i)) {
			mCoreThreadPause(&players[i].thread);
		}
	}
	activeMask = mask;

	uint8_t status[2] = { mask, plugMask };
	emit(3, 0, status, sizeof(status));
	fprintf(stderr, "gbalink: playing mask %X, link cable mask %X\n", mask, plugMask);
}

// Reads `size` bytes from stdin into buf (keeping at most `cap`, discarding the rest).
static bool readExact(uint8_t* buf, size_t size, size_t cap) {
	size_t got = 0;
	uint8_t sink[64];
	while (got < size) {
		uint8_t* dst = got < cap ? buf + got : sink;
		size_t want = got < cap ? (size < cap ? size : cap) - got : (size - got < sizeof(sink) ? size - got : sizeof(sink));
		ssize_t n = read(STDIN_FILENO, dst, want);
		if (n <= 0) {
			if (n < 0 && errno == EINTR) {
				continue;
			}
			return false;
		}
		got += n;
	}
	return true;
}

static void* stdinLoop(void* unused) {
	(void) unused;
	uint8_t cmd[4];
	size_t have = 0;
	while (!atomic_load(&quitting)) {
		ssize_t r = read(STDIN_FILENO, cmd + have, sizeof(cmd) - have);
		if (r <= 0) {
			if (r < 0 && errno == EINTR) {
				continue;
			}
			break; // parent went away
		}
		have += r;
		if (have < sizeof(cmd)) {
			continue;
		}
		have = 0;
		int player = cmd[1];
		unsigned value = cmd[2] | (cmd[3] << 8);
		if (cmd[0] == 5) {
			uint8_t payload[2 + NETLINK_MAX_PLAYERS * NETLINK_CMD_WORDS * 2];
			if (!readExact(payload, value, sizeof(payload))) {
				break;
			}
			if (netStdio && value >= 2 && value <= sizeof(payload)) {
				struct NetLinkEvent ev = { .kind = payload[0], .player = payload[1] };
				memcpy(ev.words, payload + 2, ((value - 2) / 2) * 2);
				NetLinkPushEvent(&players[0].net, &ev);
			}
			continue;
		}
		if ((cmd[0] == 6 || cmd[0] == 7) && netStdio) {
			struct Player* p = &players[0];
			mCoreThreadInterrupt(&p->thread);
			if (cmd[0] == 6) {
				netLinkId = value & 3;
				NetLinkSetLocalId(&p->net, netLinkId);
			} else {
				NetLinkDisconnect(&p->net);
				fprintf(stderr, "gbalink: link cable pulled (relay session lost)\n");
			}
			mCoreThreadContinue(&p->thread);
			continue;
		}
		if (cmd[0] == 1 && player < nPlayers) {
			atomic_store(&players[player].keys, value & 0x3FF);
			atomic_fetch_or(&players[player].latched, value & 0x3FF);
		} else if (cmd[0] == 10 && player < nPlayers) {
			TouchNavTap(&players[player].touch, value & 0xFF, value >> 8);
		} else if (cmd[0] == 11 && player < nPlayers) {
			TouchNavSwipe(&players[player].touch, value & 0xFF, value >> 8);
		} else if (cmd[0] == 2) {
			setActive(value);
		} else if (cmd[0] == 9) {
			break;
		}
	}
	atomic_store(&quitting, true);
	return NULL;
}

static void onSignal(int sig) {
	(void) sig;
	atomic_store(&quitting, true);
}

int main(int argc, char** argv) {
	if (argc < 4) {
		fprintf(stderr, "usage: %s <rom> <save-dir> <players 1-4>\n", argv[0]);
		return 1;
	}
	nPlayers = atoi(argv[3]);
	if (nPlayers < 1 || nPlayers > 4) {
		fprintf(stderr, "gbalink: players must be 1-4\n");
		return 1;
	}
	debugLink = getenv("GBALINK_DEBUG") != NULL;
	if (getenv("GBALINK_NETSIM")) {
		netSimMs = atoi(getenv("GBALINK_NETSIM"));
		netLog = getenv("GBALINK_NETLOG") != NULL;
	}
	if (getenv("GBALINK_NETLINK") && !strcmp(getenv("GBALINK_NETLINK"), "stdio")) {
		if (nPlayers != 1) {
			fprintf(stderr, "gbalink: GBALINK_NETLINK=stdio runs exactly one local GBA\n");
			return 1;
		}
		netStdio = true;
		netLinkId = getenv("GBALINK_LINK_ID") ? atoi(getenv("GBALINK_LINK_ID")) : 0;
		if (netLinkId < 0 || netLinkId >= NETLINK_MAX_PLAYERS) {
			fprintf(stderr, "gbalink: GBALINK_LINK_ID must be 0-3\n");
			return 1;
		}
	}
	if (getenv("GBALINK_TRACE")) {
		traceFile = fopen(getenv("GBALINK_TRACE"), "wb");
	}
	// Keep the real stdout for our binary protocol; anything mGBA prints goes to stderr.
	outFd = dup(STDOUT_FILENO);
	dup2(STDERR_FILENO, STDOUT_FILENO);
	signal(SIGPIPE, SIG_IGN);
	signal(SIGINT, onSignal);
	signal(SIGTERM, onSignal);

	mLogSetDefaultLogger(&logger);
	GBASIOLockstepCoordinatorInit(&coordinator);
	for (int i = 0; i < nPlayers; ++i) {
		players[i].id = i;
		if (!setupPlayer(&players[i], argv[1], argv[2])) {
			return 1;
		}
		TouchNavInit(&players[i].touch, players[i].core);
		mLockstepThreadUserInit(&players[i].user, &players[i].thread);
		players[i].user.d.requestedId = _requestedId;
		createDriver(&players[i]);

		uint8_t info[8];
		uint16_t w = players[i].width, h = players[i].height;
		uint32_t rate = OUT_AUDIO_RATE;
		memcpy(info, &w, 2);
		memcpy(info + 2, &h, 2);
		memcpy(info + 4, &rate, 4);
		emit(0, i, info, sizeof(info));
	}

	for (int i = 0; i < nPlayers; ++i) {
		if (!mCoreThreadStart(&players[i].thread)) {
			fprintf(stderr, "gbalink: could not start thread %d\n", i);
			return 1;
		}
	}

	if (netSimMs >= 0) {
		for (int i = 0; i < nPlayers; ++i) {
			mCoreThreadInterrupt(&players[i].thread);
		}
		for (int i = 0; i < nPlayers; ++i) {
			struct Player* p = &players[i];
			NetLinkCreate(&p->net, i);
			p->net.sendHandshake = _netSendHandshake;
			p->net.sendCommand = _netSendCommand;
			p->core->setPeripheral(p->core, mPERIPH_GBA_LINK_PORT, &p->net.d);
		}
		for (int i = nPlayers - 1; i >= 0; --i) {
			mCoreThreadContinue(&players[i].thread);
		}
		pthread_t seq;
		pthread_create(&seq, NULL, sequencerLoop, NULL);
		pthread_detach(seq);
	}

	if (netStdio) {
		struct Player* p = &players[0];
		mCoreThreadInterrupt(&p->thread);
		NetLinkCreate(&p->net, netLinkId);
		p->net.sendHandshake = _stdioSendHandshake;
		p->net.sendCommand = _stdioSendCommand;
		p->core->setPeripheral(p->core, mPERIPH_GBA_LINK_PORT, &p->net.d);
		mCoreThreadContinue(&p->thread);
		fprintf(stderr, "gbalink: netlink over relay, this GBA is link player %d\n", netLinkId + 1);
	}

	// Everyone starts paused and unplugged until the server says who is playing.
	activeMask = (1u << nPlayers) - 1;
	setActive(0);
	fprintf(stderr, "gbalink: %d player(s) running\n", nPlayers);

	pthread_t input;
	pthread_create(&input, NULL, stdinLoop, NULL);
	pthread_detach(input);

	unsigned lastFrames[4] = {0};
	int stalledTicks = 0;
	while (!atomic_load(&quitting)) {
		usleep(100000);
		bool linkedStall = false;
		for (int i = 0; i < nPlayers; ++i) {
			if (mCoreThreadHasCrashed(&players[i].thread)) {
				fprintf(stderr, "gbalink: core %d crashed\n", i);
				atomic_store(&quitting, true);
			}
			unsigned f = atomic_load(&players[i].frames);
			if (!_netMode() && players[i].plugged && f == lastFrames[i]) {
				linkedStall = true;
			}
			lastFrames[i] = f;
		}
		stalledTicks = linkedStall ? stalledTicks + 1 : 0;
		if (stalledTicks >= 30) {
			replugLink();
			stalledTicks = 0;
		}
	}

	fprintf(stderr, "gbalink: shutting down, writing saves\n");
	setActive(0); // unplug the cable first so no core is left waiting on a partner
	for (int i = 0; i < nPlayers; ++i) {
		mCoreThreadEnd(&players[i].thread);
	}
	for (int i = 0; i < nPlayers; ++i) {
		mCoreThreadJoin(&players[i].thread);
	}
	for (int i = 0; i < nPlayers; ++i) {
		players[i].core->deinit(players[i].core);
	}
	if (traceFile) {
		fclose(traceFile);
	}
	return 0;
}
