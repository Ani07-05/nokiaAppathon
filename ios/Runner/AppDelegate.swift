import UIKit
import Flutter

@UIApplicationMain
@objc class AppDelegate: FlutterAppDelegate {
    var videoCaptureHandler: VideoCaptureHandler?

    override func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let controller: FlutterViewController = window?.rootViewController as! FlutterViewController
        let videoChannel = FlutterMethodChannel(name: "video_control", binaryMessenger: controller.binaryMessenger)
        
        videoChannel.setMethodCallHandler({
            [weak self] (call: FlutterMethodCall, result: @escaping FlutterResult) -> Void in
            if call.method == "encodeAndSend" {
                self?.videoCaptureHandler = VideoCaptureHandler()
                self?.videoCaptureHandler?.encodeAndSendRTP()
                result(nil)
            } else {
                result(FlutterMethodNotImplemented)
            }
        })
        
        return super.application(application, didFinishLaunchingWithOptions: launchOptions)
    }
}
