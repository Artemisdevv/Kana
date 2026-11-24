package com.artemis.ebookreader.feature.reader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal fun View.logReaderHaptic(message: () -> String) {
    val tag = "KanaReaderHaptics"
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && Log.isLoggable(tag, Log.DEBUG)) {
        Log.d(tag, message())
    }
}

internal class ReaderSelectionSession(private val mode: ActionMode) {
    var active: Boolean = true
        private set

    fun end() { active = false }

    fun finish(): Boolean {
        if (!active) return false
        mode.finish()
        return true
    }
}

internal data class ReaderPopupAnchor(val passage: Rect, val safeArea: Rect)

internal fun View.readerPopupAnchor(source: View, localRect: Rect): ReaderPopupAnchor {
    val rootPosition = IntArray(2).also(::getLocationInWindow)
    val sourcePosition = IntArray(2).also(source::getLocationInWindow)
    val passage = Rect(localRect).apply { offset(sourcePosition[0], sourcePosition[1]) }
    val bars = ViewCompat.getRootWindowInsets(this)
        ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    val safeArea = Rect(
        rootPosition[0] + (bars?.left ?: 0),
        rootPosition[1] + (bars?.top ?: 0),
        rootPosition[0] + width - (bars?.right ?: 0),
        rootPosition[1] + height - (bars?.bottom ?: 0),
    )
    return ReaderPopupAnchor(passage, safeArea)
}

internal class ReaderSelectionLayout(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    var canShowSelection: () -> Boolean = { false }
    var hasSelection: () -> Boolean = { false }
    var onInitialSelection: (WebView) -> Unit = {}
    var onSelectionAnchor: (ReaderSelectionSession, ReaderPopupAnchor) -> Unit = { _, _ -> }
    var onSelectionEnd: (ReaderSelectionSession) -> Unit = {}

    private data class SelectionPress(val x: Float, val y: Float, val downTime: Long) {
        var upTime: Long? = null
        var deepPress = false

        fun isLongPress(time: Long) = deepPress || time - downTime >= ViewConfiguration.getLongPressTimeout()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var selectionPress: SelectionPress? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Observe the press without handling it. WebView still decides what gets selected.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tool = event.getToolType(0)
                selectionPress = if (
                    (tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_STYLUS) &&
                    canShowSelection() && !hasSelection()
                ) {
                    SelectionPress(event.x, event.y, event.downTime)
                } else null
            }
            MotionEvent.ACTION_MOVE -> selectionPress?.let { press ->
                if (Build.VERSION.SDK_INT >= 29 && event.classification == MotionEvent.CLASSIFICATION_DEEP_PRESS) {
                    press.deepPress = true
                }
                val dx = event.x - press.x
                val dy = event.y - press.y
                if (!press.isLongPress(event.eventTime) && dx * dx + dy * dy > touchSlop * touchSlop) {
                    selectionPress = null
                }
            }
            MotionEvent.ACTION_UP -> selectionPress?.let { press ->
                if (press.isLongPress(event.eventTime)) press.upTime = event.eventTime else selectionPress = null
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> selectionPress = null
        }
        return super.dispatchTouchEvent(event)
    }

    private fun consumeSelectionPress(): Boolean {
        val press = selectionPress ?: return false
        selectionPress = null
        val now = SystemClock.uptimeMillis()
        // WebView may start the action mode just after the long-press finger lifts.
        val upTime = press.upTime
        return press.isLongPress(upTime ?: now) &&
            (upTime == null || now - upTime <= ViewConfiguration.getDoubleTapTimeout())
    }

    override fun startActionModeForChild(originalView: View, callback: ActionMode.Callback, type: Int): ActionMode? {
        if (originalView !is WebView || type != ActionMode.TYPE_FLOATING) {
            return super.startActionModeForChild(originalView, callback, type)
        }
        if (!canShowSelection()) {
            logReaderHaptic { "selection mode=passed_through webViewEnabled=${originalView.isHapticFeedbackEnabled}" }
            return super.startActionModeForChild(originalView, callback, type)
        }
        // Keep WebView's action-mode lifecycle and handle geometry. Suppress its menu items so
        // the two-row popup is the only toolbar while this action mode is active.
        val selection = object : ActionMode.Callback2() {
            private var session: ReaderSelectionSession? = null
            private var initialPress = false

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (!callback.onCreateActionMode(mode, menu)) {
                    logReaderHaptic { "selection mode=rejected" }
                    return false
                }
                session = ReaderSelectionSession(mode)
                initialPress = consumeSelectionPress()
                logReaderHaptic {
                    "selection mode=created initialPress=$initialPress webViewEnabled=${originalView.isHapticFeedbackEnabled}"
                }
                menu.clear()
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                callback.onPrepareActionMode(mode, menu)
                menu.clear()
                onGetContentRect(mode, originalView, Rect())
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                callback.onActionItemClicked(mode, item)

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect)
                else super.onGetContentRect(mode, view, outRect)
                val current = session ?: return
                if (!current.active || outRect.isEmpty) return
                onSelectionAnchor(current, readerPopupAnchor(view, outRect))
                if (initialPress) {
                    initialPress = false
                    onInitialSelection(originalView)
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                session?.let {
                    it.end()
                    onSelectionEnd(it)
                }
                session = null
                callback.onDestroyActionMode(mode)
            }
        }
        return super.startActionModeForChild(originalView, selection, type)
    }
}
