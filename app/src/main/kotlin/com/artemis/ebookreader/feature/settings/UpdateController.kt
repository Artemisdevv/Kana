package com.artemis.ebookreader.feature.settings

import com.artemis.ebookreader.data.preferences.UpdatePreferences
import com.artemis.ebookreader.data.update.GithubReleases
import com.artemis.ebookreader.data.update.KanaRelease
import com.artemis.ebookreader.data.update.UpdateException
import com.artemis.ebookreader.data.update.UpdateFiles
import com.artemis.ebookreader.data.update.UpdatePackageInfo
import com.artemis.ebookreader.data.update.UpdateProblem
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object Current : UpdateStatus
    data class Available(val release: KanaRelease) : UpdateStatus
    data class Downloading(val release: KanaRelease, val bytes: Long = 0, val cancelling: Boolean = false) : UpdateStatus
    data class Ready(val release: KanaRelease, val notice: InstallNotice? = null, val checkProblem: UpdateProblem? = null) : UpdateStatus
    data class PreparingInstall(val release: KanaRelease) : UpdateStatus
    data class Installing(val release: KanaRelease) : UpdateStatus
    data class Failed(val problem: UpdateProblem, val release: KanaRelease? = null) : UpdateStatus
}

internal enum class InstallNotice { PermissionRequired, PermissionDenied, Cancelled, Failed }
internal enum class InstallOutcome { Succeeded, Cancelled, Failed }

// Owned by the application. Entry points are called on main; the HTTP and file layers do their own IO dispatch.
internal class UpdateController(
    private val preferences: UpdatePreferences,
    private val packages: UpdatePackageInfo,
    private val releases: GithubReleases,
    private val files: UpdateFiles,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val installedVersion = packages.installed.versionName ?: packages.installed.versionCode.toString()
    val automatic = preferences.automatic
    private val mutableStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status = mutableStatus.asStateFlow()
    private val mutableVisible = MutableStateFlow(false)
    val visible = mutableVisible.asStateFlow()
    private val dismissed = mutableSetOf<Long>()
    private var recovered = false
    private var ready: KanaRelease? = null
    private var operation: Job? = null
    private var checking = false
    private var manualRequested = false
    private var allowAutomaticPrompt = true
    private var settingsWrite: Job? = null
    private var deferredInstall: InstallOutcome? = null
    private var deferredPermission: Boolean? = null

    fun onForeground() = check(manual = false)
    fun manualCheck() = check(manual = true)

    fun setAutomatic(enabled: Boolean) {
        if (!enabled) allowAutomaticPrompt = false
        settingsWrite?.cancel()
        settingsWrite = scope.launch {
            try {
                preferences.setAutomatic(enabled)
            } catch (_: IOException) {
                if (operation?.isCompleted != false && status.value !is UpdateStatus.PreparingInstall &&
                    status.value !is UpdateStatus.Installing
                ) {
                    mutableStatus.value = ready?.let { UpdateStatus.Ready(it, checkProblem = UpdateProblem.Storage) }
                        ?: UpdateStatus.Failed(UpdateProblem.Storage)
                    mutableVisible.value = true
                }
            }
        }
    }

    fun dismiss() {
        val release = when (val current = status.value) {
            is UpdateStatus.Available -> current.release
            is UpdateStatus.Ready -> current.release
            is UpdateStatus.Failed -> current.release
            else -> null
        }
        release?.let { dismissed.add(it.code) }
        allowAutomaticPrompt = false
        mutableVisible.value = false
    }

    private fun check(manual: Boolean) {
        if (manual) {
            mutableVisible.value = true
            if (checking) {
                manualRequested = true
                allowAutomaticPrompt = false
                return
            }
        }
        if (status.value is UpdateStatus.Installing || status.value is UpdateStatus.PreparingInstall) return
        if (operation?.isCompleted == false) return
        checking = true
        manualRequested = manual
        allowAutomaticPrompt = !manual
        if (manual) mutableStatus.value = UpdateStatus.Checking
        launchOperation {
            try {
                if (!recovered) {
                    ready = files.recover()
                    recovered = true
                    if (ready != null && (deferredInstall != null || deferredPermission != null)) {
                        mutableStatus.value = UpdateStatus.Ready(checkNotNull(ready))
                        deferredInstall?.let(::installResult)
                        deferredPermission?.let { permissionResult(it, returnedFromSettings = true) }
                        mutableVisible.value = true
                    }
                    deferredInstall = null
                    deferredPermission = null
                }
                if (!manualRequested) {
                    if (ready != null) {
                        if (status.value == UpdateStatus.Idle) mutableStatus.value = UpdateStatus.Ready(checkNotNull(ready))
                        return@launchOperation
                    }
                    if (!preferences.reserveAutomaticCheck(clock()) && !manualRequested) return@launchOperation
                }
                mutableStatus.value = UpdateStatus.Checking
                val newer = releases.latest(packages.installed.versionCode, packages.sdk)
                val downloaded = ready
                when {
                    downloaded != null && (newer == null || downloaded.code >= newer.code) ->
                        mutableStatus.value = UpdateStatus.Ready(downloaded)
                    newer != null -> {
                        mutableStatus.value = UpdateStatus.Available(newer)
                        if (allowAutomaticPrompt && newer.code !in dismissed) mutableVisible.value = true
                    }
                    else -> mutableStatus.value = UpdateStatus.Current
                }
            } catch (error: IOException) {
                val problem = (error as? UpdateException)?.problem ?: UpdateProblem.Network
                mutableStatus.value = ready?.let { UpdateStatus.Ready(it, checkProblem = problem) }
                    ?: UpdateStatus.Failed(problem)
            } finally {
                checking = false
            }
        }
    }

    fun download(release: KanaRelease) {
        val current = status.value
        if (current != UpdateStatus.Available(release) && (current as? UpdateStatus.Failed)?.release != release) return
        launchOperation {
            mutableStatus.value = UpdateStatus.Downloading(release)
            try {
                files.download(release) { bytes ->
                    mutableStatus.update { status ->
                        if (status is UpdateStatus.Downloading && !status.cancelling) status.copy(bytes = bytes) else status
                    }
                }
                ready = release
                mutableStatus.value = UpdateStatus.Ready(release)
            } catch (cancelled: CancellationException) {
                mutableStatus.value = UpdateStatus.Available(release)
                throw cancelled
            } catch (error: IOException) {
                mutableStatus.value = UpdateStatus.Failed((error as? UpdateException)?.problem ?: UpdateProblem.Network, release)
            }
        }
    }

    fun cancelDownload() {
        val pending = status.value as? UpdateStatus.Downloading ?: return
        mutableStatus.value = pending.copy(cancelling = true)
        operation?.cancel()
    }

    fun permissionResult(allowed: Boolean, returnedFromSettings: Boolean = false) {
        if (!recovered && returnedFromSettings) deferredPermission = allowed
        val current = status.value as? UpdateStatus.Ready ?: return
        val notice = when {
            allowed -> null
            returnedFromSettings -> InstallNotice.PermissionDenied
            else -> InstallNotice.PermissionRequired
        }
        mutableStatus.value = current.copy(notice = notice)
    }

    suspend fun prepareInstall(): File? {
        if (operation?.isCompleted == false) return null
        val current = status.value as? UpdateStatus.Ready ?: return null
        mutableStatus.value = UpdateStatus.PreparingInstall(current.release)
        try {
            val file = files.installable(current.release)
            mutableStatus.value = UpdateStatus.Installing(current.release)
            return file
        } catch (error: IOException) {
            ready = null
            mutableStatus.value = UpdateStatus.Failed((error as? UpdateException)?.problem ?: UpdateProblem.InvalidApk, current.release)
            return null
        } finally {
            if (status.value is UpdateStatus.PreparingInstall) mutableStatus.value = current
        }
    }

    fun installResult(result: InstallOutcome) {
        if (!recovered) deferredInstall = result
        val current = status.value
        val release = when (current) {
            is UpdateStatus.Installing -> current.release
            is UpdateStatus.Ready -> current.release
            else -> return
        }
        when (result) {
            InstallOutcome.Succeeded -> {
                ready = null
                mutableVisible.value = false
                mutableStatus.value = UpdateStatus.Idle
            }
            InstallOutcome.Cancelled -> mutableStatus.value = UpdateStatus.Ready(release, InstallNotice.Cancelled)
            InstallOutcome.Failed -> mutableStatus.value = UpdateStatus.Ready(release, InstallNotice.Failed)
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (operation?.isCompleted == false) return
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        operation = job
        job.start()
    }
}
