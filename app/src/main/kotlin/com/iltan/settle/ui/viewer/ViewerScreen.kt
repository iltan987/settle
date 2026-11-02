package com.iltan.settle.ui.viewer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.iltan.settle.R
import com.iltan.settle.data.backup.BackupOutcome
import com.iltan.settle.data.contentUri
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.db.displayAspectRatio
import com.iltan.settle.data.thumbnail
import com.iltan.settle.domain.backup.BackupResult
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.AppMessages
import com.iltan.settle.ui.DayTitles
import com.iltan.settle.ui.LocalAppMessages
import com.iltan.settle.ui.SnackbarAbove
import com.iltan.settle.ui.folders.folderNames
import com.iltan.settle.ui.grid.SPECIAL_FORMAT_MOTION_PHOTO
import com.iltan.settle.ui.mediaInFlight
import com.iltan.settle.ui.sharedMedia
import com.iltan.settle.ui.theme.SettleMotion
import com.iltan.settle.ui.theme.settleColors
import com.iltan.settle.ui.trash.DeleteRequest
import com.iltan.settle.ui.trash.LocalItemActions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.DynamicZoomSpec
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/** Where the viewer's actions lead outside it. */
class ViewerHandlers(
    val openSettings: () -> Unit = {},
    /** Reconnects Google in Settings. */
    val signIn: () -> Unit = {},
    val manageStorage: () -> Unit = {},
    /** Opens the viewer again on an item that Undo brought back after the viewer closed (E4). */
    val reopen: (itemId: String) -> Unit = {},
)

/**
 * The viewer (D): a black stage in both themes, paging through the list the user opened an item
 * from. A tap shows or hides the chrome, a swipe down closes it back into the grid cell, and a
 * swipe up opens Details.
 */
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    initialId: String,
    onBack: () -> Unit,
    handlers: ViewerHandlers = ViewerHandlers(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val list = entries
    val life = remember { ViewerLife() }
    DisposableEffect(life) { onDispose { life.alive = false } }
    Box(Modifier.fillMaxSize().background(MaterialTheme.settleColors.stage)) {
        when {
            list == null -> {}
            list.isEmpty() ->
                LaunchedEffect(Unit) {
                    // The last item was deleted: Undo will bring the viewer back (E4).
                    life.closedEmpty = true
                    onBack()
                }
            else -> Viewer(list, initialId, viewModel, onBack, handlers, life)
        }
    }
}

/** Whether the viewer is still on screen, for an Undo that comes after it may have closed. */
private class ViewerLife {
    var alive = true
    var closedEmpty = false
}

@Composable
private fun Viewer(
    entries: List<MediaEntry>,
    initialId: String,
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    handlers: ViewerHandlers,
    life: ViewerLife,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val messages = LocalAppMessages.current
    val actions = LocalItemActions.current
    val scope = rememberCoroutineScope()

    // The list on screen. It lags behind [entries] while a deleted item leaves (E3).
    var shown by remember { mutableStateOf(entries) }
    val leaving = remember { Animatable(0f) }
    var leavingId by remember { mutableStateOf<String?>(null) }
    val currentShown by rememberUpdatedState(shown)
    val pagerState =
        rememberPagerState(
            initialPage = remember { entries.indexOfFirst { it.id == initialId }.coerceAtLeast(0) }
        ) {
            currentShown.size
        }
    LaunchedEffect(entries) {
        val onScreen = shown.getOrNull(pagerState.currentPage)?.id
        if (onScreen != null && entries.none { it.id == onScreen }) {
            // It shrinks and fades; then the next one takes its place.
            leavingId = onScreen
            leaving.animateTo(1f, SettleMotion.leave())
        }
        shown = entries
        leavingId = null
        leaving.snapTo(0f)
    }
    // An item Undo brought back, to show again once it is in the list.
    var focus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focus, shown) {
        val index = shown.indexOfFirst { it.id == focus }
        if (index >= 0) {
            pagerState.scrollToPage(index)
            focus = null
        }
    }

    val current = shown.getOrNull(pagerState.currentPage) ?: return
    val settled = shown.getOrNull(pagerState.settledPage) ?: current
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var playingMotion by remember { mutableStateOf(false) }
    var videoPlaying by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    // Open with true to land on the backup section, as the status chip does.
    var details by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(pagerState.settledPage) {
        playingMotion = false
        videoPlaying = false
        zoom = 1f
    }
    // Chrome never hides by itself on photos; on a playing video it does after 3 s (D2).
    LaunchedEffect(videoPlaying, chromeVisible) {
        if (videoPlaying && chromeVisible) {
            delay(VIDEO_CHROME_MILLIS)
            chromeVisible = false
        }
    }

    val cloud by
        remember(current.id) { viewModel.cloudStatus(current.id) }.collectAsStateWithLifecycle(null)
    val facts by
        remember(settled.id, locale) { viewModel.facts(settled, locale) }
            .collectAsStateWithLifecycle(null)
    val currentFacts = facts?.takeIf { it.details?.id == current.id }
    val chargingOnly by viewModel.chargingOnly.collectAsStateWithLifecycle()
    val folder = folderName(currentFacts)
    val chip = cloud?.let { viewerChip(it, folder, chargingOnly, System.currentTimeMillis()) }

    val askLocation =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.backUp(current)
        }
    fun act(action: ChipAction) {
        when (action) {
            ChipAction.BACK_UP,
            ChipAction.TRY_AGAIN -> viewModel.backUp(current)
            ChipAction.BACK_UP_FOLDER -> viewModel.backUpFolder(current)
            ChipAction.DETAILS -> details = true
            ChipAction.CONNECT -> handlers.openSettings()
            ChipAction.SIGN_IN -> handlers.signIn()
            ChipAction.MANAGE -> handlers.manageStorage()
            ChipAction.RESUME -> viewModel.resume()
            ChipAction.ALLOW -> askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    fun delete(entry: MediaEntry) {
        actions.delete(
            DeleteRequest(
                listOf(entry),
                onRestored = {
                    when {
                        life.alive -> focus = entry.id
                        life.closedEmpty -> handlers.reopen(entry.id)
                    }
                },
            )
        )
    }
    BackupMessages(viewModel, messages, handlers.openSettings) {
        askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    ImmersiveSystemBars(hidden = !chromeVisible)
    // The snackbar takes the status chip's place, 12 dp above the toolbar (E3).
    SnackbarAbove(TOOLBAR_BOTTOM + TOOLBAR_HEIGHT + 12.dp)
    val snackbarShown = messages.host.currentSnackbarData != null
    val drag = remember { Animatable(0f) }
    val dragging = drag.value != 0f
    val chromeAlpha by
        animateFloatAsState(
            if (chromeVisible && !dragging) 1f else 0f,
            SettleMotion.fade(),
            label = "chrome",
        )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        HorizontalPager(
            state = pagerState,
            key = { currentShown[it].id },
            beyondViewportPageCount = 1,
            pageSpacing = 8.dp,
            flingBehavior =
                PagerDefaults.flingBehavior(pagerState, snapAnimationSpec = SettleMotion.settle()),
            modifier =
                Modifier.fillMaxSize()
                    .verticalSwipes(
                        enabled = zoom <= FIT_ZOOM,
                        offset = drag,
                        scope = scope,
                        dismissDistance = height * DISMISS_FRACTION,
                        onDismiss = onBack,
                        onDetails = { details = false },
                    ),
        ) { page ->
            val entry = shown[page]
            val isCurrent = page == pagerState.currentPage
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    if (entry.id == leavingId) {
                        val scale = 1f - LEAVE_SHRINK * leaving.value
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - leaving.value
                    }
                    if (isCurrent && drag.value != 0f) {
                        // Down follows the finger and shrinks; up resists, then opens Details.
                        val down = drag.value.coerceAtLeast(0f)
                        translationY = if (drag.value > 0f) down else drag.value * UP_RESISTANCE
                        val scale = 1f - DRAG_SHRINK * (down / height).coerceIn(0f, 1f)
                        scaleX = scale
                        scaleY = scale
                    }
                }
            ) {
                // Only the item on screen flies to or from its grid cell.
                val shared = page == pagerState.settledPage
                when (entry.kind) {
                    MediaKind.VIDEO ->
                        VideoPage(
                            entry,
                            settled = shared,
                            shared = shared,
                            chromeVisible = chromeVisible,
                            onTap = { chromeVisible = !chromeVisible },
                            onPlayingChanged = { videoPlaying = it },
                        )
                    MediaKind.IMAGE ->
                        PhotoPage(
                            entry,
                            settled = shared,
                            shared = shared,
                            playingMotion = playingMotion && shared,
                            onTap = { chromeVisible = !chromeVisible },
                            onZoom = { if (shared) zoom = it },
                            onMotionEnded = { playingMotion = false },
                            motionVideoOffset = viewModel::motionVideoOffset,
                        )
                }
            }
        }

        if (chromeAlpha > 0f) {
            ViewerTopBar(
                title = dayTitle(current.takenAtMs),
                subtitle = subtitle(current.takenAtMs, currentFacts, folder),
                onBack = onBack,
                menu = viewerMenu(context, current, cloud),
                modifier = Modifier.align(Alignment.TopCenter).alpha(chromeAlpha),
            )
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = TOOLBAR_BOTTOM)
                    .alpha(chromeAlpha),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A playing video's own bar sits here instead; the status moves into Details.
                val video = current.kind == MediaKind.VIDEO
                val motion = current.specialFormat == SPECIAL_FORMAT_MOTION_PHOTO && !video
                if (!video && !snackbarShown) {
                    FlowRow(
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        if (motion && !playingMotion) PlayMotionPill({ playingMotion = true })
                        chip?.let {
                            ViewerStatusChip(
                                it,
                                onClick = { details = true },
                                onAction = ::act,
                            )
                        }
                    }
                }
                ViewerToolbar(
                    favorite = current.isFavorite,
                    onShare = { actions.share(listOf(current)) },
                    onFavorite = { viewModel.toggleFavorite(current) },
                    onDelete = { delete(current) },
                    onDetails = { details = false },
                )
            }
        }

        var pill by remember { mutableStateOf(false) }
        LaunchedEffect(zoom) {
            if (zoom == 1f) {
                pill = false
                return@LaunchedEffect
            }
            pill = true
            delay(ZOOM_PILL_MILLIS)
            pill = false
        }
        AnimatedVisibility(
            visible = pill,
            enter = fadeIn(SettleMotion.fade()),
            exit = fadeOut(SettleMotion.fade()),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp),
        ) {
            ZoomPill(zoom)
        }
    }

    details?.let { toBackup ->
        val upload by
            remember(current.id) { viewModel.upload(current.id) }.collectAsStateWithLifecycle(null)
        DetailsSheet(
            DetailsContent(current, currentFacts, cloud, chip, upload, folder),
            toBackup = toBackup,
            onDismiss = { details = null },
            onOpenMap = { latitude, longitude, label ->
                openMap(context, latitude, longitude, label)
            },
            onOpenInDrive = { openInDrive(context, it) },
            onAction = ::act,
        )
    }
}

/**
 * Swipes that start vertically on the stage at fit: down past [dismissDistance] (or flung) calls
 * [onDismiss], up calls [onDetails]; otherwise it springs back. A zoomed photo pans instead, and
 * pinches and sideways swipes are left to the photo and the pager, which see touches first.
 */
private fun Modifier.verticalSwipes(
    enabled: Boolean,
    offset: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    dismissDistance: Float,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
): Modifier =
    if (!enabled) this
    else
        pointerInput(dismissDistance) {
            val velocity = VelocityTracker()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start =
                    awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                velocity.resetTracking()
                velocity.addPointerInputChange(start)
                scope.launch { offset.snapTo(offset.value + start.positionChange().y) }
                verticalDrag(start.id) { change ->
                    velocity.addPointerInputChange(change)
                    val dy = change.positionChange().y
                    scope.launch { offset.snapTo(offset.value + dy) }
                    change.consume()
                }
                val speed = velocity.calculateVelocity().y
                val flung = FLING_DP_PER_S * density
                when {
                    offset.value > dismissDistance || (speed > flung && offset.value > 0f) ->
                        onDismiss()
                    offset.value < -DETAILS_DP * density ||
                        (speed < -flung && offset.value < 0f) -> {
                        onDetails()
                        scope.launch { offset.animateTo(0f, SettleMotion.settle()) }
                    }
                    else -> scope.launch { offset.animateTo(0f, SettleMotion.settle()) }
                }
            }
        }

@Composable
private fun PhotoPage(
    entry: MediaEntry,
    settled: Boolean,
    shared: Boolean,
    playingMotion: Boolean,
    onTap: () -> Unit,
    onZoom: (Float) -> Unit,
    onMotionEnded: () -> Unit,
    motionVideoOffset: suspend (MediaEntry) -> Long?,
) {
    val context = LocalContext.current
    val zoomState = rememberZoomableImageState(rememberZoomableState(zoomSpec = UpToEightTimesFit))
    // A page kept alive next to the current one shouldn't stay zoomed in.
    LaunchedEffect(settled) { if (!settled) zoomState.zoomableState.resetZoom() }
    val currentOnZoom by rememberUpdatedState(onZoom)
    LaunchedEffect(zoomState) {
        snapshotFlow {
            zoomState.zoomableState.contentTransformation
                .takeIf { it.isSpecified }
                ?.scaleMetadata
                ?.userZoom ?: 1f
        }
            .collect { currentOnZoom(it) }
    }
    val model =
        remember(entry.id, entry.dateModifiedS) {
            ImageRequest.Builder(context)
                .data(entry.contentUri)
                // Show the grid's thumbnail while the full image loads.
                .placeholderMemoryCacheKey(entry.thumbnail.cacheKey)
                .build()
        }
    val inFlight = shared && mediaInFlight()
    Box(Modifier.fillMaxSize()) {
        ZoomableAsyncImage(
            model = model,
            contentDescription = null,
            state = zoomState,
            modifier = Modifier.fillMaxSize().alpha(if (inFlight) 0f else 1f),
            onClick = { onTap() },
            onDoubleClick = FitOrTwoAndAHalf,
        )
        if (shared) FlyingImage(entry, model, inFlight)
        if (playingMotion) {
            // Finding the clip reads only the XMP and a few bytes, so it happens on tap; the
            // still stays visible until the clip's first frame.
            val clip by
                produceState<MotionClip?>(null, entry.id) {
                    value = MotionClip(motionVideoOffset(entry))
                }
            clip?.let {
                MediaPlayer(
                    uri = entry.contentUri,
                    mimeType = entry.mimeType,
                    showControls = false,
                    motionPhoto = true,
                    videoOffset = it.offset,
                    onEnded = onMotionEnded,
                    modifier = Modifier.clickable(onClick = onMotionEnded),
                )
            }
        }
    }
}

@Composable
private fun VideoPage(
    entry: MediaEntry,
    settled: Boolean,
    shared: Boolean,
    chromeVisible: Boolean,
    onTap: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
) {
    val inFlight = shared && mediaInFlight()
    Box(
        Modifier.fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (settled && !inFlight) {
            MediaPlayer(
                entry.contentUri,
                entry.mimeType,
                showControls = chromeVisible,
                controlsBottom = TOOLBAR_BOTTOM + TOOLBAR_HEIGHT + 12.dp,
                onPlayingChanged = onPlayingChanged,
            )
        } else if (!shared) {
            AsyncImage(
                model = entry.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (shared) FlyingImage(entry, entry.thumbnail, inFlight)
    }
}

/**
 * The photo as it flies between its grid cell and the stage: a box the shape of the photo, fitted
 * to the screen, that the grid's square cell grows into (and shrinks back to). It is drawn only in
 * flight; then the zoomable photo or the player takes over.
 */
@Composable
private fun FlyingImage(entry: MediaEntry, model: Any, inFlight: Boolean) {
    val ratio = entry.displayAspectRatio ?: return
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val screen = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
        Box(
            Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < screen)
                .sharedMedia(entry.id)
        ) {
            if (inFlight) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Short messages about how backups ended, with a way forward where there is one. */
@Composable
private fun BackupMessages(
    viewModel: ViewerViewModel,
    messages: AppMessages,
    onOpenSettings: () -> Unit,
    onAskOriginalAccess: () -> Unit,
) {
    val resources = LocalResources.current
    val openSettings by rememberUpdatedState(onOpenSettings)
    val askAccess by rememberUpdatedState(onAskOriginalAccess)
    LaunchedEffect(viewModel) {
        viewModel.outcomes.collect { outcome ->
            val (message, action) = outcome.message()
            messages.show(resources.getString(message), action?.let(resources::getString)) {
                when (action) {
                    R.string.backup_allow -> askAccess()
                    else -> openSettings()
                }
            }
        }
    }
}

/** The message and optional action label for a backup outcome. */
private fun BackupOutcome.message(): Pair<Int, Int?> =
    when (this) {
        is BackupOutcome.NeedsOriginalAccess ->
            R.string.backup_needs_location to R.string.backup_allow
        is BackupOutcome.Deferred -> R.string.backup_deferred to null
        is BackupOutcome.Done ->
            when (val result = result) {
                is BackupResult.Verified -> R.string.backup_done to null
                is BackupResult.AwaitingChecksum -> R.string.backup_checking to null
                is BackupResult.Stopped ->
                    result.problem.message to
                        R.string.settings_title.takeIf {
                            result.problem == UploadProblem.AUTH_NEEDED
                        }
            }
    }

private val UploadProblem.message: Int
    get() =
        when (this) {
            UploadProblem.AUTH_NEEDED -> R.string.backup_problem_auth
            UploadProblem.QUOTA_FULL -> R.string.backup_problem_quota
            UploadProblem.OFFLINE -> R.string.backup_problem_offline
            UploadProblem.ORIGINAL_UNAVAILABLE -> R.string.backup_problem_original
            UploadProblem.CHANGED_DURING_UPLOAD -> R.string.backup_problem_changed
            UploadProblem.CHECKSUM_MISMATCH -> R.string.backup_problem_mismatch
            UploadProblem.FAILED -> R.string.backup_problem_failed
        }

/** "Thursday, 29 October", "Yesterday". */
@Composable
private fun dayTitle(millis: Long): String {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val titles = remember(resources, locale) { DayTitles(resources, locale) }
    val zone = ZoneId.systemDefault()
    return titles.of(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate(), LocalDate.now(zone))
}

/** "18:42 · Kaçkar Mountains", or the folder until the place is known or if there is none. */
@Composable
private fun subtitle(millis: Long, facts: ItemFacts?, folder: String): String {
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(locale) { DayTitles.pattern(locale, "jm") }
    val where = facts?.place?.name ?: folder.takeIf { facts?.details != null }
    return listOfNotNull(
            time.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())),
            where,
        )
        .joinToString(" · ")
}

@Composable
private fun folderName(facts: ItemFacts?): String {
    val path = facts?.details?.relativePath ?: return ""
    val root = stringResource(R.string.folder_root)
    return folderNames(listOf(path), root).getValue(path)
}

/** The overflow menu: Use as, and Open in Google Drive once backed up. */
private fun viewerMenu(
    context: Context,
    entry: MediaEntry,
    cloud: CloudStatus?,
): List<ViewerMenuItem> = buildList {
    if (entry.kind == MediaKind.IMAGE) {
        add(ViewerMenuItem(R.string.viewer_use_as) { useAs(context, entry) })
    }
    (cloud as? CloudStatus.BackedUp)?.let { backedUp ->
        add(
            ViewerMenuItem(R.string.backup_open_in_drive) {
                openInDrive(context, backedUp.driveFileId)
            }
        )
    }
}

/** Hides the status and navigation bars while the viewer's chrome is hidden. */
@Composable
private fun ImmersiveSystemBars(hidden: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    DisposableEffect(controller) {
        // White icons on the black stage; restored for the app's own theme afterwards.
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

/** Opens a maps app at the coordinates, labelled with the place when known. */
private fun openMap(context: Context, latitude: Double, longitude: Double, label: String?) {
    val query = "$latitude,$longitude" + (label?.let { "(${Uri.encode(it)})" } ?: "")
    // No maps app installed is not worth a crash.
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "geo:0,0?q=$query".toUri())) }
}

/** Opens a Drive file in the Drive app, or the browser without it. */
internal fun openInDrive(context: Context, driveFileId: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, "https://drive.google.com/file/d/$driveFileId/view".toUri())
        )
    }
}

/** "Use as": wallpaper, contact photo, and whatever else the phone offers. */
private fun useAs(context: Context, entry: MediaEntry) {
    val attach =
        Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(entry.contentUri, entry.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(attach, null)) }
}

/** Where a motion photo's clip starts; a null offset means Media3 has to find it itself. */
private class MotionClip(val offset: Long?)

/** Pinch zooms from fit to 8×, whatever the photo's resolution (D2). */
private val UpToEightTimesFit = DynamicZoomSpec { inputs ->
    val fit = inputs.scaledContentBounds.size.maxDimension / inputs.unscaledContentSize.maxDimension
    ZoomSpec(maxZoomFactor = fit * MAX_ZOOM)
}

/** Double-tap toggles fit and 2.5× at the tap point (D2). */
private val FitOrTwoAndAHalf = DoubleClickToZoomListener { state, centroid ->
    if ((state.zoomFraction ?: 0f) > 0f) state.resetZoom()
    else state.zoomBy(DOUBLE_TAP_ZOOM, centroid)
}

/** The toolbar's distance above the gesture bar. */
private val TOOLBAR_BOTTOM = 24.dp
private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f
/** Zoom at or below which the photo counts as fitted: swipes close or open Details. */
private const val FIT_ZOOM = 1.01f
private const val ZOOM_PILL_MILLIS = 800L
private const val VIDEO_CHROME_MILLIS = 3_000L
private const val DISMISS_FRACTION = 0.15f
private const val DRAG_SHRINK = 0.3f
private const val UP_RESISTANCE = 0.35f
private const val LEAVE_SHRINK = 0.1f
private const val FLING_DP_PER_S = 1_200f
private const val DETAILS_DP = 64f
