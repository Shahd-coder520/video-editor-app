package com.example.shahed // ⚠️ تأكدي أن الاسم يطابق مشروعك

import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.os.Handler
import android.os.Looper

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.lomio.editor/native_engine"
    // 🌟 رابط الـ API تبع أستاذك
    private val apiUrl = "https://backend.lomio-app.com/api/generate-captions"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // تجهيز المحرك
        val nativeEngine = LomioNativeEngine(this)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "startNativeRendering") {
                val projectData = call.argument<String>("projectData") ?: "{}"

                // تشغيل محرك الرندرة
                nativeEngine.startRendering(
                    projectData,
                    onComplete = { successMessage ->
                        Handler(Looper.getMainLooper()).post {
                            result.success(successMessage)
                        }
                    },
                    onError = { errorMessage ->
                        Handler(Looper.getMainLooper()).post {
                            result.error("RENDER_FAILED", errorMessage, null)
                        }
                    }
                )
            }
            // 🌟 استقبال طلب الكابشن من فلاتر 🌟
            else if (call.method == "generateAutoCaptions") {
                val videoPath = call.argument<String>("videoPath") ?: ""

                nativeEngine.generateAutoCaptions(
                    videoPath,
                    apiUrl,
                    onComplete = { jsonResponse ->
                        // لما يرجع الـ JSON بنجاح، بنرجعه لفلاتر
                        Handler(Looper.getMainLooper()).post {
                            result.success(jsonResponse)
                        }
                    },
                    onError = { errorMessage ->
                        Handler(Looper.getMainLooper()).post {
                            result.error("CAPTION_ERROR", errorMessage, null)
                        }
                    }
                )
            } else {
                result.notImplemented()
            }
        }
    }
}