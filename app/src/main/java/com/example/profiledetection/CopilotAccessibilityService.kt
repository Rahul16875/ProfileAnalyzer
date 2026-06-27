package com.example.profiledetection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The brains of the copilot. On a bubble tap it:
 *  1. hides our overlays (so they aren't in the shots and don't block gestures),
 *  2. scrolls through the whole profile, taking a clean screenshot of each screen,
 *  3. sends all the screenshots to Gemini at once, and
 *  4. shows the openers.
 *
 * One tap = one Gemini call (with several images attached).
 */
class CopilotAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: OverlayController? = null
    private var capturing = false

    // Lines suggested earlier this session, so re-rolls/regenerates don't repeat them.
    private val recentMessages = ArrayDeque<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(
            context = this,
            onAnalyze = { mode, twist -> onAnalyzeRequested(mode, twist) },
        ).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        overlay?.destroy()
        overlay = null
        scope.cancel()
    }

    private fun onAnalyzeRequested(mode: Mode, twist: String?) {
        if (capturing) return
        val controller = overlay ?: return

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            controller.showError(
                "No API key. Add GEMINI_API_KEY to local.properties and rebuild the app."
            )
            return
        }

        capturing = true
        scope.launch {
            try {
                // 1. Hide our overlays so screenshots are clean and swipes reach the app.
                controller.prepareForCapture()
                delay(180) // let the bubble actually disappear from the rendered frame

                // 2. Scroll back to the top first (in case we started mid/bottom of the screen).
                scrollToTop()

                // 3. Scroll through the whole screen (profile or chat), capturing each frame.
                val shots = captureProfile()
                if (shots.isEmpty()) {
                    controller.showError("Couldn't read the screen. Try again.")
                    return@launch
                }

                // 4. Now it's safe to show the loading panel and call the model.
                controller.setLoading()
                Log.d(TAG, "Captured ${shots.size} screenshot(s); sending to model")
                val suggestions = GeminiClient.generateSuggestions(
                    jpegShots = shots,
                    apiKey = apiKey,
                    mode = mode,
                    twist = twist,
                    avoid = recentMessages.toList(),
                )
                rememberMessages(suggestions)
                controller.showSuggestions(suggestions)
            } catch (e: Exception) {
                Log.e(TAG, "analyze failed", e)
                controller.showError(e.message ?: "Something went wrong.")
            } finally {
                controller.restoreBubbleAfterCapture()
                capturing = false
            }
        }
    }

    /** Keep the last ~25 suggested lines so future runs don't repeat them. */
    private fun rememberMessages(suggestions: List<Suggestion>) {
        suggestions.forEach { recentMessages.addLast(it.message) }
        while (recentMessages.size > 25) recentMessages.removeFirst()
    }

    /**
     * Capture the current screen, then swipe up and capture again, repeating until the
     * screen stops changing (bottom of profile) or [MAX_SHOTS] is reached.
     */
    private suspend fun captureProfile(): List<ByteArray> {
        val shots = ArrayList<ByteArray>()
        var previous = captureOne()
        if (previous != null) shots.add(previous)

        var taken = 1
        while (taken < MAX_SHOTS) {
            if (!swipeUp()) break
            delay(700) // let the scroll settle + respect the screenshot rate limit
            val shot = captureOne() ?: break
            // If the frame is identical to the last, the profile didn't scroll → we're done.
            if (previous != null && shot.contentEquals(previous)) break
            shots.add(shot)
            previous = shot
            taken++
        }
        return shots
    }

    /**
     * Scroll up to the top of the profile before capturing, so we always start from the
     * first photo regardless of where the user tapped. Screenshots here are local/free
     * (no API cost) — used only to detect when scrolling stops (top reached).
     */
    private suspend fun scrollToTop() {
        var previous = captureOne()
        var guard = 0
        while (guard < MAX_SCROLL_STEPS) {
            if (!swipeDown()) break
            delay(550)
            val current = captureOne()
            if (current != null && previous != null && current.contentEquals(previous)) break
            previous = current
            guard++
        }
    }

    private suspend fun captureOne(): ByteArray? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (cont.isActive) cont.resume(toJpeg(screenshot))
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    /** Swipe up = scroll the profile DOWN (toward the bottom). */
    private suspend fun swipeUp(): Boolean = swipe(fromFraction = 0.72f, toFraction = 0.28f)

    /** Swipe down = scroll the profile UP (toward the top). */
    private suspend fun swipeDown(): Boolean = swipe(fromFraction = 0.28f, toFraction = 0.72f)

    private suspend fun swipe(fromFraction: Float, toFraction: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val metrics = resources.displayMetrics
            val x = metrics.widthPixels / 2f
            val path = Path().apply {
                moveTo(x, metrics.heightPixels * fromFraction)
                lineTo(x, metrics.heightPixels * toFraction)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
                .build()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(d: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    private fun toJpeg(screenshot: ScreenshotResult): ByteArray? {
        var buffer: HardwareBuffer? = null
        var bitmap: Bitmap? = null
        return try {
            buffer = screenshot.hardwareBuffer
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                ?: return null
            bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            hardwareBitmap.recycle()
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "toJpeg failed", e)
            null
        } finally {
            bitmap?.recycle()
            buffer?.close()
        }
    }

    companion object {
        private const val TAG = "ProfileCopilot"
        private const val MAX_SHOTS = 6        // max screenshots sent to Gemini (downward pass)
        private const val MAX_SCROLL_STEPS = 8 // max swipes used to reach the top first
    }
}
