package com.iltan.settle.ui.viewer

import android.content.Intent
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.iltan.settle.R
import com.iltan.settle.data.contentUri
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.thumbnail
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.grid.SPECIAL_FORMAT_MOTION_PHOTO
import com.iltan.settle.ui.theme.SettleTheme
import java.util.Date
import java.util.Locale
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Full-screen pager over the list the user opened an item from. Always dark, like the photos it
 * frames, so Media3's controls and the info sheet read well on black.
 */
@Composable
fun ViewerScreen(viewModel: ViewerViewModel, initialId: String, onBack: () -> Unit) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val list = entries
    SettleTheme(darkTheme = true) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            when {
                list == null -> Box(Modifier.fillMaxSize().background(Color.Black))
                list.isEmpty() -> LaunchedEffect(Unit) { onBack() }
                else -> Viewer(list, initialId, viewModel, onBack)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Viewer(
    entries: List<MediaEntry>,
    initialId: String,
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val currentEntries by rememberUpdatedState(entries)
    val pagerState =
        rememberPagerState(
            initialPage = remember { entries.indexOfFirst { it.id == initialId }.coerceAtLeast(0) }
        ) {
            currentEntries.size
        }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var playingMotion by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val current = entries.getOrNull(pagerState.currentPage) ?: return

    LaunchedEffect(pagerState.settledPage) { playingMotion = false }
    ImmersiveSystemBars(hidden = !chromeVisible)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            key = { currentEntries[it].id },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val entry = entries[page]
            val settled = pagerState.settledPage == page
            when (entry.kind) {
                MediaKind.VIDEO ->
                    VideoPage(
                        entry,
                        settled,
                        chromeVisible,
                        onTap = { chromeVisible = !chromeVisible },
                    )
                MediaKind.IMAGE ->
                    PhotoPage(
                        entry,
                        settled,
                        playingMotion = playingMotion && settled,
                        onTap = { chromeVisible = !chromeVisible },
                        onMotionEnded = { playingMotion = false },
                    )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                entry = current,
                onBack = onBack,
                onFavorite = { viewModel.toggleFavorite(current) },
                onShare = { share(context, current) },
                onInfo = { showInfo = true },
            )
        }

        if (current.specialFormat == SPECIAL_FORMAT_MOTION_PHOTO) {
            AnimatedVisibility(
                visible = chromeVisible && !playingMotion,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                FilledTonalButton(
                    onClick = { playingMotion = true },
                    modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_motion_photo),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.viewer_play_motion),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) { InfoSheet(viewModel, current) }
    }
}

@Composable
private fun PhotoPage(
    entry: MediaEntry,
    settled: Boolean,
    playingMotion: Boolean,
    onTap: () -> Unit,
    onMotionEnded: () -> Unit,
) {
    val context = LocalContext.current
    val zoomState = rememberZoomableImageState(rememberZoomableState())
    // A page kept alive next to the current one shouldn't stay zoomed in.
    LaunchedEffect(settled) { if (!settled) zoomState.zoomableState.resetZoom() }
    Box(Modifier.fillMaxSize()) {
        ZoomableAsyncImage(
            model =
                remember(entry.id, entry.dateModifiedS) {
                    ImageRequest.Builder(context)
                        .data(entry.contentUri)
                        // Show the grid's thumbnail while the full image loads.
                        .placeholderMemoryCacheKey(entry.thumbnail.cacheKey)
                        .build()
                },
            contentDescription = null,
            state = zoomState,
            modifier = Modifier.fillMaxSize(),
            onClick = { onTap() },
        )
        if (playingMotion) {
            MediaPlayer(
                uri = entry.contentUri,
                mimeType = entry.mimeType,
                showControls = false,
                transparentUntilFirstFrame = true,
                onEnded = onMotionEnded,
                modifier = Modifier.clickable(onClick = onMotionEnded),
            )
        }
    }
}

@Composable
private fun VideoPage(
    entry: MediaEntry,
    settled: Boolean,
    chromeVisible: Boolean,
    onTap: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (settled) {
            MediaPlayer(entry.contentUri, entry.mimeType, showControls = chromeVisible)
        } else {
            AsyncImage(
                model = entry.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TopBar(
    entry: MediaEntry,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(R.drawable.ic_arrow_back, R.string.navigate_back, onBack)
        Text(
            formatDateTime(entry.takenAtMs),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        BarButton(
            if (entry.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite,
            if (entry.isFavorite) R.string.viewer_unfavorite else R.string.viewer_favorite,
            onFavorite,
        )
        BarButton(R.drawable.ic_share, R.string.viewer_share, onShare)
        BarButton(R.drawable.ic_info, R.string.viewer_info, onInfo)
    }
}

@Composable
private fun BarButton(icon: Int, description: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), stringResource(description), tint = Color.White)
    }
}

@Composable
private fun InfoSheet(viewModel: ViewerViewModel, entry: MediaEntry) {
    val context = LocalContext.current
    val info by produceState<ItemInfo?>(null, entry.id) { value = viewModel.info(entry) }
    val details = info?.details ?: return
    Column(Modifier.padding(bottom = 24.dp)) {
        ListItem(
            content = { Text(details.displayName) },
            supportingContent = { Text(formatDateTime(details.takenAtMs)) },
        )
        ListItem(
            content = { Text(Formatter.formatShortFileSize(context, details.sizeBytes)) },
            supportingContent = {
                Text(
                    stringResource(R.string.viewer_dimensions, details.width, details.height) +
                        " · " +
                        details.mimeType
                )
            },
        )
        ListItem(
            content = { Text(details.relativePath.trimEnd('/')) },
            supportingContent = { Text(stringResource(R.string.viewer_folder)) },
        )
        info?.location?.let { (lat, lon) ->
            ListItem(
                onClick = {
                    // No maps app installed is not worth a crash.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, "geo:0,0?q=$lat,$lon".toUri())
                        )
                    }
                },
                leadingContent = { Icon(painterResource(R.drawable.ic_location), null) },
                content = { Text("%.5f, %.5f".format(Locale.ROOT, lat, lon)) },
                supportingContent = { Text(stringResource(R.string.viewer_open_map)) },
            )
        }
    }
}

/** Hides the status and navigation bars while the viewer's chrome is hidden. */
@Composable
private fun ImmersiveSystemBars(hidden: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    DisposableEffect(controller) {
        // White icons on the black viewer; restored for the app's own theme afterwards.
        val lightBars = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightBars
            controller.isAppearanceLightNavigationBars = lightBars
        }
    }
    LaunchedEffect(hidden) {
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun formatDateTime(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "yMMMMdjmm") }
    return remember(millis, pattern) {
        java.text.SimpleDateFormat(pattern, locale).format(Date(millis))
    }
}

private fun share(context: android.content.Context, entry: MediaEntry) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(entry.mimeType)
            .putExtra(Intent.EXTRA_STREAM, entry.contentUri)
            // Granted explicitly: Android 18 stops adding this grant for ACTION_SEND by itself.
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
