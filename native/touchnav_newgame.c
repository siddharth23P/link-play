/*
 * TouchNav area: title screen, main menu, OPTIONS+ (new-game setup; also reached from
 * START > OPTION), the intro and the starter choice.
 * Not implemented yet: the generic ▶ handlers run here.
 */
#include "touchnav_int.h"

static const struct TouchNavScreen SCREENS[] = {
	{ .name = "title", .area = TN_AREA_NEWGAME, .cb2 = { 0x080F9B15 } },
	{ .name = "main-menu", .area = TN_AREA_NEWGAME, .cb2 = { 0x0802C9FD } }, // CONTINUE / NEW GAME
	{ .name = "options", .area = TN_AREA_NEWGAME, .cb2 = { 0x0827EAB5 } }, // OPTIONS+ pages
};

TN_AREA_SCREENS(touchNavAreaNewGame, TN_AREA_NEWGAME, "newgame", SCREENS);
