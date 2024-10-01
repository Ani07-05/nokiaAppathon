import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        primaryColor: Colors.blueAccent,
        fontFamily: 'Montserrat',
      ),
      home: VideoCaptureDemo(),
    );
  }
}

class VideoCaptureDemo extends StatefulWidget {
  @override
  _VideoCaptureDemoState createState() => _VideoCaptureDemoState();
}

class _VideoCaptureDemoState extends State<VideoCaptureDemo>
    with SingleTickerProviderStateMixin {
  static const platform = MethodChannel('video_control');
  bool isCapturing = false;
  bool hasPermission = false;

  late AnimationController _controller;
  late Animation<Color?> _colorTween;

  @override
  void initState() {
    super.initState();
    _controller =
        AnimationController(vsync: this, duration: Duration(seconds: 1));
    _colorTween = _controller.drive(
      ColorTween(begin: Colors.redAccent, end: Colors.greenAccent),
    );
    _requestCameraPermission();
  }

  Future<void> _requestCameraPermission() async {
    PermissionStatus status = await Permission.camera.request();
    setState(() {
      hasPermission = status.isGranted;
    });
    if (!hasPermission) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text("Camera permission is required to capture video."),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _startStopVideo() async {
    if (!hasPermission) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text("Camera permission denied."),
          backgroundColor: Colors.red,
        ),
      );
      return;
    }

    try {
      if (isCapturing) {
        await platform.invokeMethod('stopCapture');
        _controller.reverse();
      } else {
        await platform.invokeMethod('startCapture');
        _controller.forward();
        await platform.invokeMethod('encodeAndSend'); // Method to encode and send video
      }
      setState(() {
        isCapturing = !isCapturing;
      });
    } on PlatformException catch (e) {
      print("Failed to control video capture: '${e.message}'.");
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // Fun, playful animated gradient background
          AnimatedContainer(
            duration: Duration(seconds: 3),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: isCapturing
                    ? [Colors.red, Colors.purple]
                    : [Colors.blue, Colors.green],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
            ),
          ),
          // Camera Preview (NativeView)
          Positioned.fill(
            child: hasPermission
                ? AspectRatio(
                    aspectRatio: 16 / 9,
                    child: NativeView(),
                  )
                : Center(
                    child: Text(
                      "Waiting for camera permission...",
                      style: GoogleFonts.montserrat(
                          fontSize: 18, color: Colors.white),
                    ),
                  ),
          ),
          // Video Capture Controls
          Align(
            alignment: Alignment.bottomCenter,
            child: Padding(
              padding: const EdgeInsets.only(bottom: 50),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Animated Capture Status Text
                  AnimatedOpacity(
                    opacity: isCapturing ? 1.0 : 0.7,
                    duration: Duration(seconds: 1),
                    child: Text(
                      isCapturing ? "🎥 Recording..." : "Ready to record 🎬",
                      style: GoogleFonts.montserrat(
                        color: Colors.white,
                        fontSize: 22,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  SizedBox(height: 20),
                  // Animated Capture Button
                  AnimatedContainer(
                    duration: Duration(milliseconds: 300),
                    width: isCapturing ? 80 : 70,
                    height: isCapturing ? 80 : 70,
                    decoration: BoxDecoration(
                      color: _colorTween.value,
                      shape: BoxShape.circle,
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black38,
                          spreadRadius: 4,
                          blurRadius: 10,
                        ),
                      ],
                    ),
                    child: IconButton(
                      iconSize: 40,
                      icon: Icon(
                        isCapturing ? Icons.stop : Icons.play_arrow,
                        color: Colors.white,
                      ),
                      onPressed: _startStopVideo,
                    ),
                  ),
                  SizedBox(height: 20),
                  // Fun Circular Progress Indicator when recording
                  if (isCapturing)
                    CircularProgressIndicator(
                      valueColor:
                          AlwaysStoppedAnimation<Color>(Colors.white),
                      strokeWidth: 4.0,
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class NativeView extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    const String viewType = 'native-view-type';
    final Map<String, dynamic> creationParams = <String, dynamic>{};

    return Container(
      width: double.infinity,
      height: double.infinity,
      child: PlatformViewLink(
        viewType: viewType,
        surfaceFactory: (
          BuildContext context,
          PlatformViewController controller,
        ) {
          return AndroidViewSurface(
            controller: controller as AndroidViewController,
            gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
            hitTestBehavior: PlatformViewHitTestBehavior.opaque,
          );
        },
        onCreatePlatformView: (PlatformViewCreationParams params) {
          return PlatformViewsService.initSurfaceAndroidView(
            id: params.id,
            viewType: viewType,
            layoutDirection: TextDirection.ltr,
            creationParams: creationParams,
            creationParamsCodec: const StandardMessageCodec(),
          )
            ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
            ..create();
        },
      ),
    );
  }
}
