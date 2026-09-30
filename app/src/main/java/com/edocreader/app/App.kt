package com.edocreader.app

import android.app.Application
import android.util.Log
import com.edocreader.app.data.ExportManager
import com.edocreader.app.data.RecordRepository
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用级单例容器。数据量小、生命周期与应用一致，无需引入 DI 框架。
 *
 * 同时安装一个全局未捕获异常处理器：把崩溃堆栈写到应用私有目录下的
 * `crash.log`，便于用户在无法连接调试器时把现场反馈回来。
 */
class App : Application() {

    val repository: RecordRepository by lazy { RecordRepository(this) }
    val exportManager: ExportManager by lazy { ExportManager(this, repository) }

    companion object {
        lateinit var instance: App
            private set

        private const val TAG = "App"
        const val CRASH_LOG_NAME = "crash.log"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
    }

    /** 崩溃日志文件（应用私有目录，卸载即清除）。 */
    fun crashLogFile(): File = File(filesDir, CRASH_LOG_NAME)

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(thread, throwable) }
            // 交回系统默认处理，保证应用仍会被正常终止并弹出系统崩溃提示
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val text = buildString {
            append("时间：$stamp\n")
            append("线程：${thread.name}\n")
            append("异常：${throwable.javaClass.name}: ${throwable.message}\n")
            append("----- 堆栈 -----\n")
            append(sw.toString())
            append("\n")
        }
        // 追加写入，保留多次崩溃现场；单文件上限 256 KB
        val file = crashLogFile()
        runCatching {
            if (file.exists() && file.length() > 256 * 1024) file.delete()
            file.appendText(text)
        }
        Log.e(TAG, "已写入崩溃日志：${file.absolutePath}", throwable)
    }
}
