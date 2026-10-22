/*
 * TouchNav area: battles (action / move menus, targets, battle messages and prompts).
 * Not implemented yet: the generic ▶ handlers run here.
 */
#include "touchnav_int.h"

static const struct TouchNavScreen SCREENS[] = {
	{ .name = "battle", .area = TN_AREA_BATTLE }, // Quetzal's not recorded (Emerald's in its profile)
};

TN_AREA_SCREENS(touchNavAreaBattle, TN_AREA_BATTLE, "battle", SCREENS);
