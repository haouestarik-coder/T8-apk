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
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.UUID

private fun uuid16(v: Int): UUID =
    UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

/**
 * Étape 1 et 2 : scan BLE du T8+ (nom "ABCTransBLE"), connexion,
 * lecture des appuis longs sur les boutons rouge (0x02) et bleu (0x04).
 * L'audio (micro / haut-parleur) passe par le Bluetooth classique :
 * appairer d'abord "ABCTrans" dans les réglages Bluetooth du téléphone.
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    companion object {
        val SERVICE_UUID: UUID = uuid16(0xA035)
        val CHAR_BUTTONS: UUID = uuid16(0x2541) // NOTIFY : 0x02 = rouge, 0x04 = bleu (appui long)
        val CHAR_EVENTS: UUID = uuid16(0xA043)  // NOTIFY : rôle inconnu pour l'instant
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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startScan()
            else log("Permissions refusées. Autorise « Appareils à proximité » et le micro dans les réglages de l'application.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        statusView = TextView(this).apply { textSize = 18f; text = "Non connecté" }
        val connectBtn = Button(this).apply { text = "Connecter le T8+"; setOnClickListener { onConnectClicked() } }
        val disconnectBtn = Button(this).apply { text = "Déconnecter"; setOnClickListener { disconnect() } }
        logView = TextView(this).apply { textSize = 14f }
        scroll = ScrollView(this).apply { addView(logView) }

        root.addView(statusView)
        root.addView(connectBtn)
        root.addView(disconnectBtn)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        log("Avant de commencer : appaire « ABCTrans » dans Paramètres > Bluetooth (audio).")
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

    private fun onConnectClicked() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------- Scan ----------

    private val stopScanRunnable = Runnable {
        if (scanning) {
            stopScan()
            log("Aucun T8+ trouvé. Vérifie qu'il est allumé et proche.")
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
            val hex = v.joinToString(" ") { "%02X".format(it) }
            val text = if (c.uuid == CHAR_BUTTONS && v.size == 1) {
                when (v[0].toInt()) {
                    0x02 -> "Bouton ROUGE (appui long)"
                    0x04 -> "Bouton BLEU (appui long)"
                    else -> "Bouton inconnu : $hex"
                }
            } else {
                "Notification ${c.uuid.toString().substring(4, 8)} : $hex"
            }
            runOnUiThread { log(text) }
        }
    }

    private fun enableNext(g: BluetoothGatt) {
        if (pending.isEmpty()) {
            runOnUiThread {
                setStatus("T8+ connecté")
                log("Prêt. Fais un appui long sur le rouge ou le bleu.")
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

    // ---------- Utilitaires UI ----------

    private fun setStatus(s: String) = runOnUiThread { statusView.text = s }

    private fun log(s: String) = runOnUiThread {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
