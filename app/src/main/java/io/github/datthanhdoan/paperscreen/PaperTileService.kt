package io.github.datthanhdoan.paperscreen

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.datthanhdoan.paperscreen.overlay.OverlayLauncher
import io.github.datthanhdoan.paperscreen.overlay.OverlayService
import io.github.datthanhdoan.paperscreen.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Quick Settings toggle (ACTIVE_TILE + TOGGLEABLE_TILE), section C2. */
class PaperTileService : TileService() {

    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = MainScope().also { listening = it }
        val repository = SettingsRepository.get(this)
        scope.launch {
            repository.settings.combine(OverlayService.running) { s, running -> s.enabled && running }
                .collect { render(it) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (qsTile?.state == Tile.STATE_ACTIVE) {
            OverlayLauncher.disable(this)
            render(false)
            return
        }
        when (OverlayLauncher.start(this)) {
            OverlayLauncher.Result.STARTED, OverlayLauncher.Result.PENDING -> render(true)
            // Last resort: open an activity, which is always allowed to start the FGS.
            OverlayLauncher.Result.FAILED -> openActivity(OverlayStarterActivity::class.java)
            OverlayLauncher.Result.NO_PERMISSION -> openActivity(SettingsActivity::class.java)
        }
    }

    private fun render(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (active) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openActivity(cls: Class<*>) {
        val intent = Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: the Intent overload throws, a PendingIntent is required.
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
