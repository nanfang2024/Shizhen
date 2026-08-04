package com.example.mediaextractor.ui.navigation

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Transform
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mediaextractor.R
import com.example.mediaextractor.ui.converter.ConverterScreen
import com.example.mediaextractor.ui.converter.ConverterViewModel
import com.example.mediaextractor.ui.extractor.ExtractorScreen
import com.example.mediaextractor.ui.extractor.ExtractorViewModel
import com.example.mediaextractor.ui.history.HistoryScreen
import com.example.mediaextractor.ui.history.HistoryViewModel
import com.example.mediaextractor.ui.theme.autumnColors
import kotlinx.coroutines.flow.StateFlow

private enum class AppDestination(
    val labelRes: Int,
    val icon: ImageVector,
) {
    EXTRACTOR(R.string.nav_extractor, Icons.Outlined.Link),
    CONVERTER(R.string.nav_converter, Icons.Outlined.Transform),
    HISTORY(R.string.nav_history, Icons.Outlined.History),
}

@Composable
fun MediaExtractorApp(sharedText: StateFlow<String?>) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.EXTRACTOR) }
    val extractorViewModel: ExtractorViewModel = viewModel()
    val converterViewModel: ConverterViewModel = viewModel()
    val historyViewModel: HistoryViewModel = viewModel()

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
                            indicatorColor = MaterialTheme.autumnColors.selectedContainer,
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
            AppDestination.CONVERTER -> ConverterScreen(converterViewModel, Modifier.padding(padding))
            AppDestination.HISTORY -> HistoryScreen(historyViewModel, Modifier.padding(padding))
        }
    }
}
