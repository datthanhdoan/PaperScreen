package io.github.datthanhdoan.paperscreen

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.datthanhdoan.paperscreen.overlay.OverlayLauncher
import io.github.datthanhdoan.paperscreen.settings.PaperSettings
import io.github.datthanhdoan.paperscreen.settings.SettingsRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Onboarding (overlay permission, restricted-settings hint, optional notifications) and
 * the sliders. Every change is written to DataStore; the running service reacts to it.
 */
class SettingsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var repository: SettingsRepository

    private lateinit var overlayStatus: TextView
    private lateinit var grantOverlay: Button
    private lateinit var restrictedHelp: View
    private lateinit var notificationGroup: View
    private lateinit var enabledSwitch: Switch
    private lateinit var dimInDarkSwitch: Switch
    private lateinit var sliders: List<Slider>

    /** Set while the UI is being updated from DataStore, to avoid writing it back. */
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        repository = SettingsRepository.get(this)

        overlayStatus = findViewById(R.id.overlay_status)
        grantOverlay = findViewById(R.id.grant_overlay)
        restrictedHelp = findViewById(R.id.restricted_help)
        notificationGroup = findViewById(R.id.notification_group)
        enabledSwitch = findViewById(R.id.enabled_switch)
        dimInDarkSwitch = findViewById(R.id.dim_in_dark_switch)

        grantOverlay.setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.open_app_info).setOnClickListener { openAppInfo() }
        findViewById<Button>(R.id.grant_notifications).setOnClickListener { requestNotifications() }
        findViewById<Button>(R.id.new_seed).setOnClickListener {
            scope.launch { repository.update { it.copy(seed = Random.nextLong()) } }
        }

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (!binding) onEnabledToggled(checked)
        }
        dimInDarkSwitch.setOnCheckedChangeListener { _, checked ->
            if (!binding) scope.launch { repository.update { it.copy(dimTintInDarkMode = checked) } }
        }

        sliders = listOf(
            Slider(findViewById(R.id.intensity), { it.intensity }) { s, v -> s.copy(intensity = v) },
            Slider(findViewById(R.id.coarseness), { it.coarseness }) { s, v -> s.copy(coarseness = v) },
            Slider(findViewById(R.id.warmth), { it.warmth }) { s, v -> s.copy(warmth = v) },
            Slider(findViewById(R.id.vignette), { it.vignette }) { s, v -> s.copy(vignette = v) },
        )
        for (slider in sliders) {
            val bar = slider.bar
            bar.max = SLIDER_MAX
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser || binding) return
                    val v = progress / SLIDER_MAX.toFloat()
                    scope.launch { repository.update { slider.set(it, v) } }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }

        scope.launch { repository.settings.collect(::bind) }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can change while we are in the background (system settings).
        refreshPermissions()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun bind(s: PaperSettings) {
        binding = true
        enabledSwitch.isChecked = s.enabled
        dimInDarkSwitch.isChecked = s.dimTintInDarkMode
        for (slider in sliders) {
            // Don't fight the finger with a slightly older value echoed back from DataStore.
            if (!slider.bar.isPressed) slider.bar.progress = (slider.get(s) * SLIDER_MAX).roundToInt()
        }
        binding = false
    }

    private fun onEnabledToggled(checked: Boolean) {
        if (!checked) {
            OverlayLauncher.disable(this)
            return
        }
        when (OverlayLauncher.start(this)) {
            OverlayLauncher.Result.STARTED, OverlayLauncher.Result.PENDING -> Unit
            OverlayLauncher.Result.NO_PERMISSION -> {
                setSwitchSilently(false)
                Toast.makeText(this, R.string.need_overlay_permission, Toast.LENGTH_LONG).show()
                requestOverlayPermission()
            }
            OverlayLauncher.Result.FAILED -> {
                setSwitchSilently(false)
                Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setSwitchSilently(checked: Boolean) {
        binding = true
        enabledSwitch.isChecked = checked
        binding = false
    }

    private fun refreshPermissions() {
        val canDraw = Settings.canDrawOverlays(this)
        overlayStatus.setText(if (canDraw) R.string.overlay_granted else R.string.overlay_missing)
        grantOverlay.visibility = if (canDraw) View.GONE else View.VISIBLE
        restrictedHelp.visibility = if (canDraw) View.GONE else View.VISIBLE
        enabledSwitch.isEnabled = canDraw

        // Section C1: ask for notifications only after the overlay works; it is optional.
        val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        notificationGroup.visibility = if (canDraw && needsNotifications) View.VISIBLE else View.GONE
    }

    private fun requestOverlayPermission() {
        // Since Android 11 the package Uri is ignored and the generic list opens.
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppInfo()
        }
    }

    /** Where "Allow restricted settings" lives (⋮ menu) for sideloaded APKs. */
    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open_settings, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) refreshPermissions()
    }

    private class Slider(
        val bar: SeekBar,
        val get: (PaperSettings) -> Float,
        val set: (PaperSettings, Float) -> PaperSettings,
    )

    private companion object {
        const val SLIDER_MAX = 100
        const val REQUEST_NOTIFICATIONS = 1
    }
}
