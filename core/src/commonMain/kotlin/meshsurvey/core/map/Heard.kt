package meshsurvey.core.map

import meshsurvey.core.model.KnownNode

/**
 * "Heard from" identities. Packets name the node a radio heard them from only by a short prefix:
 * MeshCore by the first byte(s) of the repeater's public key, Meshtastic (relayed packets) by the last byte of the
 * relaying node's number. Receptions are grouped by that byte, and a group is matched to known nodes where possible.
 * Two nodes can share a byte, so a match is shown as "possibly" when it is not unique.
 */
object Heard {
    /** Group key: "mc:a3" (MeshCore, first key byte) or "mt:~dd" (Meshtastic, last node-number byte). */
    fun group(heardId: String): String =
        if (heardId.startsWith("mc:")) heardId.take(5) else "mt:~" + heardId.takeLast(2)

    fun protocolOf(group: String) = if (group.startsWith("mc:")) "meshcore" else "meshtastic"

    /** The prefix byte a known node would show in packets. */
    fun groupOf(n: KnownNode): String? = when (n.protocol) {
        "meshcore" -> n.nodeId.take(2).takeIf { it.length == 2 }?.let { "mc:${it.lowercase()}" }
        "meshtastic" -> n.nodeId.takeLast(2).takeIf { it.length == 2 }?.let { "mt:~${it.lowercase()}" }
        else -> null
    }

    /** Known nodes that could be the node behind [group], repeaters first. */
    fun candidates(group: String, known: List<KnownNode>): List<KnownNode> =
        known.filter { groupOf(it) == group }.sortedBy { if (it.kind == "repeater" || it.kind == "ROUTER" || it.kind == "REPEATER") 0 else 1 }

    /** "Hilltop RPT (mc:a3)", "possibly Hilltop RPT or Barn (mc:a3)", or just the key when nothing matches. */
    fun label(group: String, known: List<KnownNode>): String {
        val c = candidates(group, known)
        val name = { n: KnownNode -> n.name ?: n.nodeId.take(8) }
        return when (c.size) {
            0 -> group
            1 -> "${name(c[0])} ($group)"
            else -> "possibly ${c.take(2).joinToString(" or ") { name(it) }}${if (c.size > 2) " +${c.size - 2}" else ""} ($group)"
        }
    }

    /** Stable, distinguishable colour (ARGB) for a group. */
    fun color(group: String): Long {
        val palette = longArrayOf(0xFFE6194BL, 0xFF3CB44BL, 0xFF4363D8L, 0xFFF58231L, 0xFF911EB4L, 0xFF42D4F4L, 0xFFF032E6L,
            0xFF9A6324L, 0xFF469990L, 0xFF800000L, 0xFF808000L, 0xFF000075L, 0xFFBFEF45L, 0xFFDCBEFFL)
        var h = 0
        for (c in group) h = h * 31 + c.code
        return palette[(h and 0x7fffffff) % palette.size]
    }
}
