/*
 * NetLink — a GBA link-cable driver for Pokémon Emerald-engine games (Quetzal)
 * that works over a network with real latency.
 *
 * The game's link engine (pokeemerald src/link.c) does a 9-word multiplayer
 * transfer burst every frame: word 0 is a checksum, words 1-8 are one 8-word
 * command per player. Commands are queued, a frame of zeros means "nothing",
 * and the engine only errors out if serial interrupts stop entirely. So instead
 * of syncing CPUs, NetLink completes every transfer locally and instantly, ships
 * the local command to a sequencer, and feeds back the sequencer's single
 * ordered stream of command "entries" — identical on every GBA, like a cable.
 */
#ifndef NETLINK_H
#define NETLINK_H

#include <mgba-util/common.h>

#include <mgba/core/timing.h>
#include <mgba/gba/interface.h>
#include <mgba-util/threading.h>

#define NETLINK_MAX_PLAYERS 4
#define NETLINK_CMD_WORDS 8
#define NETLINK_QUEUE 4096
// Above this many queued entries the host runs the GBA slightly faster to catch up.
#define NETLINK_MAX_BACKLOG 4
// Quetzal's per-frame overworld state command; only the newest one matters.
#define NETLINK_STATE_CMD 0xBEBE

enum NetLinkEventKind {
	NETLINK_EV_HANDSHAKE = 1, // a player's handshake word changed (words[0])
	NETLINK_EV_ENTRY = 2,     // one sequenced entry: words[slot * 8 + i]
	NETLINK_EV_LEFT = 3,      // a player left: their GBA is unplugged from the cable
};

struct NetLinkEvent {
	uint8_t kind;
	uint8_t player;
	uint16_t words[NETLINK_MAX_PLAYERS * NETLINK_CMD_WORDS];
};

enum NetLinkState {
	NETLINK_HANDSHAKE,
	NETLINK_DATA,
};

struct NetLink {
	struct GBASIODriver d;
	int localId;

	// Outgoing, called on the emulation thread.
	void (*sendHandshake)(struct NetLink*, uint16_t word);
	void (*sendCommand)(struct NetLink*, const uint16_t cmd[NETLINK_CMD_WORDS]);
	void* context;

	// Incoming events, pushed from any thread.
	Mutex mutex;
	struct NetLinkEvent queue[NETLINK_QUEUE];
	unsigned head;
	unsigned count;
	bool overflowed;

	// Mirror of the game's link state.
	enum NetLinkState state;
	uint16_t hsWord[NETLINK_MAX_PLAYERS];
	uint32_t hsAge[NETLINK_MAX_PLAYERS];
	uint16_t lastHsSent;
	uint32_t hsSinceSent;
	int prevCount;
	int playerCount;
	int xferIndex;
	uint16_t entry[NETLINK_MAX_PLAYERS][NETLINK_CMD_WORDS];
	uint16_t checksum;
	uint16_t sendCmd[NETLINK_CMD_WORDS];
	uint16_t lastSiocnt;
	// Players whose GBA is gone. They read like an unplugged cable (0xFFFF), so
	// the game's own disconnect handling kicks in instead of a silent hang.
	uint32_t absent;

	struct mTimingEvent event; // paces transfers on non-master GBAs
};

void NetLinkCreate(struct NetLink*, int localId);
void NetLinkDestroy(struct NetLink*);
void NetLinkPushEvent(struct NetLink*, const struct NetLinkEvent*);
unsigned NetLinkBacklog(struct NetLink*);
// Call these only while the core thread is interrupted.
void NetLinkSetLocalId(struct NetLink*, int localId);
void NetLinkDisconnect(struct NetLink*);
// True if newer state packet b can replace a without losing anything but
// position/facing/animation (flags such as "wants to battle" must survive).
bool NetLinkStateReplaceable(const uint16_t* a, const uint16_t* b);

#endif
