package com.proactiveai.extreme.core.context.plugins

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        val signature = "$hasWifi|$hasCellular|$hasVpn|$hasInternet|$bluetoothEnabled"

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

    companion object {
        private const val EMIT_MIN_INTERVAL_MS = 90_000L
    }
}
