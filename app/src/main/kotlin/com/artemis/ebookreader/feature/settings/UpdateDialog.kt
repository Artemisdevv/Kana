package com.artemis.ebookreader.feature.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.artemis.ebookreader.R
import com.artemis.ebookreader.data.update.UpdateProblem

@Composable
internal fun UpdateDialog(updates: UpdateController, install: () -> Unit, openPermission: () -> Unit) {
    val visible by updates.visible.collectAsStateWithLifecycle()
    val status by updates.status.collectAsStateWithLifecycle()
    if (!visible) return
    val current = status
    val title: Int
    var confirm: DialogAction? = null
    var dismiss = R.string.close
    var canDismiss = true
    when (current) {
        UpdateStatus.Idle, UpdateStatus.Checking -> title = R.string.update_checking
        UpdateStatus.Current -> title = R.string.update_current
        is UpdateStatus.Available -> {
            title = R.string.update_available
            confirm = DialogAction(R.string.update_download) { updates.download(current.release) }
            dismiss = R.string.update_later
        }
        is UpdateStatus.Downloading -> {
            title = R.string.update_downloading
            canDismiss = false
            if (!current.cancelling) confirm = DialogAction(R.string.update_cancel_download, updates::cancelDownload)
        }
        is UpdateStatus.Ready -> {
            title = R.string.update_download_complete
            val needsPermission = current.notice == InstallNotice.PermissionRequired ||
                current.notice == InstallNotice.PermissionDenied
            confirm = if (needsPermission) DialogAction(R.string.update_open_settings, openPermission)
            else DialogAction(R.string.update_install, install)
            dismiss = R.string.update_later
        }
        is UpdateStatus.PreparingInstall -> {
            title = R.string.update_verifying
            canDismiss = false
        }
        is UpdateStatus.Installing -> {
            title = R.string.update_installing
            canDismiss = false
        }
        is UpdateStatus.Failed -> {
            title = R.string.update_failed
            confirm = DialogAction(R.string.update_retry) {
                val retry = current.release
                if (retry != null) updates.download(retry) else updates.manualCheck()
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (canDismiss) updates.dismiss() },
        title = { Text(stringResource(title)) },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (current) {
                    UpdateStatus.Idle, UpdateStatus.Checking, is UpdateStatus.PreparingInstall, is UpdateStatus.Installing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    UpdateStatus.Current -> Text(stringResource(R.string.update_installed, updates.installedVersion))
                    is UpdateStatus.Available -> {
                        val size = Formatter.formatFileSize(LocalContext.current, current.release.size)
                        Text(stringResource(R.string.update_version, current.release.version))
                        Text(stringResource(R.string.update_installed, updates.installedVersion))
                        Text(stringResource(R.string.update_size, size))
                        Text(stringResource(R.string.update_notes), style = MaterialTheme.typography.titleSmall)
                        Text(current.release.notes.ifBlank { stringResource(R.string.update_no_notes) })
                    }
                    is UpdateStatus.Downloading -> {
                        val fraction = (current.bytes.toDouble() / current.release.size).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(if (current.cancelling) stringResource(R.string.update_cancelling)
                            else stringResource(R.string.update_progress, (fraction * 100).toInt()))
                    }
                    is UpdateStatus.Ready -> {
                        Text(stringResource(R.string.update_install_required, current.release.version))
                        current.notice?.let { Text(stringResource(it.label)) }
                        current.checkProblem?.let { Text(stringResource(it.label)) }
                    }
                    is UpdateStatus.Failed -> Text(stringResource(current.problem.label))
                }
            }
        },
        confirmButton = {
            val action = confirm
            if (action != null) TextButton(onClick = action.run) { Text(stringResource(action.label)) }
            else if (canDismiss) TextButton(onClick = updates::dismiss) { Text(stringResource(dismiss)) }
        },
        dismissButton = if (canDismiss && confirm != null) {
            { TextButton(onClick = updates::dismiss) { Text(stringResource(dismiss)) } }
        } else null,
    )
}

private data class DialogAction(@param:StringRes val label: Int, val run: () -> Unit)

private val InstallNotice.label: Int get() = when (this) {
    InstallNotice.PermissionRequired -> R.string.update_permission_required
    InstallNotice.PermissionDenied -> R.string.update_permission_denied
    InstallNotice.Cancelled -> R.string.update_install_cancelled
    InstallNotice.Failed -> R.string.update_install_error
}

private val UpdateProblem.label: Int get() = when (this) {
    UpdateProblem.Network -> R.string.update_network_error
    UpdateProblem.RateLimited -> R.string.update_rate_limit
    UpdateProblem.ReleaseMetadata -> R.string.update_metadata_error
    UpdateProblem.Storage -> R.string.update_storage_error
    UpdateProblem.Incomplete -> R.string.update_incomplete
    UpdateProblem.InvalidApk -> R.string.update_invalid_apk
    UpdateProblem.Signing -> R.string.update_signing_error
    UpdateProblem.Digest -> R.string.update_digest_error
}
