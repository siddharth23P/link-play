package com.linkplay.desktop.ui

import com.linkplay.app.net.Candidate
import com.linkplay.desktop.DesktopLink
import com.linkplay.desktop.DesktopNet

/**
 * The words of the link screens: what the room is doing and what to do about it. Ported from the
 * phone app's MainActivity, with "phone" and "mobile data" turned into what a computer has. The
 * text of failures from the net package still says "phone" (that code is shared and unchanged).
 */
object LinkText {
    /** Which network the room uses and what friends can reach, in plain words. */
    fun hostInfo(l: DesktopLink, roster: Int): String {
        val net = l.net ?: return ""
        if (!net.online) return "No network: connect to Wi-Fi or Ethernet."
        val c = l.candidates
        val v6 = c.any { it.kind == Candidate.Kind.IPV6 }
        val lan = c.any { it.kind == Candidate.Kind.LAN }
        val public4 = c.any { it.kind == Candidate.Kind.MAPPED }
        // Words, not check marks: Segoe UI has no glyph for them, so they would be boxes on Windows.
        val via = mutableListOf("IPv6 " + if (v6) "yes" else "no")
        if (lan) via += if (net.label == "Wi-Fi") "Wi-Fi LAN" else "LAN"
        l.routerPort?.let { via += "router port " + if (it) "yes" else "no" }
        if (public4 && l.routerPort != true) via += "public IPv4 yes"
        val lines = mutableListOf(linkUses(net), "Reachable via: " + via.joinToString(" · "))
        when {
            net.isVpn -> lines += "A VPN is on, so friends can only reach you through it. Turn it off to play directly."
            !v6 && !public4 -> lines += "Friends in other places can't reach this network. Your friend must be on the same Wi-Fi as you, " +
                "or one of you needs IPv6 (then try hosting again)."
        }
        strictAdvice(l, net)?.let { lines += it }
        // No router port and nobody in yet: on a Mac the likely cause is the Local Network switch.
        if (roster <= 1 && l.routerPort != true) macLocalNetwork()?.let { lines += it }
        if (roster < l.invite.maxPlayers && (v6 || public4) && !l.lanGuest) {
            lines += "Tip: playing with 3 or 4 in different places? Everyone connects through the host, so the player with IPv6 should host."
        }
        return lines.joinToString("\n")
    }

    /**
     * The STUN self-test (Advanced, opt-in) found a NAT that makes a new port for every friend, so our
     * public IPv4 is no use to friends. Nothing to say when the router opened a port or IPv6 does the job anyway.
     */
    fun strictAdvice(l: DesktopLink, net: DesktopNet.Snapshot): String? {
        if (l.nat?.strict != true || l.routerPort == true) return null
        val host = l.invite.hostName
        return when {
            l.hosting && net.hasIpv6 -> "Your network is strict for IPv4, so friends reach you over IPv6."
            l.hosting -> "Your network is strict for IPv4 and has no IPv6, so friends in other places can't reach you."
            net.hasIpv6 -> if (l.invite.candidates.any { it.kind == Candidate.Kind.IPV6 }) "Your network is strict for IPv4, so you reach $host over IPv6." else null
            else -> "Your network is strict for IPv4 and has no IPv6, so your reply code can't open a path to you."
        }
    }

    fun linkUses(net: DesktopNet.Snapshot) = "Link uses ${net.label} (${if (net.hasIpv6) "IPv6" else "no IPv6"})"

    /**
     * Why this guest may not get through, in plain words; null when nothing stands out. We can't
     * tell whether we share the host's Wi-Fi, so the wording fits both; on the same Wi-Fi the LAN path
     * connects within a second anyway.
     */
    fun guestHint(l: DesktopLink, net: DesktopNet.Snapshot): String? {
        val host = l.invite.hostName
        val c = l.invite.candidates
        val hostV6 = c.any { it.kind == Candidate.Kind.IPV6 }
        val hostPublic = c.any { it.kind == Candidate.Kind.MAPPED }
        val hostLan = c.any { it.kind == Candidate.Kind.LAN }
        val main = when {
            !net.online -> null
            c.isEmpty() -> "$host can't be reached directly: send $host the reply code below."
            !hostV6 && !hostPublic -> "$host can only be reached on their own Wi-Fi. If you're not on it, join the same Wi-Fi, or ask $host to host again from a network with IPv6."
            hostV6 && !net.hasIpv6 && !hostPublic -> "Your network has no IPv6, so it can't reach $host" +
                (if (hostLan) " unless you're on the same Wi-Fi" else "") + ". Try another network, then join again."
            else -> null
        }
        // The host stayed silent and its invite holds nothing but LAN addresses: only the local network can carry this.
        val mac = if (net.online && l.hostSilent && c.isNotEmpty() && c.all { it.kind == Candidate.Kind.LAN }) macLocalNetwork() else null
        return listOfNotNull(main, mac).joinToString("\n").ifEmpty { null }
    }

    /**
     * macOS asks once whether an app may use the local network. A "Don't Allow" makes same-Wi-Fi play fail without
     * a word, so the words are here. Null on other systems ([mac] false). Callers decide when it is likely to matter.
     */
    fun macLocalNetwork(mac: Boolean = Fonts.mac): String? =
        if (mac) "On the same Wi-Fi and nothing happens? Open System Settings → Privacy & Security → Local Network and switch Link Play on." else null

    fun kindName(k: Candidate.Kind) = when (k) {
        Candidate.Kind.LAN -> "LAN"
        Candidate.Kind.IPV6 -> "IPv6"
        Candidate.Kind.MAPPED -> "public IPv4"
    }

    /**
     * A guest that isn't in the room yet, in one line ([how] says where the reply code is). Asks for the
     * reply code only once the host has been silent a while and the code can help.
     */
    fun guestWait(l: DesktopLink, replySent: Boolean, how: String): String {
        val host = l.invite.hostName
        return when {
            l.connectedVia != null -> "Connected, joining ${l.invite.roomName}…"
            !l.hostSilent -> "Connecting to ${l.invite.roomName}…"
            !l.replyCanHelp -> "No answer from $host yet"
            replySent -> "Reply code sent — waiting for $host to paste it"
            else -> "No answer from $host yet — $how"
        }
    }

    /** What Advanced shows about the network. */
    fun networkLine(s: DesktopNet.Snapshot): String =
        if (!s.online) "Network: none" else "Network: ${s.label.replaceFirstChar { it.uppercase() }} · IPv6 ${if (s.hasIpv6) "yes" else "no"}\n${s.describe()}"
}
