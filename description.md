Цель:
Создать Android приложение/сервис, которое:

работает как Foreground Service

поддерживает Android 8+ (API 26+)

использует Camera2 API для захвата видео

кодирует видео в H.264 (MediaCodec)

включает встроенный HTTP управляющий сервер

включает TCP‑порт H.264 streaming

автоматически стартует стрим при подключении клиента и останавливает при отключении

имеет контролы через HTTP веб‑страницу:

переключение front/back камеры

отображение информации о батарее (уровень, статус зарядки)

работает при выключенном экране, удерживая WakeLock

включает уведомление Foreground Service с кнопками управления

📌 Технические детали (включи в prompt)

Foreground Service:

Uses startForeground() with a notification channel

Includes WakeLock (PowerManager.PARTIAL_WAKE_LOCK) so CPU stays awake while streaming

Service survives screen off / background modes

Camera capture:

Use Camera2 API (supported on API 21+)

Query camera list and choose front/back

Setup MediaCodec with createInputSurface()

Use CameraCaptureSession.setRepeatingRequest(...) for continuous capture

Generate H.264 frames with SPS/PPS in output buffer

Embedded HTTP server:

Simple embedded server (NanoHTTPD or lightweight)

Control endpoints e.g.:

GET /status → returns battery & active camera info

POST /camera/front → switch to front camera

POST /camera/back → switch to back camera

POST /stream/start → start streaming

POST /stream/stop → stop streaming

Web UI: simple HTML buttons calling these endpoints

H.264 streaming:

Dedicated TCP server socket on e.g. port 27183

When one or more clients connect, start encoder output delivery

When last client disconnects → stop camera capture + encoder

Send raw Annex‑B H.264 byte stream (with SPS/PPS before frames)

Battery info:

Read from BatteryManager via registerReceiver → ACTION_BATTERY_CHANGED

Include in /status JSON response

Permissions:

<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

Threading:

HTTP server & stream socket run on separate threads

Camera capture & encoder run on dedicated threads

📌 Non‑Functional Requirements

Performance:

Aim for 720p @ 30fps by default

Low latency between camera capture and delivery

Stability:

Gracefully handle camera errors / codec errors

Auto retry if encoder stops unexpectedly

Compatibility:

Must compile and run on Android 8+ (API level >= 26)

Supports devices with H.264 hardware encoder

📌 Example Control HTML Page

Place under / of embedded server:

<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>Camera Service Controls</title>
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
  </script>

</body>
</html>
🧠 Архитектурный Pipeline (для AI)
Camera2 → MediaCodec (H.264) → Byte Streams
        ↓                                    ↓
  CameraCaptureSession               TCP Server (port 27183)
                                         ↑
                               Connected clients receive H.264
                                         ↓
                                Embedded HTTP Server
                                      Controls &
                                  Status/Battery Info
📌 Integration Notes

You may use NanoHTTPD for embedded HTTP server — simple Java library to serve HTTP on Android. turn0search5

Optionally you can use RTSP or RTP libraries like libstreaming or RootEncoder for easier H.264 work. turn0search0

But the requirement here is custom raw H.264 TCP, not RTSP.

📌 Prompt Template (copy/paste)

Use this as the prompt for Copilot/Cursor/Codex:

Generate a complete Android project in Java (or Kotlin — choose one) that implements:

- Foreground Service with WakeLock to keep CPU running when screen is off.
- Uses Camera2 API to capture camera frames on Android 8+.
- Encodes camera frames to H.264 using MediaCodec.
- Embedded lightweight HTTP server with endpoints to start/stop streaming, switch front/back camera, and report battery status/level.
- A simple HTML page served over HTTP with controls for those actions.
- A separate TCP server socket for H.264 streaming to multiple clients.
- Stream starts when clients connect and stops when last client disconnects.
- Include manifest permissions and service declaration.
- Assume raw TCP streaming of Annex B H.264 over a socket.
- Include comments, threading, error handling, and basic UI controls in notification.

Если нужно, могу также сгенерировать готовый CLI/terminal prompt под конкретную IDE или инструменты (например, Cursor, Copilot CLI, Codex CLI) с точными параметрами (язык, структуры пакетов), включая build.gradle/AndroidManifest.xml.