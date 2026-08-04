package com.example.mediaextractor

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.mediaextractor.ui.brand.BrandSplashScreen
import com.example.mediaextractor.ui.navigation.MediaExtractorApp
import com.example.mediaextractor.ui.theme.MediaExtractorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val sharedText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        receiveSharedText(intent)
        val showBrandOnThisLaunch = savedInstanceState == null
        setContent {
            MediaExtractorTheme {
                var showBrand by remember { mutableStateOf(showBrandOnThisLaunch) }
                LaunchedEffect(showBrand) {
                    if (showBrand) {
                        delay(BRAND_SPLASH_DURATION_MS)
                        showBrand = false
                    }
                }
                if (showBrand) {
                    BrandSplashScreen()
                } else {
                    MediaExtractorApp(sharedText)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedText(intent)
    }

    private fun receiveSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        sharedText.value = text
    }

    private companion object {
        const val BRAND_SPLASH_DURATION_MS = 900L
    }
}
