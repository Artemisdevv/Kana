package com.artemis.ebookreader.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artemis.ebookreader.data.library.CacheCleanup
import com.artemis.ebookreader.data.library.IntegrityIssue
import com.artemis.ebookreader.data.library.LibraryRepository
import com.artemis.ebookreader.data.library.StorageOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class StorageTask { Refresh, Inspect, Clear }

internal data class StorageState(
    val overview: StorageOverview? = null,
    val busy: StorageTask? = null,
    val failed: StorageTask? = null,
    val issues: List<IntegrityIssue>? = null,
    val cleanup: CacheCleanup? = null,
)

internal class StorageViewModel(private val repository: LibraryRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(StorageState())
    val state = mutableState.asStateFlow()

    fun refresh() = runTask(StorageTask.Refresh) {
        val result = repository.storageReport()
        mutableState.update { it.copy(overview = result.overview) }
    }

    fun inspect() = runTask(StorageTask.Inspect) {
        val result = repository.storageReport(checkIntegrity = true)
        mutableState.update { it.copy(overview = result.overview, issues = result.issues) }
    }

    fun clear() = runTask(StorageTask.Clear) {
        val result = repository.clearStorageCache()
        mutableState.update { it.copy(cleanup = result, issues = null) }
        try {
            val overview = repository.storageReport().overview
            mutableState.update { it.copy(overview = overview) }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w("StorageViewModel", "Could not refresh storage after cleanup", failure)
            mutableState.update { it.copy(overview = null, failed = StorageTask.Refresh) }
        }
    }

    fun dismissIssues() = mutableState.update { it.copy(issues = null) }
    fun dismissCleanup() = mutableState.update { it.copy(cleanup = null) }

    private fun runTask(task: StorageTask, action: suspend () -> Unit) {
        if (mutableState.value.busy != null) return
        mutableState.update {
            it.copy(busy = task, failed = null, issues = null, cleanup = if (task == StorageTask.Clear) null else it.cleanup)
        }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("StorageViewModel", "Storage operation failed", failure)
                mutableState.update { it.copy(failed = task) }
            } finally {
                mutableState.update { it.copy(busy = null) }
            }
        }
    }
}
