import Flutter
import UIKit
import AVFoundation

@UIApplicationMain
@objc class AppDelegate: FlutterAppDelegate {
    override func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let controller = window?.rootViewController as! FlutterViewController
        let nativeViewFactory = NativeViewFactory()
        
        registrar(forPlugin: "native-view-type")?.register(nativeViewFactory, withId: "native-view-type")

        let videoChannel = FlutterMethodChannel(name: "video_control", binaryMessenger: controller.binaryMessenger)
        videoChannel.setMethodCallHandler { (call, result) in
            switch call.method {
            case "startCapture":
                // Start video capture on iOS
                result("Video capture started")
            case "stopCapture":
                // Stop video capture on iOS
                result("Video capture stopped")
            default:
                result(FlutterMethodNotImplemented)
            }
        }
        
        return super.application(application, didFinishLaunchingWithOptions: launchOptions)
    }
}

class NativeViewFactory: NSObject, FlutterPlatformViewFactory {
    func create(
        withFrame frame: CGRect,
        viewIdentifier viewId: Int64,
        arguments args: Any?
    ) -> FlutterPlatformView {
        return NativeUIView(frame: frame)
    }
}

class NativeUIView: NSObject, FlutterPlatformView {
    private var uiView: UIView
    private var captureSession: AVCaptureSession?

    init(frame: CGRect) {
        uiView = UIView(frame: frame)
        super.init()

        // Set up video capture using AVFoundation
        setupCameraPreview()
    }

    func setupCameraPreview() {
        captureSession = AVCaptureSession()
        guard let captureDevice = AVCaptureDevice.default(for: .video) else { return }
        let input = try? AVCaptureDeviceInput(device: captureDevice)
        if let input = input {
            captureSession?.addInput(input)
        }

        let previewLayer = AVCaptureVideoPreviewLayer(session: captureSession!)
        previewLayer.frame = uiView.bounds
        uiView.layer.addSublayer(previewLayer)

        captureSession?.startRunning()
    }

    func view() -> UIView {
        return uiView
    }
}
