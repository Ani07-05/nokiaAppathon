package com.example.appathon

import android.content.Context
import android.hardware.camera2.*
import android.view.Surface
import android.view.SurfaceView
import android.view.SurfaceHolder
import android.widget.FrameLayout
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.common.StandardMessageCodec

class MainActivity : FlutterActivity() {
    private val CHANNEL = "video_control"
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var surfaceHolder: SurfaceHolder  // This will hold the reference to SurfaceHolder

    override fun configureFlutterEngine(flutterEngine: io.flutter.embedding.engine.FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Register native view for SurfaceView
        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("native-view-type", SurfaceViewFactory { holder ->
                surfaceHolder = holder  // Save SurfaceHolder reference when passed
            })

        // Platform channel for controlling video
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCapture" -> {
                    startVideoCapture()
                    result.success("Video capture started")
                }
                "stopCapture" -> {
                    stopVideoCapture()
                    result.success("Video capture stopped")
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun startVideoCapture() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList[0] // Use first camera
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview(cameraDevice!!)
            }

            override fun onDisconnected(camera: CameraDevice) {
                cameraDevice?.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                cameraDevice?.close()
            }
        }, null)
    }

    private fun startPreview(cameraDevice: CameraDevice) {
        if (!::surfaceHolder.isInitialized) {
            throw IllegalStateException("SurfaceHolder is not initialized")
        }

        val surface = surfaceHolder.surface
        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder.addTarget(surface)

        cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                cameraCaptureSession = session
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                // Handle failure
            }
        }, null)
    }

    private fun stopVideoCapture() {
        cameraCaptureSession.close()
        cameraDevice?.close()
        cameraDevice = null
    }

    class SurfaceViewFactory(private val onSurfaceReady: (SurfaceHolder) -> Unit) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
        override fun create(context: Context?, id: Int, args: Any?): PlatformView {
            return NativeSurfaceView(context!!, onSurfaceReady)
        }
    }

    class NativeSurfaceView(context: Context, private val onSurfaceReady: (SurfaceHolder) -> Unit) : PlatformView {
        private val surfaceView: SurfaceView = SurfaceView(context)
        private var cameraDevice: CameraDevice? = null

        init {
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    onSurfaceReady(holder)  // Pass the SurfaceHolder back to MainActivity
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    // Handle surface changes, such as resizing
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    cameraDevice?.close()
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
            cameraDevice?.close()
        }
    }
}
