package io.github.lycheeappf.tmm.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.security.KeystoreApiKeyStore

/**
 * Bindet die [ApiKeyStore]-Implementation. Hilt-getrennt von [LlmModule],
 * damit Tests einen Fake-Store via TestInstallIn ersetzen können.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    abstract fun bindApiKeyStore(impl: KeystoreApiKeyStore): ApiKeyStore
}
