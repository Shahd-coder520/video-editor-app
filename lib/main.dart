import 'dart:io';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:file_picker/file_picker.dart';
import 'package:ffmpeg_kit_flutter_new/ffprobe_kit.dart'; // أبقينا فقط على FFprobe لقراءة مدة الفيديو بسرعة
import 'package:flutter/services.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Lomio Pro Max',
      theme: ThemeData.dark(),
      debugShowCheckedModeBanner: false,
      home: const EditorScreen(),
    );
  }
}

class EditorScreen extends StatefulWidget {
  const EditorScreen({super.key});
  @override
  State<EditorScreen> createState() => _EditorScreenState();
}

class _EditorScreenState extends State<EditorScreen> {
  late final WebViewController _controller;
  HttpServer? _localServer;
  int _serverPort = 0;

  static const platform = MethodChannel('com.lomio.editor/native_engine');

  @override
  void initState() {
    super.initState();
    _requestPermissions();
    _initWebView();
    _startSafeLocalServer();
  }

  Future<void> _requestPermissions() async {
    await [
      Permission.storage,
      Permission.videos,
      Permission.photos,
      Permission.audio
    ].request();
  }

  void _initWebView() {
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFF09090B))
      ..addJavaScriptChannel('FlutterControlChannel', onMessageReceived: (msg) => _handleExportData(msg.message))
      ..addJavaScriptChannel('MediaPickerChannel', onMessageReceived: (msg) => _pickMedia(msg.message))
      ..addJavaScriptChannel('AutoCaptionChannel', onMessageReceived: (msg) => _handleAutoCaption(msg.message));

    if (_controller.platform is AndroidWebViewController) {
      AndroidWebViewController.enableDebugging(true);
      (_controller.platform as AndroidWebViewController).setMediaPlaybackRequiresUserGesture(false);
    }
    _loadHtmlSafely();
  }

  Future<void> _loadHtmlSafely() async {
    try {
      await _controller.loadFlutterAsset('assets/lomio.html');
    } catch (e) {
      debugPrint(" فشل تحميل واجهة الويب");
    }
  }

  Future<void> _startSafeLocalServer() async {
    try {
      _localServer = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      _serverPort = _localServer!.port;
      _localServer!.listen((HttpRequest request) async {
        final path = request.uri.queryParameters['path'];
        if (path != null) {
          final file = File(path);
          if (await file.exists()) {
            request.response.headers.add('Access-Control-Allow-Origin', '*');
            request.response.headers.add('Accept-Ranges', 'bytes');
            if (path.endsWith('.mp4')) request.response.headers.add(HttpHeaders.contentTypeHeader, 'video/mp4');
            else request.response.headers.add(HttpHeaders.contentTypeHeader, 'image/jpeg');
            final length = await file.length();
            final rangeHeader = request.headers.value('range');
            if (rangeHeader != null && rangeHeader.startsWith('bytes=')) {
              final parts = rangeHeader.substring(6).split('-');
              final start = int.tryParse(parts[0]) ?? 0;
              final end = parts.length > 1 && parts[1].isNotEmpty ? int.parse(parts[1]) : length - 1;
              request.response.statusCode = HttpStatus.partialContent;
              request.response.headers.add('Content-Range', 'bytes $start-$end/$length');
              request.response.contentLength = end - start + 1;
              await request.response.addStream(file.openRead(start, end + 1));
            } else {
              request.response.headers.add(HttpHeaders.contentLengthHeader, length);
              await request.response.addStream(file.openRead());
            }
            await request.response.close();
            return;
          }
        }
        request.response.statusCode = HttpStatus.notFound;
        request.response.close();
      });
    } catch (e) {}
  }

  Future<void> _pickMedia(String type) async {
    try {
      FileType pickerType = type == 'audio' ? FileType.audio : (type == 'image' ? FileType.image : FileType.video);
      FilePickerResult? result = await FilePicker.platform.pickFiles(type: pickerType);
      if (result != null && result.files.single.path != null) {
        String originalPath = result.files.single.path!;
        String fileName = result.files.single.name;

        // استنساخ آمن للملف لمنع حظر الأندرويد
        final tempDir = Directory.systemTemp;
        final safeFile = await File(originalPath).copy('${tempDir.path}/${DateTime.now().millisecondsSinceEpoch}_$fileName');
        String safePath = safeFile.path;

        double mediaDuration = 5.0;
        if (type == 'video' || type == 'audio') {
          // جلب مدة الفيديو بسرعة
          final session = await FFprobeKit.getMediaInformation(safePath);
          final info = session.getMediaInformation();
          if (info != null && info.getDuration() != null) mediaDuration = double.parse(info.getDuration()!);
        }

        String previewUrl = "http://127.0.0.1:$_serverPort/?path=${Uri.encodeComponent(safePath)}";
        Map<String, dynamic> mediaData = {'previewPath': previewUrl, 'exportPath': safePath};
        String safeData = base64Encode(utf8.encode(jsonEncode(mediaData)));
        String safeName = base64Encode(utf8.encode(fileName));
        _controller.runJavaScript("window.addMediaFromFlutter('$type', '$safeData', '$safeName', $mediaDuration);");
      }
    } catch (e) {}
  }

  Future<void> _handleExportData(String jsonData) async {
    debugPrint(" جاري إرسال بيانات التايم لاين للمحرك الأصلي...");

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => const AlertDialog(
        backgroundColor: Color(0xFF18181b),
        title: Text(' جاري الرندرة الاحترافية', style: TextStyle(color: Colors.white)),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(color: Color(0xFFe11d48)),
            SizedBox(height: 16),
            Text('يتم الآن معالجة الفيديو عبر المحرك الأصلي...', style: TextStyle(color: Colors.grey)),
          ],
        ),
      ),
    );

    try {
      // إرسال الـ JSON إلى نظام الأندرويد ليقوم بالرندرة
      final String result = await platform.invokeMethod('startNativeRendering', {'projectData': jsonData});

      Navigator.pop(context); // إغلاق نافذة التحميل
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(' $result'), backgroundColor: Colors.green));

    } on PlatformException catch (e) {
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(' فشل المحرك: ${e.message}'), backgroundColor: Colors.red));
    }
  }

  Future<void> _handleAutoCaption(String videoPath) async {
    debugPrint(" جاري طلب الكابشن التلقائي للمسار: $videoPath");

    // إظهار تنبيه للمستخدم في فلاتر
    ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text(' جاري استخراج الصوت وتوليد الكلمات...'), backgroundColor: Colors.blueAccent)
    );

    try {
      // 1. الاتصال بمحرك Kotlin لفصل الصوت وضرب الـ API
      final String jsonResponse = await platform.invokeMethod('generateAutoCaptions', {'videoPath': videoPath});

      // 2. تنظيف الـ JSON المرتجع عشان ما يكسر كود الجافاسكريبت
      String safeJson = jsonResponse
          .replaceAll(r'\', r'\\')
          .replaceAll("'", r"\'")
          .replaceAll('"', r'\"');

      // 3. إرسال الكلمات للـ WebView لفرشها على التايم لاين
      _controller.runJavaScript("window.receiveCaptionsFromFlutter('$safeJson');");

    } on PlatformException catch (e) {
      debugPrint(" فشل الكابشن: ${e.message}");
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(' فشل توليد الكابشن: ${e.message}'), backgroundColor: Colors.red)
      );
      _controller.runJavaScript("alert('فشل الاتصال بخادم الكابشن!');");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF09090B),
      body: SafeArea(child: WebViewWidget(controller: _controller)),
    );
  }
}