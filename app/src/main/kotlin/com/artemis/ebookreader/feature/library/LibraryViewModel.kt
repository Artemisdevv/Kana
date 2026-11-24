package com.artemis.ebookreader.feature.library

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.library.ImportResult
import com.artemis.ebookreader.data.library.DeleteFailure
import com.artemis.ebookreader.data.library.ExportFailure
import com.artemis.ebookreader.data.library.ExportCancelled
import com.artemis.ebookreader.data.library.DeleteResult
import com.artemis.ebookreader.data.library.LibraryRepository
import com.artemis.ebookreader.data.library.ReadingReset
import com.artemis.ebookreader.data.library.matchesSearch
import com.artemis.ebookreader.data.preferences.BookDisplayName
import com.artemis.ebookreader.data.preferences.LibraryLayoutSettings
import com.artemis.ebookreader.data.preferences.LibrarySortBy
import com.artemis.ebookreader.data.preferences.LibrarySortOrder
import com.artemis.ebookreader.data.preferences.LibraryViewMode
import com.artemis.ebookreader.publication.BookFailure
import com.artemis.ebookreader.publication.BookProblem
import com.artemis.ebookreader.ui.bookProblemMessage
import com.artemis.ebookreader.ui.forgetBookCover
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class BulkBookAction { Export, Delete }
internal enum class BulkBookIssue { Missing, Permissions, Destination, Recovery }
internal data class BulkBookProgress(
    val action: BulkBookAction,
    val total: Int,
    val processed: Int = 0,
    val cancelling: Boolean = false,
)
internal data class BulkBookReport(
    val action: BulkBookAction,
    val successful: Int,
    val failed: Int,
    val renamed: Int = 0,
    val cleanupPending: Int = 0,
    val cancelled: Boolean = false,
    val issues: Set<BulkBookIssue> = emptySet(),
    val operationId: Long = System.nanoTime(),
)

internal class LibraryViewModel(
    private val repository: LibraryRepository,
    private val layoutSettings: LibraryLayoutSettings,
) : ViewModel() {
    // Start each query on first use and retain its latest result for the lifetime of the ViewModel.
    val books = repository.library.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val history = repository.history.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val recent = repository.recent.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val layout = layoutSettings.layout
    val organization = layoutSettings.organization
    val sortedBooks = combine(books.filterNotNull(), organization.filterNotNull(), ::orderLibrary)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    fun cover(digest: String) = repository.cover(digest)
    fun details(bookId: String) = repository.details(bookId)
    suspend fun renameBook(bookId: String, name: String) = repository.renameBook(bookId, name)
    suspend fun resetReading(bookId: String) = repository.resetReading(bookId)
    suspend fun undoReset(reset: ReadingReset) = repository.undoReset(reset)

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()
    private val _message = MutableStateFlow<Int?>(null)
    val message = _message.asStateFlow()
    private val _selection = MutableStateFlow<LibrarySelection?>(null)
    val selection = _selection.asStateFlow()
    private var nextSelection = 0L
    private data class SelectionRequest(val session: Long, val ids: List<String>)
    private var exportRequest: SelectionRequest? = null
    private var stopExport: AtomicBoolean? = null
    private val _bulkProgress = MutableStateFlow<BulkBookProgress?>(null)
    val bulkProgress = _bulkProgress.asStateFlow()
    private val _bulkReport = MutableStateFlow<BulkBookReport?>(null)
    val bulkReport = _bulkReport.asStateFlow()

    fun startSelection(bookId: String) {
        if (books.value.orEmpty().none { it.book.bookId == bookId && it.available }) return
        _selection.value = LibrarySelection(++nextSelection, setOf(bookId))
    }

    fun clearSelection() {
        _selection.value = null
    }

    fun restrictSelection(availableIds: Set<String>) {
        _selection.update { it?.visible(availableIds) }
    }

    fun toggleSelection(bookId: String, availableIds: Set<String>) {
        _selection.update { it?.toggle(bookId, availableIds) }
    }

    fun toggleAll(availableIds: Set<String>) {
        _selection.update { it?.toggleAll(availableIds) }
    }

    private fun selectedTargets(session: Long, requestedIds: Collection<String>, query: String?): List<String> {
        val selection = _selection.value?.takeIf { it.session == session } ?: return emptyList()
        val requested = requestedIds.toSet().intersect(selection.ids)
        val term = query.orEmpty().trim()
        // Eligibility uses the latest Room emission in case a rename or removal is still being sorted.
        val eligible = books.value.orEmpty().mapNotNullTo(mutableSetOf()) { shelf ->
            shelf.book.bookId.takeIf { it in requested && shelf.available && shelf.book.matchesSearch(term) }
        }
        return sortedBooks.value.orEmpty().mapNotNull { it.shelf.book.bookId.takeIf(eligible::contains) }
    }

    fun requestBulkExport(selection: LibrarySelection, query: String?): Boolean {
        if (_bulkProgress.value != null || exportRequest != null) return false
        val ids = selectedTargets(selection.session, selection.ids, query)
        if (ids.isEmpty()) return false
        exportRequest = SelectionRequest(selection.session, ids)
        return true
    }

    fun exportDirectoryPicked(destination: Uri?, query: String?, defaultName: String) {
        val request = exportRequest
        exportRequest = null
        if (destination == null || request == null || _bulkProgress.value != null) return
        val ids = selectedTargets(request.session, request.ids, query)
        if (ids.isEmpty()) return
        val stop = AtomicBoolean(false)
        stopExport = stop
        _bulkProgress.value = BulkBookProgress(BulkBookAction.Export, ids.size)
        viewModelScope.launch {
            var successful = 0
            var failed = 0
            var renamed = 0
            var cleanupPending = 0
            var cancelled = false
            val issues = mutableSetOf<BulkBookIssue>()
            try {
                val directory = repository.openExportDirectory(destination)
                for (bookId in ids) {
                    try {
                        if (repository.exportBookToDirectory(bookId, directory, defaultName) {
                                if (stop.get()) throw CancellationException("Export cancelled")
                            }) renamed++
                        successful++
                    } catch (error: CancellationException) {
                        coroutineContext.ensureActive()
                        if (error is ExportCancelled && error.cleanupFailed) cleanupPending++
                        cancelled = true
                        break
                    } catch (error: Exception) {
                        failed++
                        if (error is ExportFailure && error.cleanupFailed) cleanupPending++
                        issues += exportIssue(error)
                    }
                    _bulkProgress.update { it?.copy(processed = successful + failed) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = ids.size - successful
                issues += exportIssue(error)
            } finally {
                stopExport = null
                _bulkProgress.value = null
            }
            _bulkReport.value = BulkBookReport(BulkBookAction.Export, successful, failed, renamed, cleanupPending, cancelled, issues)
        }
    }

    fun cancelBulkExport() {
        stopExport?.set(true)
        _bulkProgress.update { if (it?.action == BulkBookAction.Export) it.copy(cancelling = true) else it }
    }

    fun deleteSelection(selection: LibrarySelection, query: String?) {
        if (_bulkProgress.value != null || exportRequest != null) return
        val ids = selectedTargets(selection.session, selection.ids, query)
        if (ids.isEmpty()) {
            reportMessage(R.string.library_bulk_missing)
            return
        }
        _bulkProgress.value = BulkBookProgress(BulkBookAction.Delete, ids.size)
        viewModelScope.launch {
            val deleted = mutableSetOf<String>()
            val issues = mutableSetOf<BulkBookIssue>()
            var pendingCleanup = 0
            var processed = 0
            try {
                for (id in ids) {
                    try {
                        val result = deleteOne(id)
                        if (result == null) {
                            issues += BulkBookIssue.Missing
                        } else {
                            deleted += id
                            if (result.cleanupPending) pendingCleanup++
                            _selection.update { current ->
                                if (current?.session == selection.session) current.copy(ids = current.ids - id) else current
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w("LibraryViewModel", "Could not delete a selected book", error)
                        if (error is DeleteFailure && error.recoveryPending) issues += BulkBookIssue.Recovery
                    }
                    processed++
                    _bulkProgress.update { it?.copy(processed = processed) }
                }
                val failed = ids.size - deleted.size
                if (failed == 0 && pendingCleanup == 0 && _selection.value?.session == selection.session) clearSelection()
                _bulkReport.value = BulkBookReport(BulkBookAction.Delete, deleted.size, failed, cleanupPending = pendingCleanup, issues = issues)
            } finally {
                _bulkProgress.value = null
            }
        }
    }

    private fun exportIssue(error: Exception): BulkBookIssue = when (val cause = if (error is ExportFailure) error.cause else error) {
        is BookFailure -> if (cause.problem == BookProblem.Missing) BulkBookIssue.Missing else BulkBookIssue.Destination
        is SecurityException -> BulkBookIssue.Permissions
        else -> BulkBookIssue.Destination
    }

    fun bulkReportShown(report: BulkBookReport) {
        _bulkReport.compareAndSet(report, null)
    }

    fun reportMessage(message: Int) {
        _message.value = message
    }

    fun exportBook(bookId: String, destination: Uri) = manageBook(R.string.library_export_failed) {
        try {
            repository.exportBook(bookId, destination)
            R.string.library_exported
        } catch (failure: ExportFailure) {
            when {
                failure.cleanupFailed -> R.string.library_export_failed_partial
                failure.cause is BookFailure -> bookProblemMessage((failure.cause as BookFailure).problem)
                else -> R.string.library_export_failed
            }
        }
    }

    fun resetBookProgress(bookId: String) = manageBook(R.string.history_update_failed) {
        repository.resetReading(bookId)
        R.string.library_progress_reset
    }

    fun deleteBook(bookId: String) = manageBook(R.string.library_delete_failed) {
        if (_bulkProgress.value != null) return@manageBook R.string.library_bulk_in_progress
        try {
            val result = deleteOne(bookId)
            if (result == null) {
                R.string.book_missing
            } else {
                if (result.cleanupPending) R.string.library_deleted_pending_cleanup else R.string.library_deleted
            }
        } catch (failure: DeleteFailure) {
            Log.w("LibraryViewModel", "Book deletion failed", failure)
            if (failure.recoveryPending) R.string.library_delete_pending_recovery else R.string.library_delete_failed
        }
    }

    private suspend fun deleteOne(bookId: String): DeleteResult? =
        repository.deleteBook(bookId)?.also { forgetBookCover(repository.cover(it.digest)) }

    private fun manageBook(failureMessage: Int, operation: suspend () -> Int) {
        viewModelScope.launch {
            try {
                reportMessage(operation())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("LibraryViewModel", "Book management operation failed", failure)
                reportMessage(failureMessage)
            }
        }
    }

    fun setViewMode(mode: LibraryViewMode) = saveLayout { layoutSettings.setMode(mode) }

    fun setGridColumns(columns: Int) = saveLayout { layoutSettings.setColumns(columns) }

    fun setDisplayName(mode: BookDisplayName) = saveOrganization { layoutSettings.setDisplayName(mode) }
    fun setSortBy(sortBy: LibrarySortBy) = saveOrganization { layoutSettings.setSortBy(sortBy) }
    fun setSortOrder(order: LibrarySortOrder) = saveOrganization { layoutSettings.setSortOrder(order) }

    private fun saveLayout(change: suspend () -> Unit) {
        savePreference(R.string.library_layout_save_error, change)
    }

    private fun saveOrganization(change: suspend () -> Unit) {
        savePreference(R.string.library_sort_save_error, change)
    }

    private fun savePreference(error: Int, change: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                change()
            } catch (_: IOException) {
                _message.value = error
            }
        }
    }

    fun importBook(uri: Uri) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                _message.value = when (repository.importBook(uri)) {
                    ImportResult.Added -> R.string.book_imported
                    ImportResult.Duplicate -> R.string.book_duplicate
                    ImportResult.Relinked -> R.string.book_relinked
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("LibraryViewModel", "Book import failed", error)
                _message.value = if (error is BookFailure) bookProblemMessage(error.problem) else R.string.book_unreadable
            } finally {
                _importing.value = false
            }
        }
    }

    fun messageShown(message: Int) {
        _message.compareAndSet(message, null)
    }
}
