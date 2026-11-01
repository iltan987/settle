package com.iltan.settle.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.iltan.settle.R
import com.iltan.settle.domain.backup.BackupHeadline
import com.iltan.settle.domain.backup.BackupPause
import com.iltan.settle.ui.chrome.NoticeAction
import com.iltan.settle.ui.chrome.NoticeContent
import com.iltan.settle.ui.chrome.Tone

/** A card the timeline shows above its grid (B6–B9, B12). */
sealed interface BackupNotice {
    /** What it is about, to remember a dismissal by; null when it can't be dismissed. */
    val key: String?

    /** Items backup couldn't handle on its own (B6). */
    data class Attention(val count: Int) : BackupNotice {
        override val key = "attention"
    }

    /** Nothing backs up until [reason] is resolved; [waiting] items stay on the phone (B7, B8). */
    data class Pause(val reason: BackupPause, val waiting: Int) : BackupNotice {
        override val key = "pause:$reason"
    }

    /** Many files vanished at once and wait for the user's answer (B9, R14): never dismissed. */
    data class Mass(val count: Int) : BackupNotice {
        override val key: String? = null
    }
}

/** Every notice that applies now, the most important first. */
fun backupNotices(headline: BackupHeadline?, breakerCount: Int): List<BackupNotice> = buildList {
    if (breakerCount > 0) add(BackupNotice.Mass(breakerCount))
    when (headline) {
        is BackupHeadline.NeedsAttention -> add(BackupNotice.Attention(headline.count))
        is BackupHeadline.Paused -> add(BackupNotice.Pause(headline.reason, headline.waitingItems))
        else -> {}
    }
}

/** What the notices' buttons do. */
@Immutable
data class NoticeHandlers(
    val review: () -> Unit = {},
    val connect: () -> Unit = {},
    val signIn: () -> Unit = {},
    val manageStorage: () -> Unit = {},
    val allowLocation: () -> Unit = {},
    val resume: () -> Unit = {},
    /** Opens the Backup tab. */
    val details: () -> Unit = {},
    val dismiss: (key: String) -> Unit = {},
    val keepDriveCopies: () -> Unit = {},
    val moveDriveCopies: () -> Unit = {},
    val checkAgain: () -> Unit = {},
)

/**
 * How [notice] reads, as a card or, once dismissed, in the chip's popover, where "Backup details"
 * takes the place of dismissing.
 */
@Composable
fun noticeContent(notice: BackupNotice, on: NoticeHandlers, popover: Boolean): NoticeContent {
    val details = NoticeAction(stringResource(R.string.notice_backup_details), on.details)
    val extra = if (popover) listOf(details) else emptyList()
    return when (notice) {
        is BackupNotice.Attention ->
            NoticeContent(
                Tone.ERR,
                R.drawable.ic_error_filled,
                pluralStringResource(R.plurals.notice_attention_title, notice.count, notice.count),
                stringResource(R.string.notice_attention_body),
                NoticeAction(stringResource(R.string.action_review), on.review),
                extra,
            )
        is BackupNotice.Mass ->
            NoticeContent(
                Tone.WARN,
                R.drawable.ic_hide_image,
                pluralStringResource(R.plurals.notice_mass_title, notice.count, notice.count),
                stringResource(R.string.notice_mass_body),
                NoticeAction(stringResource(R.string.breaker_keep), on.keepDriveCopies),
                // Until the full-screen review (G1) arrives, the card holds every answer.
                listOf(
                    NoticeAction(stringResource(R.string.breaker_check_again), on.checkAgain),
                    NoticeAction(
                        stringResource(R.string.move_drive_copies_to_trash),
                        on.moveDriveCopies,
                    ),
                ),
            )
        is BackupNotice.Pause -> pauseContent(notice, on, extra)
    }
}

@Composable
private fun pauseContent(
    notice: BackupNotice.Pause,
    on: NoticeHandlers,
    extra: List<NoticeAction>,
): NoticeContent =
    when (notice.reason) {
        BackupPause.NOT_CONNECTED ->
            NoticeContent(
                Tone.INFO,
                R.drawable.ic_add_to_drive,
                stringResource(R.string.drive_connect),
                stringResource(R.string.notice_connect_body),
                NoticeAction(stringResource(R.string.action_connect), on.connect),
                // "Not now" is how this card is dismissed (B12).
                extra.ifEmpty {
                    listOf(
                        NoticeAction(stringResource(R.string.action_not_now)) {
                            notice.key.let(on.dismiss)
                        }
                    )
                },
            )
        BackupPause.AUTH_NEEDED ->
            NoticeContent(
                Tone.WARN,
                R.drawable.ic_account_circle,
                stringResource(R.string.notice_signin_title),
                stringResource(R.string.notice_signin_body),
                NoticeAction(stringResource(R.string.action_sign_in), on.signIn),
                extra,
            )
        BackupPause.QUOTA_FULL ->
            NoticeContent(
                Tone.WARN,
                R.drawable.ic_cloud_off_filled,
                stringResource(R.string.notice_drive_full_title),
                pluralStringResource(
                    R.plurals.notice_drive_full_body,
                    notice.waiting,
                    notice.waiting,
                ),
                NoticeAction(stringResource(R.string.notice_manage_storage), on.manageStorage),
                extra,
            )
        BackupPause.NO_ORIGINAL_ACCESS ->
            NoticeContent(
                Tone.WARN,
                R.drawable.ic_location_off,
                stringResource(R.string.notice_location_title),
                stringResource(R.string.notice_location_body),
                NoticeAction(stringResource(R.string.backup_allow), on.allowLocation),
                extra,
            )
        BackupPause.BY_USER ->
            NoticeContent(
                Tone.WARN,
                R.drawable.ic_pause_circle_filled,
                stringResource(R.string.status_paused_by_you),
                pluralStringResource(R.plurals.notice_paused_body, notice.waiting, notice.waiting),
                NoticeAction(stringResource(R.string.backup_resume), on.resume),
                extra,
            )
    }

/** Whether the card has a close button; the connect card is dismissed with "Not now" instead. */
val BackupNotice.closable: Boolean
    get() = key != null && !(this is BackupNotice.Pause && reason == BackupPause.NOT_CONNECTED)
