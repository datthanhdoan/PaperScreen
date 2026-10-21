package io.github.datthanhdoan.paperscreen.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams

/**
 * Owns the overlay window. Kept separate from rendering so a v2 accessibility-overlay
 * mode can provide another host around the same [PaperView] (section 3.1).
 */
interface OverlayHost {
    val isAttached: Boolean
    fun attach(): Boolean
    fun detach()
}

class WindowOverlayHost(
    private val windowContext: Context,
    private val view: View,
    private val params: LayoutParams = overlayLayoutParams(windowContext),
) : OverlayHost {

    private val windowManager = windowContext.getSystemService(WindowManager::class.java)

    override var isAttached = false
        private set

    override fun attach(): Boolean {
        if (isAttached) return true
        return try {
            windowManager.addView(view, params)
            isAttached = true
            true
        } catch (e: RuntimeException) {
            // BadTokenException / SecurityException when the overlay permission is missing.
            Log.w(TAG, "addView failed", e)
            false
        }
    }

    override fun detach() {
        if (!isAttached) return
        isAttached = false
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "removeView failed", e)
        }
    }

    private companion object {
        const val TAG = "WindowOverlayHost"
    }
}

/**
 * On API 30+ windows are added through a window context bound to the display, so the
 * view sees that display's Configuration and metrics (section 3.2).
 */
fun overlayWindowContext(context: Context): Context {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return context
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    return context.createDisplayContext(display)
        .createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null)
}

/**
 * Touches only pass through a SYSTEM_ALERT_WINDOW overlay when its *window* alpha is at
 * most the maximum obscuring opacity (0.8 by default), section A1/A2.
 */
fun overlayWindowAlpha(context: Context): Float {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return MAX_TOUCH_PASSTHROUGH_ALPHA
    val max = context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
    return minOf(MAX_TOUCH_PASSTHROUGH_ALPHA, max).coerceAtLeast(0.05f)
}

private const val MAX_TOUCH_PASSTHROUGH_ALPHA = 0.8f

/** Section 3.2 of docs/feasibility-report.md. */
fun overlayLayoutParams(context: Context, fullScreen: Boolean = true): LayoutParams {
    val size = if (fullScreen) LayoutParams.MATCH_PARENT else 1
    return LayoutParams(
        size,
        size,
        LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_TOUCHABLE or
            LayoutParams.FLAG_NOT_FOCUSABLE or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        alpha = overlayWindowAlpha(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            fitInsetsSides = 0
        }
        gravity = Gravity.TOP or Gravity.START
        title = "PaperScreen"
    }
}
