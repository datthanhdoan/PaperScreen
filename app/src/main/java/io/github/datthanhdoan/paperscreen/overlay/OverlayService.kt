package io.github.datthanhdoan.paperscreen.overlay

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.datthanhdoan.paperscreen.R
import io.github.datthanhdoan.paperscreen.SettingsActivity
import io.github.datthanhdoan.paperscreen.settings.PaperSettings
import io.github.datthanhdoan.paperscreen.settings.SettingsRepository
import io.github.datthanhdoan.paperscreen.texture.TextureParams
import io.github.datthanhdoan.paperscreen.texture.TileCache
import io.github.datthanhdoan.paperscreen.texture.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service (type specialUse) that keeps one [PaperView] in a
 * TYPE_APPLICATION_OVERLAY window. All state comes from [SettingsRepository], so a
 * START_STICKY restart simply re-reads it.
 */
class OverlayService : Service(), PaperView.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val autoResume = Runnable { setPaused(false) }

    private lateinit var repository: SettingsRepository
    private lateinit var tileCache: TileCache
    private lateinit var windowContext: Context
    private lateinit var paperView: PaperView
    private lateinit var host: OverlayHost
    private var windowAlpha = 0.8f

    private val densityDpi = MutableStateFlow(0)
    private val nightMode = MutableStateFlow(false)
    private var settings = PaperSettings()
    private var observeJob: Job? = null
    private var isForeground = false
    /** startForeground was refused; retry once our overlay window becomes visible. */
    private var foregroundPendingWindow = false
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository.get(this)
        tileCache = TileCache(this)
        windowContext = overlayWindowContext(this)
        windowAlpha = overlayWindowAlpha(windowContext)
        paperView = PaperView(windowContext).also { it.listener = this }
        host = WindowOverlayHost(windowContext, paperView)
        applyConfiguration(windowContext.resources.configuration)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                OverlayLauncher.disable(this)
                shutDown()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> setPaused(!paused)
            ACTION_START -> {
                // Explicit start (activity, tile, boot): persist "on" before observing,
                // otherwise the collector could see a stale enabled = false and stop.
                ensureForeground()
                scope.launch {
                    repository.setEnabled(true)
                    observeSettings()
                }
                return START_STICKY
            }
            // null intent: START_STICKY restart after the process was killed.
        }
        ensureForeground()
        observeSettings()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoResume)
        scope.cancel()
        host.detach()
        _running.value = false
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyConfiguration(windowContext.resources.configuration)
    }

    // PaperView.Listener

    override fun onWindowShown() {
        OverlayLauncher.onServiceOverlayShown()
        if (foregroundPendingWindow) {
            foregroundPendingWindow = false
            if (!startForegroundCompat()) {
                Log.w(TAG, "startForeground refused even with a visible overlay; stopping")
                shutDown()
            }
        }
    }

    override fun onViewConfigurationChanged(config: Configuration) {
        applyConfiguration(config)
    }

    // Internals

    private fun observeSettings() {
        if (observeJob != null) return
        _running.value = true
        observeJob = scope.launch {
            launch {
                repository.settings.collect { s ->
                    settings = s
                    if (!s.enabled) {
                        shutDown()
                        return@collect
                    }
                    updateAppearance()
                    updateAttachment()
                }
            }
            launch {
                nightMode.collect { updateAppearance() }
            }
            launch {
                combine(
                    repository.settings.map { it.textureParams }.distinctUntilChanged(),
                    densityDpi,
                ) { params, dpi -> params to dpi }
                    .distinctUntilChanged()
                    .collectLatest { (params, dpi) -> loadTile(params, dpi) }
            }
        }
    }

    private suspend fun loadTile(params: TextureParams, dpi: Int) {
        if (dpi <= 0) return
        val bitmap = withContext(Dispatchers.Default) {
            val tile = tileCache.loadOrGenerate(params, dpi, windowAlpha) { ensureActive() }
            tile.toBitmap()
        }
        paperView.setTile(bitmap)
    }

    /** Section A6: density changes regenerate the tile, uiMode changes adjust the tint. */
    private fun applyConfiguration(config: Configuration) {
        densityDpi.value = config.densityDpi
        nightMode.value =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateAppearance() {
        paperView.setAppearance(
            PaperAppearance(
                warmth = settings.warmth,
                vignette = settings.vignette,
                windowAlpha = windowAlpha,
                nightMode = nightMode.value,
                dimTintInDarkMode = settings.dimTintInDarkMode,
            ),
        )
    }

    private fun updateAttachment() {
        if (paused || !settings.enabled) {
            host.detach()
        } else if (!host.attach()) {
            // Overlay permission was revoked. Keep the user's "on" preference so the
            // overlay comes back once permission is granted again.
            shutDown()
        }
    }

    private fun setPaused(value: Boolean) {
        paused = value
        handler.removeCallbacks(autoResume)
        if (value) handler.postDelayed(autoResume, PAUSE_DURATION_MS)
        updateAttachment()
        if (isForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun ensureForeground() {
        if (isForeground || foregroundPendingWindow) return
        if (startForegroundCompat()) return
        // Started from the background without an exemption (e.g. sticky restart on
        // Android 15+): show our overlay first, then retry from onWindowShown().
        foregroundPendingWindow = true
        if (!host.attach()) {
            foregroundPendingWindow = false
            shutDown()
        }
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            isForeground = true
            true
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e !is ForegroundServiceStartNotAllowedException
            ) {
                throw e
            }
            Log.w(TAG, "startForeground refused: ${e.message}")
            false
        }
    }

    private fun shutDown() {
        host.detach()
        observeJob?.cancel()
        observeJob = null
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseLabel = if (paused) R.string.action_resume else R.string.action_pause
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (paused) R.string.notification_paused else R.string.notification_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(pauseLabel), serviceAction(ACTION_TOGGLE_PAUSE, 1))
            .addAction(0, getString(R.string.action_stop), serviceAction(ACTION_STOP, 2))
            .build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, OverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1
        private const val PAUSE_DURATION_MS = 5 * 60_000L

        const val ACTION_START = "io.github.datthanhdoan.paperscreen.action.START"
        const val ACTION_TOGGLE_PAUSE = "io.github.datthanhdoan.paperscreen.action.TOGGLE_PAUSE"
        const val ACTION_STOP = "io.github.datthanhdoan.paperscreen.action.STOP"

        private val _running = MutableStateFlow(false)
        /** True while the service is up and observing settings (paused counts as running). */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun startIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).setAction(ACTION_START)
    }
}
