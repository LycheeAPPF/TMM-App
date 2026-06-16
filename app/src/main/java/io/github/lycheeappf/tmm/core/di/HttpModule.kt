package io.github.lycheeappf.tmm.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.lycheeappf.tmm.channel.llm.provider.grok.GrokApi
import io.github.lycheeappf.tmm.channel.llm.provider.grok.GrokConfig
import io.github.lycheeappf.tmm.channel.llm.provider.grok.RedactingAuthInterceptor
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Stellt OkHttp + Retrofit für die Grok-Integration bereit. Bewusst KEIN globaler
 * "App-Http-Client", weil V1 (Notification-Bridge) komplett offline ist und keinen
 * HTTP-Stack braucht; jeder weitere Provider (Claude, Ollama, …) würde später
 * eigene Qualifier-Module bekommen.
 */
@Module
@InstallIn(SingletonComponent::class)
object HttpModule {

    @Provides
    @Singleton
    @AssistantJson
    fun provideAssistantJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        // Defensive defaults — xAI darf jederzeit neue Output-Item-Typen einführen,
        // ohne dass wir Updates pushen müssen.
        coerceInputValues = true
    }

    @Provides
    @Singleton
    @AssistantHttpClient
    fun provideAssistantOkHttp(redactInterceptor: RedactingAuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(redactInterceptor)
            .retryOnConnectionFailure(false)         // 5xx-Retry erfolgt höher (RetryPolicy V3)
            .build()

    @Provides
    @Singleton
    @AssistantRetrofit
    fun provideAssistantRetrofit(
        @AssistantHttpClient client: OkHttpClient,
        @AssistantJson json: Json
    ): Retrofit = Retrofit.Builder()
        .baseUrl(GrokConfig.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideGrokApi(@AssistantRetrofit retrofit: Retrofit): GrokApi =
        retrofit.create(GrokApi::class.java)
}

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AssistantJson
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AssistantHttpClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AssistantRetrofit
