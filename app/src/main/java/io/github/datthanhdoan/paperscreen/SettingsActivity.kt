package io.github.datthanhdoan.paperscreen

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import io.github.datthanhdoan.paperscreen.overlay.OverlayLauncher
import io.github.datthanhdoan.paperscreen.overlay.PaperAppearance
import io.github.datthanhdoan.paperscreen.overlay.PaperView
import io.github.datthanhdoan.paperscreen.overlay.overlayWindowAlpha
import io.github.datthanhdoan.paperscreen.settings.PaperSettings
import io.github.datthanhdoan.paperscreen.settings.SettingsRepository
import io.github.datthanhdoan.paperscreen.texture.PaperPreset
import io.github.datthanhdoan.paperscreen.texture.TileCache
import io.github.datthanhdoan.paperscreen.texture.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.random.Random
import com.google.android.material.R as MaterialR

/**
 * Main switch, onboarding steps (overlay permission, restricted-settings hint, optional
 * notifications), a live preview and the texture controls. Every change is written to
 * DataStore; the running service and the preview react to it.
 */
class SettingsActivity : AppCompatActivity() {

    private val scope = MainScope()
    private lateinit var repository: SettingsRepository
    private lateinit var tileCache: TileCache

    private lateinit var root: View
    private lateinit var mainCard: MaterialCardView
    private lateinit var mainIcon: ImageView
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var enabledStatus: TextView
    private lateinit var setupCard: View
    private lateinit var overlayStepIcon: ImageView
    private lateinit var overlayStepText: TextView
    private lateinit var grantOverlay: View
    private lateinit var restrictedToggle: MaterialButton
    private lateinit var restrictedHelp: View
    private lateinit var notificationStep: View
    private lateinit var notificationStepIcon: ImageView
    private lateinit var notificationStepText: TextView
    private lateinit var grantNotifications: View
    private lateinit var dimInDarkSwitch: MaterialSwitch
    private lateinit var paperChips: List<Pair<Chip, PaperPreset>>
    private lateinit var sliders: List<SliderRow>
    private lateinit var paperView: PaperView
    private var windowAlpha = 1f

    /** Set while the UI is being updated from DataStore, to avoid writing it back. */
    private var binding = false
    private var settings = PaperSettings()
    private var canDraw = false
    /** The user already went to the overlay settings once, so the restricted hint is likely needed. */
    private var overlayRequested = false
    private var notificationsRequested = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        setContentView(R.layout.activity_settings)
        repository = SettingsRepository.get(this)
        tileCache = TileCache(this)
        overlayRequested = savedInstanceState?.getBoolean(STATE_OVERLAY_REQUESTED) ?: false
        notificationsRequested = savedInstanceState?.getBoolean(STATE_NOTIFICATIONS_REQUESTED) ?: false

        root = findViewById(R.id.root)
        setUpInsets()
        setUpMainSwitch()
        setUpSetupSteps()
        setUpControls()
        setUpPreview()

        scope.launch { repository.settings.collect(::bind) }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can change while we are in the background (system settings).
        refreshPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OVERLAY_REQUESTED, overlayRequested)
        outState.putBoolean(STATE_NOTIFICATIONS_REQUESTED, notificationsRequested)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // Set-up

    /** Edge-to-edge (enforced from Android 15): pad the bars and cutouts by hand. */
    private fun setUpInsets() {
        val appBar = findViewById<View>(R.id.app_bar)
        val scroll = findViewById<View>(R.id.scroll)
        val previewPane: View? = findViewById(R.id.preview_pane)
        val scrollBottom = scroll.paddingBottom
        val paneBottom = previewPane?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(left = bars.left, right = bars.right)
            appBar.updatePadding(top = bars.top)
            scroll.updatePadding(bottom = scrollBottom + bars.bottom)
            previewPane?.updatePadding(bottom = paneBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setUpMainSwitch() {
        mainCard = findViewById(R.id.main_card)
        mainIcon = findViewById(R.id.main_icon)
        enabledSwitch = findViewById(R.id.enabled_switch)
        enabledStatus = findViewById(R.id.enabled_status)
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (!binding) onEnabledToggled(checked)
        }
    }

    private fun setUpSetupSteps() {
        setupCard = findViewById(R.id.setup_card)
        overlayStepIcon = findViewById(R.id.step_overlay_icon)
        overlayStepText = findViewById(R.id.step_overlay_text)
        grantOverlay = findViewById(R.id.grant_overlay)
        restrictedToggle = findViewById(R.id.restricted_toggle)
        restrictedHelp = findViewById(R.id.restricted_help)
        notificationStep = findViewById(R.id.step_notifications)
        notificationStepIcon = findViewById(R.id.step_notifications_icon)
        notificationStepText = findViewById(R.id.step_notifications_text)
        grantNotifications = findViewById(R.id.grant_notifications)

        grantOverlay.setOnClickListener { requestOverlayPermission() }
        restrictedToggle.setOnClickListener { setRestrictedHelpVisible(!restrictedHelp.isVisible) }
        findViewById<View>(R.id.open_app_info).setOnClickListener { openAppInfo() }
        grantNotifications.setOnClickListener { requestNotifications() }
    }

    private fun setUpControls() {
        dimInDarkSwitch = findViewById(R.id.dim_in_dark_switch)
        dimInDarkSwitch.setOnCheckedChangeListener { _, checked ->
            if (!binding) update { it.copy(dimTintInDarkMode = checked) }
        }
        findViewById<View>(R.id.new_seed).setOnClickListener { update { it.copy(seed = Random.nextLong()) } }
        findViewById<View>(R.id.reset_defaults).setOnClickListener { resetDefaults() }

        val paperGroup = findViewById<ChipGroup>(R.id.paper_types)
        paperChips = PaperPreset.entries.map { preset ->
            val chip = layoutInflater.inflate(R.layout.item_paper_chip, paperGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.setText(paperLabel(preset))
            paperGroup.addView(chip)
            chip.setOnClickListener {
                // The click already toggled the chip; show the outcome before DataStore echoes it.
                bindPaperChips(preset)
                update { it.copy(preset = preset) }
            }
            chip to preset
        }

        val container = findViewById<LinearLayout>(R.id.slider_container)
        sliders = SLIDERS.map { spec ->
            val item = layoutInflater.inflate(R.layout.item_slider, container, false)
            container.addView(item)
            item.findViewById<TextView>(R.id.slider_label).setText(spec.label)
            item.findViewById<TextView>(R.id.slider_low).setText(spec.low)
            item.findViewById<TextView>(R.id.slider_high).setText(spec.high)
            val row = SliderRow(spec, item.findViewById(R.id.slider), item.findViewById(R.id.slider_value))
            row.slider.contentDescription = getString(spec.label)
            row.slider.setLabelFormatter(::percent)
            row.slider.addOnChangeListener { _, value, fromUser ->
                row.value.text = percent(value)
                if (fromUser && !binding) update { spec.set(it, value / SLIDER_MAX) }
            }
            row.slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    row.dragging = true
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    row.dragging = false
                }
            })
            row
        }
    }

    /** A PaperView over a sample page, fed the same tile and appearance as the overlay. */
    @SuppressLint("ClickableViewAccessibility") // The compare button is the accessible path.
    private fun setUpPreview() {
        val frame = findViewById<FrameLayout>(R.id.preview_frame)
        // The overlay window is drawn at this alpha; apply it to the view to match.
        windowAlpha = overlayWindowAlpha(this)
        paperView = PaperView(this).apply { alpha = windowAlpha }
        frame.addView(paperView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val compare = findViewById<MaterialButton>(R.id.preview_compare)
        compare.addOnCheckedChangeListener { _, checked -> paperView.isInvisible = checked }
        // Press and hold the sample to see it without paper.
        frame.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> paperView.isInvisible = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> paperView.isInvisible = compare.isChecked
            }
            true
        }

        scope.launch {
            var first = true
            repository.settings.map { it.textureParams }.distinctUntilChanged().collectLatest { params ->
                // Let a dragged slider settle before regenerating; warmth and vignette are instant.
                if (!first) delay(PREVIEW_DEBOUNCE_MS)
                first = false
                val dpi = resources.configuration.densityDpi
                val bitmap = withContext(Dispatchers.Default) {
                    tileCache.loadOrGenerate(params, dpi, windowAlpha) { ensureActive() }.toBitmap()
                }
                paperView.setTile(bitmap)
            }
        }
    }

    // Binding

    private fun bind(s: PaperSettings) {
        settings = s
        binding = true
        enabledSwitch.isChecked = s.enabled
        dimInDarkSwitch.isChecked = s.dimTintInDarkMode
        for (row in sliders) {
            // Don't fight the finger with a slightly older value echoed back from DataStore.
            if (!row.dragging) {
                row.slider.value = (row.spec.get(s) * SLIDER_MAX).roundToInt().coerceIn(0, SLIDER_MAX.toInt()).toFloat()
            }
            row.value.text = percent(row.slider.value)
        }
        bindPaperChips(s.preset)
        binding = false
        renderStatus()
        paperView.setAppearance(
            PaperAppearance(
                warmth = s.warmth,
                vignette = s.vignette,
                windowAlpha = windowAlpha,
                nightMode = isNightMode(),
                dimTintInDarkMode = s.dimTintInDarkMode,
            ),
        )
    }

    private fun bindPaperChips(selected: PaperPreset) {
        for ((chip, preset) in paperChips) chip.isChecked = preset == selected
    }

    private fun renderStatus() {
        val on = settings.enabled && canDraw
        enabledStatus.setText(
            when {
                !canDraw -> R.string.status_need_permission
                settings.enabled -> R.string.status_on
                else -> R.string.status_off
            },
        )
        val background = color(if (on) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerHigh)
        val content = color(if (on) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnSurface)
        mainCard.setCardBackgroundColor(background)
        enabledSwitch.setTextColor(content)
        enabledStatus.setTextColor(content)
        mainIcon.imageTintList = ColorStateList.valueOf(content)
    }

    private fun refreshPermissions() {
        canDraw = Settings.canDrawOverlays(this)
        setStepState(overlayStepIcon, canDraw)
        overlayStepText.setText(if (canDraw) R.string.overlay_granted else R.string.overlay_missing)
        grantOverlay.isVisible = !canDraw
        restrictedToggle.isVisible = !canDraw
        setRestrictedHelpVisible(!canDraw && (overlayRequested || restrictedHelp.isVisible))

        // Section C1: notifications are optional, the overlay works without them.
        val askNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notificationsGranted = !askNotifications ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notificationStep.isVisible = askNotifications
        setStepState(notificationStepIcon, notificationsGranted)
        notificationStepText.setText(
            if (notificationsGranted) R.string.notifications_granted else R.string.notifications_help,
        )
        grantNotifications.isVisible = !notificationsGranted

        setupCard.isVisible = !canDraw || !notificationsGranted
        renderStatus()
    }

    private fun setStepState(icon: ImageView, done: Boolean) {
        icon.setImageResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_step_pending)
        icon.imageTintList = ColorStateList.valueOf(
            color(if (done) android.R.attr.colorPrimary else MaterialR.attr.colorOutline),
        )
        icon.contentDescription = getString(if (done) R.string.step_done else R.string.step_pending)
    }

    private fun setRestrictedHelpVisible(visible: Boolean) {
        restrictedHelp.isVisible = visible
        restrictedToggle.setIconResource(if (visible) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
    }

    // Actions

    private fun update(transform: (PaperSettings) -> PaperSettings) {
        scope.launch { repository.update(transform) }
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
                Snackbar.make(root, R.string.need_overlay_permission, Snackbar.LENGTH_LONG)
                    .setAction(R.string.grant_overlay) { requestOverlayPermission() }
                    .show()
            }
            OverlayLauncher.Result.FAILED -> {
                setSwitchSilently(false)
                Snackbar.make(root, R.string.start_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setSwitchSilently(checked: Boolean) {
        binding = true
        enabledSwitch.isChecked = checked
        binding = false
    }

    private fun resetDefaults() {
        val previous = settings
        // Everything except the on/off state, including parameters added later.
        update { PaperSettings(enabled = it.enabled) }
        Snackbar.make(root, R.string.reset_done, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { update { previous.copy(enabled = it.enabled) } }
            .show()
    }

    private fun requestOverlayPermission() {
        overlayRequested = true
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
            Snackbar.make(root, R.string.cannot_open_settings, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        // After a second denial the system stops showing the dialog; go to the settings page.
        if (notificationsRequested && !shouldShowRequestPermissionRationale(permission)) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                openAppInfo()
            }
            return
        }
        notificationsRequested = true
        notificationPermission.launch(permission)
    }

    // Helpers

    private fun percent(value: Float): String = getString(R.string.percent, value.roundToInt())

    private fun color(@AttrRes attr: Int): Int = MaterialColors.getColor(root, attr)

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** One texture parameter. Adding a parameter to [PaperSettings] means adding one entry here. */
    private class SliderSpec(
        @StringRes val label: Int,
        @StringRes val low: Int,
        @StringRes val high: Int,
        val get: (PaperSettings) -> Float,
        val set: (PaperSettings, Float) -> PaperSettings,
    )

    private class SliderRow(val spec: SliderSpec, val slider: Slider, val value: TextView) {
        var dragging = false
    }

    private companion object {
        const val SLIDER_MAX = 100f
        const val PREVIEW_DEBOUNCE_MS = 120L
        const val STATE_OVERLAY_REQUESTED = "overlay_requested"
        const val STATE_NOTIFICATIONS_REQUESTED = "notifications_requested"

        val SLIDERS = listOf(
            SliderSpec(R.string.intensity, R.string.intensity_low, R.string.intensity_high, { it.intensity }) { s, v ->
                s.copy(intensity = v)
            },
            SliderSpec(R.string.coarseness, R.string.coarseness_low, R.string.coarseness_high, { it.coarseness }) { s, v ->
                s.copy(coarseness = v)
            },
            SliderSpec(R.string.relief, R.string.relief_low, R.string.relief_high, { it.relief }) { s, v ->
                s.copy(relief = v)
            },
            SliderSpec(R.string.warmth, R.string.warmth_low, R.string.warmth_high, { it.warmth }) { s, v ->
                s.copy(warmth = v)
            },
            SliderSpec(R.string.vignette, R.string.vignette_low, R.string.vignette_high, { it.vignette }) { s, v ->
                s.copy(vignette = v)
            },
        )

        /** Exhaustive, so a new paper type does not compile without a name. */
        @StringRes
        fun paperLabel(preset: PaperPreset): Int = when (preset) {
            PaperPreset.PLAIN -> R.string.paper_plain
            PaperPreset.NEWSPRINT -> R.string.paper_newsprint
            PaperPreset.DRAWING -> R.string.paper_drawing
            PaperPreset.KRAFT -> R.string.paper_kraft
            PaperPreset.DO -> R.string.paper_do
        }
    }
}
