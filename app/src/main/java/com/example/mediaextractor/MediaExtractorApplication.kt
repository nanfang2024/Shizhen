package com.example.mediaextractor

import android.app.Application
import android.os.Build
import android.os.Process
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.mediaextractor.data.database.MediaExtractorDatabase
import com.example.mediaextractor.data.converter.FfmpegMediaConverter
import com.example.mediaextractor.data.download.DownloadRepository
import com.example.mediaextractor.data.download.WorkManagerDownloadRepository
import com.example.mediaextractor.data.repository.HistoryRepository
import com.example.mediaextractor.data.repository.RoomHistoryRepository
import com.example.mediaextractor.domain.parser.GenericMediaParser
import com.example.mediaextractor.domain.parser.DouyinStructuredMediaParser
import com.example.mediaextractor.domain.parser.DoubaoPublicMediaParser
import com.example.mediaextractor.domain.parser.KuaishouStructuredMediaParser
import com.example.mediaextractor.domain.parser.InternationalPublicPageMediaParser
import com.example.mediaextractor.domain.parser.NeteaseMusicMediaParser
import com.example.mediaextractor.domain.parser.ParserRegistry
import com.example.mediaextractor.domain.parser.PublicMusicPageMediaParser
import com.example.mediaextractor.domain.parser.XiaohongshuStructuredMediaParser
import com.example.mediaextractor.domain.parser.YtDlpMediaParser
import com.example.mediaextractor.domain.parser.XStructuredMediaParser
import com.example.mediaextractor.domain.converter.MediaConverter
import com.example.mediaextractor.util.DiagnosticLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MediaExtractorApplication : Application(), ImageLoaderFactory {
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

    val database: MediaExtractorDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            MediaExtractorDatabase::class.java,
            "media_extractor.db",
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

    val parserRegistry: ParserRegistry by lazy {
        ParserRegistry(
            listOf(
                DoubaoPublicMediaParser(httpClient),
                DouyinStructuredMediaParser(httpClient),
                XiaohongshuStructuredMediaParser(httpClient),
                KuaishouStructuredMediaParser(httpClient),
                NeteaseMusicMediaParser(httpClient),
                YtDlpMediaParser(this, httpClient),
                InternationalPublicPageMediaParser(httpClient),
                PublicMusicPageMediaParser(httpClient),
                XStructuredMediaParser(httpClient),
                GenericMediaParser(httpClient),
            ),
        )
    }
}
