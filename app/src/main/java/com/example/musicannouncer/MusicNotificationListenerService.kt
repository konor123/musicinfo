package com.example.musicannouncer

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class MusicNotificationListenerService : NotificationListenerService() {

    private data class Segment(val lang: String, val text: String)
    private data class RegisteredController(val controller: MediaController, val callback: MediaController.Callback)

    private var ttsKorean: TextToSpeech? = null
    private var ttsEnglish: TextToSpeech? = null
    private var koreanReady = false
    private var englishReady = false
    private var koreanAvailable = true
    private var englishAvailable = true
    private var lastSpokenTitle: String? = null
    private var lastSpokenTime: Long = 0
    private val COOLDOWN_MS = 1000L // 1 second cooldown
    private val TAG = "MusicAnnouncer"
    private val MIN_SEGMENT_CHARS = 2
    private val retryHandler = Handler(Looper.getMainLooper())

    private val pendingSegments = mutableListOf<Segment>()
    private var isSpeaking = false
    private val registeredControllers = mutableMapOf<String, RegisteredController>()
    
    private var mediaSessionManager: MediaSessionManager? = null
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        processControllers(controllers)
    }

    override fun onCreate() {
        super.onCreate()
        initTtsWithConfig() // Load Config on start
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        broadcastLog("Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_CONFIG") {
            broadcastLog("Updating Configuration...")
            initTtsWithConfig()
        } else {
            broadcastLog("Service Started")
            startForegroundService()
        }
        return START_STICKY
    }
    
    @Synchronized
    private fun initTtsWithConfig() {
        // Shutdown old TTS
        ttsKorean?.stop()
        ttsKorean?.shutdown()
        ttsEnglish?.stop()
        ttsEnglish?.shutdown()
        pendingSegments.clear()
        isSpeaking = false
        koreanReady = false
        englishReady = false
        koreanAvailable = true
        englishAvailable = true
        
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val koreanEnginePkg = prefs.getString("tts_engine_ko", null)
        val englishEnginePkg = prefs.getString("tts_engine_en", null)
        val englishLocale = englishLocaleFromPrefs(prefs.getString("tts_locale_en", "English (US)"))

        ttsKorean = createTtsForLanguage("ko", koreanEnginePkg, Locale.KOREAN)
        ttsEnglish = createTtsForLanguage("en", englishEnginePkg, englishLocale)
    }

    private fun englishLocaleFromPrefs(value: String?): Locale {
        return when (value) {
            "English (UK)" -> Locale.UK
            else -> Locale.US
        }
    }

    private fun createTtsForLanguage(tag: String, enginePkg: String?, locale: Locale): TextToSpeech {
        var instance: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                instance?.setOnUtteranceProgressListener(utteranceListener)
                val result = instance?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    markTtsReady(tag, ready = true, available = true)
                    broadcastLog("Error: TTS $tag language $locale not supported")
                } else {
                    markTtsReady(tag, ready = true, available = true)
                    broadcastLog("TTS $tag ready ($locale)")
                }
            } else {
                markTtsReady(tag, ready = false, available = false)
                broadcastLog("Error: TTS $tag initialization failed")
            }
        }

        instance = if (enginePkg.isNullOrEmpty()) {
            TextToSpeech(this, listener)
        } else {
            TextToSpeech(this, listener, enginePkg)
        }
        return instance
    }

    @Synchronized
    private fun markTtsReady(tag: String, ready: Boolean, available: Boolean) {
        if (tag == "ko") {
            koreanReady = ready
            koreanAvailable = available
        } else {
            englishReady = ready
            englishAvailable = available
        }
        if (isSpeaking && pendingSegments.isNotEmpty()) {
            retryHandler.post { speakNextSegment() }
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            onSegmentFinished()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onSegmentFinished()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onSegmentFinished()
        }
    }

    @Synchronized
    private fun onSegmentFinished() {
        speakNextSegment()
    }

    private fun startForegroundService() {
        val channelId = "music_announcer_service"
        val channelName = "Music Announcer Service"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        
        val notificationBuilder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            android.app.Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle("Music Announcer Running")
            .setContentText("Listening for music...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
            
        if (android.os.Build.VERSION.SDK_INT >= 34) {
             startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
             startForeground(1001, notification)
        }
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        broadcastLog("Notification Listener Connected")
        
        try {
            val componentName = android.content.ComponentName(this, MusicNotificationListenerService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            
            // Process initial state
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            if (controllers != null) {
                processControllers(controllers)
            }
        } catch (e: SecurityException) {
            broadcastLog("Error: Permission missing for MediaSession access. ${e.message}")
        }
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        broadcastLog("Listener Disconnected")
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            unregisterAllControllerCallbacks()
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onDestroy() {
        unregisterAllControllerCallbacks()
        retryHandler.removeCallbacksAndMessages(null)
        ttsKorean?.stop()
        ttsKorean?.shutdown()
        ttsEnglish?.stop()
        ttsEnglish?.shutdown()
        super.onDestroy()
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // We rely on MediaSession, but we can log notification events for debugging
        if (isAllowedApp(sbn.packageName)) {
            Log.d(TAG, "Posted: ${sbn.packageName}")
            // Trigger a check just in case MediaSession didn't fire (some apps are weird)
            triggerSessionCheck()
        }
    }
    
    private fun triggerSessionCheck() {
         try {
            val componentName = android.content.ComponentName(this, MusicNotificationListenerService::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            if (controllers != null) {
                processControllers(controllers)
            }
        } catch (e: SecurityException) {
            // Ignore
        }
    }

    private fun processControllers(controllers: List<MediaController>?) {
        if (controllers == null) return
        
        for (controller in controllers) {
            val pkg = controller.packageName
            if (isAllowedApp(pkg)) {
                registerCallback(controller)
                
                // Check current state immediately
                val metadata = controller.metadata
                if (metadata != null) {
                    handleMetadata(pkg, metadata)
                }
            }
        }
    }
    
    private fun registerCallback(controller: MediaController) {
        val existing = registeredControllers[controller.packageName]
        if (existing?.controller === controller) {
            return
        }

        existing?.controller?.unregisterCallback(existing.callback)

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                handleMetadata(controller.packageName, metadata)
            }
            
            override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                if (state?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                     handleMetadata(controller.packageName, controller.metadata)
                }
            }
        }
        controller.registerCallback(callback)
        registeredControllers[controller.packageName] = RegisteredController(controller, callback)
    }

    private fun unregisterAllControllerCallbacks() {
        for (registered in registeredControllers.values) {
            registered.controller.unregisterCallback(registered.callback)
        }
        registeredControllers.clear()
    }
    
    private fun handleMetadata(packageName: String, metadata: MediaMetadata?) {
        if (metadata == null) return
        
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        
        if (!title.isNullOrEmpty() && !artist.isNullOrEmpty()) {
             speakMusicInfo(packageName, artist, title)
        }
    }

    private fun isAllowedApp(packageName: String): Boolean {
        val sharedPrefs = getSharedPreferences("allowed_apps", Context.MODE_PRIVATE)
        val allowedApps = sharedPrefs.getStringSet("packages", setOf()) ?: setOf()
        
        if (allowedApps.isEmpty()) {
            val defaultApps = setOf("com.spotify.music", "com.google.android.apps.youtube.music")
            return defaultApps.contains(packageName)
        }
        
        return allowedApps.contains(packageName)
    }

    private fun speakMusicInfo(app: String, artist: String, title: String) {
        val currentInfo = "$artist, $title"
        val currentTime = System.currentTimeMillis()

        if (currentInfo == lastSpokenTitle) {
            return
        }

        if ((currentTime - lastSpokenTime) < COOLDOWN_MS) {
            return
        }

        lastSpokenTitle = currentInfo
        lastSpokenTime = currentTime
        
        // Apply Config
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val speed = prefs.getFloat("tts_speed", 1.0f).coerceAtLeast(0.1f)
        val volume = prefs.getInt("tts_volume", 100) / 100.0f
        
        val textToSpeak = "$artist, $title"
        val segments = splitByLanguage(textToSpeak)
        val languageLabel = when (segments.map { it.lang }.distinct().size) {
            0 -> "none"
            1 -> segments.first().lang
            else -> "mixed"
        }
        Log.d(TAG, "Speaking: $textToSpeak (Rate:$speed, Vol:$volume, Segments:${segments.size}, Lang:$languageLabel)")
        broadcastLog("Speaking [$app]: $textToSpeak (segments=${segments.size}, lang=$languageLabel)")

        enqueueSegments(segments)
    }

    private fun isHangul(char: Char): Boolean {
        val code = char.code
        return code in 0xAC00..0xD7AF || code in 0x1100..0x11FF || code in 0x3130..0x318F
    }

    private fun classifyLanguage(char: Char): String {
        return if (isHangul(char)) "ko" else "en"
    }

    private fun significantLength(text: String): Int {
        return text.count { !it.isWhitespace() }
    }

    private fun splitByLanguage(text: String): List<Segment> {
        if (text.isBlank()) return emptyList()

        val rawSegments = mutableListOf<Segment>()
        val builder = StringBuilder()
        var currentLang: String? = null

        for (char in text) {
            val lang = classifyLanguage(char)
            if (currentLang == null || currentLang == lang) {
                builder.append(char)
                currentLang = lang
            } else {
                rawSegments.add(Segment(currentLang, builder.toString()))
                builder.clear()
                builder.append(char)
                currentLang = lang
            }
        }

        currentLang?.let { rawSegments.add(Segment(it, builder.toString())) }
        if (rawSegments.size <= 1) return rawSegments

        return mergeShortSegments(rawSegments)
    }

    private fun mergeShortSegments(segments: List<Segment>): List<Segment> {
        val result = segments.toMutableList()
        var index = 0

        while (index < result.size) {
            val segment = result[index]
            if (result.size > 1 && significantLength(segment.text) < MIN_SEGMENT_CHARS) {
                if (index > 0) {
                    val previous = result[index - 1]
                    result[index - 1] = previous.copy(text = previous.text + segment.text)
                    result.removeAt(index)
                    continue
                } else if (index + 1 < result.size) {
                    val next = result[index + 1]
                    result[index + 1] = next.copy(text = segment.text + next.text)
                    result.removeAt(index)
                    continue
                }
            }
            index++
        }

        return result.filter { it.text.isNotBlank() }
    }

    @Synchronized
    private fun enqueueSegments(segments: List<Segment>) {
        if (segments.isEmpty()) return
        pendingSegments.addAll(segments)
        if (!isSpeaking) {
            isSpeaking = true
            speakNextSegment()
        }
    }

    @Synchronized
    private fun speakNextSegment() {
        val segment = pendingSegments.firstOrNull()
        if (segment == null) {
            isSpeaking = false
            return
        }
        pendingSegments.removeAt(0)

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val speed = prefs.getFloat("tts_speed", 1.0f).coerceAtLeast(0.1f)
        val volume = prefs.getInt("tts_volume", 100) / 100.0f
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)

        val selectedTts = if (segment.lang == "ko") ttsKorean else ttsEnglish
        val selectedReady = if (segment.lang == "ko") koreanReady else englishReady
        val selectedAvailable = if (segment.lang == "ko") koreanAvailable else englishAvailable

        if (!selectedAvailable) {
            onSegmentFinished()
            return
        }

        if (selectedTts == null || !selectedReady) {
            pendingSegments.add(0, segment)
            retryHandler.postDelayed({ speakNextSegment() }, 250L)
            return
        }

        selectedTts.setSpeechRate(speed)
        val result = selectedTts.speak(
            segment.text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "MusicAnnounce_${segment.lang}_${System.nanoTime()}"
        )
        if (result == TextToSpeech.ERROR) {
            onSegmentFinished()
        }
    }
    
    private fun broadcastLog(message: String) {
        Log.d(TAG, message)
        val intent = Intent("com.example.musicannouncer.LOG")
        intent.putExtra("log", message)
        intent.setPackage(packageName) // Security
        sendBroadcast(intent)
    }
}
