package io.github.datthanhdoan.paperscreen

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.slider.Slider
import io.github.datthanhdoan.paperscreen.overlay.OverlayLauncher
import io.github.datthanhdoan.paperscreen.overlay.PaperView
import io.github.datthanhdoan.paperscreen.overlay.appearance
import io.github.datthanhdoan.paperscreen.overlay.overlayWindowAlpha
import io.github.datthanhdoan.paperscreen.settings.PaperSettings
import io.github.datthanhdoan.paperscreen.settings.SettingsRepository
import io.github.datthanhdoan.paperscreen.texture.PaperPreset
import io.github.datthanhdoan.paperscreen.texture.TileCache
import io.github.datthanhdoan.paperscreen.texture.toBitmap
import io.github.datthanhdoan.paperscreen.ui.IosSwitch
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

/**
 * iOS-style grouped settings: a live preview card, the main switch, setup rows
 * (overlay permission, restricted-settings hint, optional notifications), paper type
 * and the texture sliders, with the current UI mode's section first. Every change is
 * written to DataStore; the running service and the preview react to it.
 */
class SettingsActivity : AppCompatActivity() {

    private val scope = MainScope()
    private lateinit var repository: SettingsRepository
    private lateinit var tileCache: TileCache

    private lateinit var root: View
    private lateinit var enabledSwitch: IosSwitch
    private lateinit var enabledStatus: TextView
    private lateinit var setupSection: View
    private lateinit var overlayRow: View
    private lateinit var overlayValue: TextView
    private lateinit var overlayChevron: View
    private lateinit var restrictedBlock: View
    private lateinit var restrictedRow: View
    private lateinit var restrictedChevron: ImageView
    private lateinit var restrictedHelp: View
    private lateinit var notificationsBlock: View
    private lateinit var notificationsRow: View
    private lateinit var notificationsValue: TextView
    private lateinit var notificationsChevron: View
    private lateinit var paperTypes: RadioGroup
    private lateinit var paperNote: TextView
    private lateinit var modeSections: LinearLayout
    private lateinit var darkSection: View
    private lateinit var darkHeader: TextView
    private lateinit var darkProfileSwitch: IosSwitch
    private lateinit var darkRows: View
    private lateinit var dimBlock: View
    private lateinit var dimInDarkSwitch: IosSwitch
    private lateinit var darkFooter: TextView
    private lateinit var lightHeader: TextView
    private lateinit var lightFooter: TextView
    private lateinit var compareButton: TextView
    private lateinit var paperView: PaperView
    private val sliders = mutableListOf<SliderRow>()
    private val paperButtons = mutableMapOf<PaperPreset, RadioButton>()
    private var windowAlpha = 1f
    private var night = false

    /** Set while the UI is being updated from DataStore, to avoid writing it back. */
    private var binding = false
    private var settings = PaperSettings()
    private var canDraw = false
    private var comparing = false
    /** The user already went to the overlay settings once, so the restricted hint is likely needed. */
    private var overlayRequested = false
    private var notificationsRequested = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        repository = SettingsRepository.get(this)
        tileCache = TileCache(this)
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        overlayRequested = savedInstanceState?.getBoolean(STATE_OVERLAY_REQUESTED) ?: false
        notificationsRequested = savedInstanceState?.getBoolean(STATE_NOTIFICATIONS_REQUESTED) ?: false

        root = findViewById(R.id.root)
        setUpInsets()
        setUpMainSwitch()
        setUpSetupRows()
        setUpPaperTypes()
        setUpSliders()
        setUpActions()
        setUpPreview()
        for (id in GROUP_IDS) findViewById<View>(id)?.clipToOutline = true

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

    /** Edge-to-edge (enforced from Android 15): pad for the bars and cutouts by hand. */
    private fun setUpInsets() {
        val title = findViewById<View>(R.id.large_title)
        val scroll = findViewById<View>(R.id.scroll)
        val previewPane: View? = findViewById(R.id.preview_pane)
        val titleTop = title.paddingTop
        val scrollBottom = scroll.paddingBottom
        val paneBottom = previewPane?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(left = bars.left, right = bars.right)
            title.updatePadding(top = titleTop + bars.top)
            scroll.updatePadding(bottom = scrollBottom + bars.bottom)
            previewPane?.updatePadding(bottom = paneBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setUpMainSwitch() {
        enabledSwitch = findViewById(R.id.enabled_switch)
        enabledStatus = findViewById(R.id.enabled_status)
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (!binding) onEnabledToggled(checked)
        }
    }

    private fun setUpSetupRows() {
        setupSection = findViewById(R.id.setup_section)
        overlayRow = findViewById(R.id.row_overlay)
        overlayValue = findViewById(R.id.overlay_value)
        overlayChevron = findViewById(R.id.overlay_chevron)
        restrictedBlock = findViewById(R.id.restricted_block)
        restrictedRow = findViewById(R.id.row_restricted)
        restrictedChevron = findViewById(R.id.restricted_chevron)
        restrictedHelp = findViewById(R.id.restricted_help)
        notificationsBlock = findViewById(R.id.notifications_block)
        notificationsRow = findViewById(R.id.row_notifications)
        notificationsValue = findViewById(R.id.notifications_value)
        notificationsChevron = findViewById(R.id.notifications_chevron)

        overlayRow.setOnClickListener { requestOverlayPermission() }
        restrictedRow.setOnClickListener { setRestrictedHelpVisible(!restrictedHelp.isVisible) }
        findViewById<View>(R.id.open_app_info).setOnClickListener { openAppInfo() }
        notificationsRow.setOnClickListener { requestNotifications() }
    }

    /** A segmented control: one radio button per [PaperPreset], equal widths. */
    private fun setUpPaperTypes() {
        paperTypes = findViewById(R.id.paper_types)
        paperNote = findViewById(R.id.paper_note)
        val height = dp(32)
        for (preset in PaperPreset.entries) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                buttonDrawable = null
                setBackgroundResource(R.drawable.bg_segment)
                gravity = Gravity.CENTER
                minHeight = height
                minimumHeight = height
                setPadding(0, 0, 0, 0)
                setText(paperLabel(preset))
                setTextColor(getColor(R.color.ios_label))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                isSingleLine = true
            }
            paperTypes.addView(button, RadioGroup.LayoutParams(0, height, 1f))
            paperButtons[preset] = button
        }
        paperTypes.setOnCheckedChangeListener { _, checkedId ->
            val preset = paperButtons.entries.firstOrNull { it.value.id == checkedId }?.key ?: return@setOnCheckedChangeListener
            styleSegments(preset)
            if (!binding) update { it.copy(preset = preset) }
        }
    }

    private fun setUpSliders() {
        addSliders(findViewById(R.id.shared_rows), SHARED_SLIDERS, firstHasSeparator = false)
        addSliders(findViewById(R.id.dark_rows), DARK_SLIDERS, firstHasSeparator = true)
        addSliders(findViewById(R.id.light_rows), LIGHT_SLIDERS, firstHasSeparator = false)

        modeSections = findViewById(R.id.mode_sections)
        darkSection = findViewById(R.id.dark_section)
        darkHeader = findViewById(R.id.dark_header)
        darkProfileSwitch = findViewById(R.id.dark_profile_switch)
        darkRows = findViewById(R.id.dark_rows)
        dimBlock = findViewById(R.id.dim_block)
        dimInDarkSwitch = findViewById(R.id.dim_in_dark_switch)
        darkFooter = findViewById(R.id.dark_footer)
        lightHeader = findViewById(R.id.light_header)
        lightFooter = findViewById(R.id.light_footer)

        darkProfileSwitch.setOnCheckedChangeListener { _, checked ->
            if (!binding) update { it.copy(darkProfileEnabled = checked) }
        }
        dimInDarkSwitch.setOnCheckedChangeListener { _, checked ->
            if (!binding) update { it.copy(dimTintInDarkMode = checked) }
        }
    }

    private fun addSliders(container: ViewGroup, specs: List<SliderSpec>, firstHasSeparator: Boolean) {
        specs.forEachIndexed { index, spec ->
            val item = layoutInflater.inflate(R.layout.item_slider, container, false)
            container.addView(item)
            item.findViewById<View>(R.id.slider_separator).isVisible = index > 0 || firstHasSeparator
            item.findViewById<TextView>(R.id.slider_label).setText(spec.label)
            item.findViewById<ImageView>(R.id.slider_low).setImageResource(spec.icon)
            item.findViewById<ImageView>(R.id.slider_high).setImageResource(spec.icon)
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
            sliders += row
        }
    }

    private fun setUpActions() {
        findViewById<View>(R.id.new_seed).setOnClickListener { update { it.copy(seed = Random.nextLong()) } }
        findViewById<View>(R.id.reset_defaults).setOnClickListener { confirmReset() }
    }

    /** A PaperView over a sample page, fed the same tile and appearance as the overlay. */
    @SuppressLint("ClickableViewAccessibility") // The compare button is the accessible path.
    private fun setUpPreview() {
        val frame = findViewById<FrameLayout>(R.id.preview_frame)
        frame.clipToOutline = true
        // The overlay window is drawn at this alpha; apply it to the view to match.
        windowAlpha = overlayWindowAlpha(this)
        paperView = PaperView(this).apply { alpha = windowAlpha }
        frame.addView(paperView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        compareButton = findViewById(R.id.preview_compare)
        compareButton.setOnClickListener {
            comparing = !comparing
            compareButton.setText(if (comparing) R.string.preview_compare_off else R.string.preview_compare)
            paperView.isInvisible = comparing
        }
        // Press and hold the sample to see it without paper.
        frame.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> paperView.isInvisible = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> paperView.isInvisible = comparing
            }
            true
        }

        scope.launch {
            var first = true
            repository.settings.map { it.textureParams(night) }.distinctUntilChanged().collectLatest { params ->
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
        darkProfileSwitch.isChecked = s.darkProfileEnabled
        dimInDarkSwitch.isChecked = s.dimTintInDarkMode
        paperButtons[s.preset]?.let { if (!it.isChecked) it.isChecked = true }
        styleSegments(s.preset)
        for (row in sliders) {
            // Don't fight the finger with a slightly older value echoed back from DataStore.
            if (!row.dragging) {
                row.slider.value = (row.spec.get(s) * SLIDER_MAX).roundToInt().coerceIn(0, SLIDER_MAX.toInt()).toFloat()
            }
            row.value.text = percent(row.slider.value)
        }
        binding = false

        paperNote.setText(paperNote(s.preset))
        bindModeSections(s)
        renderStatus()
        paperView.setAppearance(s.appearance(night, windowAlpha))
    }

    /** Dark-mode users see their section first; without a dark profile it is just a switch. */
    private fun bindModeSections(s: PaperSettings) {
        val profile = s.darkProfileEnabled
        darkRows.isVisible = profile
        dimBlock.isVisible = !profile
        darkHeader.text = sectionTitle(R.string.section_dark, inUse = night)
        darkFooter.setText(if (profile) R.string.dark_footer_profile else R.string.dark_footer_shared)
        lightHeader.text = if (profile) sectionTitle(R.string.section_light, inUse = !night) else getString(R.string.section_strength)
        lightFooter.isVisible = !profile
        lightFooter.setText(R.string.strength_footer)

        val darkFirst = night && profile
        if ((modeSections.indexOfChild(darkSection) == 0) != darkFirst) {
            modeSections.removeView(darkSection)
            modeSections.addView(darkSection, if (darkFirst) 0 else 1)
        }
    }

    private fun sectionTitle(@StringRes name: Int, inUse: Boolean): String =
        if (inUse) getString(R.string.section_in_use, getString(name)) else getString(name)

    private fun styleSegments(selected: PaperPreset) {
        for ((preset, button) in paperButtons) {
            button.setTypeface(null, if (preset == selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun renderStatus() {
        enabledStatus.setText(
            when {
                !canDraw -> R.string.status_need_permission
                settings.enabled -> R.string.status_on
                else -> R.string.status_off
            },
        )
    }

    private fun refreshPermissions() {
        canDraw = Settings.canDrawOverlays(this)
        overlayValue.setText(if (canDraw) R.string.value_granted else R.string.value_grant)
        overlayValue.setTextColor(getColor(if (canDraw) R.color.ios_secondary_label else R.color.ios_accent))
        overlayChevron.isVisible = !canDraw
        overlayRow.isClickable = !canDraw
        restrictedBlock.isVisible = !canDraw
        setRestrictedHelpVisible(!canDraw && (overlayRequested || restrictedHelp.isVisible))

        // Section C1: notifications are optional, the overlay works without them.
        val askNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notificationsGranted = !askNotifications ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notificationsBlock.isVisible = askNotifications
        notificationsValue.setText(if (notificationsGranted) R.string.value_allowed else R.string.value_allow)
        notificationsChevron.isVisible = !notificationsGranted
        notificationsRow.isClickable = !notificationsGranted

        setupSection.isVisible = !canDraw || !notificationsGranted
        renderStatus()
    }

    private fun setRestrictedHelpVisible(visible: Boolean) {
        restrictedHelp.isVisible = visible
        restrictedChevron.rotation = if (visible) 180f else 0f
        ViewCompat.setStateDescription(
            restrictedRow,
            getString(if (visible) R.string.restricted_toggle_expanded else R.string.restricted_toggle_collapsed),
        )
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

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_confirm_title)
            .setMessage(R.string.reset_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            // Everything except the on/off state, including parameters added later.
            .setPositiveButton(R.string.reset_confirm) { _, _ -> update { PaperSettings(enabled = it.enabled) } }
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
            Toast.makeText(this, R.string.cannot_open_settings, Toast.LENGTH_LONG).show()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    /** One texture parameter. Adding a parameter to [PaperSettings] means adding one entry below. */
    private class SliderSpec(
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
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

        /** Groups whose rounded corners must clip their rows' ripples. */
        val GROUP_IDS = intArrayOf(
            R.id.main_group, R.id.setup_group, R.id.shared_rows, R.id.dark_group, R.id.light_rows, R.id.actions_group,
        )

        /** Shared by light and dark mode. */
        val SHARED_SLIDERS = listOf(
            SliderSpec(R.string.coarseness, R.drawable.ic_slider_grain, { it.coarseness }) { s, v -> s.copy(coarseness = v) },
            SliderSpec(R.string.vignette, R.drawable.ic_slider_vignette, { it.vignette }) { s, v -> s.copy(vignette = v) },
        )

        /** Light mode, and dark mode too while the dark profile is off. */
        val LIGHT_SLIDERS = listOf(
            SliderSpec(R.string.intensity, R.drawable.ic_slider_intensity, { it.intensity }) { s, v -> s.copy(intensity = v) },
            SliderSpec(R.string.relief, R.drawable.ic_slider_relief, { it.relief }) { s, v -> s.copy(relief = v) },
            SliderSpec(R.string.warmth, R.drawable.ic_slider_warmth, { it.warmth }) { s, v -> s.copy(warmth = v) },
        )

        val DARK_SLIDERS = listOf(
            SliderSpec(R.string.intensity, R.drawable.ic_slider_intensity, { it.darkIntensity }) { s, v -> s.copy(darkIntensity = v) },
            SliderSpec(R.string.relief, R.drawable.ic_slider_relief, { it.darkRelief }) { s, v -> s.copy(darkRelief = v) },
            SliderSpec(R.string.warmth, R.drawable.ic_slider_warmth, { it.darkWarmth }) { s, v -> s.copy(darkWarmth = v) },
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

        @StringRes
        fun paperNote(preset: PaperPreset): Int = when (preset) {
            PaperPreset.PLAIN -> R.string.paper_plain_note
            PaperPreset.NEWSPRINT -> R.string.paper_newsprint_note
            PaperPreset.DRAWING -> R.string.paper_drawing_note
            PaperPreset.KRAFT -> R.string.paper_kraft_note
            PaperPreset.DO -> R.string.paper_do_note
        }
    }
}
