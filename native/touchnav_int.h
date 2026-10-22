/*
 * TouchNav internals shared by the core (touchnav.c) and the area files
 * (touchnav_<area>.c). Callers outside touchnav*.c use touchnav.h only.
 *
 * How a gesture runs (touchnav.c): a tap / swipe is queued; on the emulation thread,
 * every frame, TouchNavStep either holds / releases the key it pressed, or asks the
 * screen's handler for the next key. Before the first press it waits out screen
 * transitions, then picks the screen (TouchNavScreenOf: callback2 of gMain + the
 * screens' detectors, highest priority wins) and keeps that screen's handlers for the
 * whole gesture. A handler gets the current frame (struct TouchNavView) and the
 * gesture (struct TouchNavJob) and returns the keys to press next, TN_WAIT to look
 * again on the next frame without pressing, or 0 when the gesture is done.
 *
 * Adding screens: each area file defines an array of struct TouchNavScreen and
 * exports it with TN_AREA_SCREENS (one table per file, declared below). Keep the
 * detection specific (callback2 values + RAM / pixel conditions in `detect`) so one
 * area never takes another area's screens; use `priority` when a screen runs on top
 * of another one's callback2 (START menu, dialogs and popups on the overworld's):
 *
 *   static bool _dialogUp(struct TouchNav* nav, const struct TouchNavView* v) { ... RAM ... }
 *   static const struct TouchNavScreen SCREENS[] = {
 *       { .name = "overworld", .area = TN_AREA_OVERWORLD, .cb2 = { 0x080C043D }, .tap = _fieldTap },
 *       { .name = "field-dialog", .area = TN_AREA_OVERWORLD, .priority = 10, .cb2 = { 0x080C043D },
 *         .detect = _dialogUp, .tap = _dialogTap },
 *   };
 *   TN_AREA_SCREENS(touchNavAreaOverworld, TN_AREA_OVERWORLD, "overworld", SCREENS);
 *
 * Build and test an area on a private copy of native/: scripts/build-probe.sh <dir> <binary>.
 */
#ifndef TOUCHNAV_INT_H
#define TOUCHNAV_INT_H

#include "touchnav.h"

#define TN_W TOUCHNAV_WIDTH
#define TN_H TOUCHNAV_HEIGHT

// Handler result: press nothing this frame and look again on the next one.
#define TN_WAIT 0x8000

// One key press = hold it, release, then wait for the menu to redraw before looking
// again. Defaults; screens can override them (struct TouchNavScreen hold / gap / swipeGap).
#define TN_HOLD_FRAMES 3
#define TN_GAP_FRAMES 8 // menunav.js waited 2 + 6 frames before re-reading the cursor
#define TN_SWIPE_GAP 3  // plain D-pad steps: menus take one new press per few frames
#define TN_SWIPE_RETRIES 2 // a step nothing reacted to was dropped (menu busy / fading): press again
#define TN_SETTLE_MAX 90   // frames to wait for a menu to (re)appear after an animation or a fade

// The ▶ cursor and menu windows, measured from Pokémon Quetzal frames (luminance, see the view).
#define TN_INK_MAX 140   // cursor pixels measured at luminance 68-96
#define TN_PAPER_MIN 180 // window background measured at 205+
#define TN_ROW_TOLERANCE 7 // a tap this close to the cursor's row is on that row
// Only look for another column when the tap is clearly past this item's text;
// left/right mean other things in some menus (e.g. Bag pockets).
#define TN_COLUMN_GAP 24 // at least this far right of the ▶
#define TN_TEXT_GAP 10   // paper wider than this ends an item's text (spaces are 3-5 px)

// Request types (struct TouchNavRequest.type).
enum { TN_REQ_TAP = 1, TN_REQ_SWIPE = 2 };

// Phases of the generic handlers (struct TouchNavJob.phase). A screen's own handler numbers
// its phases from TN_P_AREA up; it may hand over to the generic ones (they start at TN_P_START).
enum {
	TN_P_START, TN_P_ROWS, TN_P_ROWS_UNDO, TN_P_COLS_LEFT, TN_P_COLS_RIGHT, TN_P_COLS_UNDO,
	TN_P_FINISH, TN_P_SETTLE, TN_P_SWIPING,
	TN_P_AREA = 100,
};

// ---------------------------------------------------------------- the running gesture

// One gesture (zeroed when it starts). Handlers keep their own progress in phase / iter /
// etc.; `vars` is free for a screen's handlers.
struct TouchNavJob {
	struct TouchNavRequest req;
	const struct TouchNavScreen* screen;
	int screenId;
	int phase, iter;
	int steps, done; // swipes: requested (grows when more of the same swipe arrives) and pressed
	int tapX, tapY;
	struct TouchNavCursor cur;
	struct TouchNavBox box;
	int16_t seenX[16], seenY[16];
	int nSeen;
	int rowHeight;
	bool noColumns; // left/right mean something else here (bag pockets): rows only
	bool rowsAgain; // a column move left the tap's row unreached: one more pass over the rows
	uint32_t listHash; // the ▶ row's text before the last press: changed under an unmoved ▶ = scrolled
	uint16_t lastKey;
	bool settleOnMove; // this press only steers the ▶: look again as soon as it has moved
	struct TouchNavCursor moved;
	int stable;
	uint32_t readyInk, readyPaper; // colours at the ▶ last frame (fade detection)
	int readyFrames;
	uint32_t stillHash; // a region's picture last frame (has an animation ended?)
	int stillFrames;
	bool watchChange; // swipe / steering steps: did anything in `watch` react to the press?
	bool changed;
	uint32_t preHash;
	struct TouchNavBox watch;
	int retries;
	int frames;
	bool started;       // a key has been pressed for this gesture
	int transitionWait; // frames waited for the previous screen change to finish
	int lumMean;        // overall brightness last frame (fade detection without RAM)
	int lumQuiet;       // frames in a row it has not moved
	char moves[48];
	int32_t vars[8];    // free for a screen's own handlers
};

typedef uint16_t (*TouchNavHandler)(struct TouchNav*, struct TouchNavJob*, const struct TouchNavView*);
typedef bool (*TouchNavDetector)(struct TouchNav*, const struct TouchNavView*);

// ---------------------------------------------------------------- screens and areas

enum TouchNavArea {
	TN_AREA_CORE, // touchnav.c: the generic screen
	TN_AREA_OVERWORLD, // overworld, field dialogs, field popups, text boxes
	TN_AREA_NEWGAME,
	TN_AREA_STARTMENU,
	TN_AREA_PARTY,
	TN_AREA_NAMING,
	TN_AREA_BAG,
	TN_AREA_BATTLE,
	TN_AREA_TOWN, // PC boxes, Town Map, Marts
	TN_AREA_LINK,
	TN_AREA_COUNT
};

#define TN_CB2_MAX 8

struct TouchNavScreen {
	const char* name; // shown by `info`, checked by the probe's `expect screen`
	uint8_t area;     // enum TouchNavArea: the file that owns this screen
	int8_t priority;  // when several screens match a frame the highest wins (ties: registry order)
	// gMain.callback2 values of Pokémon Quetzal 0.9.0 (EN), thumb bit set, 0-terminated. Without
	// any, only `detect` decides, and it is asked on every frame (any callback2, even unknown).
	// Other ROMs map their own callback2 values to screen names in their profile (touchnav.c).
	uint32_t cb2[TN_CB2_MAX];
	// NULL = the callback2 alone decides. Must not change any state: it may be skipped (a
	// screen of higher priority already matched) and runs on frames where no gesture runs.
	TouchNavDetector detect;
	TouchNavHandler tap, swipe; // NULL = the generic ▶ handlers (tnGenericTap / tnGenericSwipe)
	uint8_t hold, gap, swipeGap; // frames; 0 = TN_HOLD_FRAMES / TN_GAP_FRAMES / TN_SWIPE_GAP
};

// The screens of one area file.
struct TouchNavAreaTable {
	uint8_t area; // enum TouchNavArea
	const char* name;
	const struct TouchNavScreen* screens;
	int count;
};

// In touchnav_<area>.c, once:  TN_AREA_SCREENS(touchNavAreaBag, TN_AREA_BAG, "bag", SCREENS);
#define TN_AREA_SCREENS(VAR, AREA, NAME, ARRAY) \
	const struct TouchNavAreaTable VAR = { (AREA), (NAME), (ARRAY), (int) (sizeof(ARRAY) / sizeof((ARRAY)[0])) }
// An area with no screens yet.
#define TN_AREA_NONE(VAR, AREA, NAME) const struct TouchNavAreaTable VAR = { (AREA), (NAME), NULL, 0 }

// The registry (touchnav.c lists them in this order after the core's generic screen).
extern const struct TouchNavAreaTable touchNavAreaOverworld; // touchnav_overworld.c
extern const struct TouchNavAreaTable touchNavAreaNewGame;   // touchnav_newgame.c
extern const struct TouchNavAreaTable touchNavAreaStartMenu; // touchnav_startmenu.c
extern const struct TouchNavAreaTable touchNavAreaParty;     // touchnav_party.c
extern const struct TouchNavAreaTable touchNavAreaNaming;    // touchnav_naming.c
extern const struct TouchNavAreaTable touchNavAreaBag;       // touchnav_bag.c
extern const struct TouchNavAreaTable touchNavAreaBattle;    // touchnav_battle.c
extern const struct TouchNavAreaTable touchNavAreaTown;      // touchnav_town.c
extern const struct TouchNavAreaTable touchNavAreaLink;      // touchnav_link.c

// ---------------------------------------------------------------- ROM profiles

// A ROM's own callback2 value for a screen (by name), for ROMs other than Quetzal 0.9.0.
struct TouchNavScreenCb {
	const char* name;
	uint32_t cb2; // with the thumb bit, as stored in gMain.callback2
};

struct TouchNavProfile {
	const char* name;
	const char* title; // ROM header 0xA0, 12 chars
	const char* code;  // ROM header 0xAC, 4 chars
	uint32_t crc32;    // whole ROM (mGBA's romCrc32); 0 = any version
	uint32_t gMain;    // 0 = find it at runtime
	uint32_t paletteFade; // pokeemerald's gPaletteFade (EWRAM); 0 = unknown, fades are told from the picture
	uint32_t sMenu;    // pokeemerald's menu.c sMenu (EWRAM); 0 = unknown
	uint32_t gWindows; // pokeemerald's gWindows[32] (EWRAM); 0 = unknown
	// NULL: the screens' own cb2 values apply (Quetzal 0.9.0). Otherwise this ROM's
	// values by screen name, ending at cb2 == 0.
	const struct TouchNavScreenCb* screens;
};

// The ROM is Pokémon Quetzal 0.9.0 (EN): the callback2 values in the screen definitions
// and the Quetzal RAM addresses an area file knows apply.
bool tnQuetzal(const struct TouchNav*);

// ---------------------------------------------------------------- RAM

uint8_t tnRead8(struct TouchNav*, uint32_t addr);
uint16_t tnRead16(struct TouchNav*, uint32_t addr);
uint32_t tnRead32(struct TouchNav*, uint32_t addr);
bool tnInBattle(struct TouchNav*); // gMain.inBattle; false while gMain is unknown
// gPaletteFade.active: 1 / 0, or -1 when the profile doesn't know where gPaletteFade is.
int tnFadeActive(struct TouchNav*);

// pokeemerald's sMenu (menu.c): the menu run by the generic Menu_* functions (START menu, YES/NO,
// multichoice, OPTION / MPLAYER / MISC, the party action menu, the bag's item menu...). It keeps the
// last menu's values after that menu closed: tnMenuLive tells whether it is the one on screen.
// Layout checked on Quetzal 0.9.0 (startmenu, options, mplayer, misc, save_dialog, party_action,
// bag_itemmenu savestates).
struct TouchNavMenu {
	uint8_t left, top;   // +0 +1: pixels inside the window of item 0's cursor
	int8_t cursorPos;    // +2: selected item (grid: row * columns + column)
	int8_t minCursorPos; // +3
	int8_t maxCursorPos; // +4: items - 1
	uint8_t windowId;    // +5: index into gWindows
	uint8_t fontId;      // +6
	uint8_t optionWidth; // +7: grid column width in pixels (0 when not a grid)
	uint8_t optionHeight; // +8: row height in pixels (16)
	uint8_t columns, rows; // +9 +10: grid size (0 when not a grid)
	bool APressMuted;    // +11
};

// gWindows[id]: pokeemerald's WindowTemplate + tile data pointer, 12 bytes each, 32 of them.
struct TouchNavWindow {
	uint8_t bg;          // +0: 0xFF = slot not in use
	uint8_t tilemapLeft, tilemapTop, width, height; // +1..+4: in 8-pixel tiles
	uint8_t paletteNum;  // +5
	uint16_t baseBlock;  // +6
	uint32_t tileData;   // +8
};

// false when the profile has no address (and *out is zeroed).
bool tnReadMenu(struct TouchNav*, struct TouchNavMenu* out);
// false when the profile has no address, id >= 32 or the slot is not in use (bg 0xFF).
bool tnReadWindow(struct TouchNav*, int id, struct TouchNavWindow* out);
// The window's pixels on screen (its BG assumed unscrolled, as for menus). The ▶ menu box
// (tnMenuBox) is 3 px larger on each side: it includes the frame's paper margin.
struct TouchNavBox tnWindowBox(const struct TouchNavWindow*);
// Where the ▶ glyph's top-left (struct TouchNavCursor x/y) is when item `index` is selected:
// window + menu left/top + column * optionWidth / row * optionHeight, + (1, 3) for the glyph.
void tnMenuItemCursor(const struct TouchNavMenu*, const struct TouchNavWindow*, int index, int* x, int* y);
// sMenu is the menu on screen: its window is in use and the view's ▶ sits on its selected item.
bool tnMenuLive(struct TouchNav*, const struct TouchNavView*, struct TouchNavMenu* menu, struct TouchNavWindow* window);

// ---------------------------------------------------------------- frame analysis

// The ▶ cursor (thin or bold) in a frame; lum = its luminance (TouchNavView.lum).
struct TouchNavCursor tnFindCursor(const uint32_t* frame, const uint8_t* lum);
extern const char* const TN_CURSOR_NAMES[2]; // by TouchNavCursor.kind: "thin", "bold"
bool tnSame(struct TouchNavCursor, struct TouchNavCursor); // same position
// The menu window's paper around the ▶ (scanned outwards to its frame).
struct TouchNavBox tnMenuBox(const uint8_t* lum, struct TouchNavCursor);
// Hash of the item text on the ▶'s row (changes when the list scrolls under an unmoved ▶).
uint32_t tnRowHash(const uint8_t* lum, struct TouchNavCursor, struct TouchNavBox);
// x of the last ink of the ▶'s item text before a wide gap.
int tnTextEnd(const uint8_t* lum, struct TouchNavCursor, struct TouchNavBox);
// Hash of a region of the frame (colours).
uint32_t tnFrameHash(const uint32_t* frame, struct TouchNavBox);

// ---------------------------------------------------------------- handler building blocks

// The result line (`last`, shown by the probe and `info`).
void tnResult(struct TouchNav*, const char* fmt, ...) __attribute__((format(printf, 2, 3)));
// Ends the gesture with "aborted: why (moves ...)"; returns 0.
uint16_t tnAbort(struct TouchNav*, struct TouchNavJob*, const char* why);

// Press `key` next and go to `phase`; records it in the moves string. Returns key.
uint16_t tnPress(struct TouchNavJob*, uint16_t key, int phase);
// A press that steers the ▶ (in j->box): it is looked at again as soon as the ▶ moved and
// stayed put a frame, and the box is watched so a dropped press can be told from a list edge.
uint16_t tnSteer(struct TouchNavJob*, const struct TouchNavView*, uint16_t key, int phase);
// A press whose effect is checked: j->changed is set when anything in j->watch changed during
// the press and the gap after it (j->watchChange stays set until the next tnPress).
uint16_t tnWatched(struct TouchNavJob*, const struct TouchNavView*, uint16_t key);
// The ▶ is at the tapped row: A, unless it is still more than half a row away (blank space): 0.
uint16_t tnConfirm(struct TouchNav*, struct TouchNavJob*);
// Generic steering from j->cur towards (tapX, tapY): rows (UP/DOWN) then columns, then A.
uint16_t tnRows(struct TouchNav*, struct TouchNavJob*, const struct TouchNavView*);
uint16_t tnColumns(struct TouchNav*, struct TouchNavJob*, const struct TouchNavView*);
// Positions the ▶ has been at during this gesture (wrap-around detection).
bool tnSeen(struct TouchNavJob*, struct TouchNavCursor);
void tnRemember(struct TouchNavJob*, struct TouchNavCursor);

// The ▶ is on screen and not fading: its ink and the paper next to it kept their colours for
// 3 frames. Call once per frame (it counts frames in the job).
bool tnMenuReady(struct TouchNavJob*, const struct TouchNavView*);
// The region looked the same for 3 frames (no fade or slide there). Once per frame.
bool tnStill(struct TouchNavJob*, const struct TouchNavView*, struct TouchNavBox);
// After the last press: TN_WAIT until the ▶ is back and still (at most TN_SETTLE_MAX frames),
// then 0. Switches the job to TN_P_SETTLE.
uint16_t tnSettle(struct TouchNavJob*, const struct TouchNavView*);

// The generic handlers (any screen with a ▶; see touchnav.c).
uint16_t tnGenericTap(struct TouchNav*, struct TouchNavJob*, const struct TouchNavView*);
uint16_t tnGenericSwipe(struct TouchNav*, struct TouchNavJob*, const struct TouchNavView*);

extern const uint16_t TN_DIR_KEYS[4];       // by enum TouchNavDir
extern const char* const TN_DIR_NAMES[4];   // "up", "down", "left", "right"

#endif
