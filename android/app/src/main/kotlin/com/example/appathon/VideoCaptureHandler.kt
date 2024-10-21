package com.example.appathon

import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class VideoCaptureHandler(
    private val context: Context,
    private val surfaceView: SurfaceView
) {

    companion object {
        private const val TAG = "VideoCaptureHandler"
        private const val SERVER_PORT = 1234 // Port to send/receive RTP packets
        private const val BUFFER_SIZE = 1500 // RTP packet size (1500 bytes)
    }

    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread

    private var mediaCodec: MediaCodec? = null
    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null

    // Video streaming: start capturing from camera and sending RTP packets
    fun startCaptureAndSend() {
        startBackgroundThread()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0] // Choose the first camera

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.d(TAG, "Camera opened")
                cameraDevice = camera
                startPreview(cameraDevice!!)
                startEncodingAndSending()
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

    // Initialize and start the camera preview
    private fun startPreview(cameraDevice: CameraDevice) {
        val surface = surfaceView.holder.surface
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

    // Start encoding the camera frames and sending them as RTP packets
    private fun startEncodingAndSending() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                mediaCodec = MediaCodec.createEncoderByType("video/avc")
                val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 1250000)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

                mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mediaCodec?.start()

                udpSocket = DatagramSocket()
                serverAddress = InetAddress.getByName("172.20.240.1") 

                while (true) {
                    val bufferInfo = MediaCodec.BufferInfo()
                    val outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10000)

                    if (outputBufferIndex >= 0) {
                        val encodedData: ByteBuffer = mediaCodec!!.getOutputBuffer(outputBufferIndex)!!
                        val packetData = ByteArray(bufferInfo.size)
                        encodedData.get(packetData)

                        sendRTPPacket(packetData)

                        mediaCodec!!.releaseOutputBuffer(outputBufferIndex, false)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during encoding and sending", e)
            }
        }
    }

    // Send the encoded video frame as RTP packets
    private fun sendRTPPacket(encodedData: ByteArray) {
        val packetSize = BUFFER_SIZE
        var offset = 0

        while (offset < encodedData.size) {
            val remainingDataSize = minOf(packetSize, encodedData.size - offset)
            val packet = DatagramPacket(encodedData, offset, remainingDataSize, serverAddress, SERVER_PORT)
            udpSocket?.send(packet)
            offset += remainingDataSize
        }
    }

    // Stop the camera and video sending
    fun stopCaptureAndSend() {
        cameraCaptureSession.close()
        cameraDevice?.close()
        mediaCodec?.stop()
        mediaCodec?.release()
        udpSocket?.close()
        stopBackgroundThread()
    }

    // Start the background thread for camera and encoding
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    // Stop the background thread
    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        backgroundThread.join()
    }

    // Start receiving RTP packets and decode them to display video
    fun startReceiving() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val receiveSocket = DatagramSocket(SERVER_PORT)
                val buffer = ByteArray(BUFFER_SIZE)

                mediaCodec = MediaCodec.createDecoderByType("video/avc")
                val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
                mediaCodec?.configure(format, surfaceView.holder.surface, null, 0)
                mediaCodec?.start()

                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    receiveSocket.receive(packet)

                    val packetData = packet.data.copyOf(packet.length)
                    decodeRTPPacket(packetData)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error receiving or decoding RTP packet", e)
            }
        }
    }

    // Decode the received RTP packets and render them
    private fun decodeRTPPacket(packetData: ByteArray) {
        val inputBufferIndex = mediaCodec!!.dequeueInputBuffer(10000)
        if (inputBufferIndex >= 0) {
            val inputBuffer: ByteBuffer = mediaCodec!!.getInputBuffer(inputBufferIndex)!!
            inputBuffer.clear()
            inputBuffer.put(packetData)

            mediaCodec!!.queueInputBuffer(
                inputBufferIndex,
                0,
                packetData.size,
                System.currentTimeMillis() * 1000,
                0
            )

            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex >= 0) {
                mediaCodec!!.releaseOutputBuffer(outputBufferIndex, true)
            }
        }
    }

    // Stop receiving and decoding the RTP packets
    fun stopReceiving() {
        mediaCodec?.stop()
        mediaCodec?.release()
        stopBackgroundThread()
    }
}
