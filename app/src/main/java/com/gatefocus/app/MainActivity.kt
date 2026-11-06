package com.gatefocus.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.gatefocus.app.data.QuestionRepository
import com.gatefocus.app.data.SyllabusRepository
import com.gatefocus.app.models.GateQuestion
import com.gatefocus.app.models.GateSubject
import com.gatefocus.app.models.MockQuestionState
import com.gatefocus.app.models.MockTestResult
import com.gatefocus.app.models.QuestionType
import com.gatefocus.app.views.TelegramCapsuleNavBar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "gate_focus_prefs"
        const val KEY_STUDY_MODE = "study_mode"
        const val KEY_BLOCKED = "blocked_packages"
        const val KEY_VIDEOS = "curated_videos"
        const val KEY_TELEGRAM_FILTER = "telegram_channel_filter"
        const val KEY_TELEGRAM_ALLOWED = "telegram_allowed_channels"
        const val KEY_BLOCK_YOUTUBE_SHORTS = "block_youtube_shorts"
        const val KEY_BLOCK_SHORT_VIDEOS_60S = "block_short_videos_60s"
        const val KEY_FOCUS_TIMER_TOTAL = "focus_timer_total"
        const val KEY_FOCUS_TIMER_REMAINING = "focus_timer_remaining"
        const val KEY_FOCUS_TIMER_TARGET_MS = "focus_timer_target_ms"
        const val KEY_FOCUS_TIMER_RUNNING = "focus_timer_running"
        const val KEY_FOCUS_TIMER_SELECTED_MINS = "focus_timer_selected_mins"
        const val KEY_STOPWATCH_START_MS = "stopwatch_start_ms"
        const val KEY_STOPWATCH_ELAPSED_MS = "stopwatch_elapsed_ms"
        const val KEY_STOPWATCH_RUNNING = "stopwatch_running"
        const val KEY_FOCUS_MODE = "focus_mode"
        const val KEY_AUTO_GUARD_ENABLED = "auto_guard_enabled"
        const val KEY_STRICT_LOCKOUT = "strict_lockout_mode"
        const val KEY_HAPTIC_ENABLED = "haptic_alerts_enabled"
        const val KEY_TARGET_STREAM = "target_stream"
        const val KEY_TARGET_YEAR = "target_year"
        val defaultBlockedApps = setOf(
            "com.instagram.android", "com.instagram.barcelona", "com.threads.android",
            "com.facebook.katana", "com.snapchat.android"
        )
    }

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val executor = Executors.newSingleThreadExecutor()

    // Repositories
    private val syllabusRepo by lazy { SyllabusRepository(this) }
    private val questionRepo by lazy { QuestionRepository(this) }

    // File Picker for importing PYQ JSON
    private val jsonFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    val (success, msg) = questionRepo.importQuestionsFromJson(text)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshPracticeHub()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    enum class FocusMode { POMODORO, STOPWATCH }

    // Timer & Stopwatch state (Wall-clock synced & persistent)
    private var currentFocusMode = FocusMode.POMODORO
    private var totalTimerSeconds = 45 * 60
    private var secondsLeft = 45 * 60
    private var timerTargetEndTime = 0L
    private var timerRunning = false
    private var selectedPresetMins = 45
    private var stopwatchStart = 0L
    private var stopwatchRunning = false
    private var stopwatchElapsed = 0L

    // Tab Views (5 Tabs)
    private lateinit var focusView: View
    private lateinit var trackerView: View
    private lateinit var practiceView: View
    private lateinit var libraryView: View
    private lateinit var settingsView: View
    private lateinit var headerBadge: TextView
    private lateinit var mainTopAppBar: View
    private lateinit var telegramNavBar: TelegramCapsuleNavBar
    private lateinit var viewPager: ViewPager2

    // Practice Session State
    private var activePracticeQuestions: List<GateQuestion> = emptyList()
    private var currentPracticeIndex = 0

    // Mock Test State
    private var mockStates: List<MockQuestionState> = emptyList()
    private var currentMockIndex = 0
    private var mockTotalSeconds = 20 * 60
    private var mockSecondsLeft = 20 * 60
    private var mockTimerRunning = false
    private var mockStartTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        headerBadge = findViewById(R.id.header_status_badge)
        headerBadge.setOnClickListener {
            openAccessibilitySettings()
        }
        mainTopAppBar = findViewById(R.id.main_top_app_bar)

        // Handle Back button to return to Practice Hub from sessions
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val practiceSessionView = practiceView.findViewById<View>(R.id.view_practice_session)
                val mockSessionView = practiceView.findViewById<View>(R.id.view_mock_session)

                if (practiceSessionView != null && practiceSessionView.visibility == View.VISIBLE) {
                    showPracticeHub()
                } else if (mockSessionView != null && mockSessionView.visibility == View.VISIBLE) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Exit Mock Test?")
                        .setMessage("Your current test session and answers will be discarded. Are you sure?")
                        .setPositiveButton("Exit") { _, _ ->
                            mockTimerRunning = false
                            showPracticeHub()
                        }
                        .setNegativeButton("Continue Test", null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // Inflate all five tab views
        val inflater = LayoutInflater.from(this)
        focusView = inflater.inflate(R.layout.tab_focus, null, false)
        trackerView = inflater.inflate(R.layout.tab_tracker, null, false)
        practiceView = inflater.inflate(R.layout.tab_practice, null, false)
        libraryView = inflater.inflate(R.layout.tab_library, null, false)
        settingsView = inflater.inflate(R.layout.tab_settings, null, false)

        // Setup controllers for each tab
        setupFocusTab()
        setupTrackerTab()
        setupPracticeTab()
        setupLibraryTab()
        setupSettingsTab()

        // Setup ViewPager2 with 5 tabs
        viewPager = findViewById(R.id.view_pager)
        viewPager.offscreenPageLimit = 4
        viewPager.adapter = TabPagerAdapter(listOf(focusView, trackerView, practiceView, libraryView, settingsView))

        // Telegram-style floating capsule navbar
        telegramNavBar = findViewById(R.id.telegram_nav_bar)
        telegramNavBar.setOnTabSelectedListener(object : TelegramCapsuleNavBar.OnTabSelectedListener {
            override fun onTabSelected(position: Int, tabId: Int) {
                viewPager.setCurrentItem(position, true)
            }
        })

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                telegramNavBar.onPageScrollStateChanged(state)
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                telegramNavBar.onPageScrolled(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                telegramNavBar.onPageSelected(position)
                when (position) {
                    0 -> updateHeroCard()
                    1 -> refreshTrackerProgress()
                    2 -> refreshPracticeHub()
                    3 -> refreshLibraryList()
                    4 -> refreshSettingsTabStatus()
                }
            }
        })

        // Set initial tab to Focus
        showTab(R.id.nav_focus)
    }

    override fun onResume() {
        super.onResume()
        updateHeroCard()
        updateServiceStatus()
        refreshTelegramChips()
        syncFocusTimerOnResume()
        syncStopwatchOnResume()
    }

    override fun onPause() {
        super.onPause()
        saveFocusTimerState()
        saveStopwatchState()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveFocusTimerState()
        saveStopwatchState()
        mockTimerRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun showTab(itemId: Int) {
        val position = when (itemId) {
            R.id.nav_focus -> 0
            R.id.nav_tracker -> 1
            R.id.nav_practice -> 2
            R.id.nav_library -> 3
            R.id.nav_settings -> 4
            else -> 0
        }
        telegramNavBar.selectTab(position, animate = true, notify = false)
        viewPager.setCurrentItem(position, true)

        // Ensure navigation bar and top bar are visible when on any main tab
        if (::telegramNavBar.isInitialized) telegramNavBar.visibility = View.VISIBLE
        if (::mainTopAppBar.isInitialized) mainTopAppBar.visibility = View.VISIBLE
        if (::viewPager.isInitialized) viewPager.isUserInputEnabled = true
    }

    private class TabPagerAdapter(private val pages: List<View>) : RecyclerView.Adapter<TabPagerAdapter.PageViewHolder>() {
        class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val frame = android.widget.FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return PageViewHolder(frame)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val frame = holder.itemView as android.widget.FrameLayout
            val targetView = pages[position]
            (targetView.parent as? ViewGroup)?.removeView(targetView)
            frame.removeAllViews()
            frame.addView(
                targetView,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    // -------------------------------------------------------------
    // Unified Focus Hub Controller
    // -------------------------------------------------------------
    private val modePillTimer by lazy { focusView.findViewById<TextView>(R.id.mode_pill_timer) }
    private val modePillStopwatch by lazy { focusView.findViewById<TextView>(R.id.mode_pill_stopwatch) }
    private val ledStatusDot by lazy { focusView.findViewById<View>(R.id.led_status_dot) }
    private val textGuardBanner by lazy { focusView.findViewById<TextView>(R.id.text_guard_banner) }
    private val badgeSessionState by lazy { focusView.findViewById<TextView>(R.id.badge_session_state) }
    private val focusTimeDisplay by lazy { focusView.findViewById<TextView>(R.id.focus_time_display) }
    private val focusProgressBar by lazy { focusView.findViewById<ProgressBar>(R.id.focus_timer_progress_bar) }
    private val timerPresetsContainer by lazy { focusView.findViewById<View>(R.id.timer_presets_container) }
    private val chip25 by lazy { focusView.findViewById<TextView>(R.id.chip_25m) }
    private val chip45 by lazy { focusView.findViewById<TextView>(R.id.chip_45m) }
    private val chip60 by lazy { focusView.findViewById<TextView>(R.id.chip_60m) }
    private val switchAutoGuard by lazy { focusView.findViewById<MaterialSwitch>(R.id.switch_auto_guard) }
    private val btnFocusToggle by lazy { focusView.findViewById<MaterialButton>(R.id.btn_focus_toggle) }
    private val btnFocusReset by lazy { focusView.findViewById<MaterialButton>(R.id.btn_focus_reset) }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val remainingMs = timerTargetEndTime - System.currentTimeMillis()
            val remaining = ((remainingMs + 500) / 1000).toInt().coerceAtLeast(0)
            secondsLeft = remaining

            if (remaining > 0) {
                if (currentFocusMode == FocusMode.POMODORO) {
                    focusTimeDisplay.text = formatTime(remaining)
                    focusProgressBar.progress = remaining
                }
                val nextDelay = (remainingMs % 1000).coerceIn(100L, 1000L)
                handler.postDelayed(this, nextDelay)
            } else {
                onFocusTimerCompleted()
            }
        }
    }

    private val stopwatchRunnable = object : Runnable {
        override fun run() {
            if (!stopwatchRunning) return
            val currentMs = stopwatchElapsed + (System.currentTimeMillis() - stopwatchStart)
            val sec = (currentMs / 1000).toInt()
            if (currentFocusMode == FocusMode.STOPWATCH) {
                focusTimeDisplay.text = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
            }
            val nextDelay = (1000L - (currentMs % 1000L)).coerceIn(100L, 1000L)
            handler.postDelayed(this, nextDelay)
        }
    }

    private fun setupFocusTab() {
        restoreFocusTimerState()
        restoreStopwatchState()

        val savedModeOrdinal = prefs.getInt(KEY_FOCUS_MODE, FocusMode.POMODORO.ordinal)
        currentFocusMode = FocusMode.values().getOrElse(savedModeOrdinal) { FocusMode.POMODORO }
        switchAutoGuard.isChecked = prefs.getBoolean(KEY_AUTO_GUARD_ENABLED, true)

        switchAutoGuard.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_GUARD_ENABLED, isChecked).apply()
            val isSessionActive = (currentFocusMode == FocusMode.POMODORO && timerRunning) ||
                                 (currentFocusMode == FocusMode.STOPWATCH && stopwatchRunning)
            if (isSessionActive) {
                setStudyGuardActive(isChecked)
            }
        }

        modePillTimer.setOnClickListener { switchFocusMode(FocusMode.POMODORO) }
        modePillStopwatch.setOnClickListener { switchFocusMode(FocusMode.STOPWATCH) }

        chip25.setOnClickListener { selectTimerPreset(25) }
        chip45.setOnClickListener { selectTimerPreset(45) }
        chip60.setOnClickListener { selectTimerPreset(60) }

        btnFocusToggle.setOnClickListener {
            handlePrimaryToggleClick()
        }

        btnFocusReset.setOnClickListener {
            handleResetClick()
        }

        focusView.findViewById<View>(R.id.btn_open_accessibility).setOnClickListener {
            openAccessibilitySettings()
        }
        focusView.findViewById<View>(R.id.btn_open_notifications).setOnClickListener {
            openNotificationListenerSettings()
        }

        applyFocusModeUI(currentFocusMode)
        updateFocusGuardUI()
        updateServiceStatus()
    }

    private fun switchFocusMode(mode: FocusMode) {
        if (mode == currentFocusMode) return
        currentFocusMode = mode
        prefs.edit().putInt(KEY_FOCUS_MODE, mode.ordinal).apply()
        applyFocusModeUI(mode)
    }

    private fun applyFocusModeUI(mode: FocusMode) {
        if (mode == FocusMode.POMODORO) {
            modePillTimer.setBackgroundResource(R.drawable.bg_chip_selected)
            modePillTimer.setTextColor(ContextCompat.getColor(this, R.color.btn_text_pure_white))

            modePillStopwatch.setBackgroundResource(android.R.color.transparent)
            modePillStopwatch.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            focusProgressBar.visibility = View.VISIBLE
            timerPresetsContainer.visibility = View.VISIBLE

            focusProgressBar.max = totalTimerSeconds
            focusProgressBar.progress = secondsLeft
            focusTimeDisplay.text = formatTime(secondsLeft)
            updateChipsUI(selectedPresetMins)
            setTimerPresetChipsLocked(timerRunning)

            if (timerRunning) {
                btnFocusToggle.text = "Stop Focus"
                btnFocusToggle.setIconResource(R.drawable.ic_pause)
            } else if (secondsLeft < totalTimerSeconds && secondsLeft > 0) {
                btnFocusToggle.text = "Resume Focus"
                btnFocusToggle.setIconResource(R.drawable.ic_play)
            } else {
                btnFocusToggle.text = "Start Focus"
                btnFocusToggle.setIconResource(R.drawable.ic_play)
            }
        } else {
            modePillStopwatch.setBackgroundResource(R.drawable.bg_chip_selected)
            modePillStopwatch.setTextColor(ContextCompat.getColor(this, R.color.btn_text_pure_white))

            modePillTimer.setBackgroundResource(android.R.color.transparent)
            modePillTimer.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            focusProgressBar.visibility = View.GONE
            timerPresetsContainer.visibility = View.GONE

            val currentMs = if (stopwatchRunning) {
                stopwatchElapsed + (System.currentTimeMillis() - stopwatchStart)
            } else {
                stopwatchElapsed
            }
            val sec = (currentMs / 1000).toInt()
            focusTimeDisplay.text = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)

            if (stopwatchRunning) {
                btnFocusToggle.text = "Stop Stopwatch"
                btnFocusToggle.setIconResource(R.drawable.ic_pause)
            } else if (stopwatchElapsed > 0) {
                btnFocusToggle.text = "Resume Stopwatch"
                btnFocusToggle.setIconResource(R.drawable.ic_play)
            } else {
                btnFocusToggle.text = "Start Stopwatch"
                btnFocusToggle.setIconResource(R.drawable.ic_play)
            }
        }
        updateFocusGuardUI()
    }

    private fun handlePrimaryToggleClick() {
        if (currentFocusMode == FocusMode.POMODORO) {
            if (timerRunning) {
                if (prefs.getBoolean(KEY_STRICT_LOCKOUT, false)) {
                    AlertDialog.Builder(this)
                        .setTitle("Strict Lockout Active 🔒")
                        .setMessage("Strict Study Lockout is enabled in Settings. Are you sure you want to stop this study session early?")
                        .setPositiveButton("Emergency Stop") { _, _ ->
                            stopFocusTimerInternal()
                        }
                        .setNegativeButton("Keep Focusing", null)
                        .show()
                    return
                }
                stopFocusTimerInternal()
            } else {
                // Start Focus -> Turn ON strict guard immediately
                if (secondsLeft <= 0) {
                    secondsLeft = totalTimerSeconds
                }
                timerTargetEndTime = System.currentTimeMillis() + (secondsLeft * 1000L)
                timerRunning = true
                btnFocusToggle.text = "Stop Focus"
                btnFocusToggle.setIconResource(R.drawable.ic_pause)
                setTimerPresetChipsLocked(true)
                saveFocusTimerState()

                if (switchAutoGuard.isChecked) {
                    setStudyGuardActive(true)
                }

                handler.removeCallbacks(timerRunnable)
                handler.post(timerRunnable)
                updateFocusGuardUI()
            }
        } else {
            if (stopwatchRunning) {
                // Stop Stopwatch -> Turn OFF strict guard immediately
                handler.removeCallbacks(stopwatchRunnable)
                stopwatchElapsed += System.currentTimeMillis() - stopwatchStart
                stopwatchRunning = false
                btnFocusToggle.text = "Resume Stopwatch"
                btnFocusToggle.setIconResource(R.drawable.ic_play)
                saveStopwatchState()

                if (switchAutoGuard.isChecked) {
                    setStudyGuardActive(false)
                }

                updateFocusGuardUI()
            } else {
                // Start Stopwatch -> Turn ON strict guard immediately
                stopwatchStart = System.currentTimeMillis()
                stopwatchRunning = true
                btnFocusToggle.text = "Stop Stopwatch"
                btnFocusToggle.setIconResource(R.drawable.ic_pause)
                saveStopwatchState()

                if (switchAutoGuard.isChecked) {
                    setStudyGuardActive(true)
                }

                handler.removeCallbacks(stopwatchRunnable)
                handler.post(stopwatchRunnable)
                updateFocusGuardUI()
            }
        }
    }

    private fun handleResetClick() {
        if (currentFocusMode == FocusMode.POMODORO) {
            handler.removeCallbacks(timerRunnable)
            timerRunning = false
            secondsLeft = totalTimerSeconds
            focusTimeDisplay.text = formatTime(secondsLeft)
            focusProgressBar.progress = secondsLeft
            btnFocusToggle.text = "Start Focus Sprint"
            btnFocusToggle.setIconResource(R.drawable.ic_play)
            setTimerPresetChipsLocked(false)
            saveFocusTimerState()

            if (!stopwatchRunning && switchAutoGuard.isChecked) {
                setStudyGuardActive(false)
            }
        } else {
            handler.removeCallbacks(stopwatchRunnable)
            stopwatchRunning = false
            stopwatchElapsed = 0L
            stopwatchStart = 0L
            focusTimeDisplay.text = "00:00:00"
            btnFocusToggle.text = "Start Stopwatch"
            btnFocusToggle.setIconResource(R.drawable.ic_play)
            saveStopwatchState()

            if (!timerRunning && switchAutoGuard.isChecked) {
                setStudyGuardActive(false)
            }
        }
        updateFocusGuardUI()
    }

    private fun selectTimerPreset(mins: Int) {
        if (timerRunning) {
            Toast.makeText(this, "Pause or reset the timer before changing duration", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPresetMins = mins
        totalTimerSeconds = mins * 60
        secondsLeft = totalTimerSeconds
        focusProgressBar.max = totalTimerSeconds
        focusProgressBar.progress = secondsLeft
        focusTimeDisplay.text = formatTime(secondsLeft)
        btnFocusToggle.text = "Start Focus Sprint"
        btnFocusToggle.setIconResource(R.drawable.ic_play)
        updateChipsUI(mins)
        saveFocusTimerState()
    }

    private fun updateChipsUI(selectedMins: Int) {
        chip25.setBackgroundResource(if (selectedMins == 25) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        chip25.setTextColor(ContextCompat.getColor(this, if (selectedMins == 25) R.color.btn_text_pure_white else R.color.text_secondary))

        chip45.setBackgroundResource(if (selectedMins == 45) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        chip45.setTextColor(ContextCompat.getColor(this, if (selectedMins == 45) R.color.btn_text_pure_white else R.color.text_secondary))

        chip60.setBackgroundResource(if (selectedMins == 60) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        chip60.setTextColor(ContextCompat.getColor(this, if (selectedMins == 60) R.color.btn_text_pure_white else R.color.text_secondary))
    }

    private fun setTimerPresetChipsLocked(locked: Boolean) {
        val alphaVal = if (locked) 0.5f else 1.0f
        chip25.alpha = alphaVal
        chip45.alpha = alphaVal
        chip60.alpha = alphaVal
    }

    private fun stopFocusTimerInternal() {
        handler.removeCallbacks(timerRunnable)
        val remainingMs = (timerTargetEndTime - System.currentTimeMillis()).coerceAtLeast(0L)
        secondsLeft = ((remainingMs + 500) / 1000).toInt()
        timerRunning = false
        btnFocusToggle.text = "Resume Focus"
        btnFocusToggle.setIconResource(R.drawable.ic_play)
        setTimerPresetChipsLocked(false)
        saveFocusTimerState()

        if (switchAutoGuard.isChecked) {
            setStudyGuardActive(false)
        }

        updateFocusGuardUI()
    }

    private fun onFocusTimerCompleted() {
        timerRunning = false
        secondsLeft = 0
        saveFocusTimerState()

        if (currentFocusMode == FocusMode.POMODORO) {
            focusTimeDisplay.text = "00:00"
            focusProgressBar.progress = 0
            btnFocusToggle.text = "Start Focus Sprint"
            btnFocusToggle.setIconResource(R.drawable.ic_play)
            setTimerPresetChipsLocked(false)
        }

        // Auto stand-down guard when timer finishes
        if (!stopwatchRunning && switchAutoGuard.isChecked) {
            setStudyGuardActive(false)
        }

        // Haptic vibration alert
        if (prefs.getBoolean(KEY_HAPTIC_ENABLED, true)) {
            try {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 400, 200, 400), -1)
                }
            } catch (_: Exception) {}
        }

        Toast.makeText(this, "Focus session complete! Time for a short break.", Toast.LENGTH_LONG).show()

        // Prompt post-focus retention quiz
        AlertDialog.Builder(this)
            .setTitle("Session Complete! 🎉")
            .setMessage("Great job focusing! Would you like to solve quick GATE questions now to reinforce what you learned?")
            .setPositiveButton("Practice Now") { _, _ ->
                telegramNavBar.selectedItemId = R.id.nav_practice
            }
            .setNegativeButton("Take Break", null)
            .show()

        updateFocusGuardUI()
    }

    private fun syncFocusTimerOnResume() {
        restoreFocusTimerState()
        focusProgressBar.max = totalTimerSeconds

        if (timerRunning) {
            val now = System.currentTimeMillis()
            if (now >= timerTargetEndTime) {
                onFocusTimerCompleted()
            } else {
                val remainingMs = timerTargetEndTime - now
                secondsLeft = ((remainingMs + 500) / 1000).toInt().coerceAtLeast(0)
                if (currentFocusMode == FocusMode.POMODORO) {
                    focusTimeDisplay.text = formatTime(secondsLeft)
                    focusProgressBar.progress = secondsLeft
                    btnFocusToggle.text = "Stop Focus"
                    btnFocusToggle.setIconResource(R.drawable.ic_pause)
                    setTimerPresetChipsLocked(true)
                }
                handler.removeCallbacks(timerRunnable)
                handler.post(timerRunnable)
            }
        } else {
            if (currentFocusMode == FocusMode.POMODORO) {
                focusTimeDisplay.text = formatTime(secondsLeft)
                focusProgressBar.progress = secondsLeft
                btnFocusToggle.text = if (secondsLeft < totalTimerSeconds && secondsLeft > 0) "Resume Focus" else "Start Focus"
                btnFocusToggle.setIconResource(R.drawable.ic_play)
                setTimerPresetChipsLocked(false)
            }
        }
    }

    private fun syncStopwatchOnResume() {
        restoreStopwatchState()
        if (stopwatchRunning) {
            if (currentFocusMode == FocusMode.STOPWATCH) {
                btnFocusToggle.text = "Pause Stopwatch"
                btnFocusToggle.setIconResource(R.drawable.ic_pause)
            }
            handler.removeCallbacks(stopwatchRunnable)
            handler.post(stopwatchRunnable)
        } else {
            if (currentFocusMode == FocusMode.STOPWATCH) {
                val sec = (stopwatchElapsed / 1000).toInt()
                focusTimeDisplay.text = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60)
                btnFocusToggle.text = if (stopwatchElapsed > 0) "Resume Stopwatch" else "Start Stopwatch"
                btnFocusToggle.setIconResource(R.drawable.ic_play)
            }
        }
    }

    private fun saveFocusTimerState() {
        prefs.edit()
            .putInt(KEY_FOCUS_TIMER_TOTAL, totalTimerSeconds)
            .putInt(KEY_FOCUS_TIMER_REMAINING, secondsLeft)
            .putLong(KEY_FOCUS_TIMER_TARGET_MS, timerTargetEndTime)
            .putBoolean(KEY_FOCUS_TIMER_RUNNING, timerRunning)
            .putInt(KEY_FOCUS_TIMER_SELECTED_MINS, selectedPresetMins)
            .apply()
    }

    private fun restoreFocusTimerState() {
        totalTimerSeconds = prefs.getInt(KEY_FOCUS_TIMER_TOTAL, 45 * 60)
        secondsLeft = prefs.getInt(KEY_FOCUS_TIMER_REMAINING, totalTimerSeconds)
        timerTargetEndTime = prefs.getLong(KEY_FOCUS_TIMER_TARGET_MS, 0L)
        timerRunning = prefs.getBoolean(KEY_FOCUS_TIMER_RUNNING, false)
        selectedPresetMins = prefs.getInt(KEY_FOCUS_TIMER_SELECTED_MINS, 45)
    }

    private fun saveStopwatchState() {
        prefs.edit()
            .putLong(KEY_STOPWATCH_START_MS, stopwatchStart)
            .putLong(KEY_STOPWATCH_ELAPSED_MS, stopwatchElapsed)
            .putBoolean(KEY_STOPWATCH_RUNNING, stopwatchRunning)
            .apply()
    }

    private fun restoreStopwatchState() {
        stopwatchStart = prefs.getLong(KEY_STOPWATCH_START_MS, 0L)
        stopwatchElapsed = prefs.getLong(KEY_STOPWATCH_ELAPSED_MS, 0L)
        stopwatchRunning = prefs.getBoolean(KEY_STOPWATCH_RUNNING, false)
    }

    private fun setStudyGuardActive(active: Boolean) {
        val currentActive = prefs.getBoolean(KEY_STUDY_MODE, false)
        if (currentActive == active) return

        prefs.edit().putBoolean(KEY_STUDY_MODE, active).apply()
        if (active && !isAccessibilityEnabled()) {
            openAccessibilitySettings()
            Toast.makeText(this, "Enable GATE FOCUS Accessibility to enforce blocking", Toast.LENGTH_LONG).show()
        }
        updateFocusGuardUI()
        updateServiceStatus()
    }

    private fun updateHeroCard() {
        updateFocusGuardUI()
    }

    private fun updateFocusGuardUI() {
        val isGuardActive = prefs.getBoolean(KEY_STUDY_MODE, false)
        val isAccEnabled = isAccessibilityEnabled()
        val isSessionRunning = (currentFocusMode == FocusMode.POMODORO && timerRunning) ||
                              (currentFocusMode == FocusMode.STOPWATCH && stopwatchRunning)

        if (isGuardActive) {
            ledStatusDot.setBackgroundResource(R.drawable.bg_led_active)
            val pulse = AlphaAnimation(0.35f, 1.0f).apply {
                duration = 800
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            ledStatusDot.startAnimation(pulse)

            textGuardBanner.text = if (isAccEnabled) "Guard Active · Distractions Blocked" else "Guard Active · Needs Accessibility"
            textGuardBanner.setTextColor(ContextCompat.getColor(this, if (isAccEnabled) R.color.accent_emerald_text else R.color.accent_amber))

            badgeSessionState.text = if (isSessionRunning) "Focusing" else "Active"
            badgeSessionState.setBackgroundResource(if (isAccEnabled) R.drawable.bg_badge_success else R.drawable.bg_badge_warning)
            badgeSessionState.setTextColor(ContextCompat.getColor(this, if (isAccEnabled) R.color.accent_emerald else R.color.accent_amber))
        } else {
            ledStatusDot.clearAnimation()
            ledStatusDot.alpha = 1.0f
            ledStatusDot.setBackgroundResource(R.drawable.bg_led_inactive)

            textGuardBanner.text = "Distraction Guard: Standby"
            textGuardBanner.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

            badgeSessionState.text = if (isSessionRunning) "Timer Only" else "Standby"
            badgeSessionState.setBackgroundResource(R.drawable.bg_chip)
            badgeSessionState.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun updateServiceStatus() {
        val isAccEnabled = isAccessibilityEnabled()
        val textAcc = focusView.findViewById<TextView>(R.id.text_accessibility_status)
        val btnAcc = focusView.findViewById<MaterialButton>(R.id.btn_open_accessibility)
        val studyActive = prefs.getBoolean(KEY_STUDY_MODE, false)

        if (isAccEnabled) {
            textAcc.text = "Accessibility Guard: Active ✓"
            textAcc.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald_text))
            btnAcc.text = "Enabled"
            btnAcc.alpha = 0.6f
        } else {
            textAcc.text = "Accessibility Guard: Not Granted ⚠"
            textAcc.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            btnAcc.text = "Enable"
            btnAcc.alpha = 1.0f
        }

        // Telegram Notification Listener Status
        val isNotifEnabled = isNotificationListenerEnabled()
        val textNotif = focusView.findViewById<TextView>(R.id.text_notification_status)
        val btnNotif = focusView.findViewById<MaterialButton>(R.id.btn_open_notifications)

        if (isNotifEnabled) {
            textNotif.text = "Telegram Notification Guard: Active ✓"
            textNotif.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald_text))
            btnNotif.text = "Enabled"
            btnNotif.alpha = 0.6f
        } else {
            textNotif.text = "Telegram Notification Access: Not Granted ⚠"
            textNotif.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            btnNotif.text = "Enable"
            btnNotif.alpha = 1.0f
        }

        // Header status badge
        if (studyActive && isAccEnabled) {
            headerBadge.text = "Guard Active"
            headerBadge.setBackgroundResource(R.drawable.bg_badge_success)
            headerBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
        } else if (studyActive && !isAccEnabled) {
            headerBadge.text = "Permission Needed"
            headerBadge.setBackgroundResource(R.drawable.bg_badge_warning)
            headerBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        } else if (!studyActive && isAccEnabled) {
            headerBadge.text = "Ready"
            headerBadge.setBackgroundResource(R.drawable.bg_chip_selected)
            headerBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            headerBadge.text = "Standby"
            headerBadge.setBackgroundResource(R.drawable.bg_chip)
            headerBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        return try {
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            !flat.isNullOrEmpty() && flat.contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------
    // Tracker Tab Logic (Syllabus & Topics)
    // -------------------------------------------------------------
    private fun setupTrackerTab() {
        val container = trackerView.findViewById<LinearLayout>(R.id.subjects_list_container)
        container.removeAllViews()

        val subjects = syllabusRepo.getSubjects()
        val inflater = LayoutInflater.from(this)

        subjects.forEach { subject ->
            val card = inflater.inflate(R.layout.item_subject_tracker, container, false)
            val badgeCode = card.findViewById<TextView>(R.id.badge_subject_code)
            val textName = card.findViewById<TextView>(R.id.text_subject_name)
            val textDesc = card.findViewById<TextView>(R.id.text_subject_desc)
            val textPercent = card.findViewById<TextView>(R.id.text_subject_percent)
            val textCount = card.findViewById<TextView>(R.id.text_subject_topics_count)
            val iconExpand = card.findViewById<ImageView>(R.id.icon_subject_expand)
            val progressBar = card.findViewById<LinearProgressIndicator>(R.id.progress_subject)
            val topicsContainer = card.findViewById<LinearLayout>(R.id.topics_container)
            val headerLayout = card.findViewById<View>(R.id.subject_header)

            badgeCode.text = subject.shortCode.ifEmpty { subject.name.take(2).uppercase() }
            textName.text = subject.name
            textDesc.text = subject.description

            fun updateSubjectView() {
                val pct = syllabusRepo.getSubjectCompletionPercentage(subject)
                val completed = syllabusRepo.getSubjectCompletedCount(subject)
                val total = subject.topics.size
                textPercent.text = "$pct%"
                textCount.text = "$completed/$total Done"
                progressBar.setProgressCompat(pct, true)
            }

            updateSubjectView()

            // Populate topics
            subject.topics.forEach { topic ->
                val topicView = inflater.inflate(R.layout.item_topic_tracker, topicsContainer, false)
                val textTopicTitle = topicView.findViewById<TextView>(R.id.text_topic_title)
                val textTopicSubtopics = topicView.findViewById<TextView>(R.id.text_topic_subtopics)
                val cbComplete = topicView.findViewById<MaterialCheckBox>(R.id.cb_topic_complete)
                val btnNotes = topicView.findViewById<MaterialButton>(R.id.btn_topic_notes)
                val btnLecture = topicView.findViewById<MaterialButton>(R.id.btn_watch_topic_lecture)

                textTopicTitle.text = topic.title
                if (topic.subtopics.isNotEmpty()) {
                    textTopicSubtopics.text = topic.subtopics.joinToString(" · ")
                    textTopicSubtopics.visibility = View.VISIBLE
                } else {
                    textTopicSubtopics.visibility = View.GONE
                }

                val progress = syllabusRepo.getTopicProgress(topic.id)
                cbComplete.isChecked = progress.isCompleted

                fun refreshNotesButtonState() {
                    val savedNotes = syllabusRepo.getTopicNotes(topic.id)
                    if (savedNotes.isNotBlank()) {
                        btnNotes.text = "Notes (Saved)"
                        btnNotes.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_emerald))
                        btnNotes.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald_text))
                        btnNotes.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_emerald))
                    } else {
                        btnNotes.text = "Notes"
                        btnNotes.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.border_subtle))
                        btnNotes.setTextColor(ContextCompat.getColor(this, R.color.primary_indigo_light))
                        btnNotes.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_indigo_light))
                    }
                }
                refreshNotesButtonState()

                cbComplete.setOnCheckedChangeListener { _, isChecked ->
                    syllabusRepo.setTopicProgress(topic.id, isChecked)
                    updateSubjectView()
                    refreshTrackerProgress()
                }

                // Notes Dialog / Modal
                btnNotes.setOnClickListener {
                    showTopicNotesDialog(subject, topic) {
                        refreshNotesButtonState()
                    }
                }

                // Lecture Player
                if (topic.recommendedVideoId.isNotBlank()) {
                    btnLecture.visibility = View.VISIBLE
                    btnLecture.setOnClickListener {
                        startActivity(
                            Intent(this, PlayerActivity::class.java)
                                .putExtra("title", "${subject.shortCode}: ${topic.title}")
                                .putExtra("video_id", topic.recommendedVideoId)
                        )
                    }
                } else {
                    btnLecture.visibility = View.GONE
                }

                topicsContainer.addView(topicView)
            }

            // Expand/collapse toggle
            headerLayout.setOnClickListener {
                val isVisible = topicsContainer.visibility == View.VISIBLE
                topicsContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
                iconExpand.setImageResource(if (isVisible) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
            }

            container.addView(card)
        }

        refreshTrackerProgress()
    }

    private fun showTopicNotesDialog(subject: GateSubject, topic: com.gatefocus.app.models.GateTopic, onSaved: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_topic_notes, null)
        val titleView = dialogView.findViewById<TextView>(R.id.dialog_notes_topic_title)
        val subjectNameView = dialogView.findViewById<TextView>(R.id.dialog_notes_subject_name)
        val inputNotes = dialogView.findViewById<TextInputEditText>(R.id.input_topic_notes)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_notes)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btn_save_notes)

        titleView.text = "${subject.shortCode}: ${topic.title}"
        subjectNameView.text = "${subject.name} · Revision Notes & Formulas"

        val currentNotes = syllabusRepo.getTopicNotes(topic.id)
        inputNotes.setText(currentNotes)
        inputNotes.setSelection(currentNotes.length)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val updatedText = inputNotes.text?.toString()?.trim().orEmpty()
            syllabusRepo.setTopicNotes(topic.id, updatedText)
            Toast.makeText(this, "Notes saved for ${topic.title}", Toast.LENGTH_SHORT).show()
            onSaved()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun refreshTrackerProgress() {
        val (earned, total) = syllabusRepo.getOverallProgress()
        val percent = if (total == 0) 0 else (earned * 100) / total

        val textSummary = trackerView.findViewById<TextView>(R.id.text_tracker_summary)
        val textPercent = trackerView.findViewById<TextView>(R.id.text_overall_percent)
        val progressBar = trackerView.findViewById<LinearProgressIndicator>(R.id.progress_overall)

        textSummary.text = "13 Subjects · $earned of $total Topics Completed"
        textPercent.text = "$percent%"
        progressBar.setProgressCompat(percent, true)
    }

    // -------------------------------------------------------------
    // Practice Tab Logic: 3 Portals (PYQs, Mock Test, Import)
    // -------------------------------------------------------------
    private fun setupPracticeTab() {
        val hubView = practiceView.findViewById<View>(R.id.view_practice_hub)
        val practiceSessionView = practiceView.findViewById<View>(R.id.view_practice_session)
        val mockSessionView = practiceView.findViewById<View>(R.id.view_mock_session)

        // Navigation back buttons from sessions
        practiceView.findViewById<View>(R.id.btn_exit_practice_to_hub).setOnClickListener {
            showPracticeHub()
        }
        practiceView.findViewById<View>(R.id.btn_exit_mock_to_hub).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Exit Mock Test?")
                .setMessage("Your current test session and answers will be discarded. Are you sure?")
                .setPositiveButton("Exit") { _, _ ->
                    mockTimerRunning = false
                    showPracticeHub()
                }
                .setNegativeButton("Continue Test", null)
                .show()
        }

        // --- PORTAL 1: GATE PYQs Actions (Dedicated Popup) ---
        val openPyqOptions = { openPyqOptionsDialog() }
        practiceView.findViewById<View>(R.id.card_portal_gate_pyqs)?.setOnClickListener { openPyqOptions() }
        practiceView.findViewById<View>(R.id.btn_open_pyq_options)?.setOnClickListener { openPyqOptions() }

        // --- PORTAL 2: Mock Test Actions (Dedicated Popup) ---
        val openMockOptions = { openMockOptionsDialog() }
        practiceView.findViewById<View>(R.id.card_portal_mock_test)?.setOnClickListener { openMockOptions() }
        practiceView.findViewById<View>(R.id.btn_open_mock_options)?.setOnClickListener { openMockOptions() }

        // --- PORTAL 3: Import the PYQs Action ---
        practiceView.findViewById<View>(R.id.btn_open_import_sources).setOnClickListener {
            openImportSourceDialog()
        }

        // Hub bookmarks shortcut
        practiceView.findViewById<View>(R.id.btn_hub_view_bookmarks).setOnClickListener {
            val bookmarked = questionRepo.getBookmarkedQuestions()
            if (bookmarked.isEmpty()) {
                Toast.makeText(this, "No bookmarked questions yet! Bookmark tricky questions while practicing.", Toast.LENGTH_SHORT).show()
            } else {
                startPracticeSession(bookmarked, "★ Bookmarked Questions")
            }
        }

        // --- Wire Practice Session Controls ---
        val btnCheck = practiceView.findViewById<MaterialButton>(R.id.btn_practice_check_answer)
        val btnReset = practiceView.findViewById<MaterialButton>(R.id.btn_practice_reset_answer)
        val btnPrev = practiceView.findViewById<MaterialButton>(R.id.btn_practice_prev)
        val btnNext = practiceView.findViewById<MaterialButton>(R.id.btn_practice_next)
        val btnBookmark = practiceView.findViewById<ImageView>(R.id.btn_practice_bookmark)

        btnCheck.setOnClickListener { evaluatePracticeAnswer() }
        btnReset.setOnClickListener { displayPracticeQuestion() }
        btnPrev.setOnClickListener {
            if (currentPracticeIndex > 0) {
                currentPracticeIndex--
                displayPracticeQuestion()
            }
        }
        btnNext.setOnClickListener {
            if (currentPracticeIndex < activePracticeQuestions.size - 1) {
                currentPracticeIndex++
                displayPracticeQuestion()
            }
        }
        btnBookmark.setOnClickListener {
            val q = activePracticeQuestions.getOrNull(currentPracticeIndex) ?: return@setOnClickListener
            val isNowBookmarked = questionRepo.toggleBookmark(q.id)
            btnBookmark.setImageResource(if (isNowBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            btnBookmark.setColorFilter(
                ContextCompat.getColor(this, if (isNowBookmarked) R.color.accent_amber else R.color.text_secondary)
            )
            Toast.makeText(this, if (isNowBookmarked) "Saved to Bookmarks" else "Removed from Bookmarks", Toast.LENGTH_SHORT).show()
            updateHubStats()
        }

        // --- Wire Mock Test Controls ---
        val btnMockPrev = practiceView.findViewById<MaterialButton>(R.id.btn_mock_prev)
        val btnMockNext = practiceView.findViewById<MaterialButton>(R.id.btn_mock_next)
        val btnMockReview = practiceView.findViewById<MaterialButton>(R.id.btn_mock_mark_review)
        val btnMockClear = practiceView.findViewById<MaterialButton>(R.id.btn_mock_clear_response)
        val btnMockSubmit = practiceView.findViewById<MaterialButton>(R.id.btn_mock_submit_test)

        btnMockPrev.setOnClickListener {
            saveCurrentMockInput()
            if (currentMockIndex > 0) {
                currentMockIndex--
                displayMockQuestion()
            }
        }
        btnMockNext.setOnClickListener {
            saveCurrentMockInput()
            if (currentMockIndex < mockStates.size - 1) {
                currentMockIndex++
                displayMockQuestion()
            }
        }
        btnMockReview.setOnClickListener {
            saveCurrentMockInput()
            val state = mockStates.getOrNull(currentMockIndex) ?: return@setOnClickListener
            state.isMarkedForReview = !state.isMarkedForReview
            btnMockReview.text = if (state.isMarkedForReview) "★ Marked for Review" else "Mark for Review"
            refreshMockPalette()
        }
        btnMockClear.setOnClickListener {
            val state = mockStates.getOrNull(currentMockIndex) ?: return@setOnClickListener
            state.selectedOptions.clear()
            state.natAnswer = null
            state.isMarkedForReview = false
            displayMockQuestion()
            refreshMockPalette()
        }
        btnMockSubmit.setOnClickListener {
            saveCurrentMockInput()
            AlertDialog.Builder(this)
                .setTitle("Submit Mock Test?")
                .setMessage("Are you sure you want to finish and view your scorecard? Total Questions: ${mockStates.size}")
                .setPositiveButton("Yes, Submit") { _, _ ->
                    submitMockTest()
                }
                .setNegativeButton("Keep Solving", null)
                .show()
        }

        showPracticeHub()
    }

    private fun showPracticeHub() {
        val hubView = practiceView.findViewById<View>(R.id.view_practice_hub)
        val practiceSessionView = practiceView.findViewById<View>(R.id.view_practice_session)
        val mockSessionView = practiceView.findViewById<View>(R.id.view_mock_session)

        hubView.visibility = View.VISIBLE
        practiceSessionView.visibility = View.GONE
        mockSessionView.visibility = View.GONE

        // Restore navigation bar, top bar and re-enable swipe on main hub
        if (::telegramNavBar.isInitialized) telegramNavBar.visibility = View.VISIBLE
        if (::mainTopAppBar.isInitialized) mainTopAppBar.visibility = View.VISIBLE
        if (::viewPager.isInitialized) viewPager.isUserInputEnabled = true

        updateHubStats()
    }

    private fun openPyqOptionsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pyq_options, null, false)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btn_popup_pyq_topic).setOnClickListener {
            dialog.dismiss()
            openTopicPickerForPractice(isMock = false)
        }
        dialogView.findViewById<View>(R.id.btn_popup_pyq_year).setOnClickListener {
            dialog.dismiss()
            openYearPickerForPractice(isMock = false)
        }
        dialogView.findViewById<View>(R.id.btn_popup_pyq_mix).setOnClickListener {
            dialog.dismiss()
            openCombinedMixPicker(isMock = false)
        }

        dialog.show()
    }

    private fun openMockOptionsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_mock_options, null, false)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btn_popup_mock_topic).setOnClickListener {
            dialog.dismiss()
            openTopicPickerForPractice(isMock = true)
        }
        dialogView.findViewById<View>(R.id.btn_popup_mock_year).setOnClickListener {
            dialog.dismiss()
            openYearPickerForPractice(isMock = true)
        }
        dialogView.findViewById<View>(R.id.btn_popup_mock_mix).setOnClickListener {
            dialog.dismiss()
            openCombinedMixPicker(isMock = true)
        }

        dialog.show()
    }

    private fun refreshPracticeHub() {
        updateHubStats()
    }

    private fun updateHubStats() {
        val allQ = questionRepo.getQuestions()
        val bookmarked = questionRepo.getBookmarkedQuestions()
        val textTotal = practiceView.findViewById<TextView>(R.id.text_hub_total_questions)
        val textBookmarked = practiceView.findViewById<TextView>(R.id.text_hub_bookmarks_count)

        textTotal.text = "${allQ.size}"
        textBookmarked.text = "${bookmarked.size}"
    }

    // --- Topic Picker (used by PYQs and Mock Test) ---
    private fun openTopicPickerForPractice(isMock: Boolean) {
        val subjects = questionRepo.getDistinctSubjects()
        if (subjects.isEmpty()) {
            Toast.makeText(this, "No subjects found.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(if (isMock) "Select Subject for Mock Test" else "Select Subject (Topic-wise PYQs)")
            .setItems(subjects.toTypedArray()) { _, whichSubject ->
                val selectedSub = subjects[whichSubject]
                val topics = questionRepo.getTopicsForSubject(selectedSub)
                if (topics.isEmpty()) {
                    Toast.makeText(this, "No specific topics found for $selectedSub", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("$selectedSub — Choose Topic")
                        .setItems(topics.toTypedArray()) { _, whichTopic ->
                            val selectedTopic = topics[whichTopic]
                            val qList = questionRepo.getQuestionsByTopic(selectedSub, selectedTopic)
                            if (qList.isEmpty()) {
                                Toast.makeText(this, "No questions found for $selectedTopic", Toast.LENGTH_SHORT).show()
                            } else {
                                if (isMock) {
                                    startMockTestSession(qList, "$selectedSub — $selectedTopic Mock", 15)
                                } else {
                                    startPracticeSession(qList, "$selectedSub: $selectedTopic")
                                }
                            }
                        }
                        .show()
                }
            }
            .show()
    }

    // --- Year Picker (used by PYQs and Mock Test) ---
    private fun openYearPickerForPractice(isMock: Boolean) {
        val years = questionRepo.getDistinctYears()
        if (years.isEmpty()) {
            Toast.makeText(this, "No years found.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(if (isMock) "Select Year for Mock Exam" else "Select Year (Year-wise PYQs)")
            .setItems(years.toTypedArray()) { _, which ->
                val selectedYear = years[which]
                val qList = questionRepo.getQuestionsByYear(selectedYear)
                if (qList.isEmpty()) {
                    Toast.makeText(this, "No questions found for $selectedYear", Toast.LENGTH_SHORT).show()
                } else {
                    if (isMock) {
                        startMockTestSession(qList, "$selectedYear Paper Mock", 25)
                    } else {
                        startPracticeSession(qList, "$selectedYear PYQs")
                    }
                }
            }
            .show()
    }

    // --- Combined Mix Picker (used by PYQs and Mock Test) ---
    private fun openCombinedMixPicker(isMock: Boolean) {
        val options = arrayOf("10 Questions (Quick Drill)", "20 Questions (Standard)", "All Mixed Questions")
        AlertDialog.Builder(this)
            .setTitle(if (isMock) "Select Mock Test Length" else "Select Combined Mix Length")
            .setItems(options) { _, which ->
                val count = when (which) {
                    0 -> 10
                    1 -> 20
                    else -> questionRepo.getQuestions().size
                }
                val questions = questionRepo.getShuffledMix(count)
                if (questions.isEmpty()) {
                    Toast.makeText(this, "No questions available in question bank.", Toast.LENGTH_SHORT).show()
                } else {
                    if (isMock) {
                        val duration = if (count <= 10) 15 else 30
                        startMockTestSession(questions, "Combined Mix Mock ($count Qs)", duration)
                    } else {
                        startPracticeSession(questions, "Combined Mix (${questions.size} Qs)")
                    }
                }
            }
            .show()
    }

    // --- Starting a Practice Session ---
    private fun startPracticeSession(questions: List<GateQuestion>, title: String) {
        activePracticeQuestions = questions
        currentPracticeIndex = 0

        val hubView = practiceView.findViewById<View>(R.id.view_practice_hub)
        val practiceSessionView = practiceView.findViewById<View>(R.id.view_practice_session)
        val mockSessionView = practiceView.findViewById<View>(R.id.view_mock_session)

        hubView.visibility = View.GONE
        practiceSessionView.visibility = View.VISIBLE
        mockSessionView.visibility = View.GONE

        // Hide navigation bar, top bar and lock swipe for immersive full-screen study
        if (::telegramNavBar.isInitialized) telegramNavBar.visibility = View.GONE
        if (::mainTopAppBar.isInitialized) mainTopAppBar.visibility = View.GONE
        if (::viewPager.isInitialized) viewPager.isUserInputEnabled = false

        practiceView.findViewById<TextView>(R.id.text_practice_session_title).text = title
        displayPracticeQuestion()
    }

    private fun displayPracticeQuestion() {
        if (activePracticeQuestions.isEmpty()) return

        val q = activePracticeQuestions[currentPracticeIndex]
        val total = activePracticeQuestions.size

        practiceView.findViewById<TextView>(R.id.text_practice_counter).text = "Question ${currentPracticeIndex + 1} of $total"
        practiceView.findViewById<MaterialButton>(R.id.btn_practice_prev).isEnabled = currentPracticeIndex > 0
        practiceView.findViewById<MaterialButton>(R.id.btn_practice_next).isEnabled = currentPracticeIndex < total - 1

        practiceView.findViewById<TextView>(R.id.badge_practice_subject).text = q.subject
        practiceView.findViewById<TextView>(R.id.badge_practice_year).text = q.year
        practiceView.findViewById<TextView>(R.id.badge_practice_type).text = q.type.name
        practiceView.findViewById<TextView>(R.id.badge_practice_marks).text = "${q.marks} Mark${if (q.marks == 1) "" else "s"}"
        practiceView.findViewById<TextView>(R.id.text_practice_topic).text = "Topic: ${q.topic}"
        practiceView.findViewById<TextView>(R.id.text_practice_body).text = q.questionText

        val btnBookmark = practiceView.findViewById<ImageView>(R.id.btn_practice_bookmark)
        val isBm = questionRepo.isBookmarked(q.id)
        btnBookmark.setImageResource(if (isBm) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        btnBookmark.setColorFilter(ContextCompat.getColor(this, if (isBm) R.color.accent_amber else R.color.text_secondary))

        practiceView.findViewById<View>(R.id.practice_card_solution).visibility = View.GONE

        // Setup answer inputs
        val rgMcq = practiceView.findViewById<RadioGroup>(R.id.practice_radio_group_mcq)
        val containerMsq = practiceView.findViewById<LinearLayout>(R.id.practice_container_msq)
        val containerNat = practiceView.findViewById<LinearLayout>(R.id.practice_container_nat)
        val inputNat = practiceView.findViewById<TextInputEditText>(R.id.practice_input_nat)

        rgMcq.removeAllViews()
        containerMsq.removeAllViews()
        inputNat.text?.clear()

        when (q.type) {
            QuestionType.MCQ -> {
                rgMcq.visibility = View.VISIBLE
                containerMsq.visibility = View.GONE
                containerNat.visibility = View.GONE
                val optionLabels = listOf("A", "B", "C", "D", "E")
                q.options.forEachIndexed { i, opt ->
                    val rb = RadioButton(this)
                    rb.id = View.generateViewId()
                    rb.tag = i
                    val label = optionLabels.getOrElse(i) { "$i" }
                    rb.text = "($label) $opt"
                    rb.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    rb.textSize = 13f
                    rb.setPadding(12, 16, 12, 16)
                    rb.buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_indigo_light))
                    rgMcq.addView(rb)
                }
            }
            QuestionType.MSQ -> {
                rgMcq.visibility = View.GONE
                containerMsq.visibility = View.VISIBLE
                containerNat.visibility = View.GONE
                val optionLabels = listOf("A", "B", "C", "D", "E")
                q.options.forEachIndexed { i, opt ->
                    val cb = MaterialCheckBox(this)
                    cb.tag = i
                    val label = optionLabels.getOrElse(i) { "$i" }
                    cb.text = "($label) $opt"
                    cb.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    cb.textSize = 13f
                    cb.setPadding(12, 16, 12, 16)
                    cb.buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_emerald))
                    containerMsq.addView(cb)
                }
            }
            QuestionType.NAT -> {
                rgMcq.visibility = View.GONE
                containerMsq.visibility = View.GONE
                containerNat.visibility = View.VISIBLE
            }
        }
    }

    private fun evaluatePracticeAnswer() {
        val q = activePracticeQuestions.getOrNull(currentPracticeIndex) ?: return
        val selected = mutableSetOf<Int>()
        var natText: String? = null

        when (q.type) {
            QuestionType.MCQ -> {
                val rg = practiceView.findViewById<RadioGroup>(R.id.practice_radio_group_mcq)
                val checkedId = rg.checkedRadioButtonId
                if (checkedId != -1) {
                    val rb = rg.findViewById<RadioButton>(checkedId)
                    val idx = rb?.tag as? Int
                    if (idx != null) selected.add(idx)
                }
            }
            QuestionType.MSQ -> {
                val container = practiceView.findViewById<LinearLayout>(R.id.practice_container_msq)
                for (i in 0 until container.childCount) {
                    val cb = container.getChildAt(i) as? MaterialCheckBox ?: continue
                    if (cb.isChecked) {
                        val idx = cb.tag as? Int ?: i
                        selected.add(idx)
                    }
                }
            }
            QuestionType.NAT -> {
                val input = practiceView.findViewById<TextInputEditText>(R.id.practice_input_nat)
                natText = input.text?.toString()?.trim()
            }
        }

        val res = questionRepo.verifyAnswer(q, selected, natText)

        val cardSol = practiceView.findViewById<View>(R.id.practice_card_solution)
        val banner = practiceView.findViewById<View>(R.id.practice_banner_status)
        val tvVerdict = practiceView.findViewById<TextView>(R.id.practice_text_verdict)
        val tvUser = practiceView.findViewById<TextView>(R.id.practice_text_user_selection)
        val tvCorrect = practiceView.findViewById<TextView>(R.id.practice_text_correct_selection)
        val tvExpl = practiceView.findViewById<TextView>(R.id.practice_text_explanation)

        cardSol.visibility = View.VISIBLE

        if (res.isCorrect) {
            banner.setBackgroundResource(R.drawable.bg_badge_success)
            tvVerdict.text = "✓ Correct Answer!"
            tvVerdict.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald_text))
        } else {
            banner.setBackgroundResource(R.drawable.bg_badge_warning)
            tvVerdict.text = "✗ Incorrect Answer"
            tvVerdict.setTextColor(ContextCompat.getColor(this, R.color.accent_rose_text))
        }

        tvUser.text = res.userSummary
        tvCorrect.text = res.correctSummary
        tvExpl.text = res.explanation
    }

    // --- Starting a Mock Test Session ---
    private fun startMockTestSession(questions: List<GateQuestion>, title: String, durationMinutes: Int) {
        mockStates = questions.map { MockQuestionState(question = it) }
        currentMockIndex = 0
        mockTotalSeconds = durationMinutes * 60
        mockSecondsLeft = mockTotalSeconds
        mockStartTime = System.currentTimeMillis()
        mockTimerRunning = true

        val hubView = practiceView.findViewById<View>(R.id.view_practice_hub)
        val practiceSessionView = practiceView.findViewById<View>(R.id.view_practice_session)
        val mockSessionView = practiceView.findViewById<View>(R.id.view_mock_session)

        hubView.visibility = View.GONE
        practiceSessionView.visibility = View.GONE
        mockSessionView.visibility = View.VISIBLE

        // Hide navigation bar, top bar and lock swipe for immersive mock exam
        if (::telegramNavBar.isInitialized) telegramNavBar.visibility = View.GONE
        if (::mainTopAppBar.isInitialized) mainTopAppBar.visibility = View.GONE
        if (::viewPager.isInitialized) viewPager.isUserInputEnabled = false

        practiceView.findViewById<TextView>(R.id.text_mock_session_title).text = title

        displayMockQuestion()
        tickMockTimer()
    }

    private fun tickMockTimer() {
        handler.postDelayed({
            if (mockTimerRunning) {
                if (mockSecondsLeft > 0) {
                    mockSecondsLeft--
                    val tv = practiceView.findViewById<TextView>(R.id.text_mock_timer_display)
                    tv.text = String.format(Locale.US, "⏱ %02d:%02d Remaining", mockSecondsLeft / 60, mockSecondsLeft % 60)
                    if (mockSecondsLeft <= 60) {
                        tv.setTextColor(ContextCompat.getColor(this, R.color.accent_rose))
                    } else {
                        tv.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                    }
                    tickMockTimer()
                } else {
                    mockTimerRunning = false
                    Toast.makeText(this, "Time is up! Submitting Mock Test automatically.", Toast.LENGTH_LONG).show()
                    submitMockTest()
                }
            }
        }, 1000)
    }

    private fun saveCurrentMockInput() {
        val state = mockStates.getOrNull(currentMockIndex) ?: return
        when (state.question.type) {
            QuestionType.MCQ -> {
                val rg = practiceView.findViewById<RadioGroup>(R.id.mock_radio_group_mcq)
                val checkedId = rg.checkedRadioButtonId
                state.selectedOptions.clear()
                if (checkedId != -1) {
                    val rb = rg.findViewById<RadioButton>(checkedId)
                    val idx = rb?.tag as? Int
                    if (idx != null) state.selectedOptions.add(idx)
                }
            }
            QuestionType.MSQ -> {
                val container = practiceView.findViewById<LinearLayout>(R.id.mock_container_msq)
                state.selectedOptions.clear()
                for (i in 0 until container.childCount) {
                    val cb = container.getChildAt(i) as? MaterialCheckBox ?: continue
                    if (cb.isChecked) {
                        val idx = cb.tag as? Int ?: i
                        state.selectedOptions.add(idx)
                    }
                }
            }
            QuestionType.NAT -> {
                val input = practiceView.findViewById<TextInputEditText>(R.id.mock_input_nat)
                state.natAnswer = input.text?.toString()?.trim()
            }
        }
    }

    private fun displayMockQuestion() {
        if (mockStates.isEmpty()) return

        val state = mockStates[currentMockIndex]
        val q = state.question
        val total = mockStates.size

        practiceView.findViewById<TextView>(R.id.text_mock_question_counter).text = "Q ${currentMockIndex + 1} of $total"
        practiceView.findViewById<MaterialButton>(R.id.btn_mock_prev).isEnabled = currentMockIndex > 0
        practiceView.findViewById<MaterialButton>(R.id.btn_mock_next).isEnabled = currentMockIndex < total - 1

        practiceView.findViewById<TextView>(R.id.badge_mock_subject).text = q.subject
        practiceView.findViewById<TextView>(R.id.badge_mock_type).text = q.type.name
        val markingText = if (q.type == QuestionType.MCQ) {
            if (q.marks == 1) "+1.0 / -0.33" else "+2.0 / -0.66"
        } else {
            "+${q.marks}.0 / 0.0"
        }
        practiceView.findViewById<TextView>(R.id.badge_mock_marks).text = markingText
        practiceView.findViewById<TextView>(R.id.text_mock_topic).text = "Topic: ${q.topic}"
        practiceView.findViewById<TextView>(R.id.text_mock_question_body).text = q.questionText

        val btnReview = practiceView.findViewById<MaterialButton>(R.id.btn_mock_mark_review)
        btnReview.text = if (state.isMarkedForReview) "★ Marked for Review" else "Mark for Review"

        val rgMcq = practiceView.findViewById<RadioGroup>(R.id.mock_radio_group_mcq)
        val containerMsq = practiceView.findViewById<LinearLayout>(R.id.mock_container_msq)
        val containerNat = practiceView.findViewById<LinearLayout>(R.id.mock_container_nat)
        val inputNat = practiceView.findViewById<TextInputEditText>(R.id.mock_input_nat)

        rgMcq.removeAllViews()
        containerMsq.removeAllViews()
        inputNat.text?.clear()

        when (q.type) {
            QuestionType.MCQ -> {
                rgMcq.visibility = View.VISIBLE
                containerMsq.visibility = View.GONE
                containerNat.visibility = View.GONE
                val optionLabels = listOf("A", "B", "C", "D", "E")
                q.options.forEachIndexed { i, opt ->
                    val rb = RadioButton(this)
                    rb.id = View.generateViewId()
                    rb.tag = i
                    val label = optionLabels.getOrElse(i) { "$i" }
                    rb.text = "($label) $opt"
                    rb.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    rb.textSize = 13f
                    rb.setPadding(12, 16, 12, 16)
                    rb.buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_indigo_light))
                    if (i in state.selectedOptions) {
                        rb.isChecked = true
                    }
                    rgMcq.addView(rb)
                }
            }
            QuestionType.MSQ -> {
                rgMcq.visibility = View.GONE
                containerMsq.visibility = View.VISIBLE
                containerNat.visibility = View.GONE
                val optionLabels = listOf("A", "B", "C", "D", "E")
                q.options.forEachIndexed { i, opt ->
                    val cb = MaterialCheckBox(this)
                    cb.tag = i
                    val label = optionLabels.getOrElse(i) { "$i" }
                    cb.text = "($label) $opt"
                    cb.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    cb.textSize = 13f
                    cb.setPadding(12, 16, 12, 16)
                    cb.buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_emerald))
                    if (i in state.selectedOptions) {
                        cb.isChecked = true
                    }
                    containerMsq.addView(cb)
                }
            }
            QuestionType.NAT -> {
                rgMcq.visibility = View.GONE
                containerMsq.visibility = View.GONE
                containerNat.visibility = View.VISIBLE
                if (!state.natAnswer.isNullOrBlank()) {
                    inputNat.setText(state.natAnswer)
                }
            }
        }

        refreshMockPalette()
    }

    private fun refreshMockPalette() {
        val paletteContainer = practiceView.findViewById<LinearLayout>(R.id.layout_mock_palette_container)
        paletteContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        mockStates.forEachIndexed { idx, st ->
            val tv = inflater.inflate(R.layout.item_mock_palette, paletteContainer, false) as TextView
            tv.text = "${idx + 1}"

            when {
                idx == currentMockIndex -> {
                    tv.setBackgroundResource(R.drawable.bg_badge_primary)
                    tv.setTextColor(ContextCompat.getColor(this, R.color.btn_text_pure_white))
                }
                st.isMarkedForReview -> {
                    tv.setBackgroundResource(R.drawable.bg_badge_warning)
                    tv.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                }
                st.isAnswered -> {
                    tv.setBackgroundResource(R.drawable.bg_badge_success)
                    tv.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
                }
                else -> {
                    tv.setBackgroundResource(R.drawable.bg_card_secondary)
                    tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }

            tv.setOnClickListener {
                saveCurrentMockInput()
                currentMockIndex = idx
                displayMockQuestion()
            }

            paletteContainer.addView(tv)
        }
    }

    private fun submitMockTest() {
        mockTimerRunning = false
        saveCurrentMockInput()

        val timeTakenSeconds = ((System.currentTimeMillis() - mockStartTime) / 1000).toInt()
        val result = questionRepo.evaluateMockTest(mockStates, timeTakenSeconds)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_mock_result, null, false)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<TextView>(R.id.text_mock_test_title).text =
            practiceView.findViewById<TextView>(R.id.text_mock_session_title).text
        dialogView.findViewById<TextView>(R.id.text_mock_final_score).text =
            "${result.totalScore} / ${result.maxScore}"

        val attempted = result.correctCount + result.incorrectCount
        val accuracy = if (attempted == 0) 0 else ((result.correctCount * 100.0) / attempted).toInt()
        dialogView.findViewById<TextView>(R.id.text_mock_accuracy).text =
            "Accuracy: $accuracy% · Negative marking applied (-1/3, -2/3)"

        dialogView.findViewById<TextView>(R.id.text_mock_correct_count).text = "${result.correctCount}"
        dialogView.findViewById<TextView>(R.id.text_mock_incorrect_count).text = "${result.incorrectCount}"
        dialogView.findViewById<TextView>(R.id.text_mock_unattempted_count).text = "${result.unattemptedCount}"

        dialogView.findViewById<TextView>(R.id.text_mock_time_taken).text =
            String.format(Locale.US, "⏱ Time Elapsed: %dm %ds", timeTakenSeconds / 60, timeTakenSeconds % 60)

        dialogView.findViewById<View>(R.id.btn_mock_review_solutions).setOnClickListener {
            dialog.dismiss()
            startPracticeSession(mockStates.map { it.question }, "Reviewing Mock Test Solutions")
        }

        dialogView.findViewById<View>(R.id.btn_mock_finish_done).setOnClickListener {
            dialog.dismiss()
            showPracticeHub()
        }

        dialog.show()
    }

    // --- Import Source Dialog (Local File, Paste JSON, Web URL) ---
    private fun openImportSourceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_import_source, null, false)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // 1. From Local File
        dialogView.findViewById<View>(R.id.btn_source_local_file).setOnClickListener {
            dialog.dismiss()
            jsonFilePicker.launch("application/json")
        }

        // 2. Paste JSON
        dialogView.findViewById<View>(R.id.btn_source_paste_json).setOnClickListener {
            dialog.dismiss()
            openPasteJsonDialog()
        }

        // 3. Web URL / GitHub Source
        dialogView.findViewById<View>(R.id.btn_source_web_url).setOnClickListener {
            dialog.dismiss()
            openWebUrlImportDialog()
        }

        // 4. View Expected Format
        dialogView.findViewById<View>(R.id.btn_view_json_format).setOnClickListener {
            dialog.dismiss()
            showSampleJsonFormatGuide()
        }

        dialog.show()
    }

    private fun openPasteJsonDialog() {
        val input = EditText(this)
        input.hint = "Paste questions JSON here [ { ... } ]"
        input.minLines = 8
        input.setPadding(32, 24, 32, 24)
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        input.textSize = 12f

        AlertDialog.Builder(this)
            .setTitle("Paste Questions JSON")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val content = input.text.toString().trim()
                if (content.isEmpty()) {
                    Toast.makeText(this, "Content is empty.", Toast.LENGTH_SHORT).show()
                } else {
                    val (success, msg) = questionRepo.importQuestionsFromJson(content)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshPracticeHub()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openWebUrlImportDialog() {
        val input = EditText(this)
        input.hint = "https://example.com/gate_questions.json"
        input.setPadding(32, 24, 32, 24)
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        input.textSize = 13f

        AlertDialog.Builder(this)
            .setTitle("Import from Web URL")
            .setMessage("Enter direct raw JSON URL for GATE CSE questions:")
            .setView(input)
            .setPositiveButton("Download & Import") { _, _ ->
                val urlString = input.text.toString().trim()
                if (!urlString.startsWith("http://") && !urlString.startsWith("https://")) {
                    Toast.makeText(this, "Enter a valid HTTP/HTTPS URL.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Fetching questions...", Toast.LENGTH_SHORT).show()
                    executor.execute {
                        try {
                            val url = URL(urlString)
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 8000
                            conn.readTimeout = 8000
                            val text = conn.inputStream.bufferedReader().readText()
                            handler.post {
                                val (success, msg) = questionRepo.importQuestionsFromJson(text)
                                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                                refreshPracticeHub()
                            }
                        } catch (e: Exception) {
                            handler.post {
                                Toast.makeText(this, "Fetch error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSampleJsonFormatGuide() {
        val sample = """
[
  {
    "id": "gate_2024_os_01",
    "subject": "Operating Systems",
    "topic": "Deadlocks",
    "year": "GATE CSE 2024",
    "marks": 2,
    "type": "MCQ",
    "questionText": "What are the 4 necessary conditions for deadlock?",
    "options": [
      "Mutual Exclusion, Hold and Wait, No Preemption, Circular Wait",
      "Paging, Segmentation, TLB, Cache",
      "FCFS, SJF, SRTF, RR",
      "None of the above"
    ],
    "correctAnswers": [0],
    "explanation": "Coffman conditions: Mutual Exclusion, Hold and Wait, No Preemption, and Circular Wait."
  }
]
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("JSON Schema Reference")
            .setMessage(sample)
            .setPositiveButton("Got It", null)
            .show()
    }

    // -------------------------------------------------------------
    // Settings Tab Logic (Focus Guard, Timer Rules, Goals & Data)
    // -------------------------------------------------------------
    private fun setupSettingsTab() {
        // --- 1. Focus Guard & App Blocker ---
        val switchShorts = settingsView.findViewById<MaterialSwitch>(R.id.switch_block_shorts)
        switchShorts.isChecked = prefs.getBoolean(KEY_BLOCK_YOUTUBE_SHORTS, true)
        switchShorts.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_BLOCK_YOUTUBE_SHORTS, isChecked).apply()
        }

        val switchShortVideos = settingsView.findViewById<MaterialSwitch>(R.id.switch_block_short_videos)
        switchShortVideos.isChecked = prefs.getBoolean(KEY_BLOCK_SHORT_VIDEOS_60S, true)
        switchShortVideos.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_BLOCK_SHORT_VIDEOS_60S, isChecked).apply()
        }

        val selectedApps = getSavedBlockedApps().toMutableSet()
        selectedApps.remove("com.google.android.youtube") // Keep YouTube allowed

        fun bindAppSwitch(switchId: Int, pkg: String) {
            val sw = settingsView.findViewById<MaterialSwitch>(switchId)
            sw.isChecked = pkg in selectedApps
            sw.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedApps.add(pkg) else selectedApps.remove(pkg)
                prefs.edit().putStringSet(KEY_BLOCKED, selectedApps).apply()
            }
        }

        bindAppSwitch(R.id.switch_block_instagram, "com.instagram.android")
        bindAppSwitch(R.id.switch_block_threads, "com.threads.android")
        bindAppSwitch(R.id.switch_block_facebook, "com.facebook.katana")
        bindAppSwitch(R.id.switch_block_snapchat, "com.snapchat.android")

        // Telegram allowlist
        val switchTelegram = settingsView.findViewById<MaterialSwitch>(R.id.switch_telegram_filter)
        val inputTelegram = settingsView.findViewById<TextInputEditText>(R.id.input_telegram_allowed)
        val btnSaveTelegram = settingsView.findViewById<MaterialButton>(R.id.btn_save_telegram)

        switchTelegram.isChecked = prefs.getBoolean(KEY_TELEGRAM_FILTER, true)
        switchTelegram.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_TELEGRAM_FILTER, isChecked).apply()
        }

        val existingAllowed = getSavedAllowedTelegram()
        inputTelegram.setText(existingAllowed.joinToString("\n"))

        btnSaveTelegram.setOnClickListener {
            val lines = inputTelegram.text?.toString()?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet() ?: emptySet()
            prefs.edit().putStringSet(KEY_TELEGRAM_ALLOWED, lines).apply()
            refreshTelegramChips()
            Toast.makeText(this, "Telegram allowlist updated", Toast.LENGTH_SHORT).show()
        }

        refreshTelegramChips()

        // --- 2. Timer & Session Rules ---
        val switchStrict = settingsView.findViewById<MaterialSwitch>(R.id.switch_strict_lockout)
        switchStrict.isChecked = prefs.getBoolean(KEY_STRICT_LOCKOUT, false)
        switchStrict.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_STRICT_LOCKOUT, isChecked).apply()
        }

        val switchHaptic = settingsView.findViewById<MaterialSwitch>(R.id.switch_haptic_feedback)
        switchHaptic.isChecked = prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
        switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, isChecked).apply()
        }

        // --- 3. GATE Exam Target Goal ---
        val chipStreamCse = settingsView.findViewById<Chip>(R.id.chip_stream_cse)
        val chipStreamDa = settingsView.findViewById<Chip>(R.id.chip_stream_da)
        val savedStream = prefs.getString(KEY_TARGET_STREAM, "CSE") ?: "CSE"
        if (savedStream == "DA") chipStreamDa.isChecked = true else chipStreamCse.isChecked = true

        chipStreamCse.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) prefs.edit().putString(KEY_TARGET_STREAM, "CSE").apply()
        }
        chipStreamDa.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) prefs.edit().putString(KEY_TARGET_STREAM, "DA").apply()
        }

        val chipYear2025 = settingsView.findViewById<Chip>(R.id.chip_year_2025)
        val chipYear2026 = settingsView.findViewById<Chip>(R.id.chip_year_2026)
        val chipYear2027 = settingsView.findViewById<Chip>(R.id.chip_year_2027)
        val savedYear = prefs.getString(KEY_TARGET_YEAR, "2026") ?: "2026"
        when (savedYear) {
            "2025" -> chipYear2025.isChecked = true
            "2027" -> chipYear2027.isChecked = true
            else -> chipYear2026.isChecked = true
        }

        chipYear2025.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) prefs.edit().putString(KEY_TARGET_YEAR, "2025").apply()
        }
        chipYear2026.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) prefs.edit().putString(KEY_TARGET_YEAR, "2026").apply()
        }
        chipYear2027.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) prefs.edit().putString(KEY_TARGET_YEAR, "2027").apply()
        }

        // --- 4. Data, Backup & Reset ---
        val btnExport = settingsView.findViewById<MaterialButton>(R.id.btn_export_backup)
        val btnReset = settingsView.findViewById<MaterialButton>(R.id.btn_reset_progress)

        btnExport.setOnClickListener {
            exportProgressBackup()
        }

        btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Syllabus Progress? ⚠️")
                .setMessage("This will clear all completed topics and personal notes. This action cannot be undone.")
                .setPositiveButton("Reset Everything") { _, _ ->
                    val syllabusPrefs = getSharedPreferences("gate_focus_syllabus_prefs", MODE_PRIVATE)
                    syllabusPrefs.edit().clear().apply()
                    setupTrackerTab()
                    refreshTrackerProgress()
                    Toast.makeText(this, "Syllabus progress has been reset", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // --- 5. System Permissions & Health ---
        val btnManageAcc = settingsView.findViewById<MaterialButton>(R.id.btn_manage_accessibility)
        val btnManageNotif = settingsView.findViewById<MaterialButton>(R.id.btn_manage_notification)

        btnManageAcc.setOnClickListener {
            openAccessibilitySettings()
        }

        btnManageNotif.setOnClickListener {
            openNotificationListenerSettings()
        }

        refreshSettingsTabStatus()
    }

    private fun refreshSettingsTabStatus() {
        val badgeAcc = settingsView.findViewById<TextView>(R.id.badge_accessibility_status) ?: return
        val badgeNotif = settingsView.findViewById<TextView>(R.id.badge_notification_status) ?: return

        val isAcc = isAccessibilityEnabled()
        if (isAcc) {
            badgeAcc.text = "Active ✓ (Blocking Enforced)"
            badgeAcc.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald_text))
        } else {
            badgeAcc.text = "Permission Needed ⚠️ (Blocking Inactive)"
            badgeAcc.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
        }

        val isNotif = isNotificationListenerEnabled()
        if (isNotif) {
            badgeNotif.text = "Active ✓ (Telegram Filtering Enabled)"
            badgeNotif.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald_text))
        } else {
            badgeNotif.text = "Disabled (Notifications Unfiltered)"
            badgeNotif.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun exportProgressBackup() {
        try {
            val root = JSONObject()
            root.put("app", "GateFocus")
            root.put("export_version", 1)
            root.put("export_timestamp", System.currentTimeMillis())

            val subjects = syllabusRepo.getSubjects()
            val completedList = JSONArray()
            val notesObj = JSONObject()

            for (subject in subjects) {
                for (topic in subject.topics) {
                    val progress = syllabusRepo.getTopicProgress(topic.id)
                    if (progress.isCompleted) {
                        completedList.put(topic.id)
                    }
                    val notes = syllabusRepo.getTopicNotes(topic.id)
                    if (notes.isNotBlank()) {
                        notesObj.put(topic.id, notes)
                    }
                }
            }

            root.put("completed_topics", completedList)
            root.put("topic_notes", notesObj)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "GateFocus_Backup_${System.currentTimeMillis()}.json")
                putExtra(Intent.EXTRA_TEXT, root.toString(2))
            }
            startActivity(Intent.createChooser(shareIntent, "Export GateFocus Progress Backup"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshTelegramChips() {
        val container = settingsView.findViewById<LinearLayout>(R.id.layout_telegram_chips) ?: return
        container.removeAllViews()
        val allowed = getSavedAllowedTelegram()
        if (allowed.isEmpty()) {
            val emptyTv = TextView(this)
            emptyTv.text = "No allowed channels. Notifications will be silenced during study mode."
            emptyTv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            emptyTv.textSize = 12f
            container.addView(emptyTv)
        } else {
            allowed.forEach { channelName ->
                val tv = TextView(this)
                tv.text = "✓ $channelName"
                tv.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald_text))
                tv.textSize = 13f
                tv.setPadding(0, 4, 0, 4)
                container.addView(tv)
            }
        }
    }

    // -------------------------------------------------------------
    // Library Tab Logic
    // -------------------------------------------------------------
    private fun setupLibraryTab() {
        val inputTitle = libraryView.findViewById<TextInputEditText>(R.id.input_video_title)
        val inputId = libraryView.findViewById<TextInputEditText>(R.id.input_video_id)
        val btnAdd = libraryView.findViewById<MaterialButton>(R.id.btn_add_video)
        val btnStarter = libraryView.findViewById<MaterialButton>(R.id.btn_load_starter)

        btnAdd.setOnClickListener {
            val title = inputTitle.text?.toString()?.trim().orEmpty()
            val videoId = inputId.text?.toString()?.trim().orEmpty()

            if (title.isBlank() || !videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                Toast.makeText(this, "Enter lecture title & valid 11-char YouTube ID", Toast.LENGTH_SHORT).show()
            } else {
                val videos = readVideos()
                videos.put(JSONObject().put("title", title).put("id", videoId))
                prefs.edit().putString(KEY_VIDEOS, videos.toString()).apply()

                inputTitle.text?.clear()
                inputId.text?.clear()
                refreshLibraryList()
                Toast.makeText(this, "Added to GATE library", Toast.LENGTH_SHORT).show()
            }
        }

        btnStarter.setOnClickListener {
            loadStarterLectures()
        }

        refreshLibraryList()
    }

    private fun loadStarterLectures() {
        val starterList = listOf(
            Pair("OS — CPU Scheduling Algorithms & Gantt Charts", "ewdnODPnGvI"),
            Pair("DBMS — Normalization, 1NF to BCNF with Keys", "5fs1HDom6eU"),
            Pair("Algorithms — Dynamic Programming Foundations", "oBt53YbR9Kk"),
            Pair("Computer Networks — TCP 3-Way Handshake & Flow Control", "JFF2vJaN0Cw")
        )

        val existing = readVideos()
        val existingIds = (0 until existing.length()).map { existing.optJSONObject(it)?.optString("id") }.toSet()

        var addedCount = 0
        starterList.forEach { (title, id) ->
            if (id !in existingIds) {
                existing.put(JSONObject().put("title", title).put("id", id))
                addedCount++
            }
        }

        prefs.edit().putString(KEY_VIDEOS, existing.toString()).apply()
        refreshLibraryList()
        Toast.makeText(this, "Added $addedCount recommended GATE lectures!", Toast.LENGTH_SHORT).show()
    }

    private fun refreshLibraryList() {
        val videosList = libraryView.findViewById<LinearLayout>(R.id.layout_videos_list)
        val emptyLayout = libraryView.findViewById<View>(R.id.layout_empty_library)
        val textCount = libraryView.findViewById<TextView>(R.id.text_lectures_count)

        videosList.removeAllViews()
        val videos = readVideos()
        val count = videos.length()

        textCount.text = "$count lecture${if (count == 1) "" else "s"}"

        if (count == 0) {
            emptyLayout.visibility = View.VISIBLE
            videosList.visibility = View.GONE
        } else {
            emptyLayout.visibility = View.GONE
            videosList.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(this)
            for (i in 0 until count) {
                val item = videos.optJSONObject(i) ?: continue
                val card = inflater.inflate(R.layout.item_curated_video, videosList, false)

                val titleView = card.findViewById<TextView>(R.id.text_video_title)
                val idView = card.findViewById<TextView>(R.id.text_video_id)
                val btnWatch = card.findViewById<MaterialButton>(R.id.btn_watch_video)
                val btnDelete = card.findViewById<ImageView>(R.id.btn_delete_video)

                val lectureTitle = item.optString("title")
                val lectureId = item.optString("id")

                titleView.text = lectureTitle
                idView.text = "ID: $lectureId"

                btnWatch.setOnClickListener {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra("title", lectureTitle)
                            .putExtra("video_id", lectureId)
                    )
                }

                btnDelete.setOnClickListener {
                    val updated = readVideos()
                    updated.remove(i)
                    prefs.edit().putString(KEY_VIDEOS, updated.toString()).apply()
                    refreshLibraryList()
                }

                videosList.addView(card)
            }
        }
    }

    // -------------------------------------------------------------
    // Helper & Service Check Methods
    // -------------------------------------------------------------
    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Please open Android Settings > Accessibility", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Please open Settings > Notification Access", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val expectedComponent = ComponentName(this, AppBlockerService::class.java)
            val expectedShort = expectedComponent.flattenToShortString()
            val expectedLong = expectedComponent.flattenToString()

            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!enabledServices.isNullOrEmpty()) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabledServices)
                while (splitter.hasNext()) {
                    val service = splitter.next()
                    if (service.equals(expectedShort, ignoreCase = true) ||
                        service.equals(expectedLong, ignoreCase = true) ||
                        service.contains(packageName) && service.contains(AppBlockerService::class.java.simpleName)
                    ) {
                        return true
                    }
                }
            }

            val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val list = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (list != null) {
                for (info in list) {
                    val id = info.id
                    if (id != null && (
                        id.equals(expectedShort, ignoreCase = true) ||
                        id.equals(expectedLong, ignoreCase = true) ||
                        (id.contains(packageName) && id.contains(AppBlockerService::class.java.simpleName))
                    )) {
                        return true
                    }
                    val sInfo = info.resolveInfo?.serviceInfo
                    if (sInfo != null && sInfo.packageName == packageName && sInfo.name == AppBlockerService::class.java.name) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun getSavedBlockedApps(): Set<String> {
        return try {
            prefs.getStringSet(KEY_BLOCKED, defaultBlockedApps) ?: defaultBlockedApps
        } catch (_: Exception) {
            defaultBlockedApps
        }
    }

    private fun getSavedAllowedTelegram(): Set<String> {
        return try {
            prefs.getStringSet(KEY_TELEGRAM_ALLOWED, emptySet()) ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun readVideos(): JSONArray = try {
        JSONArray(prefs.getString(KEY_VIDEOS, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun formatTime(seconds: Int) = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
