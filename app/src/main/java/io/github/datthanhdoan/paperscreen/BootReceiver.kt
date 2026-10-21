package io.github.datthanhdoan.paperscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.datthanhdoan.paperscreen.overlay.OverlayLauncher
import io.github.datthanhdoan.paperscreen.overlay.appScope
import io.github.datthanhdoan.paperscreen.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores the overlay after reboot or an app update if it was on before. specialUse is
 * not in Android 15's list of FGS types blocked from BOOT_COMPLETED (section A5).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        appScope.launch {
            try {
                if (SettingsRepository.get(app).settings.first().enabled) {
                    val result = OverlayLauncher.start(app)
                    Log.i(TAG, "${intent.action}: start overlay -> $result")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
