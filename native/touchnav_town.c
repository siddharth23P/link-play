/*
 * TouchNav area: town screens (PC storage boxes, Town Map, Poké Mart lists, nurse).
 * Not implemented yet: the generic ▶ handlers run here.
 */
#include "touchnav_int.h"

static const struct TouchNavScreen SCREENS[] = {
	{ .name = "pc", .area = TN_AREA_TOWN }, // Quetzal's not recorded (Emerald's in its profile)
};

TN_AREA_SCREENS(touchNavAreaTown, TN_AREA_TOWN, "town", SCREENS);
