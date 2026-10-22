/*
 * TouchNav area: the overworld, field dialogs / text boxes and field popups (the START
 * menu, OPTION, SAVE, MPLAYER and MISC popups also run on the overworld's callback2:
 * screens for them need a detector and a higher priority than this one).
 * Not implemented yet: the generic ▶ handlers run here.
 */
#include "touchnav_int.h"

static const struct TouchNavScreen SCREENS[] = {
	{ .name = "overworld", .area = TN_AREA_OVERWORLD, .cb2 = { 0x080C043D } }, // callback1 0x080C03B9
};

TN_AREA_SCREENS(touchNavAreaOverworld, TN_AREA_OVERWORLD, "overworld", SCREENS);
