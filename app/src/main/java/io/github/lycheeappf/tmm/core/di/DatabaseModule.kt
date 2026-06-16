package io.github.lycheeappf.tmm.core.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.lycheeappf.tmm.data.db.AppPolicyDao
import io.github.lycheeappf.tmm.data.db.MappingDao
import io.github.lycheeappf.tmm.data.db.MfsDatabase
import io.github.lycheeappf.tmm.data.db.ReplyHistoryDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MfsDatabase =
        Room.databaseBuilder(context, MfsDatabase::class.java, MfsDatabase.NAME)
            // Nur bei Downgrade destruktiv — Upgrades brauchen explizite Migrations.
            // Verhindert ungewollten Mapping-Verlust bei zukünftigem Schema-Upgrade.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideMappingDao(db: MfsDatabase): MappingDao = db.mappingDao()

    @Provides
    fun provideReplyHistoryDao(db: MfsDatabase): ReplyHistoryDao = db.replyHistoryDao()

    @Provides
    fun provideAppPolicyDao(db: MfsDatabase): AppPolicyDao = db.appPolicyDao()
}
