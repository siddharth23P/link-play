/*
 * gbaprobe — headless, deterministic, scriptable GBA runner for exploring game
 * menus and testing TouchNav (touchnav.c) without a phone or a browser.
 *
 * Usage: bin/gbaprobe <rom> <save.sav|none> [script|-]
 *   Runs as fast as possible. The .sav is read into memory and never written
 *   (nor is the ROM); the RTC is a fixed fake clock, so runs repeat exactly.
 *   Script lines come from the file, or stdin with '-' (the default); '#' starts
 *   a comment. Every command prints one result line. Exit code 1 if any `expect`
 *   (or command) failed.
 *
 * Commands (numbers may be decimal or 0x hex; ADDR always hex):
 *   run N                      run N frames with the held keys
 *   hold KEYS | release        KEYS = A,B,SELECT,START,RIGHT,LEFT,UP,DOWN,R,L joined by '+'
 *   press KEYS [hold=3] [after=10]
 *   tap X Y [max=900]          TouchNav tap; runs until it is idle (or max frames)
 *   swipe DIR STEPS [max=900]  DIR = up|down|left|right
 *   shot FILE.png [scale=2]    PNG of the current frame
 *   peek ADDR LEN | peek8 ADDR | peek16 ADDR | peek32 ADDR       bus addresses
 *   poke8|poke16|poke32 ADDR VALUE   write emulated memory (e.g. to stock the bag for a test)
 *   search8|search16|search32 VALUE|any  start a RAM search (EWRAM + IWRAM)
 *   filter eq V | ne V | xor V | changed | unchanged | inc | dec   (xor: new ^ old == V, for
 *                              values the game stores encrypted, like bag quantities)
 *   results [max=20]
 *   state-save FILE | state-load FILE    mGBA savestates
 *   info                       TouchNavDescribe: screen, gMain callbacks, cursor, menu box
 *   pixel X Y                  colour of one pixel of the current frame
 *   findmain                   list gMain candidates (shape + VBlank counter over one frame)
 *   frame                      frame counter
 *   echo TEXT
 *   expect peek8|peek16|peek32 ADDR V
 *   expect cursor-x X [tol] | expect cursor-y Y [tol] | expect cursor none|found
 *   expect cb1 V | expect cb2 V | expect screen NAME | expect result TEXT
 *
 * Example:
 *   bin/gbaprobe rom.gba save.sav - <<'EOF'
 *   state-load overworld.ss
 *   press START after=30
 *   info
 *   tap 200 40
 *   shot menu.png
 *   EOF
 */
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/log.h>
#include <mgba/core/serialize.h>
#include <mgba-util/vfs.h>

#include "touchnav.h"

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

static struct mCore* core;
static uint32_t* video;
static unsigned width, height;
static struct TouchNav nav;
static uint16_t held;
static unsigned long frames;
static int failures;

static void _log(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
	(void) logger;
	if (!(level & (mLOG_FATAL | mLOG_ERROR))) {
		return;
	}
	fprintf(stderr, "gbaprobe: [%s] ", mLogCategoryName(category));
	vfprintf(stderr, format, args);
	fputc('\n', stderr);
}
static struct mLogger logger = { .log = _log };

static void stepFrame(void) {
	uint16_t keys = held;
	TouchNavStep(&nav, video, &keys);
	core->setKeys(core, keys);
	core->runFrame(core);
	++frames;
}

static void runFrames(long n) {
	for (long i = 0; i < n; ++i) {
		stepFrame();
	}
}

// ---------------------------------------------------------------- parsing

static const char* KEY_NAMES[] = { "A", "B", "SELECT", "START", "RIGHT", "LEFT", "UP", "DOWN", "R", "L" };

static bool parseKeys(const char* s, uint16_t* out) {
	*out = 0;
	if (!strcasecmp(s, "none") || !strcmp(s, "0")) {
		return true;
	}
	char buf[128];
	snprintf(buf, sizeof(buf), "%s", s);
	for (char* tok = strtok(buf, "+,"); tok; tok = strtok(NULL, "+,")) {
		int found = -1;
		for (int i = 0; i < 10; ++i) {
			if (!strcasecmp(tok, KEY_NAMES[i])) {
				found = i;
			}
		}
		if (found < 0) {
			return false;
		}
		*out |= 1 << found;
	}
	return true;
}

static bool parseNum(const char* s, long* out) {
	if (!s) {
		return false;
	}
	char* end;
	errno = 0;
	*out = strtol(s, &end, 0);
	return !errno && end != s && !*end;
}

static bool parseHex(const char* s, uint32_t* out) {
	if (!s) {
		return false;
	}
	char* end;
	*out = (uint32_t) strtoul(s, &end, 16);
	return end != s && !*end;
}

// Optional argument: positional number or name=number.
static long optArg(char** argv, int argc, int index, const char* name, long def) {
	for (int i = 1; i < argc; ++i) {
		size_t n = strlen(name);
		if (!strncmp(argv[i], name, n) && argv[i][n] == '=') {
			long v;
			return parseNum(argv[i] + n + 1, &v) ? v : def;
		}
	}
	long v;
	if (index < argc && !strchr(argv[index], '=') && parseNum(argv[index], &v)) {
		return v;
	}
	return def;
}

// ---------------------------------------------------------------- PNG

static void pngChunk(FILE* f, const char* type, const uint8_t* data, uint32_t len) {
	uint8_t head[8] = { len >> 24, len >> 16, len >> 8, len, type[0], type[1], type[2], type[3] };
	fwrite(head, 1, 8, f);
	if (len) {
		fwrite(data, 1, len, f);
	}
	uLong crc = crc32(0, head + 4, 4);
	if (len) {
		crc = crc32(crc, data, len);
	}
	uint8_t c[4] = { crc >> 24, crc >> 16, crc >> 8, crc };
	fwrite(c, 1, 4, f);
}

static bool writePng(const char* path, int scale) {
	if (scale < 1 || scale > 8) {
		scale = 2;
	}
	unsigned ow = width * scale, oh = height * scale;
	size_t stride = ow * 3 + 1, rawLen = stride * oh;
	uint8_t* raw = malloc(rawLen);
	for (unsigned y = 0; y < oh; ++y) {
		uint8_t* row = raw + y * stride;
		row[0] = 0;
		for (unsigned x = 0; x < ow; ++x) {
			uint32_t c = video[(y / scale) * width + x / scale];
			row[1 + x * 3] = c & 0xFF;
			row[2 + x * 3] = (c >> 8) & 0xFF;
			row[3 + x * 3] = (c >> 16) & 0xFF;
		}
	}
	uLongf zlen = compressBound(rawLen);
	uint8_t* z = malloc(zlen);
	bool ok = compress2(z, &zlen, raw, rawLen, 6) == Z_OK;
	FILE* f = ok ? fopen(path, "wb") : NULL;
	if (f) {
		static const uint8_t sig[8] = { 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
		uint8_t ihdr[13] = { ow >> 24, ow >> 16, ow >> 8, ow, oh >> 24, oh >> 16, oh >> 8, oh, 8, 2, 0, 0, 0 };
		fwrite(sig, 1, 8, f);
		pngChunk(f, "IHDR", ihdr, 13);
		pngChunk(f, "IDAT", z, (uint32_t) zlen);
		pngChunk(f, "IEND", NULL, 0);
		ok = !fclose(f);
	} else {
		ok = false;
	}
	free(raw);
	free(z);
	return ok;
}

// ---------------------------------------------------------------- RAM search

struct Hit {
	uint32_t addr, value;
};
static struct Hit* hits;
static size_t nHits;
static int searchWidth;

static uint32_t readN(uint32_t addr, int w) {
	return w == 1 ? core->busRead8(core, addr) : w == 2 ? core->busRead16(core, addr) : core->busRead32(core, addr);
}

static void searchStart(int w, uint32_t value, bool any) {
	static const uint32_t regions[2][2] = { { 0x02000000, 0x02040000 }, { 0x03000000, 0x03008000 } };
	free(hits);
	hits = malloc(sizeof(*hits) * ((0x40000 + 0x8000) / w));
	nHits = 0;
	searchWidth = w;
	for (int r = 0; r < 2; ++r) {
		for (uint32_t a = regions[r][0]; a < regions[r][1]; a += w) {
			uint32_t v = readN(a, w);
			if (any || v == value) {
				hits[nHits++] = (struct Hit) { a, v };
			}
		}
	}
}

static bool searchFilter(const char* op, const char* arg) {
	long want = 0;
	bool needArg = !strcmp(op, "eq") || !strcmp(op, "ne") || !strcmp(op, "xor");
	if (needArg && !parseNum(arg, &want)) {
		return false;
	}
	size_t kept = 0;
	for (size_t i = 0; i < nHits; ++i) {
		uint32_t v = readN(hits[i].addr, searchWidth), old = hits[i].value;
		bool keep;
		if (!strcmp(op, "eq")) {
			keep = v == (uint32_t) want;
		} else if (!strcmp(op, "ne")) {
			keep = v != (uint32_t) want;
		} else if (!strcmp(op, "xor")) {
			keep = (v ^ old) == (uint32_t) want;
		} else if (!strcmp(op, "changed")) {
			keep = v != old;
		} else if (!strcmp(op, "unchanged")) {
			keep = v == old;
		} else if (!strcmp(op, "inc")) {
			keep = v > old;
		} else if (!strcmp(op, "dec")) {
			keep = v < old;
		} else {
			return false;
		}
		if (keep) {
			hits[kept++] = (struct Hit) { hits[i].addr, v };
		}
	}
	nHits = kept;
	return true;
}

// ---------------------------------------------------------------- commands

static void fail(const char* fmt, ...) {
	va_list args;
	va_start(args, fmt);
	vprintf(fmt, args);
	va_end(args);
	putchar('\n');
	++failures;
}

static void runGesture(const char* what, long max) {
	long used = 0;
	while (TouchNavBusy(&nav) && used < max) {
		stepFrame();
		++used;
	}
	char line[512];
	TouchNavDescribe(&nav, video, line, sizeof(line));
	if (TouchNavBusy(&nav)) {
		TouchNavCancel(&nav);
		stepFrame();
		fail("%s: still busy after %ld frames (%s)", what, max, nav.last);
	} else {
		printf("%s: %s [%ld frames]\n", what, nav.last, used);
	}
}

static bool expectNum(const char* what, long got, long want, long tol) {
	if (labs(got - want) <= tol) {
		printf("expect %s: ok (%ld)\n", what, got);
		return true;
	}
	fail("expect %s: FAIL got %ld (0x%lX), want %ld (0x%lX)", what, got, got, want, want);
	return false;
}

static void cmdExpect(char** argv, int argc, const char* rest) {
	if (argc < 3 && !(argc == 2 && !strcmp(argv[1], "cursor"))) {
		fail("expect: missing arguments");
		return;
	}
	const char* what = argv[1];
	uint32_t addr;
	long v, tol;
	if (!strncmp(what, "peek", 4)) {
		int w = atoi(what + 4) / 8;
		if ((w != 1 && w != 2 && w != 4) || !parseHex(argv[2], &addr) || !parseNum(argc > 3 ? argv[3] : NULL, &v)) {
			fail("expect %s: bad arguments", what);
			return;
		}
		char label[64];
		snprintf(label, sizeof(label), "%s %08X", what, addr);
		expectNum(label, readN(addr, w), (uint32_t) v, 0);
	} else if (!strcmp(what, "cursor-x") || !strcmp(what, "cursor-y")) {
		struct TouchNavCursor c = TouchNavFindCursor(&nav, video);
		if (!parseNum(argv[2], &v)) {
			fail("expect %s: bad value", what);
		} else if (!c.found) {
			fail("expect %s: FAIL no cursor on screen", what);
		} else {
			tol = optArg(argv, argc, 3, "tol", 0);
			expectNum(what, what[7] == 'x' ? c.x : c.y, v, tol);
		}
	} else if (!strcmp(what, "cursor")) {
		struct TouchNavCursor c = TouchNavFindCursor(&nav, video);
		bool wantFound = argc > 2 && !strcmp(argv[2], "found");
		if (c.found == wantFound) {
			printf("expect cursor %s: ok\n", wantFound ? "found" : "none");
		} else {
			fail("expect cursor %s: FAIL", wantFound ? "found" : "none");
		}
	} else if (!strcmp(what, "cb1") || !strcmp(what, "cb2")) {
		uint32_t cb1, cb2;
		if (!parseNum(argv[2], &v)) {
			fail("expect %s: bad value", what);
		} else if (!TouchNavReadCallbacks(&nav, &cb1, &cb2)) {
			fail("expect %s: FAIL gMain unknown", what);
		} else {
			expectNum(what, what[2] == '1' ? cb1 : cb2, (uint32_t) v, 0);
		}
	} else if (!strcmp(what, "screen")) {
		struct TouchNavView view;
		const char* name = TouchNavScreenName(TouchNavScreenOf(&nav, video, &view));
		if (!strcmp(name, argv[2])) {
			printf("expect screen: ok (%s)\n", name);
		} else {
			fail("expect screen: FAIL got %s, want %s", name, argv[2]);
		}
	} else if (!strcmp(what, "result")) {
		const char* text = strstr(rest, "result") + 6;
		while (*text == ' ') {
			++text;
		}
		if (strstr(nav.last, text)) {
			printf("expect result: ok (%s)\n", nav.last);
		} else {
			fail("expect result: FAIL got \"%s\", want \"%s\"", nav.last, text);
		}
	} else {
		fail("expect: unknown check %s", what);
	}
}

static void cmdFindMain(void) {
	uint32_t addrs[512], counters[512];
	int n = 0;
	for (uint32_t a = 0x03000000; a < 0x03008000 - 0x440 && n < 512; a += 4) {
		uint32_t cb1 = core->busRead32(core, a), cb2 = core->busRead32(core, a + 4);
		bool rom1 = (cb1 & 1) && cb1 >= 0x08000000 && cb1 < 0x0A000000;
		bool rom2 = (cb2 & 1) && cb2 >= 0x08000000 && cb2 < 0x0A000000;
		if ((!cb1 || rom1) && rom2) {
			addrs[n] = a;
			counters[n] = core->busRead32(core, a + 0x20);
			++n;
		}
	}
	stepFrame();
	int shown = 0;
	for (int i = 0; i < n; ++i) {
		uint32_t delta = core->busRead32(core, addrs[i] + 0x20) - counters[i];
		shown += delta >= 1 && delta <= 3;
	}
	printf("findmain: %d with gMain's shape, %d of them counting VBlanks (shown); held keys 0x%03X\n", n, shown, held);
	for (int i = 0; i < n; ++i) {
		uint32_t a = addrs[i];
		uint32_t delta = core->busRead32(core, a + 0x20) - counters[i];
		if (delta < 1 || delta > 3) {
			continue;
		}
		printf("  0x%08X cb1=0x%08X cb2=0x%08X vblank+%d heldRaw=0x%03X newRaw=0x%03X%s\n", a,
		       core->busRead32(core, a), core->busRead32(core, a + 4), (int) (core->busRead32(core, a + 0x20) - counters[i]),
		       core->busRead16(core, a + 0x28), core->busRead16(core, a + 0x2A), a == nav.gMain ? "  <- in use" : "");
	}
}

static void execLine(char* line) {
	char* hash = strchr(line, '#');
	if (hash) {
		*hash = 0;
	}
	char original[512];
	snprintf(original, sizeof(original), "%s", line);
	for (size_t n = strlen(original); n && isspace((unsigned char) original[n - 1]); --n) {
		original[n - 1] = 0;
	}
	char* argv[16];
	int argc = 0;
	for (char* tok = strtok(line, " \t\r\n"); tok && argc < 16; tok = strtok(NULL, " \t\r\n")) {
		argv[argc++] = tok;
	}
	if (!argc) {
		return;
	}
	const char* cmd = argv[0];
	long a, b;
	uint32_t addr;
	uint16_t keys;
	if (!strcmp(cmd, "run")) {
		if (!parseNum(argc > 1 ? argv[1] : NULL, &a)) {
			fail("run: need a frame count");
			return;
		}
		runFrames(a);
		printf("run %ld: frame %lu\n", a, frames);
	} else if (!strcmp(cmd, "hold")) {
		if (argc < 2 || !parseKeys(argv[1], &keys)) {
			fail("hold: bad keys");
			return;
		}
		held = keys;
		printf("hold: 0x%03X\n", held);
	} else if (!strcmp(cmd, "release")) {
		held = 0;
		printf("release\n");
	} else if (!strcmp(cmd, "press")) {
		if (argc < 2 || !parseKeys(argv[1], &keys)) {
			fail("press: bad keys");
			return;
		}
		long hold = optArg(argv, argc, 2, "hold", 3), after = optArg(argv, argc, 3, "after", 10);
		uint16_t saved = held;
		held = keys;
		runFrames(hold);
		held = saved;
		runFrames(after);
		printf("press %s: frame %lu\n", argv[1], frames);
	} else if (!strcmp(cmd, "tap")) {
		if (!parseNum(argc > 1 ? argv[1] : NULL, &a) || !parseNum(argc > 2 ? argv[2] : NULL, &b)) {
			fail("tap: need X Y");
			return;
		}
		TouchNavTap(&nav, (int) a, (int) b);
		char what[64];
		snprintf(what, sizeof(what), "tap %ld %ld", a, b);
		runGesture(what, optArg(argv, argc, 3, "max", 900));
	} else if (!strcmp(cmd, "swipe")) {
		static const char* dirs[] = { "up", "down", "left", "right" };
		int dir = -1;
		for (int i = 0; i < 4; ++i) {
			if (argc > 1 && !strcasecmp(argv[1], dirs[i])) {
				dir = i;
			}
		}
		if (dir < 0 || !parseNum(argc > 2 ? argv[2] : NULL, &a)) {
			fail("swipe: need DIR STEPS");
			return;
		}
		TouchNavSwipe(&nav, dir, (int) a);
		char what[64];
		snprintf(what, sizeof(what), "swipe %s %ld", dirs[dir], a);
		runGesture(what, optArg(argv, argc, 3, "max", 900));
	} else if (!strcmp(cmd, "shot")) {
		if (argc < 2) {
			fail("shot: need a file");
		} else if (writePng(argv[1], (int) optArg(argv, argc, 2, "scale", 2))) {
			printf("shot: %s\n", argv[1]);
		} else {
			fail("shot: could not write %s", argv[1]);
		}
	} else if (!strcmp(cmd, "peek")) {
		if (!parseHex(argc > 1 ? argv[1] : NULL, &addr) || !parseNum(argc > 2 ? argv[2] : "16", &a) || a <= 0 || a > 4096) {
			fail("peek: need ADDR LEN");
			return;
		}
		printf("peek %08X:", addr);
		for (long i = 0; i < a; ++i) {
			printf("%s%02X", i && i % 16 == 0 ? "\n              " : " ", core->busRead8(core, addr + i));
		}
		putchar('\n');
	} else if (!strcmp(cmd, "peek8") || !strcmp(cmd, "peek16") || !strcmp(cmd, "peek32")) {
		int w = atoi(cmd + 4) / 8;
		if (!parseHex(argc > 1 ? argv[1] : NULL, &addr)) {
			fail("%s: need ADDR", cmd);
			return;
		}
		uint32_t v = readN(addr, w);
		printf("%s %08X: 0x%0*X (%u)\n", cmd, addr, w * 2, v, v);
	} else if (!strcmp(cmd, "poke8") || !strcmp(cmd, "poke16") || !strcmp(cmd, "poke32")) {
		int w = atoi(cmd + 4) / 8;
		if (!parseHex(argc > 1 ? argv[1] : NULL, &addr) || !parseNum(argc > 2 ? argv[2] : NULL, &a)) {
			fail("%s: need ADDR VALUE", cmd);
			return;
		}
		if (w == 1) {
			core->busWrite8(core, addr, (uint8_t) a);
		} else if (w == 2) {
			core->busWrite16(core, addr, (uint16_t) a);
		} else {
			core->busWrite32(core, addr, (uint32_t) a);
		}
		printf("%s %08X: 0x%0*X\n", cmd, addr, w * 2, readN(addr, w));
	} else if (!strcmp(cmd, "search8") || !strcmp(cmd, "search16") || !strcmp(cmd, "search32")) {
		bool any = argc > 1 && !strcmp(argv[1], "any");
		if (!any && !parseNum(argc > 1 ? argv[1] : NULL, &a)) {
			fail("%s: need VALUE or any", cmd);
			return;
		}
		searchStart(atoi(cmd + 6) / 8, any ? 0 : (uint32_t) a, any);
		printf("%s %s: %zu hits\n", cmd, argv[1], nHits);
	} else if (!strcmp(cmd, "filter")) {
		if (!hits || argc < 2 || !searchFilter(argv[1], argc > 2 ? argv[2] : NULL)) {
			fail("filter: bad filter (or no search started)");
			return;
		}
		printf("filter %s%s%s: %zu hits\n", argv[1], argc > 2 ? " " : "", argc > 2 ? argv[2] : "", nHits);
	} else if (!strcmp(cmd, "results")) {
		long max = optArg(argv, argc, 1, "max", 20);
		printf("results: %zu hits\n", nHits);
		for (size_t i = 0; i < nHits && (long) i < max; ++i) {
			printf("  %08X = 0x%0*X (%u)\n", hits[i].addr, searchWidth * 2, hits[i].value, hits[i].value);
		}
	} else if (!strcmp(cmd, "state-save")) {
		struct VFile* vf = argc > 1 ? VFileOpen(argv[1], O_CREAT | O_TRUNC | O_RDWR) : NULL;
		bool ok = vf && mCoreSaveStateNamed(core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_METADATA);
		if (vf) {
			vf->close(vf);
		}
		if (ok) {
			printf("state-save: %s\n", argv[1]);
		} else {
			fail("state-save: failed");
		}
	} else if (!strcmp(cmd, "state-load")) {
		struct VFile* vf = argc > 1 ? VFileOpen(argv[1], O_RDONLY) : NULL;
		bool ok = vf && mCoreLoadStateNamed(core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
		if (vf) {
			vf->close(vf);
		}
		TouchNavCancel(&nav);
		if (ok) {
			// Re-render so `video` (and taps) match the loaded state, not the old picture.
			uint16_t saved = held;
			held = 0;
			stepFrame();
			held = saved;
			printf("state-load: %s\n", argv[1]);
		} else {
			fail("state-load: failed");
		}
	} else if (!strcmp(cmd, "info")) {
		char text[512];
		TouchNavDescribe(&nav, video, text, sizeof(text));
		printf("info: %s\n", text);
	} else if (!strcmp(cmd, "pixel")) {
		if (!parseNum(argc > 1 ? argv[1] : NULL, &a) || !parseNum(argc > 2 ? argv[2] : NULL, &b) || a < 0 || b < 0 || a >= width || b >= height) {
			fail("pixel: need X Y on screen");
			return;
		}
		uint32_t c = video[b * width + a];
		printf("pixel %ld %ld: #%02X%02X%02X\n", a, b, c & 0xFF, (c >> 8) & 0xFF, (c >> 16) & 0xFF);
	} else if (!strcmp(cmd, "findmain")) {
		cmdFindMain();
	} else if (!strcmp(cmd, "frame")) {
		printf("frame: %lu (core %u)\n", frames, core->frameCounter(core));
	} else if (!strcmp(cmd, "echo")) {
		const char* text = original + strspn(original, " \t");
		printf("%s\n", text + 4 + strspn(text + 4, " \t"));
	} else if (!strcmp(cmd, "expect")) {
		cmdExpect(argv, argc, original);
	} else {
		fail("unknown command: %s", cmd);
	}
	fflush(stdout);
}

int main(int argc, char** argv) {
	if (argc < 3) {
		fprintf(stderr, "usage: %s <rom> <save.sav|none> [script|-]\n", argv[0]);
		return 2;
	}
	mLogSetDefaultLogger(&logger);
	core = mCoreFind(argv[1]);
	if (!core || !core->init(core)) {
		fprintf(stderr, "gbaprobe: no core for %s\n", argv[1]);
		return 2;
	}
	mCoreInitConfig(core, NULL);
	mCoreConfigSetValue(&core->config, "idleOptimization", "remove"); // same as the real hosts
	mCoreLoadConfig(core);
	core->baseVideoSize(core, &width, &height);
	if (width != TOUCHNAV_WIDTH || height != TOUCHNAV_HEIGHT || sizeof(mColor) != 4) {
		fprintf(stderr, "gbaprobe: expected a 240x160 32-bit GBA picture\n");
		return 2;
	}
	video = calloc(width * height, sizeof(*video));
	core->setVideoBuffer(core, (mColor*) video, width);
	core->setAudioBufferSize(core, 2048);
	if (!mCoreLoadFile(core, argv[1])) {
		fprintf(stderr, "gbaprobe: could not load %s\n", argv[1]);
		return 2;
	}

	// The save is copied into memory: the game may write to it, the file never changes.
	uint8_t* save = NULL;
	long saveSize = 0;
	if (strcmp(argv[2], "none")) {
		FILE* f = fopen(argv[2], "rb");
		if (!f) {
			fprintf(stderr, "gbaprobe: could not read %s\n", argv[2]);
			return 2;
		}
		fseek(f, 0, SEEK_END);
		saveSize = ftell(f);
		fseek(f, 0, SEEK_SET);
		save = malloc(saveSize ? saveSize : 1);
		if (fread(save, 1, saveSize, f) != (size_t) saveSize) {
			fprintf(stderr, "gbaprobe: could not read %s\n", argv[2]);
			return 2;
		}
		fclose(f);
	}
	struct VFile* vf = VFileMemChunk(save, saveSize);
	free(save);
	if (!vf || !core->loadSave(core, vf)) {
		fprintf(stderr, "gbaprobe: could not use the save\n");
		return 2;
	}
	// A fake clock that starts at 2026-01-01 12:00 UTC and follows emulated time.
	core->rtc.override = RTC_FAKE_EPOCH;
	core->rtc.value = 1767268800000LL;
	core->reset(core);
	TouchNavInit(&nav, core);

	FILE* in = stdin;
	if (argc > 3 && strcmp(argv[3], "-")) {
		in = fopen(argv[3], "r");
		if (!in) {
			fprintf(stderr, "gbaprobe: could not open %s\n", argv[3]);
			return 2;
		}
	}
	char line[512];
	while (fgets(line, sizeof(line), in)) {
		execLine(line);
	}
	if (in != stdin) {
		fclose(in);
	}
	TouchNavDeinit(&nav);
	core->deinit(core);
	return failures ? 1 : 0;
}
