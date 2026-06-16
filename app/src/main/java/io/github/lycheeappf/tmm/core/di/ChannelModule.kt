package io.github.lycheeappf.tmm.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.lycheeappf.tmm.channel.llm.LlmChannel
import io.github.lycheeappf.tmm.channel.notification.NotificationChannel
import io.github.lycheeappf.tmm.channel.system.SystemChannel
import io.github.lycheeappf.tmm.domain.channel.MessagingChannel

/**
 * Registriert Channel-Implementations als Set<MessagingChannel> für die
 * [io.github.lycheeappf.tmm.domain.channel.ChannelRegistry].
 *
 * V2: zusätzlich [LlmChannel] (Grok). Notification + System bleiben unverändert.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChannelModule {

    @Binds
    @IntoSet
    abstract fun bindNotificationChannel(impl: NotificationChannel): MessagingChannel

    @Binds
    @IntoSet
    abstract fun bindSystemChannel(impl: SystemChannel): MessagingChannel

    @Binds
    @IntoSet
    abstract fun bindLlmChannel(impl: LlmChannel): MessagingChannel
}
