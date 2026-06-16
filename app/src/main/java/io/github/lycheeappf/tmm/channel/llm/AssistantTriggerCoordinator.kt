package io.github.lycheeappf.tmm.channel.llm

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry-point für jeden möglichen Trigger einer LLM-Session. Aktuell
 * delegiert er stupide an [LlmStarter] — die Abstraktion existiert, damit V3
 * neue Quellen (BLE, Quick-Settings, Intent-Receiver) einklinken können, ohne
 * dass die UI- oder Service-Schicht Wissen über die Initialisierungs-Logik
 * (Mapping-Allocation, Welcome-Inject, Conversation-Reset) bekommen.
 */
@Singleton
class AssistantTriggerCoordinator @Inject constructor(
    private val starter: LlmStarter
) {
    suspend fun trigger(source: AssistantTriggerSource): LlmStarter.StartResult =
        starter.start(source)
}
