package com.todo.android.core.sync.di

import com.todo.android.core.data.repository.SyncTrigger
import com.todo.android.core.sync.SyncNotifier
import com.todo.android.core.sync.SyncNotifierImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindings {
    @Binds
    @Singleton
    abstract fun bindSyncNotifier(impl: SyncNotifierImpl): SyncNotifier

    @Binds
    @Singleton
    abstract fun bindSyncTrigger(impl: SyncNotifierImpl): SyncTrigger
}
