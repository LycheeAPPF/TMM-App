package io.github.lycheeappf.tmm.core.di

import android.app.LocaleManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.lycheeappf.tmm.core.locale.LocaleProvider
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.core.util.LogFileStore
import io.github.lycheeappf.tmm.core.util.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Locale
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides @Singleton
    fun provideClock(): Clock = SystemClock

    /**
     * Rollierende On-Disk-Persistenz für [io.github.lycheeappf.tmm.core.util.LogBuffer].
     * Liegt in `filesDir/diagnostics/` (nicht cacheDir — Cache kann das System mitten
     * in der Fahrt räumen).
     */
    @Provides @Singleton
    fun provideLogFileStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): LogFileStore = LogFileStore(File(context.filesDir, "diagnostics"), ioDispatcher)

    /**
     * Aktive App-Locale (per-app locale, API 33+). Leer ("Systemsprache folgen")
     * → [Locale.getDefault]. Wird genutzt, um sprachabhängige Defaults (Grok-Prompt)
     * zur Lesezeit zu wählen.
     */
    @Provides @Singleton
    fun provideLocaleProvider(@ApplicationContext context: Context): LocaleProvider =
        LocaleProvider {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales == null || locales.isEmpty) Locale.getDefault() else locales[0]
        }
}

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MainDispatcher
