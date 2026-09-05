package com.framepick.app.ui.navigation

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framepick.app.R
import com.framepick.app.data.preferences.DisclaimerStore
import com.framepick.app.ui.components.DisclaimerDialog
import com.framepick.app.ui.converter.ConverterViewModel
import com.framepick.app.ui.downloads.DownloadsScreen
import com.framepick.app.ui.downloads.DownloadsViewModel
import com.framepick.app.ui.extractor.ExtractorScreen
import com.framepick.app.ui.extractor.ExtractorViewModel
import com.framepick.app.ui.settings.SettingsScreen
import com.framepick.app.ui.settings.SettingsViewModel
import com.framepick.app.ui.theme.extendedColors
import kotlinx.coroutines.flow.StateFlow

private enum class AppDestination(
    val labelRes: Int,
    val icon: ImageVector,
) {
    EXTRACTOR(R.string.nav_extractor, Icons.Outlined.Link),
    DOWNLOADS(R.string.nav_downloads, Icons.Outlined.Download),
    SETTINGS(R.string.nav_settings, Icons.Outlined.Settings),
}

@Composable
fun MediaExtractorApp(sharedText: StateFlow<String?>) {
    val context = LocalContext.current
    var disclaimerAccepted by rememberSaveable {
        mutableStateOf(!DisclaimerStore.shouldShowDisclaimer(DisclaimerStore.acceptedVersion(context)))
    }

    if (!disclaimerAccepted) {
        DisclaimerDialog(onAccept = { disclaimerAccepted = true })
        return
    }

    var destination by rememberSaveable { mutableStateOf(AppDestination.EXTRACTOR) }
    val extractorViewModel: ExtractorViewModel = viewModel()
    val converterViewModel: ConverterViewModel = viewModel()
    val downloadsViewModel: DownloadsViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

    val incomingText by sharedText.collectAsStateWithLifecycle()
    LaunchedEffect(incomingText) {
        incomingText?.let {
            destination = AppDestination.EXTRACTOR
            extractorViewModel.acceptSharedText(it)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == destination,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                item.icon,
                                contentDescription = stringResource(item.labelRes),
                            )
                        },
                        label = {
                            Text(
                                stringResource(item.labelRes),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.extendedColors.selectedContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        when (destination) {
            AppDestination.EXTRACTOR -> ExtractorScreen(extractorViewModel, Modifier.padding(padding))
            AppDestination.DOWNLOADS -> DownloadsScreen(downloadsViewModel, Modifier.padding(padding))
            AppDestination.SETTINGS -> SettingsScreen(
                viewModel = settingsViewModel,
                converterViewModel = converterViewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
