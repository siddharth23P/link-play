/*
 * TouchNav area: the bag (field bag; its callback2 is shared with the battle bag and the
 * GIVE / SELL bags, see the survey notes before adding screens for those).
 */
#include "touchnav_int.h"

// Quetzal's bag: pocket name + picture on the left (x < ~108), item list on the
// right, description at the bottom left. Tapping the left/right half of the
// pocket side switches pockets (like the arrows on either end of the name).
#define BAG_LIST_MIN_X 100   // the list's ▶ sits at x 113 in a window spanning the screen's height;
#define BAG_LIST_MAX_TOP 30  // the item menu (USE / REGISTER / CANCEL...) is a small window at the bottom
#define BAG_POCKET_MID 56
#define BAG_DESCRIPTION_Y 100
#define BAG_LIST ((struct TouchNavBox) { 110, 233, 6, 153 })

// Wait for the list's ▶ while the bag loads, fades in or slides to another pocket;
// a fully lit, still list without a ▶ means something else (a message) is up.
static bool _bagBusy(struct TouchNavJob* j, const struct TouchNavView* v) {
	bool ready = tnMenuReady(j, v);
	bool still = tnStill(j, v, BAG_LIST);
	bool lit = v->lum[150 * TN_W + 200] >= TN_PAPER_MIN; // the list's paper, dark while fading
	return !ready && !(still && lit && !v->cursor.found) && ++j->iter < TN_SETTLE_MAX;
}

enum { P_BAG_SWITCHED = TN_P_AREA };

static uint16_t _bagTap(struct TouchNav* nav, struct TouchNavJob* j, const struct TouchNavView* v) {
	if (j->phase == P_BAG_SWITCHED || j->phase == TN_P_SETTLE) {
		return tnSettle(j, v);
	}
	if (j->phase == TN_P_START && _bagBusy(j, v)) {
		return TN_WAIT;
	}
	struct TouchNavBox box = v->cursor.found ? tnMenuBox(v->lum, v->cursor) : (struct TouchNavBox) { 0 };
	if (j->phase == TN_P_START && v->cursor.found && v->cursor.x >= BAG_LIST_MIN_X && box.top <= BAG_LIST_MAX_TOP) {
		j->iter = 0;
		j->noColumns = true; // left/right switch pockets
		if (j->tapX < box.left - 2) {
			if (j->tapY >= BAG_DESCRIPTION_Y) {
				tnResult(nav, "bag: description, nothing to do");
				return 0;
			}
			uint16_t key = j->tapX < BAG_POCKET_MID ? TN_LEFT : TN_RIGHT;
			tnResult(nav, "bag: pocket %s", key == TN_LEFT ? "left" : "right");
			return tnPress(j, key, P_BAG_SWITCHED);
		}
	}
	j->iter = 0;
	return tnGenericTap(nav, j, v);
}

// Pocket flips are dropped while the bag fades in or slides: start once the list's ▶
// shows. The pocket dots (red = current) show whether a flip happened; the arrows
// beside the pocket name bob all the time.
#define BAG_DOTS ((struct TouchNavBox) { 8, 107, 25, 34 })

static uint16_t _bagSwipe(struct TouchNav* nav, struct TouchNavJob* j, const struct TouchNavView* v) {
	if (!j->done && j->phase == TN_P_START) {
		if (_bagBusy(j, v)) {
			return TN_WAIT;
		}
		if (j->req.dir == TOUCHNAV_LEFT || j->req.dir == TOUCHNAV_RIGHT) {
			j->watch = BAG_DOTS;
		}
	}
	return tnGenericSwipe(nav, j, v);
}

static const struct TouchNavScreen SCREENS[] = {
	{
		.name = "bag", .area = TN_AREA_BAG,
		.cb2 = {
			0x0821DCE9, // running (fades in during its first ~60 frames)
			0x0821DD19, // loading
			0x0821DAF5, // init
		},
		.tap = _bagTap, .swipe = _bagSwipe,
	},
};

TN_AREA_SCREENS(touchNavAreaBag, TN_AREA_BAG, "bag", SCREENS);
