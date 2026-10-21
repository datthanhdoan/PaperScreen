package io.github.datthanhdoan.paperscreen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.datthanhdoan.paperscreen.overlay.OverlayLauncher

/**
 * Invisible trampoline used by the QS tile when a direct background FGS start is
 * refused: a foreground activity may always start the service.
 */
class OverlayStarterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OverlayLauncher.start(this) == OverlayLauncher.Result.NO_PERMISSION) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        finish()
    }
}
