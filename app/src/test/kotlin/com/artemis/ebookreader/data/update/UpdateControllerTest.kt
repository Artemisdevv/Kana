package com.artemis.ebookreader.data.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.artemis.ebookreader.data.preferences.UpdatePreferences
import com.artemis.ebookreader.data.preferences.UpdateSettings
import com.artemis.ebookreader.data.preferences.automaticCheckDue
import com.artemis.ebookreader.feature.settings.InstallNotice
import com.artemis.ebookreader.feature.settings.InstallOutcome
import com.artemis.ebookreader.feature.settings.UpdateController
import com.artemis.ebookreader.feature.settings.UpdateStatus
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateControllerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun defaultAndThrottleSurviveReopeningDataStore() = runBlocking {
        val file = File(folder.newFolder(), "updates.preferences_pb")
        val disabled = File(folder.newFolder(), "updates.preferences_pb")
        val first = Job()
        fun settings(file: File, job: Job) =
            UpdateSettings(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
        try {
            val firstStore = settings(file, first)
            assertTrue(firstStore.automatic.first())
            assertTrue(firstStore.reserveAutomaticCheck(day))
            assertFalse(firstStore.reserveAutomaticCheck(day + 1))
            settings(disabled, first).setAutomatic(false)
        } finally {
            first.cancel()
            first.join()
        }
        val second = Job()
        try {
            val disabledStore = settings(disabled, second)
            assertFalse(disabledStore.automatic.first())
            assertFalse(disabledStore.reserveAutomaticCheck(3 * day))
            assertFalse(settings(file, second).reserveAutomaticCheck(2 * day - 1))
            assertTrue(automaticCheckDue(2 * day, day))
            assertTrue(automaticCheckDue(10, day))
        } finally {
            second.cancel()
            second.join()
        }
    }

    @Test fun coalescesChecksAndLetsManualBypassAnAutomaticReservation() = runBlocking {
        val prefs = FixturePreferences()
        val automaticStarted = CompletableDeferred<Unit>()
        val automaticAllowed = CompletableDeferred<Boolean>()
        prefs.reservation = {
            automaticStarted.complete(Unit)
            automaticAllowed.await()
        }
        val responded = CompletableDeferred<Unit>()
        val networkStarted = CompletableDeferred<Unit>()
        var requests = 0
        val http = FixtureHttp({ _, _ ->
            requests++
            networkStarted.complete(Unit)
            responded.await()
            "[]".toByteArray()
        })
        val controller = controller(prefs, http, this)
        controller.onForeground()
        withTimeout(3_000) { automaticStarted.await() }
        controller.manualCheck()
        controller.manualCheck()
        automaticAllowed.complete(false)
        withTimeout(3_000) { networkStarted.await() }
        controller.onForeground()
        controller.manualCheck()
        responded.complete(Unit)
        withTimeout(3_000) { controller.status.first { it == UpdateStatus.Current } }
        assertEquals(1, requests)
        assertTrue(controller.visible.value)
    }

    @Test fun dismissalSuppressesAutomaticPromptAndInstallCancellationKeepsVerifiedDownload() = runBlocking {
        val prefs = FixturePreferences()
        val http = FixtureHttp({ _, api -> if (api) "[${testReleaseJson()}]".toByteArray() else testApkBytes })
        val controller = controller(prefs, http, this)
        controller.onForeground()
        withTimeout(3_000) { controller.visible.first { it } }
        assertEquals(UpdateStatus.Available(testRelease), controller.status.value)
        controller.dismiss()
        controller.onForeground()
        withTimeout(3_000) {
            prefs.reservations.first { it == 2 }
            while (controller.status.value is UpdateStatus.Checking) delay(1)
        }
        assertFalse(controller.visible.value)
        controller.manualCheck()
        withTimeout(3_000) { controller.status.first { it == UpdateStatus.Available(testRelease) } }
        controller.download(testRelease)
        withTimeout(3_000) { controller.status.first { it is UpdateStatus.Ready } }

        controller.permissionResult(false)
        assertEquals(InstallNotice.PermissionRequired, (controller.status.value as UpdateStatus.Ready).notice)
        controller.permissionResult(false, returnedFromSettings = true)
        assertEquals(InstallNotice.PermissionDenied, (controller.status.value as UpdateStatus.Ready).notice)
        controller.permissionResult(true, returnedFromSettings = true)
        assertNull((controller.status.value as UpdateStatus.Ready).notice)
        assertTrue(checkNotNull(controller.prepareInstall()).isFile)
        controller.installResult(InstallOutcome.Cancelled)
        assertEquals(InstallNotice.Cancelled, (controller.status.value as UpdateStatus.Ready).notice)
        assertTrue(checkNotNull(controller.prepareInstall()).isFile)
        controller.installResult(InstallOutcome.Failed)
        assertEquals(InstallNotice.Failed, (controller.status.value as UpdateStatus.Ready).notice)
    }

    @Test fun cancelsAUniqueDownloadAndReturnsToTheRelease() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        var downloads = 0
        val http = FixtureHttp({ _, api ->
            if (api) "[${testReleaseJson()}]".toByteArray()
            else {
                downloads++
                requested.complete(Unit)
                awaitCancellation()
            }
        })
        val controller = controller(FixturePreferences(), http, this)
        controller.manualCheck()
        withTimeout(3_000) { controller.status.first { it is UpdateStatus.Available } }
        controller.download(testRelease)
        withTimeout(3_000) { requested.await() }
        controller.download(testRelease)
        controller.cancelDownload()
        withTimeout(3_000) { controller.status.first { it is UpdateStatus.Available } }
        assertEquals(1, downloads)
    }

    private fun controller(prefs: UpdatePreferences, http: UpdateHttp, scope: CoroutineScope): UpdateController {
        val packages = FixturePackages()
        return UpdateController(prefs, packages, GithubReleases(http), UpdateFiles(folder.newFolder(), packages, http), scope)
    }

    private class FixturePreferences : UpdatePreferences {
        override val automatic = MutableStateFlow(true)
        val reservations = MutableStateFlow(0)
        var reservation: suspend () -> Boolean = { automatic.value }
        override suspend fun setAutomatic(enabled: Boolean) { automatic.value = enabled }
        override suspend fun reserveAutomaticCheck(now: Long): Boolean {
            reservations.value++
            return reservation()
        }
    }

    private companion object {
        const val day = 86_400_000L
    }
}
