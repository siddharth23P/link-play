package com.linkplay.app.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Just enough STUN (RFC 5389) to ask a server what our socket looks like from outside: a binding
 * request without attributes, and the (XOR-)MAPPED-ADDRESS from its success response. It runs on
 * the transport's own socket, so the answer is the address peers can reach that socket at.
 */
internal object Stun {
    const val MAGIC_COOKIE = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS = 0x0101
    private const val ATTR_MAPPED = 0x0001
    private const val ATTR_XOR_MAPPED = 0x0020
    private const val ATTR_XOR_MAPPED_OLD = 0x8020 // some servers still send the pre-RFC code

    class Response(val txId: ByteArray, val mapped: InetSocketAddress?)

    /** Our packets start with 'L'; STUN messages with 0x00 / 0x01 and carry the magic cookie at 4..7. */
    fun isStun(p: ByteArray, len: Int) =
        len >= 20 && (p[0].toInt() == 0 || p[0].toInt() == 1) && ByteBuffer.wrap(p, 4, 4).int == MAGIC_COOKIE

    fun bindingRequest(txId: ByteArray): ByteArray {
        require(txId.size == 12)
        return ByteBuffer.allocate(20).putShort(BINDING_REQUEST.toShort()).putShort(0).putInt(MAGIC_COOKIE).put(txId).array()
    }

    /** A binding success response, or null for anything else (or anything malformed). */
    fun parseResponse(p: ByteArray, len: Int): Response? {
        if (!isStun(p, len)) return null
        val b = ByteBuffer.wrap(p, 0, len)
        if (b.short.toInt() and 0xffff != BINDING_SUCCESS) return null
        val end = 20 + (b.short.toInt() and 0xffff)
        if (end > len) return null
        val txId = p.copyOfRange(8, 20)
        b.position(20)
        var xor: InetSocketAddress? = null
        var plain: InetSocketAddress? = null
        while (b.position() + 4 <= end) {
            val type = b.short.toInt() and 0xffff
            val alen = b.short.toInt() and 0xffff
            val at = b.position()
            if (at + alen > end) return null
            when (type) {
                ATTR_XOR_MAPPED, ATTR_XOR_MAPPED_OLD -> xor = xor ?: address(p, at, alen, txId)
                ATTR_MAPPED -> plain = plain ?: address(p, at, alen, null)
            }
            b.position(at + ((alen + 3) and 3.inv()).coerceAtMost(end - at))
        }
        return Response(txId, xor ?: plain)
    }

    /** reserved, family (1 = IPv4, 2 = IPv6), port, address; XORed with cookie (+ txId) when [txId] is given. */
    private fun address(p: ByteArray, at: Int, alen: Int, txId: ByteArray?): InetSocketAddress? {
        // Before reading the family: a short attribute can end exactly at the end of the buffer.
        if (alen < 4) return null
        val size = when (p[at + 1].toInt()) { 1 -> 4; 2 -> 16; else -> return null }
        if (alen < 4 + size) return null
        val mask = ByteBuffer.allocate(16).putInt(MAGIC_COOKIE).put(txId ?: ByteArray(12)).array()
        var port = ((p[at + 2].toInt() and 0xff) shl 8) or (p[at + 3].toInt() and 0xff)
        val ip = ByteArray(size) { p[at + 4 + it] }
        if (txId != null) {
            port = port xor (MAGIC_COOKIE ushr 16)
            for (i in ip.indices) ip[i] = (ip[i].toInt() xor mask[i].toInt()).toByte()
        }
        return InetSocketAddress(InetAddress.getByAddress(ip), port)
    }
}

/**
 * What two STUN servers on different IPs saw of the room's socket (only when the user allowed STUN),
 * and so whether a friend can reach us on IPv4. [local] are the public-looking IPv4 addresses on our
 * own interface at the socket's port; [answers] holds each server's answer (null: no answer).
 *  - OPEN: every answer is one of [local]: no NAT, the interface address really is ours.
 *  - EASY: both saw the same ip:port. The NAT keeps one public port per socket, whoever we send to,
 *    so the port a server saw is the one a friend's packets will find (once our side has sent to them).
 *  - STRICT: they saw different ports (or addresses): the NAT picks a new port per destination, so the
 *    port in a code is useless to a friend. Only IPv6 (mobile data) gets through such a network.
 *  - UNSURE: only one server answered (and not with our own address): can't tell, so nothing is offered.
 *  - NO_ANSWER: STUN didn't get through at all.
 */
class NatCheck(val local: List<InetSocketAddress>, val answers: List<InetSocketAddress?>) {
    enum class Kind { NO_ANSWER, OPEN, EASY, STRICT, UNSURE }

    val kind: Kind = run {
        val got = answers.filterNotNull()
        when {
            got.isEmpty() -> Kind.NO_ANSWER
            got.all { it in local } -> Kind.OPEN
            got.size < 2 -> Kind.UNSURE
            got.all { it == got[0] } -> Kind.EASY
            else -> Kind.STRICT
        }
    }

    /** Our public IPv4 address and port for a code: only when friends can really use it. */
    val mapped: InetSocketAddress? =
        if (kind == Kind.OPEN || kind == Kind.EASY) answers.firstOrNull { it != null && PortMapper.isPublicV4(it.address) } else null

    val strict get() = kind == Kind.STRICT

    fun describe(): String = when (kind) {
        Kind.NO_ANSWER -> "no STUN server answered"
        Kind.OPEN -> "no NAT: public IPv4 ${UdpTransport.format(answers.firstNotNullOf { it })} is this phone's own"
        Kind.EASY -> "easy NAT: both servers saw ${UdpTransport.format(answers.firstNotNullOf { it })}"
        Kind.STRICT -> "strict NAT: servers saw " + answers.filterNotNull().joinToString(" and ") { UdpTransport.format(it) }
        Kind.UNSURE -> "unsure: only one server answered (${UdpTransport.format(answers.firstNotNullOf { it })})"
    }

    companion object {
        /**
         * The public IPv4 candidates for a code. [interfaceV4] are public-looking addresses on our own
         * interface (at the socket's port). On Wi-Fi / Ethernet they go in as before. On mobile data they
         * don't, unless STUN saw exactly that address and port: carriers number their internal networks
         * out of public blocks they don't announce (21, 22, 25, 29, 30, 56.0.0.0/8...), and such an
         * address would only waste a slot in the code. STUN's own answer goes in when [nat] says friends
         * can use it (no NAT, or an easy one).
         */
        fun ipv4Candidates(interfaceV4: List<InetSocketAddress>, cellular: Boolean, nat: NatCheck?): List<Candidate> {
            val out = LinkedHashSet<InetSocketAddress>()
            for (a in interfaceV4) if (!cellular || (nat?.kind == Kind.OPEN && a in nat.answers)) out += a
            nat?.mapped?.let { out += it }
            return out.map { Candidate(it, Candidate.Kind.MAPPED) }
        }
    }
}
