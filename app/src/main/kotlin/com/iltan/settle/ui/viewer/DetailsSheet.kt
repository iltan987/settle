package com.iltan.settle.ui.viewer

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.iltan.settle.R
import com.iltan.settle.data.db.MediaEntry
import com.iltan.settle.data.db.UploadEntity
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.domain.backup.UploadProblem
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.DayTitles
import com.iltan.settle.ui.chrome.Tone
import com.iltan.settle.ui.grid.formatDuration
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.SettleType
import com.iltan.settle.ui.theme.cardColor
import com.iltan.settle.ui.theme.settleColors
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** What Details shows; the screen fills it as facts and the backup status arrive. */
class DetailsContent(
    val entry: MediaEntry,
    val facts: ItemFacts?,
    val status: CloudStatus?,
    val chip: ViewerChip?,
    val upload: UploadEntity?,
    /** The folder's short name: "Camera". */
    val folder: String,
)

/**
 * DetailsSheet (D5): the file, its folder, where it was taken, and its backup. Opened by Details, a
 * swipe up, or the status chip, which scrolls it to the backup section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(
    content: DetailsContent,
    toBackup: Boolean,
    onDismiss: () -> Unit,
    onOpenMap: (latitude: Double, longitude: Double, label: String?) -> Unit,
    onOpenInDrive: (driveFileId: String) -> Unit,
    onAction: (ChipAction) -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    val backup = remember { BringIntoViewRequester() }
    LaunchedEffect(toBackup) {
        if (toBackup) {
            sheet.expand()
            backup.bringIntoView()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = SettleShape.Sheet,
        containerColor = MaterialTheme.cardColor,
        scrimColor = MaterialTheme.settleColors.scrim,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            Header(content)
            Spacer(Modifier.height(8.dp))
            FileRow(content)
            FolderRow(content)
            content.facts?.location?.let { (latitude, longitude) ->
                val place = content.facts.place
                DetailRow(
                    icon = R.drawable.ic_location,
                    title = place?.address ?: coordinates(latitude, longitude),
                    subtitle = stringResource(R.string.viewer_opens_in_maps),
                    trailing = R.drawable.ic_open_in_new,
                    onClick = { onOpenMap(latitude, longitude, place?.address) },
                )
            }
            Spacer(Modifier.height(12.dp))
            content.status?.let { status ->
                BackupSection(
                    status,
                    content,
                    onOpenInDrive,
                    onAction,
                    Modifier.bringIntoViewRequester(backup),
                )
            }
            content.upload?.let { TechnicalDetails(it) }
        }
    }
}

@Composable
private fun Header(content: DetailsContent) {
    val locale = LocalConfiguration.current.locales[0]
    val taken = Instant.ofEpochMilli(content.entry.takenAtMs).atZone(ZoneId.systemDefault())
    val day = remember(locale) { DayTitles.pattern(locale, "EEEEdMMMMyyyy") }
    val time = remember(locale) { DayTitles.pattern(locale, "jm") }
    val place = content.facts?.place?.let { listOfNotNull(it.name, it.region).joinToString(", ") }
    Text(
        day.format(taken),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
    Text(
        listOfNotNull(time.format(taken), place).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FileRow(content: DetailsContent) {
    val context = LocalContext.current
    val details = content.facts?.details ?: return
    val type = details.mimeType.substringAfter('/').uppercase(Locale.ROOT)
    val facts = buildList {
        add(Formatter.formatShortFileSize(context, details.sizeBytes))
        if (details.width > 0 && details.height > 0) {
            add(stringResource(R.string.viewer_dimensions, details.width, details.height))
        }
        if (content.entry.kind == MediaKind.VIDEO) add(formatDuration(content.entry.durationMs))
        add(type)
    }
    DetailRow(
        icon =
            if (content.entry.kind == MediaKind.VIDEO) R.drawable.ic_movie else R.drawable.ic_image,
        title = details.displayName,
        subtitle = facts.joinToString(" · "),
    )
}

@Composable
private fun FolderRow(content: DetailsContent) {
    val details = content.facts?.details ?: return
    DetailRow(
        icon = R.drawable.ic_folder,
        title = content.folder,
        subtitle =
            stringResource(R.string.viewer_folder_on_phone, details.relativePath.trimEnd('/')),
    )
}

/** DetailRow: at least 56 dp, a 24 dp icon and two lines. */
@Composable
private fun DetailRow(
    icon: Int,
    title: String,
    subtitle: String,
    trailing: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.let {
            Icon(
                painterResource(it),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** BackupSection: a c1 card whose icon and copy follow the item's status (D4). */
@Composable
private fun BackupSection(
    status: CloudStatus,
    content: DetailsContent,
    onOpenInDrive: (String) -> Unit,
    onAction: (ChipAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, body) = backupCopy(status, content)
    val chip = content.chip
    val colors = MaterialTheme.colorScheme
    val tint =
        when (chip?.tone) {
            Tone.WARN -> MaterialTheme.settleColors.warning
            Tone.ERR -> colors.error
            Tone.INFO -> colors.onSurfaceVariant
            else -> colors.primary
        }
    Surface(shape = SettleShape.Card, color = colors.surfaceContainer, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painterResource(chip?.icon ?: R.drawable.ic_cloud_upload),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(title, style = SettleType.itemTitle)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            val driveFileId = (status as? CloudStatus.BackedUp)?.driveFileId
            Box(Modifier.fillMaxWidth().height(8.dp))
            Row(Modifier.align(Alignment.End).padding(bottom = 4.dp)) {
                if (driveFileId != null) {
                    TextButton(onClick = { onOpenInDrive(driveFileId) }) {
                        Text(stringResource(R.string.backup_open_in_drive))
                        Icon(
                            painterResource(R.drawable.ic_open_in_new),
                            contentDescription = null,
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                        )
                    }
                } else {
                    // Details is where the chip's "Details" leads; here it offers the retry.
                    val action =
                        if (chip?.action == ChipAction.DETAILS) ChipAction.TRY_AGAIN
                        else chip?.action
                    action?.let { action ->
                        TextButton(onClick = { onAction(action) }) {
                            Text(stringResource(action.label))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun backupCopy(status: CloudStatus, content: DetailsContent): Pair<String, String> =
    when (status) {
        is CloudStatus.BackedUp -> {
            val checked = content.upload?.verifiedAtMs
            stringResource(R.string.backup_backed_up) to
                if (checked != null) {
                    stringResource(R.string.viewer_checks_match_at, checkedAt(checked))
                } else {
                    stringResource(R.string.viewer_checks_match)
                }
        }
        is CloudStatus.Uploading ->
            stringResource(R.string.backup_uploading) to
                stringResource(R.string.viewer_uploading_body)
        CloudStatus.Checking ->
            stringResource(R.string.viewer_uploaded) to stringResource(R.string.backup_checking)
        is CloudStatus.Waiting,
        CloudStatus.Queued ->
            stringResource(R.string.viewer_not_backed_up_yet) to
                (content.chip?.text ?: stringResource(R.string.backup_queued))
        CloudStatus.NotChosen ->
            stringResource(R.string.backup_not_backed_up) to
                stringResource(R.string.viewer_folder_not_chosen_body, content.folder)
        CloudStatus.NotBackedUp ->
            stringResource(R.string.backup_not_backed_up) to
                stringResource(R.string.viewer_not_backed_up_body)
        CloudStatus.NotConnected ->
            stringResource(R.string.backup_not_backed_up) to
                stringResource(R.string.viewer_not_connected_body)
        is CloudStatus.Problem ->
            stringResource(R.string.viewer_needs_attention) to
                stringResource(status.problem.explained)
        is CloudStatus.Paused ->
            stringResource(R.string.status_paused) to stringResource(status.reason.explained)
    }

/** Copyable SHA-256 and Drive file ID, collapsed under "Technical details". */
@Composable
private fun TechnicalDetails(upload: UploadEntity) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { open = !open }
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.viewer_technical_details),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (open) 180f else 0f),
        )
    }
    if (open) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                upload.sha256?.let { TechnicalRow("SHA-256", it) }
                TechnicalRow(stringResource(R.string.viewer_drive_file_id), upload.driveFileId)
            }
        }
    }
}

@Composable
private fun TechnicalRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun checkedAt(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = remember(locale) { DayTitles.pattern(locale, "dMMMMjm") }
    return pattern.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

private fun coordinates(latitude: Double, longitude: Double) =
    "%.5f, %.5f".format(Locale.ROOT, latitude, longitude)

/** An upload problem with what to do about it. */
private val UploadProblem.explained: Int
    get() =
        when (this) {
            UploadProblem.AUTH_NEEDED -> R.string.backup_problem_auth
            UploadProblem.QUOTA_FULL -> R.string.backup_problem_quota
            UploadProblem.OFFLINE -> R.string.backup_problem_offline
            UploadProblem.ORIGINAL_UNAVAILABLE -> R.string.viewer_problem_unreadable_body
            UploadProblem.CHANGED_DURING_UPLOAD -> R.string.backup_problem_changed
            UploadProblem.CHECKSUM_MISMATCH -> R.string.backup_problem_mismatch
            UploadProblem.FAILED -> R.string.backup_problem_failed
        }

/** Why backup as a whole waits. */
val BackupPause.explained: Int
    get() =
        when (this) {
            BackupPause.NOT_CONNECTED -> R.string.viewer_not_connected_body
            BackupPause.NO_ORIGINAL_ACCESS -> R.string.backup_needs_location
            BackupPause.BY_USER -> R.string.viewer_paused_by_you_body
            BackupPause.AUTH_NEEDED -> R.string.backup_problem_auth
            BackupPause.QUOTA_FULL -> R.string.backup_problem_quota
        }
