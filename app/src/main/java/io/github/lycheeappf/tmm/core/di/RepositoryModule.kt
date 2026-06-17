package io.github.lycheeappf.tmm.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.lycheeappf.tmm.data.repository.MappingRepositoryImpl
import io.github.lycheeappf.tmm.data.repository.RoomAppPolicyProvider
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.github.lycheeappf.tmm.domain.sms.SmsInboxReader
import io.github.lycheeappf.tmm.domain.sms.SmsSender
import io.github.lycheeappf.tmm.listener.filter.AppPolicyProvider
import io.github.lycheeappf.tmm.sms.read.SmsInboxReaderImpl
import io.github.lycheeappf.tmm.sms.send.RealSmsSender

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindMappingRepository(impl: MappingRepositoryImpl): MappingRepository

    @Binds
    abstract fun bindAppPolicyProvider(impl: RoomAppPolicyProvider): AppPolicyProvider

    @Binds
    abstract fun bindSmsInboxReader(impl: SmsInboxReaderImpl): SmsInboxReader

    @Binds
    abstract fun bindSmsSender(impl: RealSmsSender): SmsSender
}
