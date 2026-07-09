package io.github.lycheeappf.tmm.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.grok.GrokProvider
import io.github.lycheeappf.tmm.channel.llm.tools.AssistantTool
import io.github.lycheeappf.tmm.channel.llm.tools.tesla.TeslaNavigateTool

/**
 * Hängt den V2-LLM-Stack in den DI-Graph:
 *  - [LlmProvider] → [GrokProvider]
 *  - Tool-Set für [io.github.lycheeappf.tmm.channel.llm.tools.ToolRegistry]
 *    (via `@Multibinds` als leere Basis; Tools hängen sich additiv via
 *    `@Binds @IntoSet` ein).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {

    @Binds
    abstract fun bindLlmProvider(impl: GrokProvider): LlmProvider

    @Multibinds
    abstract fun bindAssistantToolSet(): Set<@JvmSuppressWildcards AssistantTool>

    @Binds
    @IntoSet
    abstract fun bindTeslaNavigateTool(impl: TeslaNavigateTool): AssistantTool
}
