package com.farafonoff.camerastreamer.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class CameraStreamService : Service() {

    companion object {
        private const val TAG = "CameraStreamService"
        const val TCP_PORT = 27183
        const val HTTP_PORT = 8080
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_WIDTH = 1280
        private const val FRAME_HEIGHT = 720
        private const val BITRATE = 3_000_000
        private const val FPS = 30
        private const val CHANNEL_ID = "camera_streamer_channel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_STATUS_UPDATE = "com.farafonoff.camerastreamer.ACTION_STATUS_UPDATE"
        const val ACTION_START_SERVICE = "com.farafonoff.camerastreamer.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.farafonoff.camerastreamer.ACTION_STOP_SERVICE"
        const val ACTION_START_STREAM = "com.farafonoff.camerastreamer.ACTION_START_STREAM"
        const val ACTION_STOP_STREAM = "com.farafonoff.camerastreamer.ACTION_STOP_STREAM"
        const val ACTION_SWITCH_TO_FRONT = "com.farafonoff.camerastreamer.ACTION_SWITCH_TO_FRONT"
        const val ACTION_SWITCH_TO_BACK = "com.farafonoff.camerastreamer.ACTION_SWITCH_TO_BACK"

        const val EXTRA_IS_RUNNING = "extra_service_running"
        const val EXTRA_IS_STREAMING = "extra_streaming"
        const val EXTRA_ACTIVE_CAMERA = "extra_active_camera"
        const val EXTRA_BATTERY_LEVEL = "extra_battery_level"
        const val EXTRA_BATTERY_STATUS = "extra_battery_status"
        const val EXTRA_CLIENT_COUNT = "extra_client_count"
        const val EXTRA_HTTP_PORT = "extra_http_port"
        const val EXTRA_TCP_PORT = "extra_tcp_port"

        private val CONTROL_PAGE = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8">
              <title>Camera Stream Controls</title>
            </head>
            <body>
              <h1>Camera Stream Controls</h1>
              <button onclick="fetch('/camera/front',{method:'POST'})">Front Camera</button>
              <button onclick="fetch('/camera/back',{method:'POST'})">Back Camera</button>
              <button onclick="fetch('/stream/start',{method:'POST'})">Start Stream</button>
              <button onclick="fetch('/stream/stop',{method:'POST'})">Stop Stream</button>
              <pre id="status"></pre>
              <script>
                async function refreshStatus(){
                  const res = await fetch('/status');
                  document.getElementById('status').innerText = await res.text();
                }
                setInterval(refreshStatus, 2000);
                refreshStatus();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private val binder = LocalBinder()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val streaming = AtomicBoolean(false)
    private val httpServer = ControlHttpServer()
    private var streamThread: StreamThread? = null
    private var serverSocket: ServerSocket? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var codecConfig: ByteArray? = null
    private var encoderCallbackThread: HandlerThread? = null
    private var encoderCallbackHandler: Handler? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private var restartAttempt = 0
    private val restartDelayMs = 2000L

    private lateinit var wakeLock: PowerManager.WakeLock
    private var selectedLensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var batteryLevel = 0
    private var batteryStatus = "unknown"

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryLevel = if (scale > 0) level * 100 / scale else 0
            batteryStatus = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                else -> "unknown"
            }
            sendStatusUpdate()
        }
    }

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraStreamer::WakeLock")
        startCameraThread()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(false))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startHttpServer()
        startTcpServer()
        sendStatusUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        stopTcpServer()
        stopHttpServer()
        releaseCamera()
        stopCameraThread()
        if (wakeLock.isHeld) wakeLock.release()
        unregisterReceiver(batteryReceiver)
        sendStatusUpdate(serviceRunning = false)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { handleAction(it) }
        sendStatusUpdate()
        return START_STICKY
    }

    private fun handleAction(action: String) {
        when (action) {
            ACTION_START_STREAM -> startStreaming()
            ACTION_STOP_STREAM -> stopStreaming()
            ACTION_SWITCH_TO_FRONT -> switchCamera(CameraCharacteristics.LENS_FACING_FRONT)
            ACTION_SWITCH_TO_BACK -> switchCamera(CameraCharacteristics.LENS_FACING_BACK)
            ACTION_STOP_SERVICE -> {
                stopSelf()
            }
            ACTION_START_SERVICE -> startStreaming()
        }
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (cameraDevice != null) return
        val cameraId = selectCameraId(selectedLensFacing) ?: return
        try {
            cameraManager.openCamera(cameraId, stateCallback, cameraHandler)
        } catch (ex: CameraAccessException) {
            Log.w(TAG, "Unable to access camera $cameraId", ex)
        }
    }

    private fun releaseCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            configureCaptureSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
            sendStatusUpdate()
        }

        override fun onError(device: CameraDevice, error: Int) {
            Log.w(TAG, "Camera error $error")
            device.close()
            cameraDevice = null
            stopStreaming()
        }
    }

    private fun configureCaptureSession() {
        val surface = encoderSurface ?: return
        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    request?.apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
                    }
                    request?.build()?.let {
                        session.setRepeatingRequest(it, null, cameraHandler)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w(TAG, "Capture session configuration failed")
                }
            },
            cameraHandler
        )
    }

    private fun configureEncoder(): Boolean {
        if (encoder != null) return true
        val parameterSets = listOf(
            listOf(
                MediaFormat.KEY_LOW_LATENCY to 1,
                MediaFormat.KEY_OPERATING_RATE to FPS,
                MediaFormat.KEY_PRIORITY to 0
            ),
            listOf(
                MediaFormat.KEY_OPERATING_RATE to FPS,
                MediaFormat.KEY_PRIORITY to 0
            ),
            listOf(
                MediaFormat.KEY_LOW_LATENCY to 1
            ),
            emptyList()
        )

        ensureEncoderCallbackThread()
        for (params in parameterSets) {
            val format = createBaseFormat()
            applyOptionalParams(format, params)
            val trialEncoder: MediaCodec = try {
                MediaCodec.createEncoderByType(MIME_TYPE)
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to instantiate encoder", ex)
                continue
            }
            try {
                trialEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                trialEncoder.setCallback(encoderOutputCallback, encoderCallbackHandler)
                val surface = trialEncoder.createInputSurface()
                trialEncoder.start()
                encoder = trialEncoder
                encoderSurface = surface
                codecConfig = null
                return true
            } catch (ex: Exception) {
                Log.w(TAG, "Encoder configure attempt failed", ex)
                trialEncoder.release()
            }
        }
        return false
    }

    private fun createBaseFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_TYPE, FRAME_WIDTH, FRAME_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
    }

    private fun applyOptionalParams(format: MediaFormat, params: List<Pair<String, Int>>) {
        params.forEach { (key, value) ->
            if (key == MediaFormat.KEY_LOW_LATENCY && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@forEach
            if (key == MediaFormat.KEY_OPERATING_RATE && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@forEach
            format.setInteger(key, value)
        }
    }

    private fun stopEncoder() {
        encoder?.stop()
        encoder?.release()
        encoder = null
        encoderSurface?.release()
        encoderSurface = null
        codecConfig = null
        releaseEncoderCallbackThread()
    }

    private fun ensureEncoderCallbackThread() {
        if (encoderCallbackThread == null) {
            encoderCallbackThread = HandlerThread("EncoderCallback").apply { start() }
            encoderCallbackHandler = Handler(encoderCallbackThread!!.looper)
        }
    }

    private fun releaseEncoderCallbackThread() {
        encoderCallbackThread?.quitSafely()
        encoderCallbackThread = null
        encoderCallbackHandler = null
    }

    private val encoderOutputCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // no-op; input handled via Surface
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!streaming.get()) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            val outputBuffer = codec.getOutputBuffer(index)
            if (outputBuffer != null && info.size > 0) {
                val data = ByteArray(info.size)
                outputBuffer.get(data)
                outputBuffer.clear()
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    codecConfig = data.copyOf()
                } else {
                    val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    sendFrame(data, isKeyFrame)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "Encoder callback error", e)
            stopStreaming()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d(TAG, "Encoder format changed")
        }
    }

    private fun sendFrame(data: ByteArray, isKeyFrame: Boolean) {
        if (data.isEmpty()) return
        if (clients.isEmpty()) return
        val payload = if (isKeyFrame && codecConfig != null) {
            ByteArray(codecConfig!!.size + data.size).also {
                System.arraycopy(codecConfig!!, 0, it, 0, codecConfig!!.size)
                System.arraycopy(data, 0, it, codecConfig!!.size, data.size)
            }
        } else {
            data
        }
        clients.forEach { socket ->
            try {
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
            } catch (ex: IOException) {
                Log.w(TAG, "Client write failed", ex)
                removeClient(socket)
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun startStreaming() {
        if (!streaming.compareAndSet(false, true)) return
        restartHandler.removeCallbacksAndMessages(null)
        if (!wakeLock.isHeld) wakeLock.acquire()
        if (!configureEncoder()) {
            Log.w(TAG, "Encoder could not be configured, scheduling restart")
            stopStreaming()
            scheduleRestart()
            return
        }
        try {
            openCamera()
            refreshNotification()
            sendStatusUpdate()
        } catch (ex: Exception) {
            Log.w(TAG, "Streaming failed to initialize", ex)
            stopStreaming()
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        restartHandler.removeCallbacksAndMessages(null)
        restartHandler.postDelayed({
            if (!streaming.get()) {
                Log.i(TAG, "Retrying streaming (attempt)")
                startStreaming()
            }
        }, restartDelayMs)
    }

    private fun stopStreaming() {
        if (!streaming.compareAndSet(true, false)) return
        stopEncoder()
        releaseCamera()
        closeAllClients()
        refreshNotification()
        if (wakeLock.isHeld) wakeLock.release()
        sendStatusUpdate()
    }

    private fun switchCamera(facing: Int) {
        selectedLensFacing = facing
        releaseCamera()
        if (streaming.get()) {
            openCamera()
        }
        sendStatusUpdate()
    }

    private fun selectCameraId(facing: Int): String? {
        return try {
            cameraManager.cameraIdList.find { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                characteristics.get(CameraCharacteristics.LENS_FACING) == facing
            }
        } catch (ex: CameraAccessException) {
            Log.w(TAG, "Unable to read camera list", ex)
            null
        }
    }

    private fun closeAllClients() {
        clients.forEach { socket ->
            try {
                socket.close()
            } catch (ignored: IOException) {
            }
        }
        clients.clear()
        sendStatusUpdate()
    }

    private fun addClient(socket: Socket) {
        clients.add(socket)
        sendStatusUpdate()
        startStreaming()
    }

    private fun removeClient(socket: Socket) {
        clients.remove(socket)
        try {
            socket.close()
        } catch (ignored: IOException) {
        }
        if (clients.isEmpty()) {
            stopStreaming()
        } else {
            sendStatusUpdate()
        }
    }

    private fun startTcpServer() {
        streamThread = StreamThread().also { it.start() }
    }

    private fun stopTcpServer() {
        streamThread?.interrupt()
        streamThread = null
        serverSocket?.closeSilently()
        serverSocket = null
    }

    private fun startHttpServer() {
        try {
            httpServer.start()
        } catch (ex: IOException) {
            Log.w(TAG, "HTTP server failed to start", ex)
        }
    }

    private fun stopHttpServer() {
        httpServer.stop()
    }

    private fun sendStatusUpdate(serviceRunning: Boolean = true) {
        sendBroadcast(
            Intent(ACTION_STATUS_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_IS_RUNNING, serviceRunning)
                putExtra(EXTRA_IS_STREAMING, streaming.get())
                putExtra(EXTRA_ACTIVE_CAMERA, if (selectedLensFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back")
                putExtra(EXTRA_BATTERY_LEVEL, batteryLevel)
                putExtra(EXTRA_BATTERY_STATUS, batteryStatus)
                putExtra(EXTRA_CLIENT_COUNT, clients.size)
                putExtra(EXTRA_HTTP_PORT, HTTP_PORT)
                putExtra(EXTRA_TCP_PORT, TCP_PORT)
            }
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Camera streamer controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls and status for the streaming service"
            }
        )
    }

    private fun buildNotification(streaming: Boolean): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Camera streamer")
            .setContentText(
                if (streaming) "Streaming to TCP port $TCP_PORT"
                else "Waiting for clients on port $TCP_PORT"
            )
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action(
                    0,
                    "Start stream",
                    makeServiceIntent(ACTION_START_STREAM)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    0,
                    "Stop stream",
                    makeServiceIntent(ACTION_STOP_STREAM)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    0,
                    "Shutdown",
                    makeServiceIntent(ACTION_STOP_SERVICE)
                )
            )
        builder.priority = NotificationCompat.PRIORITY_LOW
        return builder.build()
    }

    private fun refreshNotification() {
        startForeground(NOTIFICATION_ID, buildNotification(streaming.get()))
    }

    private fun makeServiceIntent(action: String): PendingIntent {
        val intent = Intent(this, CameraStreamService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private inner class StreamThread : Thread("H264StreamThread") {
        override fun run() {
            try {
                ServerSocket(TCP_PORT).use { server ->
                    serverSocket = server
                    while (!isInterrupted) {
                        val client = server.accept()
                        addClient(client)
                    }
                }
            } catch (ex: IOException) {
                Log.w(TAG, "TCP server stopped", ex)
            }
        }
    }

    private inner class ControlHttpServer : NanoHTTPD(HTTP_PORT) {
        override fun serve(session: IHTTPSession): Response {
            return when (session.uri.lowercase()) {
                "/" -> newFixedLengthResponse(Response.Status.OK, "text/html", CONTROL_PAGE)
                "/status" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    buildStatusJson()
                )
                "/camera/front" -> handlePost(session) {
                    switchCamera(CameraCharacteristics.LENS_FACING_FRONT)
                }
                "/camera/back" -> handlePost(session) {
                    switchCamera(CameraCharacteristics.LENS_FACING_BACK)
                }
                "/stream/start" -> handlePost(session) {
                    startStreaming()
                }
                "/stream/stop" -> handlePost(session) {
                    stopStreaming()
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown endpoint")
            }
        }

        private fun handlePost(session: IHTTPSession, action: () -> Unit): Response {
            if (session.method != Method.POST) {
                return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Use POST")
            }
            action()
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        }
    }

    private fun buildStatusJson(): String {
        return JSONObject().apply {
            put("streaming", streaming.get())
            put("clients", clients.size)
            put("camera", if (selectedLensFacing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back")
            put("batteryLevel", batteryLevel)
            put("batteryStatus", batteryStatus)
            put("tcpPort", TCP_PORT)
            put("httpPort", HTTP_PORT)
        }.toString()
    }

    private fun ServerSocket.closeSilently() {
        try {
            close()
        } catch (ignored: IOException) {
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CameraStreamService = this@CameraStreamService
    }
}
