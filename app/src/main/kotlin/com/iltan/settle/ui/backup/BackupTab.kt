package com.iltan.settle.ui.backup

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.iltan.settle.R
import com.iltan.settle.data.backup.UploadProgress
import com.iltan.settle.data.db.PendingEntry
import com.iltan.settle.data.media.MediaThumbnail
import com.iltan.settle.data.mediaContentUri
import com.iltan.settle.data.thumbnail
import com.iltan.settle.domain.ItemStatus
import com.iltan.settle.domain.backup.BackupDecision
import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.domain.backup.cellStatus
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.chrome.AccountButton
import com.iltan.settle.ui.chrome.BackupStatusHeader
import com.iltan.settle.ui.chrome.BottomBarHeight
import com.iltan.settle.ui.chrome.EmptyState
import com.iltan.settle.ui.chrome.HeaderAction
import com.iltan.settle.ui.chrome.HeaderContent
import com.iltan.settle.ui.chrome.HeaderProgress
import com.iltan.settle.ui.chrome.NoticeCard
import com.iltan.settle.ui.chrome.RowButtonStyle
import com.iltan.settle.ui.chrome.SettingsRow
import com.iltan.settle.ui.chrome.SettleTopBar
import com.iltan.settle.ui.chrome.Tone
import com.iltan.settle.ui.chrome.Trailing
import com.iltan.settle.ui.grid.CellBackup
import com.iltan.settle.ui.grid.GridCell
import com.iltan.settle.ui.settings.graceLabel
import com.iltan.settle.ui.settings.nextBackupWhen
import com.iltan.settle.ui.settings.whenText
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.SettleType
import com.iltan.settle.ui.theme.cardColor
import com.iltan.settle.ui.timeline.BackupNotice
import com.iltan.settle.ui.timeline.NoticeHandlers
import com.iltan.settle.ui.timeline.noticeContent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** What the Backup tab's buttons do. */
@Immutable
class BackupTabActions(
    val connect: () -> Unit = {},
    val signIn: () -> Unit = {},
    val manageStorage: () -> Unit = {},
    val allowLocation: () -> Unit = {},
    val setPaused: (Boolean) -> Unit = {},
    val backUpEverythingNow: () -> Unit = {},
    val tryAgain: (itemId: String) -> Unit = {},
    val open: (PendingEntry) -> Unit = {},
    val viewTrash: () -> Unit = {},
    val reviewMass: () -> Unit = {},
)

/**
 * The Backup tab (J): one status with one action, then Back up everything now, a restore in
 * progress, what needs the user, and the items still to back up with their Waiting and Queued
 * badges (the one place outside selection they show). Sections with nothing in them aren't drawn.
 * State and actions only; preferences live in Settings → Backup.
 */
@Composable
fun BackupTab(
    state: BackupTabState,
    accountEmail: String?,
    actions: BackupTabActions,
    onOpenSettings: () -> Unit,
    /** Read inside each cell, so an upload's progress redraws only its own cell. */
    uploads: State<Map<String, UploadProgress>>,
    decisions: State<Map<String, BackupDecision>?>,
) {
    val bottom =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarHeight
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            SettleTopBar(stringResource(R.string.nav_backup)) {
                AccountButton(accountEmail, onOpenSettings)
            }
            if (!state.loaded) return@Column
            val sections = state.sections
            // "Review" scrolls to the attention list: after the header and any cards above it.
            val attentionIndex =
                1 +
                    listOf(state.massCount > 0, showEverythingNow(state), state.restore != null)
                        .count { it }
            val header =
                headerContent(state, actions) {
                    scope.launch { grid.animateScrollToItem(attentionIndex) }
                }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = grid,
                contentPadding = PaddingValues(bottom = bottom + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                full("header") {
                    BackupStatusHeader(
                        header,
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                    )
                }
                if (state.massCount > 0) {
                    full("mass") {
                        NoticeCard(
                            noticeContent(
                                BackupNotice.Mass(state.massCount, state.checkingMass),
                                NoticeHandlers(reviewMass = actions.reviewMass),
                                popover = false,
                            )
                        )
                    }
                }
                if (showEverythingNow(state)) {
                    full("now") { EverythingNowRow(actions.backUpEverythingNow) }
                }
                state.restore?.let { restore ->
                    full("restore") { RestoreCard(restore, actions.viewTrash) }
                }
                if (sections.attention.isNotEmpty() || state.restoreProblems > 0) {
                    full("attention") { AttentionList(state, actions) }
                }
                val run = state.run
                section(
                    "run",
                    sections.inRun,
                    title =
                        if (run != null) {
                            {
                                pluralStringResource(
                                    R.plurals.section_in_run,
                                    sections.inRun.size,
                                    sections.inRun.size,
                                )
                            }
                        } else {
                            { stringResource(R.string.section_uploading) }
                        },
                    meta = {
                        run?.let {
                            Formatter.formatShortFileSize(
                                LocalContext.current,
                                (it.totalBytes - it.sentBytes).coerceAtLeast(0),
                            )
                        }
                    },
                    uploads = uploads,
                    decisions = decisions,
                    open = actions.open,
                )
                section(
                    "waiting",
                    sections.waiting,
                    title = { stringResource(R.string.section_waiting, sections.waiting.size) },
                    meta = {
                        sections.waitingUntilMs?.let {
                            stringResource(R.string.section_waiting_from, whenText(it))
                        }
                    },
                    uploads = uploads,
                    decisions = decisions,
                    open = actions.open,
                )
                section(
                    "queued",
                    sections.queued,
                    title = { stringResource(R.string.section_queued, sections.queued.size) },
                    meta = {
                        if (state.headline is BackupHeadline.Paused) {
                            stringResource(R.string.section_after_resume)
                        } else nextBackupWhen(state.choices.settings)
                    },
                    uploads = uploads,
                    decisions = decisions,
                    open = actions.open,
                )
                if (sections.isEmpty && state.restore == null && state.restoreProblems == 0) {
                    full("empty") { Empty(state) }
                }
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** A titled grid of items, with what its meta says about when they go. */
private fun LazyGridScope.section(
    key: String,
    items: List<PendingEntry>,
    title: @Composable () -> String,
    meta: @Composable () -> String?,
    uploads: State<Map<String, UploadProgress>>,
    decisions: State<Map<String, BackupDecision>?>,
    open: (PendingEntry) -> Unit,
) {
    if (items.isEmpty()) return
    full("$key-title") {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 20.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title(),
                style = SettleType.dayHeader,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            meta()?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    items(items, key = { "$key-${it.entry.id}" }) { item ->
        val entry = item.entry
        val upload = uploads.value[entry.id]
        val status = cellStatus(entry.cloudState, decisions.value?.get(entry.id), upload != null)
        val description = stringResource(status.text)
        // A plain tap, so the cell keeps its own semantics (and its click for accessibility).
        Box(Modifier.pointerInput(item) { detectTapGestures { open(item) } }) {
            GridCell(
                entry = entry,
                selected = null,
                backup =
                    CellBackup(
                        status,
                        upload?.let {
                            if (it.totalBytes > 0) it.sentBytes.toFloat() / it.totalBytes else 0f
                        },
                        checking = upload?.checking == true,
                    ),
                showFavorite = true,
                description = description,
                onClick = { open(item) },
                onLongClick = null,
            )
        }
    }
}

/** What a cell's badge says, for screen readers. */
private val ItemStatus.text: Int
    get() =
        when (this) {
            ItemStatus.UPLOADING -> R.string.cell_uploading
            ItemStatus.WAITING -> R.string.cell_waiting
            ItemStatus.QUEUED -> R.string.cell_queued
            else -> R.string.media_needs_attention
        }

private fun showEverythingNow(state: BackupTabState): Boolean {
    val counts = state.counts ?: return false
    return counts.pause == null && state.run == null && counts.waiting + counts.queued > 0
}

/** The header's sentence and its one action, by the app's one backup status. */
@Composable
private fun headerContent(
    state: BackupTabState,
    actions: BackupTabActions,
    review: () -> Unit,
): HeaderContent {
    val context = LocalContext.current
    return when (val headline = checkNotNull(state.headline)) {
        is BackupHeadline.NeedsAttention ->
            HeaderContent(
                Tone.ERR,
                R.drawable.ic_error_filled,
                pluralStringResource(
                    R.plurals.notice_attention_title,
                    headline.count,
                    headline.count,
                ),
                stringResource(R.string.header_attention_body),
                action = HeaderAction(stringResource(R.string.action_review), null, review),
            )
        is BackupHeadline.Paused ->
            when (headline.reason) {
                BackupPause.NOT_CONNECTED ->
                    HeaderContent(
                        Tone.INFO,
                        R.drawable.ic_cloud_off,
                        stringResource(R.string.backup_off),
                        stringResource(R.string.header_off_body),
                        action =
                            HeaderAction(
                                stringResource(R.string.action_connect),
                                null,
                                actions.connect,
                            ),
                    )
                BackupPause.AUTH_NEEDED ->
                    HeaderContent(
                        Tone.WARN,
                        R.drawable.ic_account_circle,
                        stringResource(R.string.header_paused_sign_in),
                        stringResource(R.string.header_sign_in_body),
                        action =
                            HeaderAction(
                                stringResource(R.string.action_sign_in),
                                null,
                                actions.signIn,
                            ),
                    )
                BackupPause.QUOTA_FULL ->
                    HeaderContent(
                        Tone.WARN,
                        R.drawable.ic_cloud_off_filled,
                        stringResource(R.string.header_paused_drive_full),
                        pluralStringResource(
                            R.plurals.header_drive_full_body,
                            headline.waitingItems,
                            headline.waitingItems,
                        ),
                        action =
                            HeaderAction(
                                stringResource(R.string.notice_manage_storage),
                                null,
                                actions.manageStorage,
                            ),
                    )
                BackupPause.NO_ORIGINAL_ACCESS ->
                    HeaderContent(
                        Tone.WARN,
                        R.drawable.ic_location_off,
                        stringResource(R.string.header_paused_location),
                        stringResource(R.string.header_location_body),
                        action =
                            HeaderAction(
                                stringResource(R.string.backup_allow),
                                null,
                                actions.allowLocation,
                            ),
                    )
                BackupPause.BY_USER ->
                    HeaderContent(
                        Tone.WARN,
                        R.drawable.ic_pause_circle_filled,
                        stringResource(R.string.status_paused_by_you),
                        pluralStringResource(
                            R.plurals.header_paused_body,
                            headline.waitingItems,
                            headline.waitingItems,
                        ),
                        action =
                            HeaderAction(
                                stringResource(R.string.backup_resume),
                                R.drawable.ic_play_arrow_filled,
                            ) {
                                actions.setPaused(false)
                            },
                    )
            }
        is BackupHeadline.BackingUp -> {
            val run = headline.run
            HeaderContent(
                Tone.BUSY,
                R.drawable.ic_cloud_upload,
                pluralStringResource(
                    R.plurals.header_backing_up,
                    run.totalItems,
                    run.doneItems,
                    run.totalItems,
                ),
                stringResource(R.string.header_backing_up_body),
                HeaderProgress(
                    run.fraction,
                    run.bytesPerSecond?.let {
                        stringResource(
                            R.string.header_speed,
                            Formatter.formatShortFileSize(context, it),
                        )
                    },
                    run.secondsLeft?.let { timeLeft(it) },
                ),
                HeaderAction(stringResource(R.string.action_pause), R.drawable.ic_pause_filled) {
                    actions.setPaused(true)
                },
            )
        }
        is BackupHeadline.FirstBackupPending ->
            if (headline.upcoming > 0) {
                HeaderContent(
                    Tone.OK,
                    R.drawable.ic_schedule,
                    stringResource(R.string.status_first_backup),
                    pluralStringResource(
                        R.plurals.header_first_body,
                        headline.upcoming,
                        headline.upcoming,
                    ),
                )
            } else {
                HeaderContent(
                    Tone.OK,
                    R.drawable.ic_cloud_done_filled,
                    stringResource(R.string.backup_nothing_yet),
                    stringResource(R.string.backup_nothing_yet_sub),
                )
            }
        is BackupHeadline.AllInDrive ->
            HeaderContent(
                Tone.OK,
                R.drawable.ic_cloud_done_filled,
                stringResource(R.string.status_all_in_drive),
                listOfNotNull(
                        pluralStringResource(
                            R.plurals.header_safe,
                            headline.backedUp,
                            headline.backedUp,
                        ),
                        if (headline.upcoming > 0) {
                            pluralStringResource(
                                R.plurals.header_upcoming,
                                headline.upcoming,
                                headline.upcoming,
                            )
                        } else {
                            state.lastBackupAtMs?.let {
                                stringResource(R.string.header_last_backup, whenText(it))
                            }
                        },
                    )
                    .joinToString(" "),
            )
    }
}

/** "About 6 minutes left". */
@Composable
private fun timeLeft(seconds: Long): String {
    val minutes = ((seconds + 59) / 60).toInt()
    return when {
        seconds < 60 -> stringResource(R.string.header_less_than_minute)
        minutes < 90 -> pluralStringResource(R.plurals.header_minutes_left, minutes, minutes)
        else -> {
            val hours = ((minutes + 30) / 60)
            pluralStringResource(R.plurals.header_hours_left, hours, hours)
        }
    }
}

@Composable
private fun EverythingNowRow(onStart: () -> Unit) {
    Box(
        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
            .clip(SettleShape.Card)
            .background(MaterialTheme.cardColor)
    ) {
        SettingsRow(
            title = stringResource(R.string.backup_everything_now),
            sub = stringResource(R.string.backup_everything_now_text),
            icon = R.drawable.ic_bolt,
            trailing =
                Trailing.Action(
                    stringResource(R.string.action_start),
                    RowButtonStyle.TONAL,
                    onStart,
                ),
        )
    }
}

/** A restore from the trash, visible from anywhere (RestoreCard, J5). */
@Composable
private fun RestoreCard(restore: RestoreCardState, onViewTrash: () -> Unit) {
    val context = LocalContext.current
    val batch = restore.batch
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
            .fillMaxWidth()
            .clip(SettleShape.Card)
            .background(MaterialTheme.cardColor)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row {
                restore.items.take(4).forEachIndexed { index, item ->
                    val id = item.mediaStoreId
                    Box(
                        Modifier.offset(x = (-8 * index).dp)
                            .size(32.dp)
                            .clip(SettleShape.Thumb)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (id != null) {
                            AsyncImage(
                                model =
                                    MediaThumbnail(
                                        mediaContentUri(item.kind, item.volume, id),
                                        item.dateModifiedS,
                                    ),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                painterResource(R.drawable.ic_cloud_download),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    stringResource(
                        R.string.restore_card_title,
                        batch.restored,
                        batch.total,
                    ),
                    style = SettleType.itemTitle,
                )
                Text(
                    stringResource(R.string.restore_card_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LinearProgressIndicator(
            progress = {
                if (batch.bytesTotal > 0) restore.receivedBytes.toFloat() / batch.bytesTotal
                else batch.restored.toFloat() / batch.total
            },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.restore_card_bytes,
                    Formatter.formatShortFileSize(context, restore.receivedBytes),
                    Formatter.formatShortFileSize(context, batch.bytesTotal),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onViewTrash) {
                Text(
                    stringResource(R.string.restore_card_view),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Items backup couldn't finish on its own, each with why, and that it is still on the phone. */
@Composable
private fun AttentionList(state: BackupTabState, actions: BackupTabActions) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.section_attention),
            style = SettleType.dayHeader,
            modifier =
                Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp).semantics {
                    heading()
                },
        )
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                .fillMaxWidth()
                .clip(SettleShape.Card)
                .background(MaterialTheme.cardColor)
        ) {
            state.sections.attention.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AttentionRow(item, actions)
            }
            if (state.restoreProblems > 0) {
                if (state.sections.attention.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                SettingsRow(
                    title =
                        pluralStringResource(
                            R.plurals.restore_failed_title,
                            state.restoreProblems,
                            state.restoreProblems,
                        ),
                    sub = stringResource(R.string.attention_restore_sub),
                    icon = R.drawable.ic_error_filled,
                    trailing = Trailing.Chevron,
                    onClick = actions.viewTrash,
                )
            }
        }
    }
}

@Composable
private fun AttentionRow(item: PendingEntry, actions: BackupTabActions) {
    val entry = item.entry
    val locale = LocalConfiguration.current.locales[0]
    val date =
        Instant.ofEpochMilli(entry.takenAtMs)
            .atZone(ZoneId.systemDefault())
            .format(
                DateTimeFormatter.ofPattern(
                    DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"),
                    locale,
                )
            )
    val folder =
        item.relativePath.trim('/').substringAfterLast('/').ifEmpty {
            stringResource(R.string.folder_root)
        }
    Row(
        Modifier.fillMaxWidth()
            .clickable { actions.open(item) }
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            AsyncImage(
                model = entry.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(SettleShape.Thumb),
            )
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .offset(4.dp, 4.dp)
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_priority_high),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(item.problem.title), style = SettleType.itemTitle)
            Text(
                stringResource(
                    R.string.attention_sub,
                    stringResource(
                        if (entry.kind == MediaKind.VIDEO) R.string.media_video
                        else R.string.media_photo
                    ),
                    date,
                    folder,
                    stringResource(item.problem.body),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { actions.tryAgain(entry.id) },
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(
                    stringResource(R.string.action_try_again),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private val UploadProblem?.title: Int
    get() =
        when (this) {
            UploadProblem.ORIGINAL_UNAVAILABLE -> R.string.viewer_problem_unreadable
            UploadProblem.CHANGED_DURING_UPLOAD -> R.string.viewer_problem_changed
            UploadProblem.CHECKSUM_MISMATCH -> R.string.viewer_problem_mismatch
            UploadProblem.OFFLINE -> R.string.viewer_problem_offline
            else -> R.string.viewer_problem_failed
        }

private val UploadProblem?.body: Int
    get() =
        when (this) {
            UploadProblem.ORIGINAL_UNAVAILABLE -> R.string.attention_unreadable_body
            UploadProblem.CHANGED_DURING_UPLOAD -> R.string.attention_changed_body
            UploadProblem.CHECKSUM_MISMATCH -> R.string.attention_mismatch_body
            else -> R.string.attention_failed_body
        }

/** With nothing coming: how backup goes, before Drive is connected and after (J6, J7). */
@Composable
private fun Empty(state: BackupTabState) {
    if ((state.headline as? BackupHeadline.Paused)?.reason == BackupPause.NOT_CONNECTED) {
        EmptyState(
            R.drawable.ic_nightlight,
            stringResource(R.string.backup_how_title),
            stringResource(R.string.backup_how_body),
        )
    } else {
        val grace = state.choices.settings.graceMillis
        EmptyState(
            R.drawable.ic_done_all,
            stringResource(R.string.backup_nothing_waiting),
            if (grace == 0L) stringResource(R.string.backup_rhythm_no_wait)
            else stringResource(R.string.backup_rhythm, graceLabel(grace)),
        )
    }
}
