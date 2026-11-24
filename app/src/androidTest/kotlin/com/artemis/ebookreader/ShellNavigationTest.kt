package com.artemis.ebookreader

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.artemis.ebookreader.app.MainActivity
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    @Test
    fun rapidPrimaryNavigationReturnsToSettingsLandingPage() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        try {
            clickTab("Settings")
            val preferences = awaitNode { it.isClickable && it.hasLabel("Reading preferences") }
            assertTrue(preferences.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.text?.toString() == "Reader Theme" }

            val tabs = listOf("Home", "Library", "History", "Settings").map { label ->
                val tab = awaitNode("tab $label") { (it.isClickable || it.isSelected) && it.hasLabel(label) }
                Rect().also(tab::getBoundsInScreen)
            }
            tabs.forEach(::tap)
            awaitNode { it.isSelected && it.hasLabel("Settings") }
            awaitNode { it.text?.toString() == "Kana" }
            automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            awaitNode { it.text?.toString() == "Kana" }
            automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            awaitNode("History selected after Back") { it.isSelected && it.hasLabel("History") }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun recreationRetainsSettingsAndBackReturnsToHistory() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        clickTab("History")
        awaitNode { it.isSelected && it.hasLabel("History") }
        clickTab("Settings")
        awaitNode { it.isSelected && it.hasLabel("Settings") }
        awaitNode { it.text?.toString() == "Kana" }
        val readingPreferences = awaitNode { it.isClickable && it.hasLabel("Reading preferences") }
        assertNull(automation.rootInActiveWindow?.find { it.text?.toString() == "Fullscreen Reading" })
        assertTrue(readingPreferences.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitNode { it.text?.toString() == "Reader Theme" }
        awaitNode { it.text?.toString() == "Fullscreen Reading" }
        awaitNode { it.text?.toString() == "Font size" }
        assertNull(automation.rootInActiveWindow?.find { it.isClickable && it.hasLabel("Text") })

        instrumentation.runOnMainSync { activity.recreate() }
        awaitNode { it.isSelected && it.hasLabel("Settings") }
        awaitNode { it.text?.toString() == "Reader Theme" }

        automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        awaitNode { it.isClickable && it.hasLabel("Reading preferences") }
        val libraryPreferences = awaitNode { it.isClickable && it.hasLabel("Library preferences") }
        assertTrue(libraryPreferences.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitNode { it.text?.toString() == "Title (A–Z)" }
        automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        awaitNode { it.text?.toString() == "Kana" }
        automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        awaitNode { it.isSelected && it.hasLabel("History") }
    }

    @Test
    fun updatesAndAboutAreSubpagesAndLicensesReturnToAbout() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        try {
            clickTab("Settings")
            awaitNode { it.text?.toString() == "Preferences" }
            val landing = automation.rootInActiveWindow
            for (label in listOf("Check for updates", "Automatic update checks", "GitHub", "Report an issue", "Open-source licenses")) {
                assertNull(landing?.find { it.text?.toString() == label })
            }

            clickSettingsRow("Updates", "Preferences", "Data & storage", "General")
            awaitNode { it.text?.toString() == "Installed version" }
            awaitNode { it.isClickable && it.hasLabel("Check for updates") }
            awaitNode { it.isCheckable && it.hasLabel("Automatic update checks") }
            clickSettingsBack()

            clickSettingsRow("About Kana", "Preferences", "Data & storage", "General")
            awaitNode { it.text?.toString() == "Free and open-source EPUB reader for Android." }
            clickSettingsRow("Open-source licenses", "Application information", "Links", "License")
            awaitNode { it.text?.toString() == "Core libraries" }
            automation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            clickSettingsRow("Open-source licenses", "Application information", "Links", "License")
            awaitNode { it.text?.toString() == "Core libraries" }
            clickSettingsBack()
            awaitNode { it.isClickable && it.hasLabel("Open-source licenses") }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun clickSettingsBack() {
        val back = instrumentation.targetContext.getString(R.string.navigate_back)
        val button = awaitNode { it.isClickable && it.contentDescription?.toString() == back }
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun clickSettingsRow(label: String, vararg groups: String) {
        val deadline = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val row = root?.find { it.isVisibleToUser && it.isClickable && it.hasLabel(label) }
            if (row != null && row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            val scroll = root?.find { node -> node.isScrollable && groups.any { node.hasLabel(it) } }
            scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(350)
        }
        throw AssertionError("Settings row $label was not reachable")
    }

    private fun clickTab(label: String) {
        val tab = awaitNode { it.isClickable && it.hasLabel(label) }
        val bounds = Rect().also(tab::getBoundsInScreen)
        tap(bounds)
    }

    private fun tap(bounds: Rect) {
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun awaitNode(
        description: String = "navigation state",
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < deadline) {
            automation.rootInActiveWindow?.find(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        val selected = automation.rootInActiveWindow?.find(AccessibilityNodeInfo::isSelected)
        val selectedLabel = selected?.find { it.text != null }?.text
        throw AssertionError("Expected $description was not visible. Selected label: $selectedLabel")
    }

    private fun AccessibilityNodeInfo.hasLabel(label: String): Boolean =
        find { it.text?.toString() == label } != null

    private fun AccessibilityNodeInfo.find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(this)) return this
        for (index in 0 until childCount) {
            getChild(index)?.find(predicate)?.let { return it }
        }
        return null
    }
}
