package com.todo.android.feature.widget

import android.content.Context
import com.todo.android.core.data.repository.DataChangeListener
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WidgetModule {
    /** 本地写入或 pull 落库后刷新所有桌面卡片。 */
    @Provides
    @Singleton
    fun provideDataChangeListener(@ApplicationContext context: Context): DataChangeListener =
        DataChangeListener { WidgetRenderer.updateAll(context) }
}
