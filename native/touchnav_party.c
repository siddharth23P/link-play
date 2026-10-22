/*
 * TouchNav area: the party menu and the Pokémon summary.
 * Not implemented yet beyond the summary's timing: both use the generic ▶ handlers.
 */
#include "touchnav_int.h"

static const struct TouchNavScreen SCREENS[] = {
	{ .name = "party", .area = TN_AREA_PARTY, .cb2 = { 0x08224455 } },
	// Page flips slide for a while and drop presses: Quetzal needs >= 12 frames between them.
	{ .name = "summary", .area = TN_AREA_PARTY, .cb2 = { 0x082894CD }, .swipeGap = 14 },
};

TN_AREA_SCREENS(touchNavAreaParty, TN_AREA_PARTY, "party", SCREENS);
