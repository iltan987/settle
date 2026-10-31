package com.iltan.settle.domain.backup

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Drive's limit for one app property: key and value together, in UTF-8 bytes. */
const val APP_PROPERTY_MAX_BYTES = 124

/**
 * The folders an original goes into, from the root down: `<root>/media/YYYY/MM` by the month it was
 * taken, in the phone's time zone (ADR-003).
 */
fun mediaFolderPath(root: String, takenAtMillis: Long, zone: ZoneId): List<String> {
    val date = Instant.ofEpochMilli(takenAtMillis).atZone(zone)
    return listOf(root, "media", "%04d".format(date.year), "%02d".format(date.monthValue))
}

/**
 * The item's identity and metadata on its Drive file (ADR-003), so the library can be rebuilt from
 * `files.list` alone. Values that would exceed Drive's per-property limit are left out here; the
 * description carries everything.
 */
fun appPropertiesFor(item: BackupItem): Map<String, String> {
    val all =
        linkedMapOf(
            "st.v" to "1",
            "st.id" to item.id.value,
            "st.kind" to item.kind.name.lowercase(),
            "st.taken" to item.takenAtMillis.toString(),
            "st.dim" to "${item.width}x${item.height}",
            "st.path" to item.relativePath,
        )
    if (item.specialFormat != 0) all["st.sf"] = item.specialFormat.toString()
    return all.filter { (key, value) ->
        key.toByteArray().size + value.toByteArray().size <= APP_PROPERTY_MAX_BYTES
    }
}

/** The same metadata in full, as JSON in the file's description. */
fun descriptionFor(item: BackupItem): String =
    JsonObject(
            (appPropertiesFor(item) +
                    ("st.path" to item.relativePath) +
                    ("st.name" to item.displayName))
                .mapValues { JsonPrimitive(it.value) }
        )
        .toString()
