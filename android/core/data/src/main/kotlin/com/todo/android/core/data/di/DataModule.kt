package com.todo.android.core.data.di

import android.content.Context
import androidx.room.Room
import com.todo.android.core.data.ProjectionHolder
import com.todo.android.core.data.db.LocalHlcDao
import com.todo.android.core.data.db.LocalPrefDao
import com.todo.android.core.data.db.MetaDao
import com.todo.android.core.data.db.OpLogDao
import com.todo.android.core.data.db.PreferenceDao
import com.todo.android.core.data.db.TaskLocalDao
import com.todo.android.core.data.db.TodoDatabase
import com.todo.android.core.data.repository.OpRepository
import com.todo.android.core.data.repository.OpRepositoryImpl
import com.todo.android.core.data.repository.WallClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TodoDatabase =
        Room.databaseBuilder(context, TodoDatabase::class.java, TodoDatabase.NAME).build()

    @Provides
    fun provideOpLogDao(db: TodoDatabase): OpLogDao = db.opLogDao()

    @Provides
    fun provideMetaDao(db: TodoDatabase): MetaDao = db.metaDao()

    @Provides
    fun providePreferenceDao(db: TodoDatabase): PreferenceDao = db.preferenceDao()

    @Provides
    fun provideLocalPrefDao(db: TodoDatabase): LocalPrefDao = db.localPrefDao()

    @Provides
    fun provideTaskLocalDao(db: TodoDatabase): TaskLocalDao = db.taskLocalDao()

    @Provides
    fun provideLocalHlcDao(db: TodoDatabase): LocalHlcDao = db.localHlcDao()

    @Provides
    @Singleton
    fun provideWallClock(): WallClock = WallClock { System.currentTimeMillis() }

    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds
    @Singleton
    abstract fun bindOpRepository(impl: OpRepositoryImpl): OpRepository
}
