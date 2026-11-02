package com.iltan.settle.ui.trash

import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iltan.settle.R
import com.iltan.settle.data.db.VanishedEntry
import com.iltan.settle.domain.scan.MediaKind
import com.iltan.settle.ui.DayTitles
import com.iltan.settle.ui.chrome.BackTopBar
import com.iltan.settle.ui.chrome.ConfirmDialog
import com.iltan.settle.ui.chrome.TopBarHeight
import com.iltan.settle.ui.folders.folderNames
import com.iltan.settle.ui.theme.SettleShape
import com.iltan.settle.ui.theme.settleColors
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** What helps the user tell why files disappeared (G1's FactList). */
data class MassFacts(
    val count: Int,
    /** When Settle first noticed. */
    val noticedAt: ZonedDateTime?,
    /** Most of them were on a removable card rather than the phone's own storage. */
    val mostlyOnCard: Boolean,
    /** The folder most of them were in. */
    val mostlyIn: String?,
    val firstYear: Int?,
    val lastYear: Int?,
)

fun massFacts(items: List<VanishedEntry>, zone: ZoneId): MassFacts {
    fun <T> mode(values: List<T>) =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val years =
        items
            .filter { it.takenAtMs > 0 }
            .map { Instant.ofEpochMilli(it.takenAtMs).atZone(zone).year }
    return MassFacts(
        count = items.size,
        noticedAt = items.minOfOrNull { it.sinceMs }?.let { Instant.ofEpochMilli(it).atZone(zone) },
        mostlyOnCard = mode(items.map { it.volume != MediaStore.VOLUME_EXTERNAL_PRIMARY }) == true,
        mostlyIn = mode(items.map { it.relativePath }),
        firstYear = years.minOrNull(),
        lastYear = years.maxOrNull(),
    )
}

/**
 * The mass disappearance, answered in one place (G1, R14). A full screen rather than a dialog, so
 * it can reassure: what is safe first, then the likely cause, then the choices with the safest
 * first and largest. Back leaves the question open; the timeline's notice stays.
 */
@Composable
fun MassReviewScreen(
    items: List<VanishedEntry>,
    onBack: () -> Unit,
    onSeeAll: () -> Unit,
    onKeep: () -> Unit,
    onCheckAgain: () -> Unit,
    onMove: () -> Unit,
    checking: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val facts = remember(items, zone) { massFacts(items, zone) }
    var confirmMove by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val settle = MaterialTheme.settleColors
    Surface(Modifier.fillMaxSize(), color = colors.surface) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(title = null, onBack = onBack)
            Column(
                Modifier.weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(48.dp).background(settle.warningContainer, SettleShape.Cover),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_hide_image),
                        contentDescription = null,
                        tint = settle.onWarningContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Text(
                    pluralStringResource(R.plurals.breaker_title, facts.count, facts.count),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.breaker_text),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = colors.onSurfaceVariant,
                )
                FactList(facts, onSeeAll)
            }
            // The ActionStack, pinned to the bottom.
            Column(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onKeep,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.breaker_keep))
                }
                FilledTonalButton(
                    onClick = onCheckAgain,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp).padding(end = 0.dp),
                        )
                        Text(
                            stringResource(R.string.mass_checking),
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    } else {
                        Text(stringResource(R.string.breaker_check_again))
                    }
                }
                TextButton(
                    onClick = { confirmMove = true },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    // The risky choice is neutral text: it asks first (G3), and can be undone.
                    Text(stringResource(R.string.mass_move), color = colors.onSurface)
                }
            }
        }
    }
    if (confirmMove) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.mass_move_title, facts.count, facts.count),
            text = listOf(stringResource(R.string.mass_move_text)),
            confirm = stringResource(R.string.move_drive_copies_short),
            onConfirm = {
                confirmMove = false
                onMove()
            },
            onDismiss = { confirmMove = false },
        )
    }
}

/** When, where and which years, then the way to every item (FactList: c1, radius 20). */
@Composable
private fun FactList(facts: MassFacts, onSeeAll: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val resources = LocalResources.current
    val noticed = remember(locale) { DayTitles.pattern(locale, "EEEEdMMMMjm") }
    val root = stringResource(R.string.folder_root)
    Surface(shape = SettleShape.Card, color = colors.surfaceContainer) {
        Column {
            facts.noticedAt?.let { at ->
                FactRow(
                    R.drawable.ic_schedule,
                    stringResource(R.string.mass_noticed, noticed.format(at)),
                )
                HorizontalDivider(color = colors.outlineVariant)
            }
            val where =
                when {
                    facts.mostlyOnCard -> resources.getString(R.string.mass_from_card)
                    facts.mostlyIn != null ->
                        resources.getString(
                            R.string.mass_from_folder,
                            folderNames(listOf(facts.mostlyIn), root).getValue(facts.mostlyIn),
                        )
                    else -> null
                }
            val years =
                when {
                    facts.firstYear == null || facts.lastYear == null -> null
                    facts.firstYear == facts.lastYear -> facts.firstYear.toString()
                    else ->
                        resources.getString(R.string.mass_years, facts.firstYear, facts.lastYear)
                }
            if (where != null || years != null) {
                FactRow(
                    if (facts.mostlyOnCard) R.drawable.ic_sd_card else R.drawable.ic_mobile,
                    listOfNotNull(where, years).joinToString(" · "),
                )
                HorizontalDivider(color = colors.outlineVariant)
            }
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onSeeAll)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pluralStringResource(R.plurals.mass_see_all, facts.count, facts.count),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun FactRow(icon: Int, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp))
    }
}

/**
 * Which files are missing (G2), by where they were, then by date. Read-only, so the decision stays
 * in one place (G1). Drive previews come with cloud-only items; until then each is named.
 */
@Composable
fun MissingItemsScreen(
    items: List<VanishedEntry>,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val taken = remember(locale) { DayTitles.pattern(locale, "dMMMMyyyy") }
    val root = stringResource(R.string.folder_root)
    val card = stringResource(R.string.mass_volume_card)
    val phone = stringResource(R.string.mass_volume_phone)
    val groups =
        remember(items) {
            items
                .groupBy { it.volume to it.relativePath }
                .entries
                .sortedByDescending { it.value.size }
                .map { (key, list) -> key to list.sortedByDescending { it.takenAtMs } }
        }
    val names = remember(groups, root) { folderNames(groups.map { it.first.second }, root) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(Modifier.fillMaxSize(), color = colors.surface) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(top = top, bottom = bottom + 16.dp)) {
                groups.forEach { (key, list) ->
                    val (volume, path) = key
                    item(key = "header-$volume-$path") {
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) phone else card}" +
                                    " · ${names[path] ?: path}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                list.size.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    items(list, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(
                                    if (item.kind == MediaKind.VIDEO) R.drawable.ic_movie
                                    else R.drawable.ic_image
                                ),
                                contentDescription = null,
                                tint = colors.onSurfaceVariant,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.displayName, style = MaterialTheme.typography.bodyLarge)
                                if (item.takenAtMs > 0) {
                                    Text(
                                        taken.format(
                                            Instant.ofEpochMilli(item.takenAtMs).atZone(zone)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            BackTopBar(
                pluralStringResource(R.plurals.mass_missing_title, items.size, items.size),
                onBack,
                subtitle = stringResource(R.string.mass_missing_subtitle),
            )
        }
    }
}
