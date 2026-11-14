package dev.nymph.lyrics.app.canvas.list

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nymph.lyrics.logic.list.ListSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The list-mode sort, remembered per device (not per canvas, never on the server). */
interface ListSortStore {
    /** The chosen sort; [ListSort.DEFAULT] until one was chosen. */
    val sort: Flow<ListSort>

    suspend fun save(sort: ListSort)
}

/** [ListSortStore] in Preferences DataStore (one key, [ListSort.wire]). */
class DataStoreListSortStore(private val store: DataStore<Preferences>) : ListSortStore {
    override val sort: Flow<ListSort> = store.data.map { ListSort.parse(it[KEY]) }.distinctUntilChanged()

    override suspend fun save(sort: ListSort) {
        store.edit { it[KEY] = sort.wire }
    }

    private companion object {
        val KEY = stringPreferencesKey("list_sort")
    }
}

/** A [ListSortStore] for one process (tests, previews). */
class InMemoryListSortStore(initial: ListSort = ListSort.DEFAULT) : ListSortStore {
    private val value = MutableStateFlow(initial)

    override val sort: Flow<ListSort> = value.asStateFlow()

    override suspend fun save(sort: ListSort) {
        value.value = sort
    }
}

/** The sort menu's label. */
val ListSort.label: String
    get() = when (this) {
        ListSort.LAST_EDITED -> "Last edited"
        ListSort.CREATED -> "Created"
        ListSort.A_Z -> "A-Z"
        ListSort.CUSTOM -> "Custom"
    }
