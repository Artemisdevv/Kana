package com.artemis.ebookreader.feature.library

internal data class LibrarySelection(val session: Long, val ids: Set<String>) {
    fun visible(availableIds: Set<String>): LibrarySelection =
        copy(ids = ids.intersect(availableIds))

    fun toggle(id: String, availableIds: Set<String>): LibrarySelection {
        val visible = visible(availableIds)
        if (id !in availableIds) return visible
        return visible.copy(ids = if (id in visible.ids) visible.ids - id else visible.ids + id)
    }

    fun toggleAll(availableIds: Set<String>): LibrarySelection =
        copy(ids = if (availableIds.isNotEmpty() && ids.containsAll(availableIds)) emptySet() else availableIds)
}
