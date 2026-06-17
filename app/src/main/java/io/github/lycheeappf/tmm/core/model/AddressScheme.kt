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
     * +888 X YYYYYYY — ITU-T "Telecommunications for Disaster Relief" (TDR),
     * längst "returned to spare". Von Google libphonenumber PARSEBAR (Code 888 ist
     * in CountryCodeToRegionCodeMap → Region 001), daher überlebt der Contact
     * Androids PhoneLookup-Filter (removeNoMatchPhoneNumber → areSamePhoneNumber →
     * libphonenumber.parse wirft NICHT) und Tesla bekommt den Namen. Gleichzeitig
     * global unrouted → Carrier lehnt eine ausgehende Reply-SMS kostenlos ab,
     * keine echten Teilnehmer. Carrier-Verhalten bleibt per PreFlightTester zu bestätigen.
     */
    Itu888(
        prefix = "+888",
        totalLength = 12,
        displayLabel = "+888 (ITU TDR)"
    )
}
