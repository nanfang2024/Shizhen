package com.framepick.app

import android.app.Application
import android.os.Build
import android.os.Process
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.framepick.app.data.database.FramePickDatabase
import com.framepick.app.data.converter.FfmpegMediaConverter
import com.framepick.app.data.download.DownloadRepository
import com.framepick.app.data.download.WorkManagerDownloadRepository
import com.framepick.app.data.repository.HistoryRepository
import com.framepick.app.data.repository.RoomHistoryRepository
import com.framepick.app.domain.parser.GenericMediaParser
import com.framepick.app.domain.parser.DouyinStructuredMediaParser
import com.framepick.app.domain.parser.DoubaoPublicMediaParser
import com.framepick.app.domain.parser.BilibiliStructuredMediaParser
import com.framepick.app.domain.parser.KuaishouStructuredMediaParser
import com.framepick.app.domain.parser.PipixiaStructuredMediaParser
import com.framepick.app.domain.parser.InternationalPublicPageMediaParser
import com.framepick.app.domain.parser.NeteaseMusicMediaParser
import com.framepick.app.domain.parser.ParserRegistry
import com.framepick.app.domain.parser.PublicMusicPageMediaParser
import com.framepick.app.domain.parser.XiaohongshuStructuredMediaParser
import com.framepick.app.domain.parser.YouTubeNewPipeMediaParser
import com.framepick.app.domain.parser.YtDlpMediaParser
import com.framepick.app.domain.parser.XStructuredMediaParser
import com.framepick.app.domain.converter.MediaConverter
import com.framepick.app.util.DiagnosticLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class FramePickApplication : Application(), ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.initialize(this)
        installCrashDiagnostics()
        applicationScope.launch {
            historyRepository.markInterruptedConversions()
        }
    }

    private fun installCrashDiagnostics() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            DiagnosticLogger.error(
                category = "CRASH",
                event = "uncaught_exception",
                failure = failure,
                details = mapOf("thread" to thread.name),
            )
            if (previous != null) {
                previous.uncaughtException(thread, failure)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .crossfade(true)
        .build()

    val database: FramePickDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            FramePickDatabase::class.java,
            "framepick.db",
        ).build()
    }

    val historyRepository: HistoryRepository by lazy {
        RoomHistoryRepository(database.historyDao())
    }

    val downloadRepository: DownloadRepository by lazy {
        WorkManagerDownloadRepository(this, historyRepository)
    }

    val mediaConverter: MediaConverter by lazy {
        FfmpegMediaConverter(this)
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** callTimeout covers the whole body transfer; large video downloads must not reuse the 45s parser client. */
    val downloadHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    val parserRegistry: ParserRegistry by lazy {
        ParserRegistry(
            listOf(
                DoubaoPublicMediaParser(httpClient),
                DouyinStructuredMediaParser(httpClient),
                XiaohongshuStructuredMediaParser(httpClient),
                KuaishouStructuredMediaParser(httpClient),
                PipixiaStructuredMediaParser(httpClient),
                BilibiliStructuredMediaParser(httpClient),
                NeteaseMusicMediaParser(httpClient),
                YouTubeNewPipeMediaParser(httpClient),
                YtDlpMediaParser(this, httpClient),
                InternationalPublicPageMediaParser(httpClient),
                PublicMusicPageMediaParser(httpClient),
                XStructuredMediaParser(httpClient),
                GenericMediaParser(httpClient),
            ),
        )
    }
}
