package com.example.appathon

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class VideoCaptureHandler {

    private var mediaCodec: MediaCodec? = null
    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 1234 // Set your server port

    suspend fun startEncodingAndSending() = withContext(Dispatchers.IO) {
        try {
            // Setup MediaCodec for H.264 encoding
            mediaCodec = MediaCodec.createEncoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", 1280, 720)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1250000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface: Surface = mediaCodec!!.createInputSurface()
            mediaCodec?.start()

            // Setup UDP socket for RTP packet transmission
            udpSocket = DatagramSocket()
            serverAddress = InetAddress.getByName("your.server.ip")

            // TODO: Capture video frames from inputSurface, encode using MediaCodec,
            // and send over UDP using sendRTPPacket()

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
            e.printStackTrace()
        }
    }

    // Send encoded video as RTP packet
    private fun sendRTPPacket(encodedData: ByteArray) {
        val packetSize = 1300 // Set the size of the RTP packet payload
        var offset = 0

        while (offset < encodedData.size) {
            val remainingDataSize = minOf(packetSize, encodedData.size - offset)
            val packet = DatagramPacket(encodedData, offset, remainingDataSize, serverAddress, serverPort)
            udpSocket?.send(packet)
            offset += remainingDataSize
        }
    }

    // Clean up resources
    fun stopEncodingAndSending() {
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null

        udpSocket?.close()
        udpSocket = null
    }
}
