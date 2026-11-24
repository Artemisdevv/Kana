package com.artemis.ebookreader.feature.reader

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
    var onSelectionAnchor: (ReaderSelectionSession, ReaderPopupAnchor) -> Unit = { _, _ -> }
    var onSelectionEnd: (ReaderSelectionSession) -> Unit = {}

    override fun startActionModeForChild(originalView: View, callback: ActionMode.Callback, type: Int): ActionMode? {
        if (originalView !is WebView || type != ActionMode.TYPE_FLOATING || !canShowSelection()) {
            return super.startActionModeForChild(originalView, callback, type)
        }
        // Keep WebView's action-mode lifecycle and handle geometry. Suppress its menu items so
        // the two-row popup is the only toolbar while this action mode is active.
        // The app now triggers the initial long-press haptic explicitly here.
        val selection = object : ActionMode.Callback2() {
            private var session: ReaderSelectionSession? = null

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (!callback.onCreateActionMode(mode, menu)) return false
                session = ReaderSelectionSession(mode)
                menu.clear()
                originalView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
                if (current.active && !outRect.isEmpty) onSelectionAnchor(current, readerPopupAnchor(view, outRect))
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
