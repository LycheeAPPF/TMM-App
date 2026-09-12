package io.github.lycheeappf.tmm.core.model

/**
 * Fake-Telefonnummer im Schema `<prefix> [Channel-Digit] [8-stellige Mapping-ID]`.
 *
 * Konkrete Adress-Form hängt vom [AddressScheme] ab — Default ist
 * [AddressScheme.Itu888] (`+888...`): von libphonenumber parsebar (sonst
 * verwirft Androids PhoneLookup den Contact) und vom Carrier kostenlos abgelehnt.
 */
data class FakeAddress(
    val channel: ChannelId,
    val mappingId: Long
) {
    init {
        require(mappingId in 0 until MAX_MAPPING_ID) {
            "mappingId $mappingId out of range [0, $MAX_MAPPING_ID)"
        }
    }

    /** Formatiert die Adresse im aktiven [AddressScheme] (Itu888 / +888). */
    fun toE164(): String =
        AddressScheme.Itu888.prefix + channel.code.toString() + mappingId.toString().padStart(ID_DIGITS, '0')

    companion object {
        const val ID_DIGITS = 8
        const val MAX_MAPPING_ID = 100_000_000L

        /**
         * Länge des Vor-1.0.2-Schemas (7-stellige ID). Wird weiter geparst, damit
         * Tesla-Replies auf alte Threads und die Inbox-Filterung bestehender Fake-Rows
         * nach dem Upgrade funktionieren; neu geschrieben wird nur noch [ID_DIGITS].
         */
        const val LEGACY_TOTAL_LENGTH = 12

        /**
         * Parsed eine rohe Adress-String gegen das aktive [AddressScheme] (Itu888).
         * Returnt null, wenn es nicht matched.
         */
        fun parse(raw: String): FakeAddress? {
            var clean = raw.replace("[^+0-9]".toRegex(), "")
            // Normalisierung: manche Tesla-MAP-Exporter geben Adressen als
            // `00` statt `+` (alte AT-Command-Konvention); wandle das vor dem
            // Schema-Match um, sonst werden eigene Fake-Adressen als "NotOurs"
            // klassifiziert und der Reply geht ins Leere.
            if (clean.startsWith("00")) clean = "+" + clean.substring(2)
            return tryParseWith(clean, AddressScheme.Itu888)
        }

        private fun tryParseWith(clean: String, scheme: AddressScheme): FakeAddress? {
            if (clean.length != scheme.totalLength && clean.length != LEGACY_TOTAL_LENGTH) return null
            if (!clean.startsWith(scheme.prefix)) return null
            val channelDigit = clean[scheme.prefix.length].digitToIntOrNull() ?: return null
            val channel = ChannelId.fromCode(channelDigit) ?: return null
            val id = clean.substring(scheme.prefix.length + 1).toLongOrNull() ?: return null
            return FakeAddress(channel, id)
        }

        fun isFakeAddress(raw: String): Boolean = parse(raw) != null
    }
}
