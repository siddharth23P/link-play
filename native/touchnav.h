/*
 * TouchNav: tap / swipe on the game picture -> GBA key presses, for menus.
 *
 * The game only understands buttons, so a tap is turned into D-pad presses that
 * steer the menu's cursor onto the tapped item, then A (closed loop: the screen
 * is looked at again after every press). Runs frame by frame on the emulation
 * thread, so it is deterministic and never sleeps; the phone app (bridge.c), the
 * desktop host (gbalink.c) and the probe tool (gbaprobe.c) all share this code.
 *
 * Screens: callback2 of pokeemerald's `gMain` (per ROM profile) plus RAM / pixel
 * detectors pick a per-screen handler (bag, party, ...); anything unknown gets
 * the generic ▶-cursor handler. The screens live in one file per area,
 * touchnav_<area>.c; touchnav_int.h is the internal API they share with the core.
 */
#ifndef TOUCHNAV_H
#define TOUCHNAV_H

#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct mCore;
struct TouchNavProfile;
struct TouchNavJob; // touchnav_int.h

#define TOUCHNAV_WIDTH 240
#define TOUCHNAV_HEIGHT 160
#define TOUCHNAV_QUEUE 8

// GBA key bits (KEYINPUT order).
enum {
	TN_A = 1, TN_B = 2, TN_SELECT = 4, TN_START = 8, TN_RIGHT = 16, TN_LEFT = 32,
	TN_UP = 64, TN_DOWN = 128, TN_R = 256, TN_L = 512,
};

enum TouchNavDir { TOUCHNAV_UP, TOUCHNAV_DOWN, TOUCHNAV_LEFT, TOUCHNAV_RIGHT };

// Screen ids (TouchNavScreenOf) index the registry of screens in touchnav.c; use
// TouchNavScreenName for what they are.
enum TouchNavScreenId {
	TN_SCREEN_GENERIC, // not recognised: steer the ▶ cursor
};

struct TouchNavCursor {
	int16_t x, y; // top-left of the ▶ glyph
	uint8_t kind; // 0 thin (lists), 1 bold (battle)
	bool found;
};

struct TouchNavBox {
	int16_t left, right, top, bottom; // the menu window's paper area around the cursor
};

struct TouchNavRequest {
	uint8_t type; // 1 tap, 2 swipe
	uint8_t dir;
	uint8_t x, y;
	uint16_t steps;
};

// What a handler sees on a settled frame.
struct TouchNavView {
	const uint32_t* frame; // 240x160 XBGR8888
	const uint8_t* lum;    // luminance, quantised like RGB565 (the thresholds were measured that way)
	uint32_t cb1, cb2;     // gMain callbacks; 0 while gMain is unknown
	struct TouchNavCursor cursor;
};

struct TouchNav {
	struct mCore* core;
	const struct TouchNavProfile* profile;

	// Requests from other threads.
	pthread_mutex_t lock;
	struct TouchNavRequest queue[TOUCHNAV_QUEUE];
	int queued;
	atomic_bool busy;
	atomic_bool cancel;

	// Emulation thread only.
	uint32_t gMain; // address of pokeemerald's gMain, 0 = unknown
	uint32_t candidates[256];
	uint32_t candidateCounter[256];
	uint8_t candidateHits[256];
	int nCandidates;
	int finderWait;
	uint16_t appliedKeys; // keys of the frame that was just rendered
	uint16_t prevApplied;
	bool active;
	struct TouchNavJob* job; // the running gesture (allocated by TouchNavInit)
	uint16_t pressKeys;
	int pressHold, pressGap;
	char last[160];
	uint8_t lum[TOUCHNAV_WIDTH * TOUCHNAV_HEIGHT];
};

// core must have its ROM loaded (the ROM header picks the profile).
void TouchNavInit(struct TouchNav*, struct mCore* core);
void TouchNavDeinit(struct TouchNav*);
// Thread-safe. x, y in GBA pixels (0..239, 0..159).
void TouchNavTap(struct TouchNav*, int x, int y);
// Thread-safe; consecutive swipes in one direction accumulate. dir: enum TouchNavDir.
void TouchNavSwipe(struct TouchNav*, int dir, int steps);
void TouchNavCancel(struct TouchNav*);
// Once per frame on the emulation thread, after the frame was rendered and before
// core->setKeys. frame = the 240x160 XBGR8888 picture just rendered. *keys holds the
// player's keys for the next frame; returns true while navigating, and then *keys has
// been replaced by the keys touchnav needs.
bool TouchNavStep(struct TouchNav*, const uint32_t* frame, uint16_t* keys);
bool TouchNavBusy(const struct TouchNav*);
// Debug text: screen, gMain callbacks, cursor, menu box, last result. Emulation thread only.
size_t TouchNavDescribe(struct TouchNav*, const uint32_t* frame, char* out, size_t cap);

// Helpers shared with the probe tool.
uint32_t TouchNavFindMain(struct TouchNav*); // full scan right now (needs a couple of frames of history to be sure)
bool TouchNavReadCallbacks(struct TouchNav*, uint32_t* cb1, uint32_t* cb2);
int TouchNavScreenOf(struct TouchNav*, const uint32_t* frame, struct TouchNavView* view);
const char* TouchNavScreenName(int id);
struct TouchNavCursor TouchNavFindCursor(struct TouchNav*, const uint32_t* frame);

#endif
