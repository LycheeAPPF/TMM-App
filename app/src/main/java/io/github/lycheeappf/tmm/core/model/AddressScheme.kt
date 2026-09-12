package io.github.lycheeappf.tmm.core.model

/**
 * Format-Schema für Fake-Telefonnummern. Aktiv ist [Itu888] (+888) — das einzige Schema.
 */
enum class AddressScheme(
    val prefix: String,
    val totalLength: Int,
    val displayLabel: String
) {
    /**
     * +888 X YYYYYYYY — ITU-T "Telecommunications for Disaster Relief" (TDR),
     * längst "returned to spare". Von Google libphonenumber PARSEBAR (Code 888 ist
     * in CountryCodeToRegionCodeMap → Region 001), daher überlebt der Contact
     * Androids PhoneLookup-Filter (removeNoMatchPhoneNumber → areSamePhoneNumber →
     * libphonenumber.parse wirft NICHT) und Tesla bekommt den Namen. Gleichzeitig
     * global unrouted → Carrier lehnt eine ausgehende Reply-SMS kostenlos ab.
     * Carrier-Verhalten bleibt per PreFlightTester zu bestätigen.
     *
     * 9 Ziffern nach +888 (Channel + 8-stellige ID), NICHT 8: Telegram/Fragment vergibt
     * anonyme +888-Nummern mit genau 8 Ziffern. Mit 8 Ziffern matcht Telegrams
     * Kontakt-Sync unsere Fake-Kontakte auf fremde Telegram-Accounts und zeigt sie
     * als Chats mit unserem Anzeigenamen.
     */
    Itu888(
        prefix = "+888",
        totalLength = 13,
        displayLabel = "+888 (ITU TDR)"
    )
}
