package io.github.lycheeappf.tmm.core.model

/**
 * Identifiziert einen Channel. Code-Ziffer wird in [FakeAddress] encoded.
 *
 * V1: NOTIFICATION (0), SYSTEM (9). LLM (1) ist reserviert für V2.
 */
enum class ChannelId(val code: Int, val label: String) {
    NOTIFICATION(0, "Notification"),
    LLM(1, "AI Assistant"),
    SYSTEM(9, "System");

    companion object {
        fun fromCode(code: Int): ChannelId? =
            entries.firstOrNull { it.code == code }
    }
}
