/*
 * TouchNav area: the START menu's screens (Pokédex, DexNav, quests, trainer card,
 * SAVE / OPTION / MPLAYER / MISC popups, PC from MISC, game stats).
 * Not implemented yet: the generic ▶ handlers run here.
 */
#include "touchnav_int.h"

static const struct TouchNavScreen SCREENS[] = {
	{ .name = "pokedex", .area = TN_AREA_STARTMENU, .cb2 = { 0x08270CB1 } },
	{ .name = "dexnav", .area = TN_AREA_STARTMENU, .cb2 = { 0x082665F5 } },
	{ .name = "quests", .area = TN_AREA_STARTMENU, .cb2 = { 0x0826E531 } },
	{ .name = "trainer-card", .area = TN_AREA_STARTMENU }, // Quetzal's not recorded (Emerald's in its profile)
};

TN_AREA_SCREENS(touchNavAreaStartMenu, TN_AREA_STARTMENU, "startmenu", SCREENS);
