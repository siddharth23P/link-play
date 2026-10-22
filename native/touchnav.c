/*
 * TouchNav core: see touchnav.h (public API) and touchnav_int.h (what area files use).
 * Layout of this file:
 *   frame analysis (▶ cursor, menu window), gMain finder, RAM readers (sMenu, gWindows),
 *   the request queue, the per-frame key press machine, the generic ▶ handlers,
 *   and at the end the screen registry (the area tables) and the ROM profiles.
 * Screens with their own handling live in touchnav_<area>.c.
 */
#include "touchnav_int.h"

#include <mgba/core/core.h>

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define W TN_W
#define H TN_H

#define JOB_MAX_FRAMES 1200 // give up on a gesture after ~20 s whatever happens
#define SWIPE_MAX_STEPS 40
// A gesture queued behind the one that opened a screen waits this long at most for the
// old screen to fade out and the new one to fade in (START -> BAG measured 53 frames).
#define TRANSITION_MAX 180
// Without gPaletteFade's address: the overall brightness moving by more than this per frame
// is a fade, and it is over once it held still for FADE_QUIET frames (fades step every other
// frame, and the game keeps ignoring input for ~4 frames after the last step).
#define FADE_LUM_STEP 2
#define FADE_QUIET 6

// Cursor glyphs measured from Pokémon Quetzal frames (row widths of a ▶, 9 rows tall).
static const uint8_t SHAPES[2][9] = {
	{ 1, 2, 3, 4, 5, 4, 3, 2, 1 }, // thin: start menu, yes/no, multichoice lists
	{ 2, 3, 4, 5, 5, 5, 4, 3, 2 }, // bold: battle menu, move list
};
const char* const TN_CURSOR_NAMES[2] = { "thin", "bold" };

static const struct TouchNavScreen* _screenAt(int id);
static const struct TouchNavProfile* _profileFor(struct mCore* core);

// ---------------------------------------------------------------- frame analysis

static void _luminance(uint8_t* lum, const uint32_t* frame) {
	for (int i = 0; i < W * H; ++i) {
		uint32_t c = frame[i];
		int r = c & 0xF8, g = (c >> 8) & 0xFC, b = (c >> 16) & 0xF8;
		lum[i] = (uint8_t) ((r * 30 + g * 59 + b * 11) / 100);
	}
}

// The ▶ (top-left of the glyph): uniform ink, clean paper all around it.
struct TouchNavCursor tnFindCursor(const uint32_t* frame, const uint8_t* lum) {
	for (int s = 0; s < 2; ++s) {
		const uint8_t* rows = SHAPES[s];
		const int h = 9;
		for (int y = 1; y < H - h - 1; ++y) {
			for (int x = 1; x < W - 7; ++x) {
				if (lum[y * W + x] > TN_INK_MAX) {
					continue;
				}
				uint32_t color = frame[y * W + x] & 0xFFFFFF;
				bool ok = true;
				for (int dy = 0; dy < h && ok; ++dy) {
					int w = rows[dy];
					const uint32_t* row = frame + (y + dy) * W + x;
					for (int dx = 0; dx < w; ++dx) {
						if ((row[dx] & 0xFFFFFF) != color) {
							ok = false;
							break;
						}
					}
					if (ok && (lum[(y + dy) * W + x - 1] < TN_PAPER_MIN || lum[(y + dy) * W + x + w] < TN_PAPER_MIN)) {
						ok = false;
					}
				}
				for (int dx = 0; dx < 5 && ok; ++dx) {
					if (lum[(y - 1) * W + x + dx] < TN_PAPER_MIN || lum[(y + h) * W + x + dx] < TN_PAPER_MIN) {
						ok = false;
					}
				}
				if (ok) {
					return (struct TouchNavCursor) { .x = x, .y = y, .kind = s, .found = true };
				}
			}
		}
	}
	return (struct TouchNavCursor) { .found = false };
}

// Walk from (x, y) until the window frame (two non-paper pixels in a row).
static int _scanPaper(const uint8_t* lum, int x, int y, int dx, int dy) {
	int run = 0;
	while (x >= 0 && y >= 0 && x < W && y < H) {
		run = lum[y * W + x] >= TN_PAPER_MIN ? 0 : run + 1;
		if (run >= 2) {
			return dx ? x - dx * 2 : y - dy * 2;
		}
		x += dx;
		y += dy;
	}
	return dx ? (dx > 0 ? W - 1 : 0) : (dy > 0 ? H - 1 : 0);
}

struct TouchNavBox tnMenuBox(const uint8_t* lum, struct TouchNavCursor c) {
	return (struct TouchNavBox) {
		.left = _scanPaper(lum, c.x - 1, c.y + 4, -1, 0),
		.right = _scanPaper(lum, c.x + 6, c.y - 1, 1, 0),
		.top = _scanPaper(lum, c.x - 1, c.y, 0, -1),
		.bottom = _scanPaper(lum, c.x - 1, c.y + 8, 0, 1),
	};
}

// The item text on the cursor's row. If it changes while the cursor stays put,
// the list scrolled under the cursor.
uint32_t tnRowHash(const uint8_t* lum, struct TouchNavCursor c, struct TouchNavBox box) {
	uint32_t h = 2166136261u;
	int x0 = c.x + 8, x1 = box.right - 1, y0 = c.y - 3, y1 = c.y + 11;
	for (int y = y0 < 0 ? 0 : y0; y <= y1 && y < H; ++y) {
		for (int x = x0; x <= x1 && x < W; ++x) {
			h = (h ^ (lum[y * W + x] >> 4)) * 16777619u;
		}
	}
	return h;
}

// Where the cursor's item text ends: the last ink right of the ▶ before a wide gap.
int tnTextEnd(const uint8_t* lum, struct TouchNavCursor c, struct TouchNavBox box) {
	int end = c.x + 6, gap = 0;
	for (int x = c.x + 7; x <= box.right && x < W; ++x) {
		bool ink = false;
		for (int y = c.y - 2; y <= c.y + 11 && !ink; ++y) {
			ink = y >= 0 && y < H && lum[y * W + x] < TN_PAPER_MIN;
		}
		if (ink) {
			end = x;
			gap = 0;
		} else if (++gap > TN_TEXT_GAP) {
			break;
		}
	}
	return end;
}

uint32_t tnFrameHash(const uint32_t* frame, struct TouchNavBox r) {
	uint32_t h = 2166136261u;
	for (int y = r.top < 0 ? 0 : r.top; y <= r.bottom && y < H; ++y) {
		for (int x = r.left < 0 ? 0 : r.left; x <= r.right && x < W; ++x) {
			h = (h ^ (frame[y * W + x] & 0xFFFFFF)) * 16777619u;
		}
	}
	return h;
}

struct TouchNavCursor TouchNavFindCursor(struct TouchNav* nav, const uint32_t* frame) {
	_luminance(nav->lum, frame);
	return tnFindCursor(frame, nav->lum);
}

bool tnSame(struct TouchNavCursor a, struct TouchNavCursor b) {
	return a.x == b.x && a.y == b.y;
}

// ---------------------------------------------------------------- gMain

// pokeemerald's struct Main: callback1 +0x00, callback2 +0x04, vblankCallback +0x0C,
// vblankCounter1 +0x20, heldKeysRaw +0x28, newKeysRaw +0x2A, heldKeys +0x2C,
// newKeys +0x2E; inBattle = bit 1 of +0x439.
#define MAIN_CB1 0x00
#define MAIN_CB2 0x04
#define MAIN_VBLANK_COUNTER 0x20
#define MAIN_HELD_RAW 0x28
#define MAIN_FLAGS 0x439

static bool _romThumb(uint32_t p) {
	return (p & 1) && p >= 0x08000000 && p < 0x0A000000;
}

static bool _looksLikeMain(struct mCore* core, uint32_t a) {
	uint32_t cb1 = core->busRead32(core, a + MAIN_CB1), cb2 = core->busRead32(core, a + MAIN_CB2);
	return (!cb1 || _romThumb(cb1)) && _romThumb(cb2);
}

static void _scanForMain(struct TouchNav* nav) {
	struct mCore* core = nav->core;
	nav->nCandidates = 0;
	for (uint32_t a = 0x03000000; a < 0x03008000 - 0x440 && nav->nCandidates < 256; a += 4) {
		if (_looksLikeMain(core, a)) {
			nav->candidates[nav->nCandidates] = a;
			nav->candidateCounter[nav->nCandidates] = core->busRead32(core, a + MAIN_VBLANK_COUNTER);
			nav->candidateHits[nav->nCandidates] = 0;
			++nav->nCandidates;
		}
	}
}

// Runs every frame while gMain is unknown: candidates must keep the struct's shape,
// count VBlanks one per frame, and (when keys are held) show those keys.
static void _trackMain(struct TouchNav* nav) {
	if (nav->gMain || !nav->core) {
		return;
	}
	if (!nav->nCandidates) {
		if (nav->finderWait > 0) {
			--nav->finderWait;
			return;
		}
		_scanForMain(nav);
		nav->finderWait = 120;
		return;
	}
	struct mCore* core = nav->core;
	bool keysStable = nav->appliedKeys && nav->appliedKeys == nav->prevApplied;
	int kept = 0;
	for (int i = 0; i < nav->nCandidates; ++i) {
		uint32_t a = nav->candidates[i];
		uint32_t counter = core->busRead32(core, a + MAIN_VBLANK_COUNTER);
		uint32_t delta = counter - nav->candidateCounter[i];
		bool ok = _looksLikeMain(core, a) && delta >= 1 && delta <= 3; // VBlanks: one per frame
		if (ok && keysStable && core->busRead16(core, a + MAIN_HELD_RAW) != nav->appliedKeys) {
			ok = false;
		}
		if (!ok) {
			continue;
		}
		nav->candidates[kept] = a;
		nav->candidateCounter[kept] = counter;
		nav->candidateHits[kept] = nav->candidateHits[i] + (nav->candidateHits[i] < 255);
		++kept;
	}
	nav->nCandidates = kept;
	if (kept == 1 && nav->candidateHits[0] >= 60) {
		nav->gMain = nav->candidates[0];
		nav->nCandidates = 0;
	}
}

uint32_t TouchNavFindMain(struct TouchNav* nav) {
	if (!nav->gMain) {
		_scanForMain(nav);
		if (nav->nCandidates == 1) {
			nav->gMain = nav->candidates[0];
			nav->nCandidates = 0;
		}
	}
	return nav->gMain;
}

// gPaletteFade.active: bit 7 of the byte at +7 (bitfield after blendColor:15). Set from the
// first fade step until a few frames after the last one; menus ignore input meanwhile.
#define FADE_ACTIVE_BYTE 7

int tnFadeActive(struct TouchNav* nav) {
	if (!nav->profile || !nav->profile->paletteFade) {
		return -1;
	}
	return nav->core->busRead8(nav->core, nav->profile->paletteFade + FADE_ACTIVE_BYTE) >> 7;
}

bool TouchNavReadCallbacks(struct TouchNav* nav, uint32_t* cb1, uint32_t* cb2) {
	*cb1 = *cb2 = 0;
	if (!nav->gMain || !_looksLikeMain(nav->core, nav->gMain)) {
		return false;
	}
	*cb1 = nav->core->busRead32(nav->core, nav->gMain + MAIN_CB1);
	*cb2 = nav->core->busRead32(nav->core, nav->gMain + MAIN_CB2);
	return true;
}

// ---------------------------------------------------------------- RAM readers

bool tnQuetzal(const struct TouchNav* nav) {
	return nav->profile && !nav->profile->screens;
}

uint8_t tnRead8(struct TouchNav* nav, uint32_t addr) {
	return nav->core->busRead8(nav->core, addr);
}

uint16_t tnRead16(struct TouchNav* nav, uint32_t addr) {
	return nav->core->busRead16(nav->core, addr);
}

uint32_t tnRead32(struct TouchNav* nav, uint32_t addr) {
	return nav->core->busRead32(nav->core, addr);
}

bool tnInBattle(struct TouchNav* nav) {
	return nav->gMain && (tnRead8(nav, nav->gMain + MAIN_FLAGS) >> 1) & 1;
}

#define MENU_SIZE 12
#define WINDOW_SIZE 12
#define WINDOWS_MAX 32
#define WINDOW_UNUSED 0xFF

bool tnReadMenu(struct TouchNav* nav, struct TouchNavMenu* out) {
	memset(out, 0, sizeof(*out));
	if (!nav->profile || !nav->profile->sMenu) {
		return false;
	}
	uint8_t b[MENU_SIZE];
	for (int i = 0; i < MENU_SIZE; ++i) {
		b[i] = tnRead8(nav, nav->profile->sMenu + i);
	}
	*out = (struct TouchNavMenu) {
		.left = b[0], .top = b[1], .cursorPos = (int8_t) b[2], .minCursorPos = (int8_t) b[3], .maxCursorPos = (int8_t) b[4],
		.windowId = b[5], .fontId = b[6], .optionWidth = b[7], .optionHeight = b[8], .columns = b[9], .rows = b[10],
		.APressMuted = b[11] != 0,
	};
	return true;
}

bool tnReadWindow(struct TouchNav* nav, int id, struct TouchNavWindow* out) {
	memset(out, 0, sizeof(*out));
	if (!nav->profile || !nav->profile->gWindows || id < 0 || id >= WINDOWS_MAX) {
		return false;
	}
	uint32_t a = nav->profile->gWindows + id * WINDOW_SIZE;
	*out = (struct TouchNavWindow) {
		.bg = tnRead8(nav, a), .tilemapLeft = tnRead8(nav, a + 1), .tilemapTop = tnRead8(nav, a + 2),
		.width = tnRead8(nav, a + 3), .height = tnRead8(nav, a + 4), .paletteNum = tnRead8(nav, a + 5),
		.baseBlock = tnRead16(nav, a + 6), .tileData = tnRead32(nav, a + 8),
	};
	return out->bg != WINDOW_UNUSED;
}

struct TouchNavBox tnWindowBox(const struct TouchNavWindow* w) {
	return (struct TouchNavBox) {
		.left = w->tilemapLeft * 8,
		.right = (w->tilemapLeft + w->width) * 8 - 1,
		.top = w->tilemapTop * 8,
		.bottom = (w->tilemapTop + w->height) * 8 - 1,
	};
}

void tnMenuItemCursor(const struct TouchNavMenu* m, const struct TouchNavWindow* w, int index, int* x, int* y) {
	int col = m->columns ? index % m->columns : 0;
	int row = m->columns ? index / m->columns : index;
	*x = w->tilemapLeft * 8 + m->left + col * m->optionWidth + 1;
	*y = w->tilemapTop * 8 + m->top + row * m->optionHeight + 3;
}

bool tnMenuLive(struct TouchNav* nav, const struct TouchNavView* v, struct TouchNavMenu* menu, struct TouchNavWindow* window) {
	bool known = tnReadMenu(nav, menu);
	if (!tnReadWindow(nav, menu->windowId, window) || !known || !v->cursor.found) {
		return false;
	}
	int x, y;
	tnMenuItemCursor(menu, window, menu->cursorPos, &x, &y);
	return abs(v->cursor.x - x) <= 1 && abs(v->cursor.y - y) <= 1;
}

// ---------------------------------------------------------------- setup and requests

static bool _headerIs(struct mCore* core, uint32_t addr, const char* want, int len) {
	for (int i = 0; i < len; ++i) {
		char c = (char) core->busRead8(core, addr + i);
		char w = i < (int) strlen(want) ? want[i] : 0;
		if (c != w) {
			return false;
		}
	}
	return true;
}

void TouchNavInit(struct TouchNav* nav, struct mCore* core) {
	memset(nav, 0, sizeof(*nav));
	pthread_mutex_init(&nav->lock, NULL);
	nav->core = core;
	nav->profile = _profileFor(core);
	nav->gMain = nav->profile ? nav->profile->gMain : 0;
	nav->job = calloc(1, sizeof(*nav->job));
	snprintf(nav->last, sizeof(nav->last), "-");
}

void TouchNavDeinit(struct TouchNav* nav) {
	free(nav->job);
	nav->job = NULL;
	pthread_mutex_destroy(&nav->lock);
}

static void _enqueue(struct TouchNav* nav, struct TouchNavRequest r) {
	pthread_mutex_lock(&nav->lock);
	struct TouchNavRequest* last = nav->queued ? &nav->queue[nav->queued - 1] : NULL;
	if (r.type == TN_REQ_SWIPE && last && last->type == TN_REQ_SWIPE && last->dir == r.dir) {
		last->steps = last->steps + r.steps > SWIPE_MAX_STEPS ? SWIPE_MAX_STEPS : last->steps + r.steps;
	} else if (nav->queued < TOUCHNAV_QUEUE) {
		nav->queue[nav->queued++] = r;
	}
	atomic_store(&nav->busy, true);
	pthread_mutex_unlock(&nav->lock);
}

void TouchNavTap(struct TouchNav* nav, int x, int y) {
	x = x < 0 ? 0 : x >= W ? W - 1 : x;
	y = y < 0 ? 0 : y >= H ? H - 1 : y;
	_enqueue(nav, (struct TouchNavRequest) { .type = TN_REQ_TAP, .x = x, .y = y });
}

void TouchNavSwipe(struct TouchNav* nav, int dir, int steps) {
	if (dir < 0 || dir > 3 || steps <= 0) {
		return;
	}
	_enqueue(nav, (struct TouchNavRequest) { .type = TN_REQ_SWIPE, .dir = dir, .steps = steps > SWIPE_MAX_STEPS ? SWIPE_MAX_STEPS : steps });
}

void TouchNavCancel(struct TouchNav* nav) {
	pthread_mutex_lock(&nav->lock);
	nav->queued = 0;
	pthread_mutex_unlock(&nav->lock);
	atomic_store(&nav->cancel, true);
}

bool TouchNavBusy(const struct TouchNav* nav) {
	return atomic_load(&nav->busy);
}

static bool _dequeue(struct TouchNav* nav, struct TouchNavRequest* out) {
	pthread_mutex_lock(&nav->lock);
	bool got = nav->queued > 0;
	if (got) {
		*out = nav->queue[0];
		memmove(&nav->queue[0], &nav->queue[1], (nav->queued - 1) * sizeof(nav->queue[0]));
		--nav->queued;
	} else {
		atomic_store(&nav->busy, false);
	}
	pthread_mutex_unlock(&nav->lock);
	return got;
}

// More of the same swipe arrived while it runs: keep going instead of queueing.
static int _mergeSwipes(struct TouchNav* nav, int dir) {
	int steps = 0;
	pthread_mutex_lock(&nav->lock);
	while (nav->queued && nav->queue[0].type == TN_REQ_SWIPE && nav->queue[0].dir == dir) {
		steps += nav->queue[0].steps;
		memmove(&nav->queue[0], &nav->queue[1], (nav->queued - 1) * sizeof(nav->queue[0]));
		--nav->queued;
	}
	pthread_mutex_unlock(&nav->lock);
	return steps;
}

// ---------------------------------------------------------------- the per-frame machine

void tnResult(struct TouchNav* nav, const char* fmt, ...) {
	va_list args;
	va_start(args, fmt);
	vsnprintf(nav->last, sizeof(nav->last), fmt, args);
	va_end(args);
}

// The screen is keyed by callback2: its Quetzal values (in the definition) or, for another
// ROM, its name in that ROM's profile.
static bool _keyed(const struct TouchNav* nav, const struct TouchNavScreen* s) {
	if (s->cb2[0]) {
		return true;
	}
	if (nav->profile && nav->profile->screens) {
		for (const struct TouchNavScreenCb* p = nav->profile->screens; p->cb2; ++p) {
			if (!strcmp(p->name, s->name)) {
				return true;
			}
		}
	}
	return false;
}

static bool _cb2Is(const struct TouchNav* nav, const struct TouchNavScreen* s, uint32_t cb2) {
	if (!nav->profile || !cb2) {
		return false;
	}
	if (nav->profile->screens) {
		for (const struct TouchNavScreenCb* p = nav->profile->screens; p->cb2; ++p) {
			if (p->cb2 == cb2 && !strcmp(p->name, s->name)) {
				return true;
			}
		}
		return false;
	}
	for (int i = 0; i < TN_CB2_MAX && s->cb2[i]; ++i) {
		if (s->cb2[i] == cb2) {
			return true;
		}
	}
	return false;
}

static bool _matches(struct TouchNav* nav, const struct TouchNavScreen* s, const struct TouchNavView* v) {
	if (_keyed(nav, s)) {
		if (!_cb2Is(nav, s, v->cb2)) {
			return false;
		}
	} else if (!s->detect) {
		return false; // neither callback2 values nor a detector (the generic screen)
	}
	return !s->detect || s->detect(nav, v);
}

static const struct TouchNavAreaTable* _areas(int i);

int TouchNavScreenOf(struct TouchNav* nav, const uint32_t* frame, struct TouchNavView* v) {
	_luminance(nav->lum, frame);
	v->frame = frame;
	v->lum = nav->lum;
	TouchNavReadCallbacks(nav, &v->cb1, &v->cb2);
	v->cursor = tnFindCursor(frame, nav->lum);
	int best = TN_SCREEN_GENERIC, bestPriority = 0, id = 0;
	const struct TouchNavAreaTable* area;
	for (int a = 0; (area = _areas(a)); ++a) {
		for (int i = 0; i < area->count; ++i, ++id) {
			const struct TouchNavScreen* s = &area->screens[i];
			if ((best == TN_SCREEN_GENERIC || s->priority > bestPriority) && _matches(nav, s, v)) {
				best = id;
				bestPriority = s->priority;
			}
		}
	}
	return best;
}

const char* TouchNavScreenName(int id) {
	const struct TouchNavScreen* s = _screenAt(id);
	return s && s->name ? s->name : "?";
}

static void _endJob(struct TouchNav* nav) {
	nav->active = false;
	nav->pressHold = nav->pressGap = 0;
	pthread_mutex_lock(&nav->lock);
	if (!nav->queued) {
		atomic_store(&nav->busy, false);
	}
	pthread_mutex_unlock(&nav->lock);
}

// The previous screen is still going away or the next one still coming up: a palette
// fade runs (gPaletteFade.active; without its address, the overall brightness still
// moving), or the picture is one flat colour (between a fade-out and the next fade-in).
static bool _inTransition(struct TouchNav* nav, struct TouchNavJob* j, const struct TouchNavView* v) {
	int fade = tnFadeActive(nav);
	if (fade < 0) {
		long sum = 0;
		for (int i = 0; i < W * H; ++i) {
			sum += v->lum[i];
		}
		int mean = (int) (sum / (W * H));
		// Called on every frame until the first press, so lumMean is last frame's (from frame 2 on).
		j->lumQuiet = j->frames > 1 && abs(mean - j->lumMean) <= FADE_LUM_STEP ? j->lumQuiet + 1 : 0;
		j->lumMean = mean;
		fade = j->lumQuiet < FADE_QUIET;
	}
	if (fade) {
		return true;
	}
	uint32_t c = v->frame[0] & 0xFFFFFF;
	for (int i = 1; i < W * H; ++i) {
		if ((v->frame[i] & 0xFFFFFF) != c) {
			return false;
		}
	}
	return true;
}

static uint16_t _decide(struct TouchNav* nav, const uint32_t* frame) {
	struct TouchNavJob* j = nav->job;
	struct TouchNavView v;
	int id = TouchNavScreenOf(nav, frame, &v);
	if (!j->started) {
		// Nothing pressed yet. A tap queued right behind the one that opened a screen would
		// be spent on the old screen fading out (an A the game ignores): wait for the change
		// to finish, then use the handler of the screen that is up by then.
		if (_inTransition(nav, j, &v) && ++j->transitionWait < TRANSITION_MAX) {
			return TN_WAIT;
		}
		j->screenId = id;
		j->screen = _screenAt(id);
	}
	TouchNavHandler tap = j->screen->tap ? j->screen->tap : tnGenericTap;
	TouchNavHandler swipe = j->screen->swipe ? j->screen->swipe : tnGenericSwipe;
	if (j->req.type == TN_REQ_SWIPE) {
		j->steps += _mergeSwipes(nav, j->req.dir);
		return swipe(nav, j, &v);
	}
	return tap(nav, j, &v);
}

// A steering press settles early once the ▶ has moved and stayed put for a frame. An
// unmoved cursor still waits out the whole gap, to tell a list edge from a scroll.
static bool _cursorSettled(struct TouchNav* nav, const uint32_t* frame) {
	struct TouchNavJob* j = nav->job;
	struct TouchNavCursor c = TouchNavFindCursor(nav, frame);
	if (!c.found || tnSame(c, j->cur)) {
		j->stable = 0;
		return false;
	}
	if (j->stable && tnSame(c, j->moved)) {
		return true;
	}
	j->moved = c;
	j->stable = 1;
	return false;
}

static void _applied(struct TouchNav* nav, uint16_t keys) {
	nav->prevApplied = nav->appliedKeys;
	nav->appliedKeys = keys;
}

bool TouchNavStep(struct TouchNav* nav, const uint32_t* frame, uint16_t* keys) {
	_trackMain(nav);
	if (atomic_exchange(&nav->cancel, false) && nav->active) {
		tnResult(nav, "cancelled");
		_endJob(nav);
	}
	if (!nav->active) {
		struct TouchNavRequest req;
		if (!_dequeue(nav, &req)) {
			_applied(nav, *keys);
			return false;
		}
		if (!nav->job) {
			tnResult(nav, "dropped: no memory for the gesture");
			_endJob(nav);
			_applied(nav, *keys);
			return false;
		}
		memset(nav->job, 0, sizeof(*nav->job));
		nav->job->req = req;
		nav->job->tapX = req.x;
		nav->job->tapY = req.y;
		nav->job->steps = req.type == TN_REQ_SWIPE ? req.steps : 0;
		nav->job->rowHeight = 16;
		nav->active = true;
		nav->pressHold = nav->pressGap = 0;
	}
	struct TouchNavJob* j = nav->job;
	if (++j->frames > JOB_MAX_FRAMES) {
		tnResult(nav, "gave up after %d frames", JOB_MAX_FRAMES);
		_endJob(nav);
		_applied(nav, *keys);
		return false;
	}
	bool decide = false;
	if (j->watchChange && !j->changed && (nav->pressHold > 0 || nav->pressGap > 0)) {
		j->changed = tnFrameHash(frame, j->watch) != j->preHash;
	}
	if (nav->pressHold > 0) {
		--nav->pressHold;
		*keys = nav->pressKeys;
	} else if (nav->pressGap > 0 && !(j->settleOnMove && _cursorSettled(nav, frame))) {
		--nav->pressGap;
		*keys = 0;
	} else {
		decide = true;
	}
	if (decide) {
		uint16_t next = _decide(nav, frame);
		if (!next) {
			_endJob(nav);
			_applied(nav, *keys);
			return false;
		}
		if (next == TN_WAIT) {
			nav->pressKeys = 0;
			nav->pressHold = nav->pressGap = 0;
			*keys = 0;
		} else {
			const struct TouchNavScreen* s = j->screen; // set by now: _decide picks it before any press
			int hold = s->hold ? s->hold : TN_HOLD_FRAMES;
			int gap = j->req.type == TN_REQ_SWIPE ? (s->swipeGap ? s->swipeGap : TN_SWIPE_GAP) : (s->gap ? s->gap : TN_GAP_FRAMES);
			j->started = true;
			nav->pressKeys = next;
			nav->pressHold = hold - 1;
			nav->pressGap = gap;
			*keys = next;
		}
	}
	_applied(nav, *keys);
	return true;
}

// ---------------------------------------------------------------- generic handler: steer the ▶

uint16_t tnPress(struct TouchNavJob* j, uint16_t key, int phase) {
	j->phase = phase;
	j->lastKey = key;
	j->settleOnMove = false;
	j->stable = 0;
	j->watchChange = false;
	size_t n = strlen(j->moves);
	if (n + 2 < sizeof(j->moves)) {
		const char* names = "ABsSRLUD";
		for (int b = 0; b < 8; ++b) {
			if (key & (1 << b)) {
				j->moves[n++] = names[b];
				break;
			}
		}
		j->moves[n] = 0;
	}
	return key;
}

// A press that moves the ▶ (see _cursorSettled). The menu window is watched, so a
// press the game dropped (still fading in, busy) can be told from a list edge.
uint16_t tnSteer(struct TouchNavJob* j, const struct TouchNavView* v, uint16_t key, int phase) {
	tnPress(j, key, phase);
	j->settleOnMove = true;
	j->watch = j->box;
	return tnWatched(j, v, key);
}

uint16_t tnAbort(struct TouchNav* nav, struct TouchNavJob* j, const char* why) {
	tnResult(nav, "aborted: %s (moves %s)", why, j->moves[0] ? j->moves : "-");
	return 0;
}

bool tnSeen(struct TouchNavJob* j, struct TouchNavCursor c) {
	for (int i = 0; i < j->nSeen; ++i) {
		if (j->seenX[i] == c.x && j->seenY[i] == c.y) {
			return true;
		}
	}
	return false;
}

void tnRemember(struct TouchNavJob* j, struct TouchNavCursor c) {
	if (j->nSeen < 16) {
		j->seenX[j->nSeen] = c.x;
		j->seenY[j->nSeen] = c.y;
		++j->nSeen;
	}
}

uint16_t tnConfirm(struct TouchNav* nav, struct TouchNavJob* j) {
	int slack = j->rowHeight / 2 > TN_ROW_TOLERANCE ? j->rowHeight / 2 : TN_ROW_TOLERANCE;
	if (abs(j->tapY - (j->cur.y + 4)) > slack) {
		// Steered as close as the menu goes and still not on the tapped row: the tap was on
		// blank space (below a short list, an empty grid cell). Pick nothing.
		tnResult(nav, "no item at the tap (closest (%d,%d)), moves %s", j->cur.x, j->cur.y, j->moves[0] ? j->moves : "-");
		return 0;
	}
	tnPress(j, TN_A, TN_P_FINISH);
	tnResult(nav, "select at (%d,%d) %s cursor, moves %s", j->cur.x, j->cur.y, TN_CURSOR_NAMES[j->cur.kind], j->moves);
	return TN_A;
}

// Columns: the item's text starts right of the ▶. Prefer the right-most cursor
// position that is still at or left of the tap; a tap on this item's own text
// never probes (wide single-column lists).
static bool _rightOfItem(struct TouchNavJob* j, const struct TouchNavView* v) {
	int textEnd = tnTextEnd(v->lum, j->cur, tnMenuBox(v->lum, j->cur));
	return j->tapX > j->cur.x + TN_COLUMN_GAP && j->tapX > textEnd + TN_TEXT_GAP;
}

// In a grid the tapped row may only exist in the other column (e.g. USE | REGISTER
// over an empty cell | CANCEL): after moving sideways, go over the rows once more.
static uint16_t _columnsDone(struct TouchNav* nav, struct TouchNavJob* j, const struct TouchNavView* v) {
	if (!j->rowsAgain && abs(j->tapY - (j->cur.y + 4)) > TN_ROW_TOLERANCE) {
		j->rowsAgain = true;
		j->iter = 0;
		j->nSeen = 0;
		tnRemember(j, j->cur);
		return tnRows(nav, j, v);
	}
	return tnConfirm(nav, j);
}

uint16_t tnColumns(struct TouchNav* nav, struct TouchNavJob* j, const struct TouchNavView* v) {
	j->iter = 0;
	if (j->noColumns) {
		return tnConfirm(nav, j);
	}
	if (j->tapX < j->cur.x - 2) {
		return tnSteer(j, v, TN_LEFT, TN_P_COLS_LEFT);
	}
	if (_rightOfItem(j, v)) {
		return tnSteer(j, v, TN_RIGHT, TN_P_COLS_RIGHT);
	}
	return j->rowsAgain ? tnConfirm(nav, j) : _columnsDone(nav, j, v);
}

// Rows: steer up/down until the cursor's row matches the tap.
uint16_t tnRows(struct TouchNav* nav, struct TouchNavJob* j, const struct TouchNavView* v) {
	int dy = j->tapY - (j->cur.y + 4);
	if (abs(dy) <= TN_ROW_TOLERANCE || j->iter >= 12) {
		return tnColumns(nav, j, v);
	}
	++j->iter;
	j->listHash = tnRowHash(v->lum, j->cur, j->box);
	return tnSteer(j, v, dy < 0 ? TN_UP : TN_DOWN, TN_P_ROWS);
}

uint16_t tnGenericTap(struct TouchNav* nav, struct TouchNavJob* j, const struct TouchNavView* v) {
	struct TouchNavCursor c = v->cursor;
	switch (j->phase) {
	case TN_P_START:
		if (!c.found) {
			// No menu on screen: a tap means "confirm / talk / advance text".
			tnResult(nav, "A (no menu cursor)");
			return tnPress(j, TN_A, TN_P_FINISH);
		}
		j->box = tnMenuBox(v->lum, c);
		if (j->tapX < j->box.left - 2 || j->tapX > j->box.right + 2 || j->tapY < j->box.top - 2 || j->tapY > j->box.bottom + 2) {
			// Like any phone popup: tapping outside the menu backs out of it.
			tnResult(nav, "B (tap outside the menu %d..%d x %d..%d)", j->box.left, j->box.right, j->box.top, j->box.bottom);
			return tnPress(j, TN_B, TN_P_FINISH);
		}
		j->cur = c;
		tnRemember(j, c);
		return tnRows(nav, j, v);
	case TN_P_ROWS: {
		if (!c.found) {
			return tnAbort(nav, j, "cursor vanished");
		}
		int dy = j->tapY - (j->cur.y + 4);
		if (tnSame(c, j->cur)) {
			if (tnRowHash(v->lum, c, j->box) != j->listHash) {
				// The list scrolled under a cursor that stays put: the tapped item moved one row.
				j->tapY += j->lastKey == TN_DOWN ? -j->rowHeight : j->rowHeight;
				j->retries = 0;
				return tnRows(nav, j, v);
			}
			if (!j->changed && j->retries < TN_SWIPE_RETRIES) {
				++j->retries; // nothing in the menu reacted: dropped press, or the list's edge
				return tnSteer(j, v, j->lastKey, TN_P_ROWS);
			}
			return tnColumns(nav, j, v); // edge of the list
		}
		j->retries = 0;
		if (tnSeen(j, c) || abs(j->tapY - (c.y + 4)) >= abs(dy)) {
			// Wrapped around or overshot: undo that press and stay on the closest item.
			return tnPress(j, j->lastKey == TN_UP ? TN_DOWN : TN_UP, TN_P_ROWS_UNDO);
		}
		if (c.x == j->cur.x && abs(c.y - j->cur.y) >= 8 && abs(c.y - j->cur.y) <= 32) {
			j->rowHeight = abs(c.y - j->cur.y);
		}
		tnRemember(j, c);
		j->cur = c;
		return tnRows(nav, j, v);
	}
	case TN_P_ROWS_UNDO:
		if (!c.found) {
			return tnAbort(nav, j, "cursor vanished");
		}
		j->cur = c;
		return tnColumns(nav, j, v);
	case TN_P_COLS_LEFT:
		if (!c.found) {
			return tnAbort(nav, j, "cursor vanished");
		}
		if (tnSame(c, j->cur)) {
			return _columnsDone(nav, j, v);
		}
		j->cur = c;
		if (j->tapX >= c.x - 2 || ++j->iter >= 4) {
			return _columnsDone(nav, j, v);
		}
		return tnSteer(j, v, TN_LEFT, TN_P_COLS_LEFT);
	case TN_P_COLS_RIGHT:
		if (!c.found) {
			return tnAbort(nav, j, "cursor vanished");
		}
		if (tnSame(c, j->cur)) {
			return _columnsDone(nav, j, v);
		}
		if (c.y != j->cur.y || c.x > j->tapX + 2) {
			return tnPress(j, TN_LEFT, TN_P_COLS_UNDO); // that column is past the tap (or jumped rows): undo
		}
		j->cur = c;
		if (++j->iter >= 4 || !_rightOfItem(j, v)) {
			return _columnsDone(nav, j, v);
		}
		return tnSteer(j, v, TN_RIGHT, TN_P_COLS_RIGHT);
	case TN_P_COLS_UNDO:
		if (c.found) {
			j->cur = c;
		}
		return _columnsDone(nav, j, v);
	default: // TN_P_FINISH: the last press has settled
		return 0;
	}
}

const uint16_t TN_DIR_KEYS[4] = { TN_UP, TN_DOWN, TN_LEFT, TN_RIGHT };
const char* const TN_DIR_NAMES[4] = { "up", "down", "left", "right" };

// After the last press, wait (a little) until the ▶ is back, so the next gesture
// doesn't land on a half-drawn menu (e.g. the bag sliding to another pocket).
// The ▶ is on screen and not fading: its ink and the paper next to it kept their
// colours for 3 frames (palette fades step every 2 frames). Menus ignore the
// D-pad until their fade-in ends.
bool tnMenuReady(struct TouchNavJob* j, const struct TouchNavView* v) {
	if (!v->cursor.found) {
		j->readyInk = j->readyPaper = 0;
		j->readyFrames = 0;
		return false;
	}
	struct TouchNavCursor c = v->cursor;
	uint32_t ink = v->frame[c.y * W + c.x] | 0x1000000, paper = v->frame[(c.y + 4) * W + c.x - 1] | 0x1000000;
	j->readyFrames = ink == j->readyInk && paper == j->readyPaper ? j->readyFrames + 1 : 0;
	j->readyInk = ink;
	j->readyPaper = paper;
	return j->readyFrames >= 2;
}

// The region looked the same for 3 frames: no fade or slide going on there.
bool tnStill(struct TouchNavJob* j, const struct TouchNavView* v, struct TouchNavBox r) {
	uint32_t h = tnFrameHash(v->frame, r);
	j->stillFrames = h == j->stillHash ? j->stillFrames + 1 : 0;
	j->stillHash = h;
	return j->stillFrames >= 2;
}

uint16_t tnSettle(struct TouchNavJob* j, const struct TouchNavView* v) {
	if (j->phase != TN_P_SETTLE) {
		j->phase = TN_P_SETTLE;
		j->iter = 0;
		j->readyInk = j->readyPaper = 0; // colours from before the last press say nothing now
		j->readyFrames = 0;
	}
	return !tnMenuReady(j, v) && ++j->iter < TN_SETTLE_MAX ? TN_WAIT : 0;
}

// A swipe step whose effect is checked: if the picture stays exactly the same
// through the press and the gap after it, the game ignored it.
uint16_t tnWatched(struct TouchNavJob* j, const struct TouchNavView* v, uint16_t key) {
	j->watchChange = true;
	j->changed = false;
	j->preHash = tnFrameHash(v->frame, j->watch);
	return key;
}

// Swipes: that many D-pad presses.
uint16_t tnGenericSwipe(struct TouchNav* nav, struct TouchNavJob* j, const struct TouchNavView* v) {
	if (!j->done && j->phase == TN_P_START) {
		j->cur = v->cursor; // remembered: was there a menu to come back to?
		if (!j->watch.right) {
			// Watch the menu for the effect of each step (the rest may be animated).
			j->watch = v->cursor.found ? tnMenuBox(v->lum, v->cursor) : (struct TouchNavBox) { 0, W - 1, 0, H - 1 };
		}
		j->phase = TN_P_SWIPING;
	}
	if (j->phase == TN_P_SETTLE) {
		// Steps of a slow drag arrive while the menu settles (a bag pocket slides ~17 frames):
		// once it has, carry on with them instead of ending the job and losing them.
		uint16_t k = tnSettle(j, v);
		if (k || j->done >= j->steps) {
			return k;
		}
		j->phase = TN_P_SWIPING;
	}
	if (j->watchChange && !j->changed && j->retries < TN_SWIPE_RETRIES) {
		++j->retries; // dropped (or a list edge, where pressing again is harmless)
		return tnWatched(j, v, TN_DIR_KEYS[j->req.dir]);
	}
	j->retries = 0;
	j->watchChange = false;
	if (j->done >= j->steps) {
		tnResult(nav, "swipe %s x%d", TN_DIR_NAMES[j->req.dir], j->done);
		return j->cur.found ? tnSettle(j, v) : 0;
	}
	++j->done;
	return tnWatched(j, v, TN_DIR_KEYS[j->req.dir]);
}

// ---------------------------------------------------------------- describe

size_t TouchNavDescribe(struct TouchNav* nav, const uint32_t* frame, char* out, size_t cap) {
	struct TouchNavView v;
	int id = TouchNavScreenOf(nav, frame, &v);
	size_t n = 0;
#define PUT(...) do { int k = snprintf(out + n, n < cap ? cap - n : 0, __VA_ARGS__); if (k > 0) n += (size_t) k; } while (0)
	PUT("screen=%s profile=%s", TouchNavScreenName(id), nav->profile ? nav->profile->name : "none");
	if (nav->gMain) {
		struct mCore* core = nav->core;
		PUT(" gMain=0x%08X cb1=0x%08X cb2=0x%08X heldRaw=0x%03X inBattle=%d", nav->gMain, v.cb1, v.cb2,
		    core->busRead16(core, nav->gMain + MAIN_HELD_RAW), (core->busRead8(core, nav->gMain + MAIN_FLAGS) >> 1) & 1);
	} else {
		PUT(" gMain=unknown (%d candidates)", nav->nCandidates);
	}
	if (tnFadeActive(nav) >= 0) {
		PUT(" fading=%d", tnFadeActive(nav));
	}
	if (v.cursor.found) {
		struct TouchNavBox b = tnMenuBox(v.lum, v.cursor);
		PUT(" cursor=%s(%d,%d) box=x%d..%d,y%d..%d", TN_CURSOR_NAMES[v.cursor.kind], v.cursor.x, v.cursor.y, b.left, b.right, b.top, b.bottom);
	} else {
		PUT(" cursor=none");
	}
	PUT(" busy=%d last=\"%s\"", TouchNavBusy(nav), nav->last);
#undef PUT
	return n < cap ? n : (cap ? cap - 1 : 0);
}

// ---------------------------------------------------------------- the screen registry

// Screen id 0: anything not recognised gets the generic ▶ handlers.
static const struct TouchNavScreen CORE_SCREENS[] = {
	{ .name = "generic", .area = TN_AREA_CORE, .tap = tnGenericTap, .swipe = tnGenericSwipe },
};
static TN_AREA_SCREENS(touchNavAreaCore, TN_AREA_CORE, "core", CORE_SCREENS);

// One table per area file; screen ids number the screens in this order.
static const struct TouchNavAreaTable* const AREAS[] = {
	&touchNavAreaCore,
	&touchNavAreaOverworld,
	&touchNavAreaNewGame,
	&touchNavAreaStartMenu,
	&touchNavAreaParty,
	&touchNavAreaNaming,
	&touchNavAreaBag,
	&touchNavAreaBattle,
	&touchNavAreaTown,
	&touchNavAreaLink,
};

static const struct TouchNavAreaTable* _areas(int i) {
	return i >= 0 && i < (int) (sizeof(AREAS) / sizeof(AREAS[0])) ? AREAS[i] : NULL;
}

static const struct TouchNavScreen* _screenAt(int id) {
	const struct TouchNavAreaTable* area;
	for (int a = 0; id >= 0 && (area = _areas(a)); ++a) {
		if (id < area->count) {
			return &area->screens[id];
		}
		id -= area->count;
	}
	return NULL;
}

// ---------------------------------------------------------------- ROM profiles

// Values from the pokeemerald symbol file; not tested against a real cartridge dump.
static const struct TouchNavScreenCb EMERALD_SCREENS[] = {
	{ "overworld", 0x08085E5D },
	{ "bag", 0x081AAD8D },
	{ "party", 0x081B01B1 },
	{ "summary", 0x081BFAB5 },
	{ "battle", 0x08038421 },
	{ "pc", 0x080C7D55 },
	{ "pokedex", 0x080BB775 },
	{ "trainer-card", 0x080C2711 },
	{ "options", 0x080BA4B1 },
	{ NULL, 0 },
};

static const struct TouchNavProfile PROFILES[] = {
	{
		.name = "Pokemon Emerald (US)", .title = "POKEMON EMER", .code = "BPEE", .gMain = 0x030022C0,
		.sMenu = 0x0203CD90, .gWindows = 0x02020004,
		.screens = EMERALD_SCREENS,
	},
	{
		// Measured with bin/gbaprobe (scripts/build-probe.sh). Quetzal's start menu
		// (Option, Save, MPlayer, Misc) and its submenus run on top of the overworld.
		// Its screens' callback2 values are in the area files' screen definitions.
		.name = "Pokemon Quetzal 0.9.0 (EN)", .title = "PKM QUETZAL", .code = "BPEE", .crc32 = 0x78F1693E,
		.gMain = 0x03002660,
		// Its +7 bit 7 is set exactly during the fades of START -> BAG / POKéMON / POKéDEX,
		// party -> summary, closing the bag and the intro, and never while a screen is idle.
		.paletteFade = 0x02039ABC,
		// Layouts checked against the ▶ of the START menu, OPTION, MPLAYER, MISC, the SAVE
		// YES/NO, the party action menu and the bag's item menu (2x2 grid).
		.sMenu = 0x0203E8AC, .gWindows = 0x02020004,
	},
};

static const struct TouchNavProfile* _profileFor(struct mCore* core) {
	uint32_t crc = 0;
	core->checksum(core, &crc, mCHECKSUM_CRC32);
	for (size_t i = 0; i < sizeof(PROFILES) / sizeof(PROFILES[0]); ++i) {
		const struct TouchNavProfile* p = &PROFILES[i];
		if (_headerIs(core, 0x080000A0, p->title, 12) && _headerIs(core, 0x080000AC, p->code, 4) && (!p->crc32 || p->crc32 == crc)) {
			return p;
		}
	}
	return NULL;
}
