package com.proactiveai.extreme.core.context.plugins

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.model.PluginDescriptor
import java.util.UUID

class NearbyConnectivityPlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin {
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var lastSignature: String? = null
    private var lastEmitAt: Long = 0L

    override suspend fun start(): Boolean = true

    override suspend fun stop() = Unit

    override suspend fun poll(): List<ContextEvent> {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val hasVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val bluetoothEnabled = bluetoothEnabled()
        val bondedDevices = bondedDeviceSnapshot()
        val wifiSnapshot = wifiSnapshot()
        val signature = "$hasWifi|$hasCellular|$hasVpn|$hasInternet|$bluetoothEnabled|${bondedDevices.count}|${wifiSnapshot.ssid}"

        val now = System.currentTimeMillis()
        val shouldEmit = signature != lastSignature || now - lastEmitAt > EMIT_MIN_INTERVAL_MS
        if (!shouldEmit) {
            return emptyList()
        }

        lastSignature = signature
        lastEmitAt = now

        return listOf(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = descriptor.id,
                category = "connectivity",
                summary = "Connectivity wifi=$hasWifi cellular=$hasCellular vpn=$hasVpn bluetooth=$bluetoothEnabled",
                payload = mapOf(
                    "wifi" to hasWifi,
                    "cellular" to hasCellular,
                    "vpn" to hasVpn,
                    "internet" to hasInternet,
                    "bluetoothEnabled" to bluetoothEnabled,
                    "bluetoothBondedCount" to bondedDevices.count,
                    "bluetoothBondedSamples" to bondedDevices.samples,
                    "wifiSsid" to wifiSnapshot.ssid,
                    "wifiBssid" to wifiSnapshot.bssid,
                    "wifiRssi" to wifiSnapshot.rssi,
                    "wifiFrequency" to wifiSnapshot.frequencyMhz,
                ),
                sensitivity = Sensitivity.MEDIUM,
                ttlSeconds = 43_200,
            )
        )
    }

    private fun bluetoothEnabled(): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return false
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = bluetoothManager.adapter ?: return false
        return adapter.isEnabled
    }

    private fun bondedDeviceSnapshot(): BondedDeviceSnapshot {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return BondedDeviceSnapshot(0, emptyList())
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: return BondedDeviceSnapshot(0, emptyList())
        val devices = kotlin.runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        val samples = devices
            .asSequence()
            .mapNotNull { device -> device.name?.takeIf { it.isNotBlank() } ?: device.address }
            .take(5)
            .toList()
        return BondedDeviceSnapshot(devices.size, samples)
    }

    private fun wifiSnapshot(): WifiSnapshot {
        val info = kotlin.runCatching { wifiManager.connectionInfo }.getOrNull()
        val rawSsid = info?.ssid?.trim().orEmpty().trim('"')
        val ssid = if (rawSsid.isBlank() || rawSsid == "<unknown ssid>") "unknown" else rawSsid
        val bssid = info?.bssid.orEmpty().ifBlank { "unknown" }
        val rssi = info?.rssi ?: 0
        val freq = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            info?.frequency ?: 0
        } else {
            0
        }
        return WifiSnapshot(
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            frequencyMhz = freq,
        )
    }

    private data class BondedDeviceSnapshot(
        val count: Int,
        val samples: List<String>,
    )

    private data class WifiSnapshot(
        val ssid: String,
        val bssid: String,
        val rssi: Int,
        val frequencyMhz: Int,
    )

    companion object {
        private const val EMIT_MIN_INTERVAL_MS = 90_000L
    }
}
