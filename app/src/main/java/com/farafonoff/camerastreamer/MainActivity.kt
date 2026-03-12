package com.farafonoff.camerastreamer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.farafonoff.camerastreamer.service.CameraStreamService
import com.farafonoff.camerastreamer.ui.theme.CameraStreamerTheme
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

class MainActivity : ComponentActivity() {

    private val statusState = mutableStateOf(ServiceStatus())
    private var registered = false
    private val ipAddressState = mutableStateOf("unknown")
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            statusState.value = ServiceStatus(
                serviceRunning = intent.getBooleanExtra(CameraStreamService.EXTRA_IS_RUNNING, false),
                streaming = intent.getBooleanExtra(CameraStreamService.EXTRA_IS_STREAMING, false),
                activeCamera = intent.getStringExtra(CameraStreamService.EXTRA_ACTIVE_CAMERA) ?: "unknown",
                batteryLevel = intent.getIntExtra(CameraStreamService.EXTRA_BATTERY_LEVEL, 0),
                batteryStatus = intent.getStringExtra(CameraStreamService.EXTRA_BATTERY_STATUS) ?: "unknown",
                clientCount = intent.getIntExtra(CameraStreamService.EXTRA_CLIENT_COUNT, 0),
                httpPort = intent.getIntExtra(CameraStreamService.EXTRA_HTTP_PORT, CameraStreamService.HTTP_PORT),
                tcpPort = intent.getIntExtra(CameraStreamService.EXTRA_TCP_PORT, CameraStreamService.TCP_PORT)
            )
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraService()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required to run the streamer.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerStatusReceiver()
        ipAddressState.value = resolveDeviceIp()
        setContent {
            CameraStreamerTheme {
                Scaffold(
                    modifier = Modifier.systemBarsPadding(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Camera Streamer") }
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        val status by statusState
                        val ipAddress by ipAddressState
                        ControlScreen(
                            status = status,
                            ipAddress = ipAddress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                            onStartService = { sendServiceCommand(CameraStreamService.ACTION_START_SERVICE) },
                            onStopService = { stopService(Intent(this@MainActivity, CameraStreamService::class.java)) },
                            onStartStream = { sendServiceCommand(CameraStreamService.ACTION_START_STREAM) },
                            onStopStream = { sendServiceCommand(CameraStreamService.ACTION_STOP_STREAM) },
                            onSwitchCamera = { facing ->
                                val action = when (facing) {
                                    CameraFacing.FRONT -> CameraStreamService.ACTION_SWITCH_TO_FRONT
                                    CameraFacing.BACK -> CameraStreamService.ACTION_SWITCH_TO_BACK
                                }
                                sendServiceCommand(action)
                            }
                        )
                    }
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraService()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (registered) {
            unregisterReceiver(statusReceiver)
            registered = false
        }
    }

    private fun registerStatusReceiver() {
        if (!registered) {
            ContextCompat.registerReceiver(
                this,
                statusReceiver,
                IntentFilter(CameraStreamService.ACTION_STATUS_UPDATE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        }
    }

    private fun sendServiceCommand(action: String) {
        Intent(this, CameraStreamService::class.java).also {
            it.action = action
            ContextCompat.startForegroundService(this, it)
        }
    }

    private fun startCameraService() {
        Intent(this, CameraStreamService::class.java).also {
            it.action = CameraStreamService.ACTION_START_SERVICE
            ContextCompat.startForegroundService(this, it)
        }
    }
}

@Composable
fun ControlScreen(
    status: ServiceStatus,
    ipAddress: String,
    modifier: Modifier = Modifier,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onSwitchCamera: (CameraFacing) -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Camera stream controls",
            fontWeight = FontWeight.Bold
        )
        Text("Device IP: $ipAddress", fontWeight = FontWeight.Medium)
        Text("Service running: ${status.serviceRunning}")
        Text("Streaming active: ${status.streaming}")
        Text("Active camera: ${status.activeCamera}")
        Text("Battery: ${status.batteryLevel}% (${status.batteryStatus})")
        Text("TCP stream port: ${status.tcpPort}")
        Text("HTTP controls port: ${status.httpPort}")
        Text("TCP clients: ${status.clientCount}")

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onStartService, modifier = Modifier.fillMaxWidth()) {
            Text("Start service")
        }
        Button(onClick = onStopService, modifier = Modifier.fillMaxWidth()) {
            Text("Stop service")
        }
        Button(onClick = onStartStream, modifier = Modifier.fillMaxWidth()) {
            Text("Start streaming")
        }
        Button(onClick = onStopStream, modifier = Modifier.fillMaxWidth()) {
            Text("Stop streaming")
        }
        Button(onClick = { onSwitchCamera(CameraFacing.FRONT) }, modifier = Modifier.fillMaxWidth()) {
            Text("Front camera")
        }
        Button(onClick = { onSwitchCamera(CameraFacing.BACK) }, modifier = Modifier.fillMaxWidth()) {
            Text("Back camera")
        }
    }
}

enum class CameraFacing {
    FRONT,
    BACK
}

data class ServiceStatus(
    val serviceRunning: Boolean = false,
    val streaming: Boolean = false,
    val activeCamera: String = "unknown",
    val batteryLevel: Int = 0,
    val batteryStatus: String = "unknown",
    val clientCount: Int = 0,
    val httpPort: Int = CameraStreamService.HTTP_PORT,
    val tcpPort: Int = CameraStreamService.TCP_PORT
)

@Preview(showBackground = true)
@Composable
fun ControlScreenPreview() {
    CameraStreamerTheme {
        ControlScreen(
            status = ServiceStatus(
                serviceRunning = true,
                streaming = false,
                activeCamera = "back",
                batteryLevel = 78,
                batteryStatus = "Discharging",
                clientCount = 2
            ),
            ipAddress = "192.168.1.2",
            onStartService = {},
            onStopService = {},
            onStartStream = {},
            onStopStream = {},
            onSwitchCamera = {}
        )
    }
}

private fun resolveDeviceIp(): String {
    return try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { address ->
                !address.isLoopbackAddress && address is Inet4Address
            }
            ?.hostAddress ?: "unknown"
    } catch (ex: SocketException) {
        "unknown"
    }
}
