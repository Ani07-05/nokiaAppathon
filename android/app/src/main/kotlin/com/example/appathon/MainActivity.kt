package com.example.appathon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.common.StandardMessageCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val CHANNEL = "video_control"
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread

    // Define MediaCodec and DatagramSocket for encoding and sending
    private var mediaCodec: MediaCodec? = null
    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 1234 // Set your server port

    override fun configureFlutterEngine(flutterEngine: io.flutter.embedding.engine.FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d(TAG, "Flutter Engine Configured")

        // Register native view for SurfaceView
        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("native-view-type", SurfaceViewFactory(this) { holder ->
                Log.d(TAG, "SurfaceView initialized")
                surfaceHolder = holder  // Save SurfaceHolder reference when passed
            })

        // Platform channel for controlling video
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCapture" -> {
                    Log.d(TAG, "startCapture called")
                    if (checkCameraPermission()) {
                        startBackgroundThread()
                        startVideoCapture()
                        result.success("Video capture started")
                    } else {
                        result.error("PERMISSION_DENIED", "Camera permission denied", null)
                    }
                }
                "stopCapture" -> {
                    Log.d(TAG, "stopCapture called")
                    stopVideoCapture()
                    stopBackgroundThread()
                    result.success("Video capture stopped")
                }
                "encodeAndSend" -> {
                    Log.d(TAG, "encodeAndSend called")
                    CoroutineScope(Dispatchers.IO).launch {
                        startEncodingAndSending()  // Run the network operations in background
                        result.success("Encoding and sending started")
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun startBackgroundThread() {
        Log.d(TAG, "Starting background thread")
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        Log.d(TAG, "Stopping background thread")
        backgroundThread.quitSafely()
        backgroundThread.join()
    }

    private fun startVideoCapture() {
        Log.d(TAG, "Starting video capture")
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0] // Use first camera

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission not granted")
            // Request camera permission if not granted
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d(TAG, "Camera opened")
                cameraDevice = camera
                startPreview(cameraDevice!!)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.d(TAG, "Camera disconnected")
                cameraDevice?.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                cameraDevice?.close()
                cameraDevice = null
            }
        }, backgroundHandler)
    }

    private fun startPreview(cameraDevice: CameraDevice) {
        Log.d(TAG, "Starting camera preview")
        if (!::surfaceHolder.isInitialized) {
            throw IllegalStateException("SurfaceHolder is not initialized")
        }

        val surface = surfaceHolder.surface
        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder.addTarget(surface)

        cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "Capture session configured")
                cameraCaptureSession = session
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Capture session configuration failed")
            }
        }, backgroundHandler)
    }

    private fun stopVideoCapture() {
        Log.d(TAG, "Stopping video capture")
        cameraCaptureSession.close()
        cameraDevice?.close()
        cameraDevice = null
    }

    private suspend fun startEncodingAndSending() {
        Log.d(TAG, "Start encoding and sending")
        try {
            // Setup MediaCodec for H.264 encoding
            mediaCodec = MediaCodec.createEncoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 125000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface: Surface = mediaCodec!!.createInputSurface()
            mediaCodec?.start()
            Log.d(TAG, "MediaCodec started")

            // Setup UDP socket for RTP packet transmission
            udpSocket = DatagramSocket()
            serverAddress = InetAddress.getByName("172.20.240.1")  // Replace with actual server IP
            Log.d(TAG, "UDP socket initialized with server $serverAddress")

            var sequenceNumber = 0
            val ssrc = 12345  // Unique stream identifier

            // Capture video frames, encode, and send over UDP using RTP packets
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val encodedData = mediaCodec!!.getOutputBuffer(outputBufferIndex)
                    val packetData = ByteArray(bufferInfo.size)
                    encodedData?.get(packetData)

                    Log.d(TAG, "Encoded data size: ${packetData.size}")

                    // Create RTP packet
                    val rtpPacket = createRTPPacket(
                        payload = packetData,
                        sequenceNumber = sequenceNumber++,
                        timestamp = System.currentTimeMillis() / (1000 / 30),  // Example timestamp
                        ssrc = ssrc,
                        marker = true  // Set marker bit for last packet in frame
                    )

                    Log.d(TAG, "RTP packet created with sequence number: $sequenceNumber")

                    // Send the RTP packet in a coroutine to avoid blocking the main thread
                    CoroutineScope(Dispatchers.IO).launch {
                        sendRTPPacket(rtpPacket)
                    }

                    mediaCodec!!.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during encoding and sending", e)
        }
    }

    private suspend fun sendRTPPacket(encodedData: ByteArray) {
        try {
            val packetSize = 1300 // RTP packet size
            var offset = 0
            while (offset < encodedData.size) {
                val remainingDataSize = minOf(packetSize, encodedData.size - offset)
                val packet = DatagramPacket(encodedData, offset, remainingDataSize, serverAddress, serverPort)
                udpSocket?.send(packet)
                Log.d(TAG, "Sent RTP packet size: $remainingDataSize")
                offset += remainingDataSize
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending RTP packet", e)
        }
    }

    private fun createRTPPacket(payload: ByteArray, sequenceNumber: Int, timestamp: Long, ssrc: Int, marker: Boolean): ByteArray {
        val rtpHeader = ByteArray(12)

        // RTP version 2
        rtpHeader[0] = (2 shl 6).toByte()

        // Marker bit and payload type 96 for H.264
        rtpHeader[1] = ((if (marker) 1 else 0) shl 7 or 96).toByte()

        // Sequence number
        rtpHeader[2] = (sequenceNumber shr 8).toByte()
        rtpHeader[3] = (sequenceNumber and 0xFF).toByte()

        // Timestamp
        rtpHeader[4] = (timestamp shr 24).toByte()
        rtpHeader[5] = (timestamp shr 16).toByte()
        rtpHeader[6] = (timestamp shr 8).toByte()
        rtpHeader[7] = (timestamp and 0xFF).toByte()

        // SSRC identifier
        rtpHeader[8] = (ssrc shr 24).toByte()
        rtpHeader[9] = (ssrc shr 16).toByte()
        rtpHeader[10] = (ssrc shr 8).toByte()
        rtpHeader[11] = (ssrc and 0xFF).toByte()

        // Combine RTP header and payload
        val rtpPacket = ByteArray(rtpHeader.size + payload.size)
        System.arraycopy(rtpHeader, 0, rtpPacket, 0, rtpHeader.size)
        System.arraycopy(payload, 0, rtpPacket, rtpHeader.size, payload.size)

        Log.d(TAG, "RTP packet created")
        return rtpPacket
    }

    // Check camera permission
    private fun checkCameraPermission(): Boolean {
        Log.d(TAG, "Checking camera permission")
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    // Clean up resources
    private fun disposeResources() {
        Log.d(TAG, "Disposing resources")
        cameraDevice?.close()
        mediaCodec?.stop()
        mediaCodec?.release()
        udpSocket?.close()
    }

    class SurfaceViewFactory(private val activity: MainActivity, private val onSurfaceReady: (SurfaceHolder) -> Unit) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context?, id: Int, args: Any?): PlatformView {
            return NativeSurfaceView(activity, onSurfaceReady)
        }
    }

    class NativeSurfaceView(private val activity: MainActivity, private val onSurfaceReady: (SurfaceHolder) -> Unit) : PlatformView {
        private val surfaceView: SurfaceView = SurfaceView(activity)

        init {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.d(TAG, "Surface created")
                    onSurfaceReady(holder)  // Pass the SurfaceHolder back to MainActivity
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Log.d(TAG, "Surface changed")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.d(TAG, "Surface destroyed")
                    // Close the camera if necessary
                }
            })

            val layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            surfaceView.layoutParams = layoutParams
        }

        override fun getView(): SurfaceView {
            return surfaceView
        }

        override fun dispose() {
            Log.d(TAG, "Disposing NativeSurfaceView")
            activity.disposeResources()
        }
    }
}
