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
import android.content.pm.PackageManager
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

data class Lang(val name: String, val tag: String, val ml: String)

private val LANGS = listOf(
    Lang("Français", "fr-FR", "fr"),
    Lang("English", "en-US", "en"),
    Lang("العربية", "ar-SA", "ar"),
    Lang("Español", "es-ES", "es"),
    Lang("Deutsch", "de-DE", "de"),
    Lang("Italiano", "it-IT", "it"),
    Lang("Português", "pt-PT", "pt"),
    Lang("Türkçe", "tr-TR", "tr"),
    Lang("Русский", "ru-RU", "ru"),
    Lang("中文", "zh-CN", "zh")
)

/**
 * v0.2 : traduction vocale.
 * Bouton ROUGE maintenu : la personne A parle (langue A) -> traduit en langue B, lu dans le T8+.
 * Bouton BLEU maintenu  : la personne B parle (langue B) -> traduit en langue A, lu dans le T8+.
 * Codes BLE sur 0x2541 : 01 = rouge appuyé, 02 = rouge relâché, 03 = bleu appuyé, 04 = bleu relâché
 * (hypothèse à confirmer par test).
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

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var spinnerA: Spinner
    private lateinit var spinnerB: Spinner

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false
    private var activeDir = 0 // 1 = rouge, 2 = bleu

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startScan()
            else log("Permissions refusées. Autorise « Appareils à proximité » et le micro dans les réglages de l'application.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        statusView = TextView(this).apply { textSize = 18f; text = "Non connecté" }
        spinnerA = makeSpinner(0)
        spinnerB = makeSpinner(1)
        val connectBtn = Button(this).apply { text = "Connecter le T8+"; setOnClickListener { onConnectClicked() } }
        val disconnectBtn = Button(this).apply { text = "Déconnecter"; setOnClickListener { disconnect() } }
        logView = TextView(this).apply { textSize = 14f }
        scroll = ScrollView(this).apply { addView(logView) }

        root.addView(statusView)
        root.addView(TextView(this).apply { text = "Bouton ROUGE : la personne parle en" })
        root.addView(spinnerA)
        root.addView(TextView(this).apply { text = "Bouton BLEU : la personne parle en" })
        root.addView(spinnerB)
        root.addView(connectBtn)
        root.addView(disconnectBtn)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

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
                    override fun onDone(utteranceId: String?) { runOnUiThread { finishSession() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { runOnUiThread { finishSession() } }
                })
            } else {
                log("Synthèse vocale indisponible sur ce téléphone.")
            }
        }

        log("Avant de commencer : appaire « ABCTrans » dans Paramètres > Bluetooth (audio).")
    }

    private fun makeSpinner(default: Int): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            LANGS.map { it.name }
        )
        setSelection(default)
    }

    private fun langA(): Lang = LANGS[spinnerA.selectedItemPosition]
    private fun langB(): Lang = LANGS[spinnerB.selectedItemPosition]

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

    private fun onConnectClicked() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------- Scan BLE ----------

    private val stopScanRunnable = Runnable {
        if (scanning) {
            stopScan()
            log("Aucun T8+ trouvé. Vérifie qu'il est allumé et proche, puis réessaie.")
            setStatus("Non connecté")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: ""
            if (name.contains(NAME_PART, ignoreCase = true)) {
                runOnUiThread {
                    if (scanning) {
                        stopScan()
                        log("Trouvé : $name (${result.device.address})")
                        connect(result.device)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { log("Échec du scan (code $errorCode)") }
        }
    }

    private fun startScan() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            log("Active le Bluetooth du téléphone.")
            return
        }
        if (Build.VERSION.SDK_INT < 31) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            if (!LocationManagerCompat.isLocationEnabled(lm)) {
                log("Active la localisation (GPS) : nécessaire au scan Bluetooth sur ton Android.")
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
                runOnUiThread { log("Connecté en BLE, découverte des services…") }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                runOnUiThread {
                    if (gatt === g) gatt = null
                    releaseAudio()
                    setStatus("Déconnecté")
                    log("Déconnecté (status $status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                runOnUiThread { log("Service 0xA035 introuvable : ce n'est pas un T8+ compatible ?") }
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
                runOnUiThread { log("Notification ${c.uuid.toString().substring(4, 8)} : $hex") }
            }
        }
    }

    private fun enableNext(g: BluetoothGatt) {
        if (pending.isEmpty()) {
            runOnUiThread {
                setStatus("T8+ connecté")
                routeToHeadset()
                log("Prêt. Maintiens le bouton rouge ou bleu, parle, puis relâche.")
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

    // ---------- Audio : utiliser le T8+ comme casque (micro + haut-parleur) ----------

    private fun routeToHeadset() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val dev = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (dev != null) {
                am.setCommunicationDevice(dev)
                log("Audio : ${dev.productName} utilisé comme micro et haut-parleur.")
            } else {
                log("Audio : « ABCTrans » n'est pas connecté comme casque. Appaire-le dans Paramètres > Bluetooth.")
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

    // ---------- Boutons -> reconnaissance -> traduction -> lecture ----------

    private fun onButtonEvent(code: Int) {
        when (code) {
            0x01 -> startListening(1)
            0x03 -> startListening(2)
            0x02, 0x04 -> {
                if (activeDir != 0) recognizer?.stopListening()
            }
            else -> log("Code bouton non géré : %02X".format(code))
        }
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
        val src = if (dir == 1) langA() else langB()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(recListener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, src.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        log("Écoute (${src.name})…")
        recognizer?.startListening(intent)
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            log("Reconnaissance : erreur $error (7 = rien compris, 6 = silence, 9 = permission micro)")
            finishSession()
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                log("Rien reconnu.")
                finishSession()
                return
            }
            val dir = activeDir
            val src = if (dir == 1) langA() else langB()
            val dst = if (dir == 1) langB() else langA()
            log("${src.name} : $text")
            translateAndSpeak(text, src, dst)
        }
    }

    private fun translateAndSpeak(text: String, src: Lang, dst: Lang) {
        if (src.ml == dst.ml) {
            speak(text, dst)
            return
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src.ml)
            .setTargetLanguage(dst.ml)
            .build()
        val translator = Translation.getClient(options)
        log("Traduction ${src.name} → ${dst.name}… (le premier usage télécharge le modèle, Internet requis)")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { out ->
                        translator.close()
                        log("${dst.name} : $out")
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
            log("Voix ${lang.name} non installée sur ce téléphone (Paramètres > Synthèse vocale).")
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
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        finishSession()
    }

    // ---------- Utilitaires UI ----------

    private fun setStatus(s: String) = runOnUiThread { statusView.text = s }

    private fun log(s: String) = runOnUiThread {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        disconnect()
        tts?.shutdown()
        super.onDestroy()
    }
}
