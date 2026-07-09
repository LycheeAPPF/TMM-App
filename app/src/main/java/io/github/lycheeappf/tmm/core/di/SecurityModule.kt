package io.github.lycheeappf.tmm.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.security.KeystoreApiKeyStore
import io.github.lycheeappf.tmm.core.security.KeystoreTeslaCredentialsStore
import io.github.lycheeappf.tmm.core.security.TeslaCredentialsStore

/**
 * Bindet die Secret-Store-Implementationen. Hilt-getrennt von [LlmModule],
 * damit Tests Fake-Stores via TestInstallIn ersetzen können.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    abstract fun bindApiKeyStore(impl: KeystoreApiKeyStore): ApiKeyStore

    @Binds
    abstract fun bindTeslaCredentialsStore(impl: KeystoreTeslaCredentialsStore): TeslaCredentialsStore
}
