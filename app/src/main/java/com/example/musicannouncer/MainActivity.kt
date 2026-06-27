package com.example.musicannouncer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectApps: Button
    private lateinit var btnPermission: Button
    private lateinit var btnSaveSettings: Button
    private lateinit var btnTestTts: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvAppList: TextView
    
    // TTS UI
    private lateinit var spinnerEngineKorean: Spinner
    private lateinit var spinnerEngineEnglish: Spinner
    private lateinit var spinnerEnglishVariant: Spinner
    private lateinit var seekbarSpeed: SeekBar
    private lateinit var seekbarVolume: SeekBar
    private lateinit var tvSpeedLabel: TextView
    private lateinit var tvVolumeLabel: TextView

    private var testTtsKorean: android.speech.tts.TextToSpeech? = null
    private var testTtsEnglish: android.speech.tts.TextToSpeech? = null
    private var availableEngines: List<android.speech.tts.TextToSpeech.EngineInfo> = emptyList()

    private val logReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            appendLog(log)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Views
        btnPermission = findViewById(R.id.btn_permission)
        btnSelectApps = findViewById(R.id.btn_select_apps)
        btnSaveSettings = findViewById(R.id.btn_save_settings)
        btnTestTts = findViewById(R.id.btn_test_tts)
        tvStatus = findViewById(R.id.tv_status)
        tvLog = findViewById(R.id.tv_log)
        tvAppList = findViewById(R.id.tv_app_list)
        
        spinnerEngineKorean = findViewById(R.id.spinner_engine_korean)
        spinnerEngineEnglish = findViewById(R.id.spinner_engine_english)
        spinnerEnglishVariant = findViewById(R.id.spinner_english_variant)
        seekbarSpeed = findViewById(R.id.seekbar_speed)
        seekbarVolume = findViewById(R.id.seekbar_volume)
        tvSpeedLabel = findViewById(R.id.tv_speed_label)
        tvVolumeLabel = findViewById(R.id.tv_volume_label)

        loadSettings() 
        initTtsAndUI()

        btnSelectApps.setOnClickListener {
            showAppPickerDialog()
        }

        btnSaveSettings.setOnClickListener {
            saveSettings()
        }

        btnTestTts.setOnClickListener {
            testVoice()
        }

        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        
        // Seekbar Listeners
        seekbarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                 val speed = progress.coerceAtLeast(1) / 10.0f
                  tvSpeedLabel.text = "Speed: ${speed}x"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekbarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolumeLabel.text = "Volume: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Register Log Receiver
        val filter = android.content.IntentFilter("com.example.musicannouncer.LOG")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    private fun initTtsAndUI() {
        // Initialize simple TTS just to get engines
        testTtsKorean = android.speech.tts.TextToSpeech(this) { status ->
             if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                 availableEngines = testTtsKorean?.engines ?: emptyList()
                 setupEngineSpinners()
             }
        }
        
        // Setup English Variant Spinner (Static list for simplicity)
        val englishVariants = listOf("English (US)", "English (UK)")
        val englishAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, englishVariants)
        englishAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEnglishVariant.adapter = englishAdapter
        
        // Restore English Variant Selection
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedEnglishVariant = prefs.getString("tts_locale_en", "English (US)")
        val variantIndex = englishVariants.indexOf(savedEnglishVariant)
        if (variantIndex >= 0) spinnerEnglishVariant.setSelection(variantIndex)
    }
    
    private fun setupEngineSpinners() {
        val engineNames = if (availableEngines.isEmpty()) listOf("Default") else availableEngines.map { it.label }
        val koreanAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, engineNames)
        koreanAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEngineKorean.adapter = koreanAdapter

        val englishAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, engineNames)
        englishAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEngineEnglish.adapter = englishAdapter
        
        // Restore Engine selections
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        restoreEngineSelection(spinnerEngineKorean, prefs.getString("tts_engine_ko", ""))
        restoreEngineSelection(spinnerEngineEnglish, prefs.getString("tts_engine_en", ""))
    }

    private fun restoreEngineSelection(spinner: Spinner, savedEnginePkg: String?) {
        if (savedEnginePkg.isNullOrEmpty()) return
        val index = availableEngines.indexOfFirst { it.name == savedEnginePkg }
        if (index >= 0) spinner.setSelection(index)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
        testTtsKorean?.shutdown()
        testTtsEnglish?.shutdown()
    }
    
    private fun testVoice() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val speed = prefs.getFloat("tts_speed", 1.0f).coerceAtLeast(0.1f)
        val volume = prefs.getInt("tts_volume", 100) / 100.0f
        val params = Bundle()
        params.putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)

        btnTestTts.isEnabled = false

        testTtsKorean?.stop()
        testTtsKorean?.shutdown()
        testTtsEnglish?.stop()
        testTtsEnglish?.shutdown()

        val koreanEngine = selectedEnginePackage(spinnerEngineKorean)
        val englishEngine = selectedEnginePackage(spinnerEngineEnglish)
        val englishLocale = englishLocaleFromSelection()

        appendLog("Test Voice: Korean then English. Speed=$speed, Vol=${prefs.getInt("tts_volume", 100)}%")

        var koreanTts: android.speech.tts.TextToSpeech? = null
        var englishTts: android.speech.tts.TextToSpeech? = null

        fun speakEnglishTest() {
            val englishListener = android.speech.tts.TextToSpeech.OnInitListener { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    englishTts?.setLanguage(englishLocale)
                    englishTts?.setSpeechRate(speed)
                    englishTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            runOnUiThread { btnTestTts.isEnabled = true }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            runOnUiThread { btnTestTts.isEnabled = true }
                        }
                    })
                    englishTts?.speak("This is a test voice.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "TEST_EN")
                } else {
                    runOnUiThread {
                        appendLog("English test TTS initialization failed")
                        btnTestTts.isEnabled = true
                    }
                }
            }
            englishTts = createTextToSpeech(englishEngine, englishListener)
            testTtsEnglish = englishTts
        }

        val koreanListener = android.speech.tts.TextToSpeech.OnInitListener { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                koreanTts?.setLanguage(java.util.Locale.KOREAN)
                koreanTts?.setSpeechRate(speed)
                koreanTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        speakEnglishTest()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        speakEnglishTest()
                    }
                })
                koreanTts?.speak("테스트 음성입니다.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "TEST_KO")
            } else {
                runOnUiThread { appendLog("Korean test TTS initialization failed") }
                speakEnglishTest()
            }
        }
        koreanTts = createTextToSpeech(koreanEngine, koreanListener)
        testTtsKorean = koreanTts
    }

    private fun selectedEnginePackage(spinner: Spinner): String? {
        return if (availableEngines.isNotEmpty() && spinner.selectedItemPosition >= 0) {
            availableEngines[spinner.selectedItemPosition].name
        } else {
            null
        }
    }

    private fun createTextToSpeech(
        enginePkg: String?,
        listener: android.speech.tts.TextToSpeech.OnInitListener
    ): android.speech.tts.TextToSpeech {
        return if (enginePkg.isNullOrEmpty()) {
            android.speech.tts.TextToSpeech(this, listener)
        } else {
            android.speech.tts.TextToSpeech(this, listener, enginePkg)
        }
    }

    private fun englishLocaleFromSelection(): java.util.Locale {
        return when (spinnerEnglishVariant.selectedItem?.toString()) {
            "English (UK)" -> java.util.Locale.UK
            else -> java.util.Locale.US
        }
    }

    private fun appendLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val current = tvLog.text.toString()
        tvLog.text = "[$time] $msg\n$current"
    }

    private fun showAppPickerDialog() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        
        val appList = resolveInfos.map { 
            val title = it.loadLabel(pm).toString()
            val packageName = it.activityInfo.packageName
            Pair(title, packageName)
        }.sortedBy { it.first } 
        
        val appNames = appList.map { it.first }.toTypedArray()
        val checkedItems = BooleanArray(appList.size)
        
        // Pre-check currently allowed apps
        val prefs = getSharedPreferences("allowed_apps", Context.MODE_PRIVATE)
        val currentPackages = prefs.getStringSet("packages", setOf()) ?: setOf()
            
        appList.forEachIndexed { index, pair ->
            if (currentPackages.contains(pair.second)) {
                checkedItems[index] = true
            }
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Music Apps")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val selectedPackages = mutableSetOf<String>()
                val selectedNames = mutableListOf<String>()
                
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) {
                        selectedPackages.add(appList[i].second)
                        selectedNames.add(appList[i].first)
                    }
                }
                
                // Save Logic
                val editor = prefs.edit()
                editor.putStringSet("packages", selectedPackages)
                editor.putString("app_names", selectedNames.joinToString(", ")) // For display
                editor.apply()
                
                updateAppListDisplay()
                appendLog("Updated app selection")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        checkPermission()
    }

    private fun checkPermission() {
        val cn = ComponentName(this, MusicNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val enabled = flat != null && flat.contains(cn.flattenToString())
        
        if (enabled) {
            tvStatus.text = "Music Announcer: Active"
            btnPermission.isEnabled = false
            btnPermission.text = "Permission Granted"
        } else {
            tvStatus.text = "Music Announcer: Inactive"
            btnPermission.isEnabled = true
            btnPermission.text = "Enable Notification Access"
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
        
        // Speed
        val speedVal = seekbarSpeed.progress.coerceAtLeast(1) / 10.0f
        prefs.putFloat("tts_speed", speedVal)
        
        // Volume
        val volumeVal = seekbarVolume.progress
        prefs.putInt("tts_volume", volumeVal)
        
        // Per-language engines
        selectedEnginePackage(spinnerEngineKorean)?.let { prefs.putString("tts_engine_ko", it) }
        selectedEnginePackage(spinnerEngineEnglish)?.let { prefs.putString("tts_engine_en", it) }

        // English locale variant
        val englishVariant = spinnerEnglishVariant.selectedItem?.toString() ?: "English (US)"
        prefs.putString("tts_locale_en", englishVariant)
        
        prefs.apply()
        
        Toast.makeText(this, "Settings Saved. Restart Service to Apply.", Toast.LENGTH_SHORT).show()
        appendLog("Settings Saved. Speed: $speedVal, Vol: $volumeVal%")
        
        // Notify Service to update config? 
        // For simplicity, we can rely on shared prefs polling or broadcast, 
        // but user might need to restart service or we send an intent.
        val intent = Intent(this, MusicNotificationListenerService::class.java)
        intent.action = "UPDATE_CONFIG"
        startService(intent) // Triggers onStartCommand
    }

    private fun loadSettings() {
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        val speed = appPrefs.getFloat("tts_speed", 1.0f).coerceAtLeast(0.1f)
        val vol = appPrefs.getInt("tts_volume", 100)
        
        seekbarSpeed.progress = (speed * 10).toInt()
        tvSpeedLabel.text = "Speed: ${speed}x"
        
        seekbarVolume.progress = vol
        tvVolumeLabel.text = "Volume: $vol%"
        
        updateAppListDisplay()
    }
    
    private fun updateAppListDisplay() {
        val appsPrefs = getSharedPreferences("allowed_apps", Context.MODE_PRIVATE)
        val names = appsPrefs.getString("app_names", "")
        if (!names.isNullOrEmpty()) {
            tvAppList.text = names
        } else {
             tvAppList.text = "No specific apps selected (Defaulting to Spotify/Youtube Music)"
        }
    }
}
