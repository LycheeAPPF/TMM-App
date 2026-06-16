package io.github.lycheeappf.tmm.channel.llm

/**
 * Auslöser einer LLM-Session. Encoded den Eintrittspunkt fürs Logging und für
 * spätere Erweiterungen (z.B. unterschiedliche Welcome-Messages je Quelle).
 *
 * V2: nur [MANUAL_BUTTON] ist aktiv verdrahtet.
 * V3 (geplant): BLE-HID-Button am Lenkrad, Quick-Settings-Tile, externe
 *   App-Intent. Die Coordinator-Schicht ist absichtlich da, damit das ein
 *   reiner additive change ist.
 */
enum class AssistantTriggerSource(val label: String) {
    MANUAL_BUTTON("Manual"),
    BLE_DEVICE("BLE"),
    QUICK_SETTINGS_TILE("QuickSettings"),
    INTENT_API("IntentApi"),
    AUTOMATIC("Automatic")
}
