package io.github.lycheeappf.tmm.core.model

/**
 * Format-Schema für Fake-Telefonnummern. Aktiv ist fix [Itu888] (+888); die
 * abgelösten Schemata [Itu999]/[De32] bleiben nur noch für das Parsing von
 * Alt-Mappings in [entries] erhalten.
 */
enum class AddressScheme(
    val key: String,
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
     * keine echten Teilnehmer. DEFAULT seit dem PhoneLookup-Fix. Carrier-Verhalten
     * bleibt per PreFlightTester zu bestätigen.
     */
    Itu888(
        key = "itu_888",
        prefix = "+888",
        totalLength = 12,
        displayLabel = "+888 (ITU TDR)"
    ),

    /**
     * +99942 X YYYYYYY — ITU-T E.164 Test-Range. Carrier lehnt ab (gut), ABER
     * NICHT von libphonenumber parsebar (Code 999 ist KEIN gültiger Ländercode)
     * → Android PhoneLookup verwirft den Contact, Tesla zeigt die ROHNUMMER. Nur
     * noch für Legacy-Parsing in [entries] behalten — NICHT mehr Default.
     */
    Itu999(
        key = "itu_999",
        prefix = "+99942",
        totalLength = 14,
        displayLabel = "+99942 (ITU, alt)"
    ),

    /**
     * +4932 X YYYYYYY — DE Nationale Teilnehmerrufnummer. ACHTUNG: diese Range ist
     * ZUGETEILT — echte Menschen/Firmen haben +4932-Nummern → der Carrier ROUTET
     * eine Reply-SMS ggf. (Kosten + Zustellung an Fremde). Nur noch für Legacy-
     * Parsing in [entries] behalten — wird NIE als aktives Schema gesetzt.
     */
    De32(
        key = "de_32",
        prefix = "+4932",
        totalLength = 13,
        displayLabel = "+4932 (DE, unsicher)"
    );

    companion object {
        fun fromKey(key: String?): AddressScheme =
            entries.firstOrNull { it.key == key } ?: Itu888
    }
}
