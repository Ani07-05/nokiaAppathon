import Foundation
import AVFoundation
import VideoToolbox

@objc class VideoCaptureHandler: NSObject {
    
    var captureSession: AVCaptureSession?
    var videoOutput: AVCaptureVideoDataOutput?
    var udpSocket: GCDAsyncUdpSocket?

    override init() {
        super.init()
        self.setupCaptureSession()
    }

    func setupCaptureSession() {
        captureSession = AVCaptureSession()
        guard let captureSession = captureSession else { return }

        captureSession.beginConfiguration()

        let videoDevice = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
        guard let videoDeviceInput = try? AVCaptureDeviceInput(device: videoDevice!) else { return }
        if captureSession.canAddInput(videoDeviceInput) {
            captureSession.addInput(videoDeviceInput)
        }

        videoOutput = AVCaptureVideoDataOutput()
        videoOutput?.setSampleBufferDelegate(self, queue: DispatchQueue(label: "videoQueue"))
        if captureSession.canAddOutput(videoOutput!) {
            captureSession.addOutput(videoOutput!)
        }

        captureSession.commitConfiguration()
        captureSession.startRunning()
    }

    func encodeAndSendRTP() {
        udpSocket = GCDAsyncUdpSocket(delegate: self, delegateQueue: DispatchQueue.main)
        do {
            try udpSocket?.connect(toHost: "your.server.ip", onPort: yourPort)
        } catch let error {
            print("UDP Socket Error: \(error.localizedDescription)")
        }
    }
}

extension VideoCaptureHandler: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        var formatDesc: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pixelBuffer, formatDescriptionOut: &formatDesc)

        var encodedData: Data?
        VTCompressionSessionEncodeFrame(compressionSession, imageBuffer: pixelBuffer, presentationTimeStamp: CMTimeMake(value: 1, timescale: 1), duration: CMTime.invalid, frameProperties: nil, sourceFrameRefCon: nil, infoFlagsOut: nil)

        // TODO: RTP packetize encodedData and send via UDP socket
    }
}

extension VideoCaptureHandler: GCDAsyncUdpSocketDelegate {
    // Handle sending and receiving data from the socket
}
