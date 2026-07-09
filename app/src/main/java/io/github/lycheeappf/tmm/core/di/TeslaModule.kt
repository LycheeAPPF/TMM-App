package io.github.lycheeappf.tmm.core.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.lycheeappf.tmm.channel.llm.tools.AssistantTool
import io.github.lycheeappf.tmm.channel.llm.tools.tesla.TeslaNavigateTool
import io.github.lycheeappf.tmm.data.store.DataStoreTeslaRegionStore
import io.github.lycheeappf.tmm.data.store.KeystoreTeslaTokenStore
import io.github.lycheeappf.tmm.data.store.TeslaRegionStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaFleetApi
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaOAuthEndpoints
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TeslaModule {

    @Provides
    @Singleton
    @TeslaJson
    fun provideTeslaJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    @TeslaHttpClient
    fun provideTeslaOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Provides
    @Singleton
    @TeslaRetrofit
    fun provideTeslaRetrofit(
        @TeslaHttpClient client: OkHttpClient,
        @TeslaJson json: Json
    ): Retrofit = Retrofit.Builder()
        // Base-URL ist Platzhalter — alle Calls nutzen @Url (regional).
        .baseUrl("https://fleet-api.prd.na.vn.cloud.tesla.com/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideTeslaFleetApi(@TeslaRetrofit retrofit: Retrofit): TeslaFleetApi =
        retrofit.create(TeslaFleetApi::class.java)

    @Provides
    @Singleton
    @IntoSet
    fun provideTeslaNavigateTool(impl: TeslaNavigateTool): AssistantTool = impl

    /** Produktions-Endpunkte; Tests konstruieren [TeslaOAuthEndpoints] mit MockWebServer-URLs. */
    @Provides
    @Singleton
    fun provideTeslaOAuthEndpoints(): TeslaOAuthEndpoints = TeslaOAuthEndpoints()
}

/**
 * Interface→Impl-Bindings der Tesla-Persistenz. Getrennt vom Provides-Modul,
 * damit Tests die Stores via TestInstallIn durch Fakes ersetzen können.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TeslaStoreModule {

    @Binds
    abstract fun bindTeslaTokenStore(impl: KeystoreTeslaTokenStore): TeslaTokenStore

    @Binds
    abstract fun bindTeslaRegionStore(impl: DataStoreTeslaRegionStore): TeslaRegionStore
}

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class TeslaJson
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class TeslaHttpClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class TeslaRetrofit
