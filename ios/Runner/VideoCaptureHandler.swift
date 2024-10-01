import Foundation
import AVFoundation
import VideoToolbox

@objc class VideoCaptureHandler: NSObject {
    
    var captureSession: AVCaptureSession?
    var videoOutput: AVCaptureVideoDataOutput?
    var compressionSession: VTCompressionSession?
    var udpSocket: GCDAsyncUdpSocket?

    override init() {
        super.init()
        self.setupCaptureSession()
        self.setupVideoEncoder()
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

    func setupVideoEncoder() {
        // Create the H.264 compression session
        let width = 1280
        let height = 720
        let codec = kCMVideoCodecType_H264
        VTCompressionSessionCreate(allocator: kCFAllocatorDefault, width: Int32(width), height: Int32(height), codecType: codec, encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil, outputCallback: nil, refcon: nil, compressionSessionOut: &compressionSession)
        
        // Set compression session properties
        VTSessionSetProperty(compressionSession!, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(compressionSession!, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel)
        VTSessionSetProperty(compressionSession!, key: kVTCompressionPropertyKey_AverageBitRate, value: NSNumber(value: 1250000))
        VTSessionSetProperty(compressionSession!, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: NSNumber(value: 30))
        
        VTCompressionSessionPrepareToEncodeFrames(compressionSession!)
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

        let presentationTimeStamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let duration = CMSampleBufferGetDuration(sampleBuffer)

        var flags: VTEncodeInfoFlags = VTEncodeInfoFlags()
        VTCompressionSessionEncodeFrame(compressionSession!, imageBuffer: pixelBuffer, presentationTimeStamp: presentationTimeStamp, duration: duration, frameProperties: nil, sourceFrameRefcon: nil, infoFlagsOut: &flags)

        // TODO: RTP packetize encodedData and send via UDP socket
    }
}

extension VideoCaptureHandler: GCDAsyncUdpSocketDelegate {
    // Handle sending and receiving data from the socket
    func sendRTPPacket(encodedData: Data) {
        let packetSize = 1300 // Set the size of the RTP packet payload
        var offset = 0
        
        while offset < encodedData.count {
            let remainingDataSize = min(packetSize, encodedData.count - offset)
            let packet = encodedData.subdata(in: offset..<offset + remainingDataSize)
            udpSocket?.send(packet, withTimeout: -1, tag: 0)
            offset += remainingDataSize
        }
    }
}
