package com.edocreader.app

import android.app.Application
import com.edocreader.app.data.ExportManager
import com.edocreader.app.data.RecordRepository

/**
 * 应用级单例容器。数据量小、生命周期与应用一致，无需引入 DI 框架。
 */
class App : Application() {

    val repository: RecordRepository by lazy { RecordRepository(this) }
    val exportManager: ExportManager by lazy { ExportManager(this, repository) }

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
