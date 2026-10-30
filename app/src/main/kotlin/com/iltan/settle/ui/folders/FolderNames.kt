package com.iltan.settle.ui.folders

/**
 * Short names for folders: the last path segment, plus its parent when two folders would otherwise
 * look the same (`Pictures/WhatsApp/` and `Movies/WhatsApp/`). The phone's root folder gets
 * [rootName].
 */
fun folderNames(relativePaths: List<String>, rootName: String): Map<String, String> {
    fun segments(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }
    val lastSegment = relativePaths.associateWith { segments(it).lastOrNull() ?: rootName }
    val clashing = lastSegment.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return relativePaths.associateWith { path ->
        val name = lastSegment.getValue(path)
        val parent = segments(path).dropLast(1).lastOrNull()
        if (name in clashing && parent != null) "$name ($parent)" else name
    }
}
