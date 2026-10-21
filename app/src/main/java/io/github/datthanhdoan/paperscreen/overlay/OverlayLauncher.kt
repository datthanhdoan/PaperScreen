package io.github.datthanhdoan.paperscreen.overlay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import io.github.datthanhdoan.paperscreen.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide scope for fire-and-forget writes that must outlive a service or tile. */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * Starts / stops [OverlayService]. Starting from the background (boot, package update,
 * possibly a QS tile click) may throw ForegroundServiceStartNotAllowedException. Since
 * Android 15 a SYSTEM_ALERT_WINDOW app is only exempt while it already shows an overlay
 * window, so the fallback adds a 1x1 transparent overlay first, waits until it is
 * visible and retries (section A5 / C2).
 */
object OverlayLauncher {

    private const val TAG = "OverlayLauncher"
    private const val BOOTSTRAP_TIMEOUT_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private var bootstrap: WindowOverlayHost? = null

    enum class Result { STARTED, PENDING, NO_PERMISSION, FAILED }

    /** Must be called on the main thread. */
    fun start(context: Context): Result {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return Result.NO_PERMISSION
        if (tryStart(app)) return Result.STARTED
        return if (startWithBootstrapWindow(app)) Result.PENDING else Result.FAILED
    }

    /** Persists "off" and stops the service. */
    fun disable(context: Context) {
        val app = context.applicationContext
        appScope.launch { SettingsRepository.get(app).setEnabled(false) }
        app.stopService(Intent(app, OverlayService::class.java))
    }

    /** Called by the service once its own overlay is on screen. */
    internal fun onServiceOverlayShown() {
        removeBootstrap()
    }

    private fun tryStart(context: Context): Boolean = try {
        context.startForegroundService(OverlayService.startIntent(context))
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
        Log.w(TAG, "startForegroundService refused: ${e.message}")
        false
    }

    private fun startWithBootstrapWindow(context: Context): Boolean {
        if (bootstrap != null) return true
        val windowContext = overlayWindowContext(context)
        val view = object : View(windowContext) {
            private var fired = false
            override fun onWindowVisibilityChanged(visibility: Int) {
                super.onWindowVisibilityChanged(visibility)
                if (visibility != VISIBLE || fired) return
                fired = true
                // Let the window reach the compositor before asking for the exemption.
                post {
                    if (!tryStart(context)) {
                        Log.w(TAG, "Start refused even with a visible overlay window")
                        removeBootstrap()
                    }
                }
            }
        }
        val host = WindowOverlayHost(windowContext, view, overlayLayoutParams(windowContext, fullScreen = false))
        if (!host.attach()) return false
        bootstrap = host
        main.postDelayed(::removeBootstrap, BOOTSTRAP_TIMEOUT_MS)
        return true
    }

    private fun removeBootstrap() {
        main.removeCallbacksAndMessages(null)
        bootstrap?.detach()
        bootstrap = null
    }
}
