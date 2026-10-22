/*
 * NetLink driver: see netlink.h for the idea. Protocol details mirror
 * pokeemerald's src/link.c (DoHandshake, DoRecv, DoSend, SendRecvDone).
 */
#include "netlink.h"

#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/io.h>
#include <mgba/internal/gba/sio.h>
#include <mgba/internal/gba/video.h>

#include <stdio.h>
#include <string.h>

#define MASTER_HANDSHAKE 0x8FFF
#define SLAVE_HANDSHAKE 0xB9A0

// Spacing of the 9 transfers in a frame, matching what a real master produces
// (Timer 3 at 64-cycle ticks reloaded with -197, plus the transfer itself).
#define XFER_SPACING 19330
#define XFERS_PER_FRAME (NETLINK_CMD_WORDS + 1)
// A remote handshake word not refreshed for this many handshake transfers is
// treated as "nobody there" (0xFFFF), so a player who backs out disappears.
#define HS_STALE 120
#define HS_HEARTBEAT 30

static bool _init(struct GBASIODriver* driver);
static void _deinit(struct GBASIODriver* driver);
static void _reset(struct GBASIODriver* driver);
static void _setMode(struct GBASIODriver* driver, enum GBASIOMode mode);
static bool _handlesMode(struct GBASIODriver* driver, enum GBASIOMode mode);
static int _connectedDevices(struct GBASIODriver* driver);
static int _deviceId(struct GBASIODriver* driver);
static uint16_t _writeSIOCNT(struct GBASIODriver* driver, uint16_t value);
static bool _start(struct GBASIODriver* driver);
static void _finishMultiplayer(struct GBASIODriver* driver, uint16_t data[4]);
static void _slaveEvent(struct mTiming* timing, void* context, uint32_t cyclesLate);

void NetLinkCreate(struct NetLink* nl, int localId) {
	memset(nl, 0, sizeof(*nl));
	nl->localId = localId;
	nl->d.init = _init;
	nl->d.deinit = _deinit;
	nl->d.reset = _reset;
	nl->d.setMode = _setMode;
	nl->d.handlesMode = _handlesMode;
	nl->d.connectedDevices = _connectedDevices;
	nl->d.deviceId = _deviceId;
	nl->d.writeSIOCNT = _writeSIOCNT;
	nl->d.start = _start;
	nl->d.finishMultiplayer = _finishMultiplayer;
	nl->event.context = nl;
	nl->event.callback = _slaveEvent;
	nl->event.name = "GBA SIO NetLink";
	nl->event.priority = 0x80;
	MutexInit(&nl->mutex);
}

void NetLinkDestroy(struct NetLink* nl) {
	MutexDeinit(&nl->mutex);
}

void NetLinkPushEvent(struct NetLink* nl, const struct NetLinkEvent* ev) {
	MutexLock(&nl->mutex);
	if (nl->count < NETLINK_QUEUE) {
		nl->queue[(nl->head + nl->count) % NETLINK_QUEUE] = *ev;
		++nl->count;
	} else {
		nl->overflowed = true;
	}
	MutexUnlock(&nl->mutex);
}

unsigned NetLinkBacklog(struct NetLink* nl) {
	MutexLock(&nl->mutex);
	unsigned count = nl->count;
	MutexUnlock(&nl->mutex);
	return count;
}

static bool _isState(const uint16_t* cmd) {
	return cmd[0] == NETLINK_STATE_CMD;
}

// BEBE layout seen on the wire: [0]=BEBE [1]=player/flags [2]=x [3]=y
// [4]=facing+movement [5]=map [6]=flags(high byte)+animation(low byte) [7]=misc
bool NetLinkStateReplaceable(const uint16_t* a, const uint16_t* b) {
	if (!_isState(a) || !_isState(b)) {
		return false;
	}
	return a[1] == b[1] && a[5] == b[5] && a[7] == b[7] && (a[6] & 0xFF00) == (b[6] & 0xFF00);
}

static bool _peek(struct NetLink* nl, struct NetLinkEvent* ev) {
	bool ok = false;
	MutexLock(&nl->mutex);
	if (nl->count) {
		*ev = nl->queue[nl->head];
		ok = true;
	}
	MutexUnlock(&nl->mutex);
	return ok;
}

static void _pop(struct NetLink* nl) {
	MutexLock(&nl->mutex);
	if (nl->count) {
		nl->head = (nl->head + 1) % NETLINK_QUEUE;
		--nl->count;
	}
	MutexUnlock(&nl->mutex);
}

static void _flush(struct NetLink* nl) {
	MutexLock(&nl->mutex);
	nl->head = 0;
	nl->count = 0;
	nl->overflowed = false;
	MutexUnlock(&nl->mutex);
}

// A new link session starts from scratch: anything queued belongs to an old one.
static void _resetState(struct NetLink* nl) {
	if (nl->state == NETLINK_DATA) {
		fprintf(stderr, "netlink[%d]: game reset the link\n", nl->localId);
	}
	nl->state = NETLINK_HANDSHAKE;
	nl->prevCount = 0;
	nl->playerCount = 0;
	nl->xferIndex = 0;
	nl->checksum = 0;
	nl->absent = 0;
	nl->hsSinceSent = HS_HEARTBEAT; // announce our handshake word right away
	memset(nl->entry, 0, sizeof(nl->entry));
	memset(nl->sendCmd, 0, sizeof(nl->sendCmd));
	for (int i = 0; i < NETLINK_MAX_PLAYERS; ++i) {
		nl->hsWord[i] = 0xFFFF;
		nl->hsAge[i] = HS_STALE;
	}
	_flush(nl);
}

static void _schedule(struct NetLink* nl, int32_t when) {
	struct mTiming* timing = &nl->d.p->p->timing;
	mTimingDeschedule(timing, &nl->event);
	mTimingSchedule(timing, &nl->event, when);
}

// The relay renumbered the players (someone left). Only the master (id 0)
// starts its own transfers; everyone else is paced by the event.
void NetLinkSetLocalId(struct NetLink* nl, int localId) {
	if (localId == nl->localId || localId < 0 || localId >= NETLINK_MAX_PLAYERS) {
		return;
	}
	fprintf(stderr, "netlink: this GBA is now link player %d (was %d)\n", localId + 1, nl->localId + 1);
	nl->localId = localId;
	_resetState(nl);
	if (nl->d.p) {
		if (localId != 0) {
			_schedule(nl, VIDEO_TOTAL_LENGTH);
		} else {
			mTimingDeschedule(&nl->d.p->p->timing, &nl->event);
		}
	}
}

// Pull the cable: every other player reads as disconnected until the game
// reopens the link, which starts a clean session.
void NetLinkDisconnect(struct NetLink* nl) {
	for (int p = 0; p < NETLINK_MAX_PLAYERS; ++p) {
		if (p != nl->localId) {
			nl->absent |= 1u << p;
			nl->hsWord[p] = 0xFFFF;
			nl->hsAge[p] = HS_STALE;
		}
	}
	_flush(nl);
}

static bool _init(struct GBASIODriver* driver) {
	struct NetLink* nl = (struct NetLink*) driver;
	_resetState(nl);
	if (nl->localId != 0) {
		_schedule(nl, VIDEO_TOTAL_LENGTH);
	}
	return true;
}

static void _deinit(struct GBASIODriver* driver) {
	struct NetLink* nl = (struct NetLink*) driver;
	mTimingDeschedule(&nl->d.p->p->timing, &nl->event);
}

static void _reset(struct GBASIODriver* driver) {
	struct NetLink* nl = (struct NetLink*) driver;
	_resetState(nl);
	if (nl->localId != 0) {
		_schedule(nl, VIDEO_TOTAL_LENGTH);
	}
}

static void _setMode(struct GBASIODriver* driver, enum GBASIOMode mode) {
	UNUSED(mode);
	_resetState((struct NetLink*) driver);
}

static bool _handlesMode(struct GBASIODriver* driver, enum GBASIOMode mode) {
	UNUSED(driver);
	return mode == GBA_SIO_MULTI;
}

static int _connectedDevices(struct GBASIODriver* driver) {
	struct NetLink* nl = (struct NetLink*) driver;
	return nl->playerCount > 1 ? nl->playerCount - 1 : 1;
}

static int _deviceId(struct GBASIODriver* driver) {
	return ((struct NetLink*) driver)->localId;
}

static uint16_t _writeSIOCNT(struct GBASIODriver* driver, uint16_t value) {
	struct NetLink* nl = (struct NetLink*) driver;
	// A configuration write (not starting a transfer) that changes the IRQ or
	// baud bits means the game is (re)opening or closing the link.
	if (!GBASIOMultiplayerIsBusy(value) && ((value ^ nl->lastSiocnt) & 0x4003)) {
		_resetState(nl);
	}
	nl->lastSiocnt = value;
	return GBASIOMultiplayerFillReady(value);
}

static bool _start(struct GBASIODriver* driver) {
	UNUSED(driver);
	return true; // let mGBA time the transfer; _finishMultiplayer supplies the data
}

static bool _validHandshake(uint16_t w) {
	return w == MASTER_HANDSHAKE || (w & ~3) == SLAVE_HANDSHAKE;
}

// Drain handshake events without letting a player's word be overwritten twice
// in one transfer, so every value (notably the master's one-shot 0x8FFF) is
// seen by the game at least once. Entries are kept only once the master has
// announced a new session (0x8FFF); before that they belong to a session this
// GBA is not part of, and must not block later handshakes.
static void _applyHandshakes(struct NetLink* nl) {
	uint32_t touched = 0;
	struct NetLinkEvent ev;
	while (_peek(nl, &ev)) {
		if (ev.kind == NETLINK_EV_ENTRY) {
			if (nl->hsWord[0] == MASTER_HANDSHAKE && nl->hsAge[0] < HS_STALE) {
				break; // the session is starting: these are for DATA mode
			}
			_pop(nl);
			continue;
		}
		if (ev.player >= NETLINK_MAX_PLAYERS) {
			_pop(nl);
			continue;
		}
		if (ev.kind == NETLINK_EV_LEFT) {
			nl->hsWord[ev.player] = 0xFFFF;
			nl->hsAge[ev.player] = HS_STALE;
			_pop(nl);
			continue;
		}
		if (ev.kind != NETLINK_EV_HANDSHAKE) {
			_pop(nl);
			continue;
		}
		if (touched & (1u << ev.player)) {
			break;
		}
		touched |= 1u << ev.player;
		nl->hsWord[ev.player] = ev.words[0];
		nl->hsAge[ev.player] = 0;
		_pop(nl);
	}
}

static void _handshakeTransfer(struct NetLink* nl, uint16_t mine, uint16_t data[4]) {
	_applyHandshakes(nl);

	if (mine != nl->lastHsSent || ++nl->hsSinceSent >= HS_HEARTBEAT) {
		nl->lastHsSent = mine;
		nl->hsSinceSent = 0;
		if (nl->sendHandshake) {
			nl->sendHandshake(nl, mine);
		}
	}

	for (int i = 0; i < NETLINK_MAX_PLAYERS; ++i) {
		if (i == nl->localId) {
			data[i] = mine;
		} else {
			data[i] = nl->hsAge[i] < HS_STALE ? nl->hsWord[i] : 0xFFFF;
			if (nl->hsAge[i] < HS_STALE) {
				++nl->hsAge[i];
			}
		}
	}

	// Replicate DoHandshake() to know exactly when the game goes live.
	int count = 0;
	for (int i = 0; i < NETLINK_MAX_PLAYERS; ++i) {
		if (_validHandshake(data[i])) {
			++count;
		} else {
			if (data[i] != 0xFFFF) {
				count = 0;
			}
			break;
		}
	}
	if (count > 1 && count == nl->prevCount && data[0] == MASTER_HANDSHAKE) {
		nl->state = NETLINK_DATA;
		nl->playerCount = count;
		nl->xferIndex = 0;
		nl->checksum = 0;
		memset(nl->entry, 0, sizeof(nl->entry));
		fprintf(stderr, "netlink[%d]: link established with %d players\n", nl->localId, count);
	}
	nl->prevCount = count;
}

static void _dataTransfer(struct NetLink* nl, uint16_t mine, uint16_t data[4]) {
	int idx = nl->xferIndex;
	if (idx == 0) {
		// Checksum slot: the engine compares every player's checksum with the
		// sum of the previous entry it received, so give it exactly that.
		for (int i = 0; i < NETLINK_MAX_PLAYERS; ++i) {
			data[i] = i < nl->playerCount ? nl->checksum : 0xFFFF;
		}
		// Load the next sequenced entry (or nothing) for words 1-8.
		memset(nl->entry, 0, sizeof(nl->entry));
		MutexLock(&nl->mutex);
		bool overflowed = nl->overflowed;
		MutexUnlock(&nl->mutex);
		if (overflowed) {
			// Events were lost, so this GBA can no longer follow the shared stream.
			// Behave like a pulled cable rather than silently diverging.
			fprintf(stderr, "netlink[%d]: link queue overflowed, disconnecting\n", nl->localId);
			NetLinkDisconnect(nl);
		}
		struct NetLinkEvent ev;
		while (_peek(nl, &ev)) {
			_pop(nl);
			if (ev.player >= NETLINK_MAX_PLAYERS && ev.kind != NETLINK_EV_ENTRY) {
				continue;
			}
			if (ev.kind == NETLINK_EV_HANDSHAKE) {
				nl->hsWord[ev.player] = ev.words[0];
				nl->hsAge[ev.player] = 0;
				continue;
			}
			if (ev.kind == NETLINK_EV_LEFT) {
				if (ev.player != nl->localId) {
					nl->absent |= 1u << ev.player;
					fprintf(stderr, "netlink[%d]: player %d left the link\n", nl->localId, ev.player + 1);
				}
				continue;
			}
			if (ev.kind != NETLINK_EV_ENTRY) {
				continue;
			}
			for (int p = 0; p < NETLINK_MAX_PLAYERS; ++p) {
				memcpy(nl->entry[p], &ev.words[p * NETLINK_CMD_WORDS], sizeof(nl->entry[p]));
			}
			break;
		}
		// A missing player's GBA answers like an unplugged cable.
		for (int p = 0; p < NETLINK_MAX_PLAYERS; ++p) {
			if (nl->absent & (1u << p)) {
				data[p] = 0xFFFF;
				for (int w = 0; w < NETLINK_CMD_WORDS; ++w) {
					nl->entry[p][w] = 0xFFFF;
				}
			}
		}
	} else {
		nl->sendCmd[idx - 1] = mine;
		for (int i = 0; i < NETLINK_MAX_PLAYERS; ++i) {
			data[i] = i < nl->playerCount ? nl->entry[i][idx - 1] : 0xFFFF;
		}
		if (idx == NETLINK_CMD_WORDS) {
			uint16_t sum = 0;
			for (int p = 0; p < nl->playerCount; ++p) {
				for (int w = 0; w < NETLINK_CMD_WORDS; ++w) {
					sum += nl->entry[p][w];
				}
			}
			nl->checksum = sum;
			bool nonzero = false;
			for (int w = 0; w < NETLINK_CMD_WORDS; ++w) {
				nonzero |= nl->sendCmd[w] != 0;
			}
			if (nonzero && nl->sendCommand) {
				nl->sendCommand(nl, nl->sendCmd);
			}
			memset(nl->sendCmd, 0, sizeof(nl->sendCmd));
		}
	}
	nl->xferIndex = (idx + 1) % XFERS_PER_FRAME;
}

static void _transfer(struct NetLink* nl, uint16_t data[4]) {
	uint16_t mine = nl->d.p->p->memory.io[GBA_REG(SIOMLT_SEND)];
	if (nl->state == NETLINK_HANDSHAKE) {
		_handshakeTransfer(nl, mine, data);
	} else {
		_dataTransfer(nl, mine, data);
	}
}

static void _finishMultiplayer(struct GBASIODriver* driver, uint16_t data[4]) {
	_transfer((struct NetLink*) driver, data);
}

// Non-master GBAs never start transfers themselves, so pace them like a master would:
// one handshake transfer per frame, or a burst of 9 per frame once linked.
static void _slaveEvent(struct mTiming* timing, void* context, uint32_t cyclesLate) {
	UNUSED(timing);
	struct NetLink* nl = context;
	struct GBASIO* sio = nl->d.p;
	if (nl->localId == 0) {
		return; // became the master: the game drives transfers itself now
	}
	int32_t next = VIDEO_TOTAL_LENGTH;
	if (sio->mode == GBA_SIO_MULTI) {
		uint16_t data[4];
		_transfer(nl, data);
		GBASIOMultiplayerFinishTransfer(sio, data, cyclesLate);
		if (nl->state == NETLINK_DATA && nl->xferIndex != 0) {
			next = XFER_SPACING;
		} else if (nl->state == NETLINK_DATA) {
			next = VIDEO_TOTAL_LENGTH - (XFERS_PER_FRAME - 1) * XFER_SPACING;
		}
	}
	next -= (int32_t) cyclesLate;
	_schedule(nl, next > 0 ? next : 1);
}
