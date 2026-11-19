package com.t8translator.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import java.util.UUID

private fun uuid16(v: Int): UUID =
    UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

data class Lang(val name: String, val tag: String, val ml: String, val flag: String)

private val LANGS = listOf(
    Lang("Français", "fr-FR", "fr", "🇫🇷"),
    Lang("English", "en-US", "en", "🇺🇸"),
    Lang("العربية", "ar-SA", "ar", "🇸🇦"),
    Lang("Español", "es-ES", "es", "🇪🇸"),
    Lang("Deutsch", "de-DE", "de", "🇩🇪"),
    Lang("Italiano", "it-IT", "it", "🇮🇹"),
    Lang("Português", "pt-PT", "pt", "🇵🇹"),
    Lang("Türkçe", "tr-TR", "tr", "🇹🇷"),
    Lang("Русский", "ru-RU", "ru", "🇷🇺"),
    Lang("中文", "zh-CN", "zh", "🇨🇳")
)

private const val RED = 0xFFD32F2F.toInt()
private const val BLUE = 0xFF1E6FD9.toInt()

/**
 * v0.4 : interface de conversation.
 * Bouton ROUGE du T8+ (ou micro rouge) : la personne A parle sa langue -> traduit vers la langue B.
 * Bouton BLEU du T8+ (ou micro bleu)   : la personne B parle sa langue -> traduit vers la langue A.
 * Codes BLE sur 0x2541 : 01 = rouge, 02 = fin rouge, 03 = bleu, 04 = fin bleu (à confirmer).
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    companion object {
        val SERVICE_UUID: UUID = uuid16(0xA035)
        val CHAR_BUTTONS: UUID = uuid16(0x2541)
        val CHAR_EVENTS: UUID = uuid16(0xA043)
        val CCC_UUID: UUID = uuid16(0x2902)
        const val NAME_PART = "abctrans"
        const val SCAN_TIMEOUT_MS = 20_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val pending = mutableListOf<BluetoothGattCharacteristic>()

    private lateinit var prefs: SharedPreferences
    private lateinit var langAView: TextView
    private lateinit var langBView: TextView
    private lateinit var connView: TextView
    private lateinit var stateView: TextView
    private lateinit var hintView: TextView
    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private val debugLog = StringBuilder()

    private var idxA = 0
    private var idxB = 1

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var activeDir = 0 // 1 = rouge, 2 = bleu
    private var sessionId = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startScan()
            else log("Permissions refusées : autorise « Appareils à proximité » et le micro dans les réglages de l'application.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("t8", MODE_PRIVATE)
        idxA = prefs.getInt("a", 0).coerceIn(0, LANGS.size - 1)
        idxB = prefs.getInt("b", 1).coerceIn(0, LANGS.size - 1)
        buildUi()
        initTts()
        log("Appaire « ABCTrans » dans Paramètres > Bluetooth pour l'audio.", false)
        requestAndScan()
    }

    // ---------- Interface ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Barre des langues
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        langAView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(RED)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { pickLang(true) }
        }
        val swapView = TextView(this).apply {
            text = "⇄"
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { swapLangs() }
        }
        langBView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(BLUE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { pickLang(false) }
        }
        top.addView(langAView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(swapView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        top.addView(langBView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        // Conversation
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        hintView = TextView(this).apply {
            text = "Maintiens le bouton rouge ou bleu du T8+, parle, puis relâche.\n\nTu peux aussi toucher un micro ci-dessous."
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.GRAY)
            setPadding(dp(16), dp(80), dp(16), dp(16))
        }
        chatBox.addView(hintView)
        scroll = ScrollView(this).apply { addView(chatBox) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Ligne d'état
        stateView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.DKGRAY)
            minHeight = dp(28)
        }
        root.addView(stateView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Barre du bas : état du T8+, micros, menu
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(20))
        }
        connView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { requestAndScan() }
        }
        val micA = circleButton(RED) { micTap(1) }
        val micB = circleButton(BLUE) { micTap(2) }
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 30f
            gravity = Gravity.CENTER
            setOnClickListener { showMenu() }
        }
        val micParams = { LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(10), 0, dp(10), 0) } }
        bottom.addView(connView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(micA, micParams())
        bottom.addView(micB, micParams())
        bottom.addView(gear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)

        setContentView(root)
        updateLangViews()
        setStatus("Non connecté")
    }

    private fun circleButton(color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = "🎤"
        textSize = 28f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        setOnClickListener { onClick() }
    }

    private fun updateLangViews() {
        langAView.text = "${LANGS[idxA].flag}\n${LANGS[idxA].name}"
        langBView.text = "${LANGS[idxB].flag}\n${LANGS[idxB].name}"
    }

    private fun savePrefs() {
        prefs.edit().putInt("a", idxA).putInt("b", idxB).apply()
    }

    private fun swapLangs() {
        val t = idxA
        idxA = idxB
        idxB = t
        savePrefs()
        updateLangViews()
    }

    private fun pickLang(isA: Boolean) {
        val names = LANGS.map { "${it.flag}  ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (isA) "Bouton ROUGE : parle en" else "Bouton BLEU : parle en")
            .setItems(names) { _, which ->
                if (isA) {
                    idxA = which
                } else {
                    idxB = which
                }
                savePrefs()
                updateLangViews()
            }
            .show()
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Reconnecter le T8+", "Effacer la conversation", "Journal technique")) { _, which ->
                when (which) {
                    0 -> requestAndScan()
                    1 -> clearChat()
                    else -> showDebug()
                }
            }
            .show()
    }

    private fun showDebug() {
        AlertDialog.Builder(this)
            .setTitle("Journal technique")
            .setMessage(debugLog.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun clearChat() {
        chatBox.removeAllViews()
        chatBox.addView(hintView)
    }

    private fun addBubble(dir: Int, original: String, translated: String) {
        if (hintView.parent != null) chatBox.removeView(hintView)
        val isA = dir == 1
        val maxW = (resources.displayMetrics.widthPixels * 0.78).toInt()
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (isA) 0xFFFDECEA.toInt() else 0xFFE8F1FD.toInt())
            }
        }
        bubble.addView(TextView(this).apply {
            text = translated
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            maxWidth = maxW
        })
        bubble.addView(TextView(this).apply {
            text = original
            textSize = 13f
            setTextColor(Color.GRAY)
            maxWidth = maxW
        })
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isA) Gravity.START else Gravity.END
            setMargins(0, dp(6), 0, dp(6))
        }
        chatBox.addView(bubble, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) = runOnUiThread {
        val label = when {
            s.startsWith("T8+ connecté") -> "🟢 T8+ connecté"
            s.startsWith("Recherche") || s.startsWith("Connexion") -> "🟠 Recherche du T8+…"
            else -> "🔴 T8+ non connecté\n(touche pour connecter)"
        }
        connView.text = label
    }

    private fun log(s: String, show: Boolean = true) = runOnUiThread {
        debugLog.append(s).append('\n')
        if (show) stateView.text = s
    }

    // ---------- Permissions ----------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        ) else arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAndScan() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------- Scan BLE (avec reconnexion automatique) ----------

    private val retryRunnable = Runnable {
        if (gatt == null && !scanning && hasPermissions()) startScan()
    }

    private fun scheduleRetry(ms: Long) {
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, ms)
    }

    private val stopScanRunnable = Runnable {
        if (scanning) {
            stopScan()
            log("T8+ introuvable. Vérifie qu'il est allumé et proche.")
            setStatus("Non connecté")
            scheduleRetry(10_000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: ""
            if (name.contains(NAME_PART, ignoreCase = true)) {
                runOnUiThread {
                    if (scanning) {
                        stopScan()
                        log("Trouvé : $name (${result.device.address})", false)
                        connect(result.device)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Échec du scan (code $errorCode)")
        }
    }

    private fun startScan() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Active le Bluetooth du téléphone.")
            scheduleRetry(10_000)
            return
        }
        if (Build.VERSION.SDK_INT < 31) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            if (!LocationManagerCompat.isLocationEnabled(lm)) {
                log("Active la localisation (GPS) : nécessaire au Bluetooth sur ton Android.")
                return
            }
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            log("Scanner BLE indisponible.")
            return
        }
        disconnect()
        scanning = true
        setStatus("Recherche du T8+…")
        log("Recherche du T8+…")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed(stopScanRunnable, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        if (!scanning) return
        scanning = false
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    // ---------- Connexion GATT ----------

    private fun connect(device: BluetoothDevice) {
        setStatus("Connexion…")
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        stopScan()
        cancelSession()
        releaseAudio()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        pending.clear()
        setStatus("Non connecté")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                runOnUiThread {
                    if (gatt === g) gatt = null
                    releaseAudio()
                    setStatus("Non connecté")
                    log("T8+ déconnecté, reconnexion automatique…")
                    scheduleRetry(3_000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                log("Service 0xA035 introuvable : ce n'est pas un T8+ compatible ?")
                return
            }
            pending.clear()
            listOf(CHAR_BUTTONS, CHAR_EVENTS).forEach { u ->
                service.getCharacteristic(u)?.let { pending.add(it) }
            }
            enableNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            enableNext(g)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            if (c.uuid == CHAR_BUTTONS && v.size == 1) {
                val code = v[0].toInt() and 0xFF
                runOnUiThread { onButtonEvent(code) }
            } else {
                val hex = v.joinToString(" ") { "%02X".format(it) }
                log("Notification ${c.uuid.toString().substring(4, 8)} : $hex", false)
            }
        }
    }

    private fun enableNext(g: BluetoothGatt) {
        if (pending.isEmpty()) {
            runOnUiThread {
                setStatus("T8+ connecté")
                routeToHeadset()
                log("T8+ prêt : maintiens un bouton, parle, relâche.")
            }
            return
        }
        val c = pending.removeAt(0)
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCC_UUID)
        if (d == null) {
            enableNext(g)
            return
        }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    // ---------- Audio : le T8+ comme casque (micro + haut-parleur) ----------

    private fun routeToHeadset() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (dev != null) {
                am.setCommunicationDevice(dev)
                log("Audio : ${dev.productName} = micro et haut-parleur.", false)
            } else {
                log("« ABCTrans » n'est pas appairé en audio : Paramètres > Bluetooth.")
            }
        } else {
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
        }
    }

    private fun releaseAudio() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 31) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
    }

    // ---------- Synthèse vocale ----------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { stateView.text = ""; finishSession() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { finishSession() }
                    }
                })
            } else {
                log("Synthèse vocale indisponible sur ce téléphone.")
            }
        }
    }

    // ---------- Boutons -> reconnaissance -> traduction -> lecture ----------

    private fun onButtonEvent(code: Int) {
        log("[bouton %02X]".format(code), false)
        when (code) {
            0x01 -> startListening(1)
            0x03 -> startListening(2)
            0x02, 0x04 -> {
                if (activeDir != 0) recognizer?.stopListening()
            }
            else -> {}
        }
    }

    private fun micTap(dir: Int) {
        if (!hasPermissions()) {
            requestAndScan()
            return
        }
        if (busy && activeDir != 0) recognizer?.stopListening() else startListening(dir)
    }

    private fun startListening(dir: Int) {
        if (busy) {
            log("Occupé, attends la fin de la lecture.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("Reconnaissance vocale indisponible : installe/active l'application Google.")
            return
        }
        busy = true
        activeDir = dir
        sessionId++
        val sid = sessionId
        val src = LANGS[if (dir == 1) idxA else idxB]
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(makeListener(sid, dir))
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, src.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        log("🎤 Écoute (${src.name})…")
        r.startListening(intent)
    }

    // Chaque session a son propre écouteur : les retours tardifs d'un ancien
    // recognizer sont ignorés.
    private fun makeListener(sid: Int, dir: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (sid != sessionId) return
            val msg = when (error) {
                7 -> "Je n'ai rien compris, réessaie."
                6 -> "Aucune voix détectée."
                9 -> "Permission du micro refusée."
                11 -> "Service vocal déconnecté, réessaie."
                else -> "Erreur de reconnaissance ($error)."
            }
            log(msg)
            finishSession()
        }

        override fun onResults(results: Bundle?) {
            if (sid != sessionId) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                log("Rien reconnu.")
                finishSession()
                return
            }
            val src = LANGS[if (dir == 1) idxA else idxB]
            val dst = LANGS[if (dir == 1) idxB else idxA]
            log("${src.name} : $text")
            translateAndSpeak(text, src, dst, dir)
        }
    }

    private fun translateAndSpeak(text: String, src: Lang, dst: Lang, dir: Int) {
        if (src.ml == dst.ml) {
            addBubble(dir, text, text)
            speak(text, dst)
            return
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src.ml)
            .setTargetLanguage(dst.ml)
            .build()
        val translator = Translation.getClient(options)
        log("Traduction ${src.name} → ${dst.name}… (1er usage : téléchargement du modèle, Internet requis)")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { out ->
                        translator.close()
                        addBubble(dir, text, out)
                        speak(out, dst)
                    }
                    .addOnFailureListener { e ->
                        translator.close()
                        log("Échec de la traduction : ${e.message}")
                        finishSession()
                    }
            }
            .addOnFailureListener { e ->
                translator.close()
                log("Modèle de langue non téléchargé (Internet ?) : ${e.message}")
                finishSession()
            }
    }

    private fun speak(text: String, lang: Lang) {
        if (!ttsReady) {
            log("Synthèse vocale non prête.")
            finishSession()
            return
        }
        val t = tts ?: return
        val res = t.setLanguage(Locale.forLanguageTag(lang.tag))
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            log("Voix ${lang.name} non installée (Paramètres > Synthèse vocale).")
            finishSession()
            return
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "t8-utterance")
    }

    private fun finishSession() {
        busy = false
        activeDir = 0
    }

    private fun cancelSession() {
        sessionId++
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        finishSession()
    }

    override fun onDestroy() {
        handler.removeCallbacks(retryRunnable)
        disconnect()
        tts?.shutdown()
        super.onDestroy()
    }
}
