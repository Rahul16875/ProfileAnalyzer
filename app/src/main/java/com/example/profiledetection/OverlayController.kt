package com.example.profiledetection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Owns the two overlay windows added to the WindowManager:
 *  - a small always-present bubble (tap to analyze, drag to move)
 *  - a full-screen panel that shows the suggestions (touchable only while open)
 *
 * Both host Compose via ComposeView with hand-attached lifecycle owners.
 */
class OverlayController(
    private val context: Context,
    private val onAnalyze: (Mode, twist: String?) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val state = OverlayState()
    private var lastMode = Mode.PROFILE

    private val bubbleOwner = OverlayLifecycleOwner()
    private val panelOwner = OverlayLifecycleOwner()

    private var bubbleView: ComposeView? = null
    private var panelView: ComposeView? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private val overlayType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    fun show() {
        addPanel()   // behind
        addBubble()  // in front
    }

    fun destroy() {
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        bubbleOwner.onDestroy()
        panelOwner.onDestroy()
        bubbleView = null
        panelView = null
    }

    // ---- State transitions ----

    /** Bubble tap → show the "Openers vs Reply" picker. */
    private fun showChooser() {
        state.loading = false
        state.error = null
        state.suggestions = emptyList()
        state.showChooser = true
        state.panelOpen = true
        refreshPanelTouchability()
    }

    private fun choose(mode: Mode) {
        lastMode = mode
        state.showChooser = false
        onAnalyze(mode, null)
    }

    /** Re-run the last mode with a tweak ("funnier", "bolder", …) or null to just regenerate. */
    private fun reroll(twist: String?) {
        onAnalyze(lastMode, twist)
    }

    fun setLoading() {
        state.error = null
        state.suggestions = emptyList()
        state.showChooser = false
        state.loading = true
        state.panelOpen = true
        refreshPanelTouchability()
    }

    fun showSuggestions(suggestions: List<Suggestion>) {
        state.loading = false
        state.error = null
        state.suggestions = suggestions
        state.panelOpen = true
        refreshPanelTouchability()
    }

    fun showError(message: String) {
        state.loading = false
        state.error = message
        state.panelOpen = true
        refreshPanelTouchability()
    }

    private fun closePanel() {
        state.panelOpen = false
        refreshPanelTouchability()
    }

    /**
     * Hide the bubble AND close the panel before capturing, so neither appears in the
     * screenshots and the panel window stops intercepting touches (swipes reach the app).
     */
    fun prepareForCapture() {
        state.loading = false
        state.showChooser = false
        state.panelOpen = false
        refreshPanelTouchability()
        bubbleView?.visibility = View.INVISIBLE
    }

    fun restoreBubbleAfterCapture() {
        bubbleView?.visibility = View.VISIBLE
    }

    // ---- Window setup ----

    private fun addBubble() {
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }

        bubbleOwner.onCreate()
        val view = ComposeView(context).apply {
            attachOwners(bubbleOwner)
            setContent {
                BubbleContent(
                    loading = state.loading,
                    onTap = { showChooser() },
                    onDrag = { dx, dy ->
                        bubbleParams.x += dx.toInt()
                        bubbleParams.y += dy.toInt()
                        runCatching { windowManager.updateViewLayout(this, bubbleParams) }
                    },
                )
            }
        }
        bubbleView = view
        windowManager.addView(view, bubbleParams)
    }

    private fun addPanel() {
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            // Closed by default: NOT_TOUCHABLE lets all touches fall through to the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )

        panelOwner.onCreate()
        val view = ComposeView(context).apply {
            attachOwners(panelOwner)
            setContent {
                PanelContent(
                    state = state,
                    onClose = { closePanel() },
                    onChoose = { choose(it) },
                    onRegenerate = { reroll(null) },
                    onReroll = { twist -> reroll(twist) },
                    onCopy = { copyToClipboard(it.message) },
                )
            }
        }
        panelView = view
        windowManager.addView(view, panelParams)
    }

    /** Make the panel window intercept touches only while it's open, and fully hide it when closed. */
    private fun refreshPanelTouchability() {
        val view = panelView ?: return
        // GONE guarantees nothing is drawn when closed (no lingering scrim/sliver).
        view.visibility = if (state.panelOpen) View.VISIBLE else View.GONE
        panelParams.flags = if (state.panelOpen) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, panelParams) }
    }

    private fun ComposeView.attachOwners(owner: OverlayLifecycleOwner) {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        closePanel()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
