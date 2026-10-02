package com.example.shahed

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextPaint
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.*
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LomioAudioProcessor(
    private val kfEval: KeyframeEvaluator,
    private val baseVolume: Float,
    private val fadeInDurationUs: Long,
    private val fadeOutDurationUs: Long,
    private val totalDurationUs: Long
) : AudioProcessor {

    private var format = AudioProcessor.AudioFormat.NOT_SET
    private var bytesPassed = 0L
    private var inputEnded = false

    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        format = inputAudioFormat
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        val bytesPerFrame = if (format.channelCount > 0) format.channelCount * 2 else 2

        while (inputBuffer.hasRemaining()) {
            val sample = inputBuffer.getShort().toInt()

            val currentUs = if (bytesPerFrame > 0 && format.sampleRate > 0) {
                (bytesPassed * 1_000_000L) / (format.sampleRate * format.channelCount * 2)
            } else 0L

            val localTimeSec = currentUs / 1_000_000f
            val dynamicVolume = kfEval.getFloat("volume", baseVolume, localTimeSec)
            var currentGain = (dynamicVolume / 100f).coerceIn(0f, 2f)

            if (fadeInDurationUs > 0 && currentUs < fadeInDurationUs) {
                currentGain *= (currentUs.toFloat() / fadeInDurationUs.toFloat())
            } else if (fadeOutDurationUs > 0 && currentUs > (totalDurationUs - fadeOutDurationUs)) {
                currentGain *= ((totalDurationUs - currentUs).toFloat() / fadeOutDurationUs.toFloat())
            }

            val processed = (sample * currentGain).toInt().coerceIn(-32768, 32767)
            buffer.putShort(processed.toShort())

            bytesPassed += 2
        }

        buffer.flip()
        outputBuffer = buffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isActive(): Boolean = true

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        bytesPassed = 0L
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        format = AudioProcessor.AudioFormat.NOT_SET
    }
}

class KeyframeEvaluator(private val keyframesObj: JSONObject?, private val curveType: String = "ease-in-out") {
    fun getFloat(prop: String, baseVal: Float, localTimeSec: Float): Float {
        if (keyframesObj == null || !keyframesObj.has(prop)) return baseVal
        val arr = keyframesObj.optJSONArray(prop) ?: return baseVal
        if (arr.length() == 0) return baseVal

        val firstTime = arr.getJSONObject(0).getDouble("time").toFloat()
        if (localTimeSec <= firstTime) return arr.getJSONObject(0).getDouble("val").toFloat()

        val lastTime = arr.getJSONObject(arr.length() - 1).getDouble("time").toFloat()
        if (localTimeSec >= lastTime) return arr.getJSONObject(arr.length() - 1).getDouble("val").toFloat()

        for (i in 0 until arr.length() - 1) {
            val t1 = arr.getJSONObject(i).getDouble("time").toFloat()
            val t2 = arr.getJSONObject(i + 1).getDouble("time").toFloat()
            if (localTimeSec in t1..t2) {
                val v1 = arr.getJSONObject(i).getDouble("val").toFloat()
                val v2 = arr.getJSONObject(i + 1).getDouble("val").toFloat()

                var rawProgress = (localTimeSec - t1) / (t2 - t1)
                var progress = rawProgress

                when (curveType) {
                    "ease-in-out" -> progress = (-(Math.cos(Math.PI * rawProgress) - 1.0) / 2.0).toFloat()
                    "ease-in" -> progress = Math.pow(rawProgress.toDouble(), 3.0).toFloat()
                    "bounce" -> {
                        if (rawProgress < (1f / 2.75f)) {
                            progress = 7.5625f * rawProgress * rawProgress
                        } else if (rawProgress < (2f / 2.75f)) {
                            rawProgress -= (1.5f / 2.75f)
                            progress = 7.5625f * rawProgress * rawProgress + 0.75f
                        } else if (rawProgress < (2.5f / 2.75f)) {
                            rawProgress -= (2.25f / 2.75f)
                            progress = 7.5625f * rawProgress * rawProgress + 0.9375f
                        } else {
                            rawProgress -= (2.625f / 2.75f)
                            progress = 7.5625f * rawProgress * rawProgress + 0.984375f
                        }
                    }
                    "linear" -> progress = rawProgress
                }
                return v1 + (v2 - v1) * progress
            }
        }
        return baseVal
    }

    fun getString(prop: String, baseVal: String, localTimeSec: Float): String {
        if (keyframesObj == null || !keyframesObj.has(prop)) return baseVal
        val arr = keyframesObj.optJSONArray(prop) ?: return baseVal
        if (arr.length() == 0) return baseVal

        var activeVal = baseVal
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i).getDouble("time").toFloat()
            if (localTimeSec >= t - 0.05f) {
                activeVal = arr.getJSONObject(i).getString("val")
            }
        }
        return activeVal
    }
}

interface ProcessedOverlay {
    val type: String
    val startUs: Long
    val endUs: Long
    fun createMedia3Overlay(videoStartUs: Long): TextureOverlay
}

class ImageOverlay(
    override val type: String,
    override val startUs: Long,
    override val endUs: Long,
    val frameBmp: Bitmap,
    val itemJson: JSONObject,
    val videoWidth: Int,
    val videoHeight: Int
) : ProcessedOverlay {
    override fun createMedia3Overlay(videoStartUs: Long): TextureOverlay {
        return object : BitmapOverlay() {
            private var firstTimeUs = -1L
            private val curveType = itemJson.optString("curveType", "ease-in-out")
            private val kfEval = KeyframeEvaluator(itemJson.optJSONObject("keyframes"), curveType)
            private val baseOpacity = (itemJson.optDouble("opacity", 100.0) / 100.0).toFloat()

            override fun getBitmap(pTimeUs: Long): Bitmap = frameBmp

            override fun getOverlaySettings(pTimeUs: Long): OverlaySettings {
                if (firstTimeUs == -1L) firstTimeUs = pTimeUs
                val localTimeSec = (pTimeUs - firstTimeUs) / 1000000f
                val globalTimeUs = videoStartUs + (pTimeUs - firstTimeUs)
                val isVisible = globalTimeUs in startUs..endUs

                val dynOpacity = kfEval.getFloat("opacity", baseOpacity * 100f, localTimeSec) / 100f
                val finalAlpha = if (isVisible) dynOpacity else 0f

                return OverlaySettings.Builder().setAlphaScale(finalAlpha).build()
            }
        }
    }
}

data class TextElement(
    val startUs: Long, val endUs: Long, val textStr: String,
    val paint: TextPaint, val activePaint: TextPaint, val isCaption: Boolean,
    val styleType: String, val itemJson: JSONObject,
    val posX: Float, val posY: Float, val scaleF: Float, val rotationF: Float
)

class GlobalTextOverlay(
    val textElements: List<TextElement>,
    val videoWidth: Int, val videoHeight: Int
) : ProcessedOverlay {
    override val type = "global_text"
    override val startUs = 0L
    override val endUs = Long.MAX_VALUE

    override fun createMedia3Overlay(videoStartUs: Long): TextureOverlay {
        return object : BitmapOverlay() {
            private val frameBmps = Array(2) { Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888) }
            private val canvases = Array(2) { Canvas(frameBmps[it]) }
            private var bmpIndex = 0
            private var lastHash = -1
            private var firstTimeUs = -1L

            override fun getBitmap(pTimeUs: Long): Bitmap {
                if (firstTimeUs == -1L) firstTimeUs = pTimeUs
                val localTimeUs = pTimeUs - firstTimeUs
                val globalTimeUs = videoStartUs + localTimeUs

                val activeTexts = textElements.filter { globalTimeUs in it.startUs..it.endUs }
                if (activeTexts.isEmpty()) {
                    if (lastHash != 0) {
                        bmpIndex = (bmpIndex + 1) % 2
                        frameBmps[bmpIndex].eraseColor(Color.TRANSPARENT)
                        lastHash = 0
                    }
                    return frameBmps[bmpIndex]
                }

                val hasDynamicAnimation = activeTexts.any { it.isCaption }
                val currentHash = if (hasDynamicAnimation) globalTimeUs.hashCode() else activeTexts.hashCode()
                if (currentHash == lastHash) return frameBmps[bmpIndex]
                lastHash = currentHash

                bmpIndex = (bmpIndex + 1) % 2
                val frameBmp = frameBmps[bmpIndex]
                val canvas = canvases[bmpIndex]
                frameBmp.eraseColor(Color.TRANSPARENT)

                for (item in activeTexts) {
                    val elementLocalTimeSec = (globalTimeUs - item.startUs) / 1000000f
                    val curveType = item.itemJson.optString("curveType", "ease-in-out")
                    val kfEval = KeyframeEvaluator(item.itemJson.optJSONObject("keyframes"), curveType)

                    val dynScale = kfEval.getFloat("scale", item.scaleF * 100f, elementLocalTimeSec) / 100f
                    val dynOp = kfEval.getFloat("opacity", item.itemJson.optDouble("opacity", 100.0).toFloat(), elementLocalTimeSec) / 100f
                    val dynPosX = kfEval.getFloat("posX", item.posX, elementLocalTimeSec)
                    val dynPosY = kfEval.getFloat("posY", item.posY, elementLocalTimeSec)
                    val dynRot = kfEval.getFloat("rotation", item.rotationF, elementLocalTimeSec)

                    val progress = ((globalTimeUs - item.startUs).toFloat() / (item.endUs - item.startUs).toFloat()).coerceIn(0f, 1f)
                    val metrics = item.paint.fontMetricsInt
                    val textHeight = metrics.bottom - metrics.top
                    val centerX = (dynPosX / 100f) * videoWidth
                    val centerY = (dynPosY / 100f) * videoHeight
                    val textDrawY = centerY - textHeight / 2f - metrics.top

                    item.paint.alpha = (dynOp * 255f).toInt()
                    item.activePaint.alpha = (dynOp * 255f).toInt()

                    if (item.isCaption && item.styleType != "normal") {
                        if (item.styleType == "karaoke") {
                            val textWidth = item.paint.measureText(item.textStr)
                            val textDrawX = centerX - textWidth / 2f

                            canvas.save()
                            canvas.translate(centerX, centerY)
                            canvas.rotate(dynRot)
                            canvas.scale(dynScale, dynScale)
                            canvas.translate(-centerX, -centerY)
                            canvas.drawText(item.textStr, textDrawX, textDrawY, item.paint)

                            canvas.save()
                            val isArabic = item.textStr.any { it in '\u0600'..'\u06FF' }
                            if (isArabic) {
                                val rightEdge = centerX + textWidth / 2f
                                val leftEdge = rightEdge - (textWidth * progress)
                                canvas.clipRect(leftEdge, 0f, videoWidth.toFloat(), videoHeight.toFloat())
                            } else {
                                val leftEdge = centerX - textWidth / 2f
                                val rightEdge = leftEdge + (textWidth * progress)
                                canvas.clipRect(0f, 0f, rightEdge, videoHeight.toFloat())
                            }
                            canvas.drawText(item.textStr, textDrawX, textDrawY, item.activePaint)
                            canvas.restore(); canvas.restore()
                        } else {
                            val spaceWidth = item.paint.measureText(" ") * 0.65f
                            val words = item.textStr.split(" ")
                            val totalWidth = words.sumOf { item.paint.measureText(it).toDouble() } + (words.size - 1) * spaceWidth.toDouble()
                            val startX = centerX - (totalWidth.toFloat() / 2f)

                            val isArabic = item.textStr.any { it in '\u0600'..'\u06FF' }
                            var currentX = if (isArabic) startX + totalWidth.toFloat() else startX

                            val activeWordIndex = (progress * words.size).toInt().coerceIn(0, words.size - 1)
                            val wordProgress = (progress * words.size) - activeWordIndex

                            for (i in words.indices) {
                                val word = words[i]
                                val wWidthOnly = item.paint.measureText(word)
                                val actualDrawX = if (isArabic) currentX - wWidthOnly else currentX

                                val isActive = (i == activeWordIndex)
                                val isPast = (i < activeWordIndex)

                                var wordScale = 1f
                                var wordAlpha = if (isActive || isPast) dynOp else 0f
                                var wordY = textDrawY

                                val paintToUse = if (isActive) item.activePaint else item.paint
                                val currentPaint = TextPaint(paintToUse)

                                when (item.styleType) {
                                    "popup" -> {
                                        if (isActive && wordProgress < 0.4f) {
                                            wordScale = Math.sin((wordProgress / 0.4) * Math.PI).toFloat() * 0.3f + 1.0f
                                        }
                                    }
                                    "fade" -> {
                                        wordAlpha = if (isPast) dynOp else if (isActive) dynOp * wordProgress else 0f
                                    }
                                    "slide_up" -> {
                                        wordAlpha = if (isPast) dynOp else if (isActive) (dynOp * wordProgress * 1.5f).coerceAtMost(1f) else 0f
                                        if (isActive) {
                                            wordY += (1f - wordProgress) * 40f
                                        }
                                    }
                                    "highlight" -> {
                                        wordAlpha = dynOp
                                        if (isActive) {
                                            val bgPaint = Paint().apply { color = item.activePaint.color; alpha = (dynOp * 255).toInt() }
                                            canvas.drawRect(actualDrawX - 10f, textDrawY + metrics.ascent - 10f, actualDrawX + wWidthOnly + 10f, textDrawY + metrics.descent + 10f, bgPaint)
                                            currentPaint.color = Color.WHITE
                                        }
                                    }
                                }

                                currentPaint.alpha = (wordAlpha * 255).toInt().coerceIn(0, 255)

                                canvas.save()
                                val wCenterX = actualDrawX + wWidthOnly / 2f
                                val wCenterY = wordY - metrics.ascent / 2f
                                canvas.translate(wCenterX, wCenterY)
                                val finalScale = dynScale * wordScale
                                canvas.rotate(dynRot)
                                canvas.scale(finalScale, finalScale)
                                canvas.translate(-wCenterX, -wCenterY)

                                if (wordAlpha > 0.01f) {
                                    canvas.drawText(word, actualDrawX, wordY, currentPaint)
                                }
                                canvas.restore()

                                if (isArabic) {
                                    currentX -= (wWidthOnly + spaceWidth)
                                } else {
                                    currentX += (wWidthOnly + spaceWidth)
                                }
                            }
                        }
                    } else {
                        val textWidth = item.paint.measureText(item.textStr)
                        val textDrawX = centerX - textWidth / 2f
                        canvas.save()
                        canvas.translate(centerX, centerY)
                        canvas.rotate(dynRot)
                        canvas.scale(dynScale, dynScale)
                        canvas.translate(-centerX, -centerY)
                        canvas.drawText(item.textStr, textDrawX, textDrawY, item.paint)
                        canvas.restore()
                    }
                }
                return frameBmp
            }
            override fun getOverlaySettings(pTimeUs: Long): OverlaySettings = OverlaySettings.Builder().setAlphaScale(1f).build()
        }
    }
}

class LomioNativeEngine(private val context: Context) {

    private fun applyBrightness(cm: ColorMatrix, amt: Float) { if (amt == 1f) return; cm.postConcat(ColorMatrix(floatArrayOf(amt,0f,0f,0f,0f, 0f,amt,0f,0f,0f, 0f,0f,amt,0f,0f, 0f,0f,0f,1f,0f))) }
    private fun applyContrast(cm: ColorMatrix, amt: Float) { if (amt == 1f) return; val o = (1f - amt) * 255f / 2f; cm.postConcat(ColorMatrix(floatArrayOf(amt,0f,0f,0f,o, 0f,amt,0f,0f,o, 0f,0f,amt,0f,o, 0f,0f,0f,1f,0f))) }
    private fun applySaturation(cm: ColorMatrix, amt: Float) { if (amt == 1f) return; val inv = 1f - amt; val rW = 0.213f; val gW = 0.715f; val bW = 0.072f; cm.postConcat(ColorMatrix(floatArrayOf(inv*rW+amt,inv*gW,inv*bW,0f,0f, inv*rW,inv*gW+amt,inv*bW,0f,0f, inv*rW,inv*gW,inv*bW+amt,0f,0f, 0f,0f,0f,1f,0f))) }
    private fun applySepia(cm: ColorMatrix, amt: Float) { if (amt == 0f) return; val inv = 1f - amt; cm.postConcat(ColorMatrix(floatArrayOf(inv+amt*0.393f,amt*0.769f,amt*0.189f,0f,0f, amt*0.349f,inv+amt*0.686f,amt*0.168f,0f,0f, amt*0.272f,amt*0.534f,inv+amt*0.131f,0f,0f, 0f,0f,0f,1f,0f))) }
    private fun applyGrayscale(cm: ColorMatrix, amt: Float) { if (amt == 0f) return; val inv = 1f - amt; val rW = 0.2126f; val gW = 0.7152f; val bW = 0.0722f; cm.postConcat(ColorMatrix(floatArrayOf(inv+amt*rW,amt*gW,amt*bW,0f,0f, amt*rW,inv+amt*gW,amt*bW,0f,0f, amt*rW,amt*gW,inv+amt*bW,0f,0f, 0f,0f,0f,1f,0f))) }
    private fun applyHueRotate(cm: ColorMatrix, deg: Float) {
        if (deg == 0f) return
        val rad = Math.toRadians(deg.toDouble()); val cos = Math.cos(rad).toFloat(); val sin = Math.sin(rad).toFloat()
        cm.postConcat(ColorMatrix(floatArrayOf(
            0.213f+cos*0.787f-sin*0.213f, 0.715f-cos*0.715f-sin*0.715f, 0.072f-cos*0.072f+sin*0.928f, 0f, 0f,
            0.213f-cos*0.213f+sin*0.143f, 0.715f+cos*0.285f+sin*0.140f, 0.072f-cos*0.072f-sin*0.283f, 0f, 0f,
            0.213f-cos*0.213f-sin*0.787f, 0.715f-cos*0.715f+sin*0.715f, 0.072f+cos*0.928f+sin*0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    private fun buildW3cColorMatrix(filter: String, b: Float, c: Float, s: Float, h: Float, t: Float): ColorMatrix {
        val cm = ColorMatrix()
        var baseGray = 0f; var baseSepia = 0f; var baseB = b; var baseC = c; var baseS = s; var baseH = h
        when (filter) {
            "vivid" -> { baseS = s * 1.35f; baseC = c * 1.1f }
            "noir" -> { baseGray = 1f; baseC = c * 1.2f }
            "sepia" -> { baseSepia = Math.max(t/200f, 1f); baseC = c * 1.1f }
            "warm" -> { baseSepia = Math.max(t/200f, 0.35f); baseS = s * 1.2f; baseC = c * 1.05f; baseH = h - 10f }
            "cool" -> { baseSepia = Math.max(t/200f, 0.25f); baseS = s * 1.1f; baseC = c * 1.05f; baseH = h + 175f }
            "dramatic" -> { baseGray = 0.3f; baseC = c * 1.25f; baseB = b * 0.9f }
            "cyberpunk" -> { baseS = s * 1.45f; baseC = c * 1.15f; baseH = h - 15f }
            "vintage" -> { baseSepia = Math.max(t/200f, 0.5f); baseC = c * 0.95f; baseB = b * 1.05f; baseS = s * 0.85f }
            "cinema" -> { baseC = c * 1.15f; baseS = s * 1.1f; baseSepia = Math.max(t/200f, 0.15f); baseH = h - 5f }
            else -> { baseSepia = if (t > 100f) (t - 100f) / 200f else 0f }
        }
        applyGrayscale(cm, baseGray)
        applySepia(cm, baseSepia)
        applyBrightness(cm, baseB / 100f)
        applyContrast(cm, baseC / 100f)
        applySaturation(cm, baseS / 100f)
        applyHueRotate(cm, baseH)
        return cm
    }

    private fun loadAndScaleImageSafely(context: Context, path: String, maxDim: Int): Bitmap? {
        return try {
            val decodedPath = java.net.URLDecoder.decode(path, "UTF-8").replace("file://", "")
            val isContentUri = decodedPath.startsWith("content://")
            val file = File(decodedPath)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }

            if (!isContentUri && file.exists()) { BitmapFactory.decodeFile(file.absolutePath, options) }
            else {
                val uri = if (isContentUri) Uri.parse(decodedPath) else Uri.fromFile(file)
                context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, options) }
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) { sampleSize *= 2 }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var sampledBmp: Bitmap? = null
            if (!isContentUri && file.exists()) { sampledBmp = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) }
            if (sampledBmp == null) {
                val uri = if (isContentUri) Uri.parse(decodedPath) else Uri.fromFile(file)
                sampledBmp = context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, decodeOptions) }
            }
            sampledBmp
        } catch (e: Exception) { null }
    }

    private fun getCustomTypeface(context: Context, fontName: String, fontUrl: String): Typeface {
        if (fontUrl.isEmpty() || fontUrl == "null") return Typeface.create("sans-serif", Typeface.BOLD)
        return try {
            val cacheDir = File(context.cacheDir, "lomio_fonts")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val fontFile = File(cacheDir, "${fontName.replace(" ", "_")}.ttf")
            if (!fontFile.exists()) {
                val url = java.net.URL(fontUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()
                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val input = connection.inputStream
                    val output = java.io.FileOutputStream(fontFile)
                    input.copyTo(output)
                    output.close()
                    input.close()
                }
            }
            if (fontFile.exists()) Typeface.createFromFile(fontFile) else Typeface.create("sans-serif", Typeface.BOLD)
        } catch (e: Exception) {
            Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    fun startRendering(projectDataJson: String, onComplete: (String) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                Log.d("LomioEngine", " بدء التصدير مع محرك الدمج الشامل...")

                val projectData = JSONObject(projectDataJson)
                val elements = projectData.getJSONArray("elements")

                val sortedElements = mutableListOf<JSONObject>()
                val effectElements = mutableListOf<JSONObject>()
                for (k in 0 until elements.length()) {
                    val item = elements.getJSONObject(k)
                    if (item.getString("type") == "effect") effectElements.add(item)
                    else sortedElements.add(item)
                }
                sortedElements.sortBy { it.optInt("track", 1) }

                val allSequences = mutableListOf<EditedMediaItemSequence>()

                var videoWidth = 1080
                var videoHeight = 1920

                for (item in sortedElements) {
                    if (item.getString("type") == "video") {
                        val cleanPath = java.net.URLDecoder.decode(item.getString("exportPath"), "UTF-8").replace("file://", "")
                        val isContentUri = cleanPath.startsWith("content://")
                        if (isContentUri || File(cleanPath).exists()) {
                            val retriever = MediaMetadataRetriever()
                            try {
                                if (isContentUri) {
                                    retriever.setDataSource(context, Uri.parse(cleanPath))
                                } else {
                                    retriever.setDataSource(cleanPath)
                                }
                                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080
                                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
                                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                                if (rotation == 90 || rotation == 270) {
                                    videoWidth = h; videoHeight = w
                                } else {
                                    videoWidth = w; videoHeight = h
                                }
                                videoWidth -= (videoWidth % 2)
                                videoHeight -= (videoHeight % 2)
                            } catch (e: Exception) {} finally { retriever.release() }
                            break
                        }
                    }
                }

                val processedOverlays = mutableListOf<ProcessedOverlay>()
                val textElements = mutableListOf<TextElement>()

                for (item in sortedElements) {
                    val type = item.getString("type")
                    if (type == "image" || type == "text") {
                        val startUs = (item.optDouble("start", 0.0) * 1000000).toLong()
                        val durUs = (item.optDouble("dur", 0.0) * 1000000).toLong()
                        val endUs = startUs + durUs
                        val scaleUI = item.optDouble("scale", 100.0).toFloat()
                        val posX = item.optDouble("posX", 50.0).toFloat()
                        val posY = item.optDouble("posY", 50.0).toFloat()
                        val rotationF = item.optDouble("rotation", 0.0).toFloat()
                        val scaleF = scaleUI / 100f
                        val opacity = (item.optDouble("opacity", 100.0).toFloat() / 100f).coerceIn(0f, 1f)

                        if (type == "image") {
                            val path = item.getString("exportPath")
                            val rawBmp = loadAndScaleImageSafely(context, path, Math.max(videoWidth, videoHeight))
                            if (rawBmp != null) {
                                val frameBmp = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(frameBmp)
                                var baseW = rawBmp.width.toFloat();
                                var baseH = rawBmp.height.toFloat()
                                if (baseW > videoWidth || baseH > videoHeight) {
                                    val ratio = Math.min(videoWidth / baseW, videoHeight / baseH)
                                    baseW *= ratio; baseH *= ratio
                                }
                                val finalW = baseW * scaleF;
                                val finalH = baseH * scaleF
                                val centerX = (posX / 100f) * videoWidth;
                                val centerY = (posY / 100f) * videoHeight
                                val left = centerX - (finalW / 2f);
                                val top = centerY - (finalH / 2f)
                                val destRect = android.graphics.RectF(left, top, left + finalW, top + finalH)

                                val filter = item.optString("filter", "none")
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    isFilterBitmap = true
                                    colorFilter = android.graphics.ColorMatrixColorFilter(buildW3cColorMatrix(filter, 100f, 100f, 100f, 0f, 100f))
                                }

                                canvas.save()
                                canvas.rotate(rotationF, centerX, centerY)
                                canvas.drawBitmap(rawBmp, null, destRect, paint)
                                canvas.restore()

                                rawBmp.recycle()
                                processedOverlays.add(ImageOverlay("image", startUs, endUs, frameBmp, item, videoWidth, videoHeight))
                            }
                        } else if (type == "text") {
                            val textStr = item.getString("text")
                            val colorHex = item.optString("color", "#FFFFFF")
                            val activeColorHex = item.optString("activeColor", "#ea4335")
                            val isCaption = item.optBoolean("isCaption", false)
                            val styleType = item.optString("captionStyle", if (isCaption) "karaoke" else "normal")

                            val fontSize = item.optInt("fontSize", 30)
                            val fontFamily = item.optString("fontFamily", "sans-serif")
                            val fontUrl = item.optString("fontUrl", "")

                            val scaledFontSize = (fontSize * 3f)
                            val customTypeface = getCustomTypeface(context, fontFamily, fontUrl)

                            val basePaint = TextPaint().apply {
                                color = try { Color.parseColor(colorHex) } catch (e: Exception) { Color.WHITE }
                                textSize = scaledFontSize
                                isAntiAlias = true
                                typeface = customTypeface
                                setShadowLayer(6f, 2f, 2f, Color.parseColor("#80000000"))
                            }

                            val activePaint = TextPaint().apply {
                                color = try { Color.parseColor(activeColorHex) } catch (e: Exception) { Color.RED }
                                textSize = scaledFontSize
                                isAntiAlias = true
                                typeface = customTypeface
                                setShadowLayer(6f, 2f, 2f, Color.parseColor("#80000000"))
                            }

                            textElements.add(TextElement(startUs, endUs, textStr, basePaint, activePaint, isCaption, styleType, item, posX, posY, scaleF, rotationF))
                        }
                    }
                }

                if (textElements.isNotEmpty()) {
                    processedOverlays.add(GlobalTextOverlay(textElements, videoWidth, videoHeight))
                }

                val trackSequences = mutableMapOf<Int, MutableList<EditedMediaItem>>()

                for (item in sortedElements) {
                    val type = item.getString("type")
                    if (type == "image" || type == "text") continue

                    val startUs = (item.optDouble("start", 0.0) * 1000000).toLong()
                    val durUs = (item.optDouble("dur", 0.0) * 1000000).toLong()

                    when (type) {
                        "video" -> {
                            val trackId = item.optInt("track", 1)
                            val cleanPath = java.net.URLDecoder.decode(item.getString("exportPath"), "UTF-8").replace("file://", "")
                            val isContentUri = cleanPath.startsWith("content://")
                            if (!isContentUri && !File(cleanPath).exists()) continue

                            val speed = item.optDouble("speed", 1.0).toFloat()
                            val sourceOffsetMs = (item.optDouble("sourceOffset", 0.0) * 1000).toLong()
                            val durationMs = (item.optDouble("dur", 0.0) * 1000).toLong()

                            val originalDurationMs = (durationMs * speed).toLong()
                            val videoStartUs = startUs
                            val videoEndUs = startUs + (durationMs * 1000L)

                            val curveType = item.optString("curveType", "ease-in-out")
                            val kfEval = KeyframeEvaluator(item.optJSONObject("keyframes"), curveType)

                            val uri = if (isContentUri) Uri.parse(cleanPath) else Uri.fromFile(File(cleanPath))
                            val mediaItem = MediaItem.Builder()
                                .setUri(uri)
                                .setClippingConfiguration(
                                    MediaItem.ClippingConfiguration.Builder()
                                        .setStartPositionMs(sourceOffsetMs)
                                        .setEndPositionMs(sourceOffsetMs + originalDurationMs)
                                        .build()
                                )
                                .build()

                            val finalVideoAudioProcessor = LomioAudioProcessor(
                                kfEval = kfEval,
                                baseVolume = item.optDouble("volume", 100.0).toFloat(),
                                fadeInDurationUs = (item.optDouble("fadeIn", 0.0) * 1000000).toLong(),
                                fadeOutDurationUs = (item.optDouble("fadeOut", 0.0) * 1000000).toLong(),
                                totalDurationUs = durUs
                            )

                            val audioProcessorsList = mutableListOf<AudioProcessor>(finalVideoAudioProcessor)
                            if (speed != 1.0f) {
                                val sonicProcessor = androidx.media3.common.audio.SonicAudioProcessor()
                                sonicProcessor.setSpeed(speed)
                                sonicProcessor.setPitch(1f)
                                audioProcessorsList.add(sonicProcessor)
                            }

                            val transInObj = item.optJSONObject("transitionIn")
                            val transInType = transInObj?.optString("type", "none") ?: "none"
                            val transInDurUs = (transInObj?.optDouble("dur", 0.0) ?: 0.0).times(1000000).toLong()

                            val transOutObj = item.optJSONObject("transitionOut")
                            val transOutType = transOutObj?.optString("type", "none") ?: "none"
                            val transOutDurUs = (transOutObj?.optDouble("dur", 0.0) ?: 0.0).times(1000000).toLong()

                            val videoEffectsBuilder = ImmutableList.builder<Effect>()
                            videoEffectsBuilder.add(
                                Presentation.createForAspectRatio(
                                    videoWidth.toFloat() / videoHeight.toFloat(),
                                    Presentation.LAYOUT_SCALE_TO_FIT
                                )
                            )

                            var firstTimeUs = -1L
                            videoEffectsBuilder.add(MatrixTransformation { presentationTimeUs ->
                                if (firstTimeUs == -1L) firstTimeUs = presentationTimeUs
                                val localTimeUs = presentationTimeUs - firstTimeUs
                                val localTimeSec = localTimeUs / 1000000f

                                val dynScale = kfEval.getFloat("scale", item.optDouble("scale", 100.0).toFloat(), localTimeSec) / 100f
                                val dynPosX = kfEval.getFloat("posX", item.optDouble("posX", 50.0).toFloat(), localTimeSec)
                                val dynPosY = kfEval.getFloat("posY", item.optDouble("posY", 50.0).toFloat(), localTimeSec)
                                val dynRot = kfEval.getFloat("rotation", item.optDouble("rotation", 0.0).toFloat(), localTimeSec)

                                val matrix = Matrix()
                                var currentScaleX = dynScale
                                var currentScaleY = dynScale
                                var currentOffsetX = (dynPosX - 50f) / 50f
                                var currentOffsetY = (50f - dynPosY) / 50f
                                var currentRotation = dynRot

                                if (transInType != "none" && transInDurUs > 0 && localTimeUs < transInDurUs) {
                                    val progress = Math.pow((localTimeUs.toFloat() / transInDurUs.toFloat()).coerceIn(0f, 1f).toDouble(), 2.0).toFloat()
                                    when (transInType) {
                                        "zoom_in" -> { val safeScale = progress.coerceAtLeast(0.001f); currentScaleX *= safeScale; currentScaleY *= safeScale }
                                        "pop_up" -> { val elasticScale = if (progress < 0.7f) (progress / 0.7f) * 1.2f else 1.2f - (((progress - 0.7f) / 0.3f) * 0.2f); val safeScale = elasticScale.coerceAtLeast(0.001f); currentScaleX *= safeScale; currentScaleY *= safeScale }
                                        "slide_right" -> { currentOffsetX -= 2f * (1f - progress) }
                                        "slide_left" -> { currentOffsetX += 2f * (1f - progress) }
                                        "slide_up" -> { currentOffsetY -= 2f * (1f - progress) }
                                        "slide_down" -> { currentOffsetY += 2f * (1f - progress) }
                                        "spin" -> { currentRotation += 360f * (1f - progress); val safeScale = progress.coerceAtLeast(0.001f); currentScaleX *= safeScale; currentScaleY *= safeScale }
                                    }
                                }

                                if (transOutType != "none" && transOutDurUs > 0 && localTimeUs > durUs - transOutDurUs) {
                                    val rawProgress = ((durUs - localTimeUs).toFloat() / transOutDurUs.toFloat()).coerceIn(0f, 1f)
                                    val progress = 1f - (1f - rawProgress) * (1f - rawProgress)
                                    when (transOutType) {
                                        "zoom_out" -> { val safeScale = progress.coerceAtLeast(0.001f); currentScaleX *= safeScale; currentScaleY *= safeScale }
                                        "pop_down" -> { val safeScale = progress.coerceAtLeast(0.001f); currentScaleX *= safeScale; currentScaleY *= safeScale; currentRotation += (1f - progress) * 45f }
                                        "slide_left" -> { currentOffsetX -= 2f * (1f - progress) }
                                        "slide_right" -> { currentOffsetX += 2f * (1f - progress) }
                                        "slide_up" -> { currentOffsetY += 2f * (1f - progress) }
                                        "slide_down" -> { currentOffsetY -= 2f * (1f - progress) }
                                        "spin" -> { currentRotation -= 360f * (1f - progress); val safeScale = progress.coerceAtLeast(0.001f); currentScaleX *= safeScale; currentScaleY *= safeScale }
                                    }
                                }

                                matrix.postScale(currentScaleX, currentScaleY)
                                matrix.postRotate(currentRotation)
                                matrix.postTranslate(currentOffsetX, currentOffsetY)
                                matrix
                            })

                            var firstTimeUsColor = -1L
                            val colorMatrixEffect = object : RgbMatrix {
                                override fun getMatrix(presentationTimeUs: Long, useNdc: Boolean): FloatArray {
                                    if (firstTimeUsColor == -1L) firstTimeUsColor = presentationTimeUs
                                    val localTimeUs = presentationTimeUs - firstTimeUsColor
                                    val localTimeSec = localTimeUs / 1000000f

                                    val globalTimeUs = videoStartUs + localTimeUs
                                    val globalTimeSec = globalTimeUs / 1000000f

                                    val dynB = kfEval.getFloat("brightness", item.optDouble("brightness", 100.0).toFloat(), localTimeSec)
                                    val dynC = kfEval.getFloat("contrast", item.optDouble("contrast", 100.0).toFloat(), localTimeSec)
                                    val dynS = kfEval.getFloat("saturate", item.optDouble("saturate", 100.0).toFloat(), localTimeSec)
                                    val dynT = kfEval.getFloat("temp", item.optDouble("temp", 100.0).toFloat(), localTimeSec)
                                    val dynH = kfEval.getFloat("hue", item.optDouble("hue", 0.0).toFloat(), localTimeSec)
                                    val dynF = kfEval.getString("filter", item.optString("filter", "none"), localTimeSec)
                                    val dynOpacity = kfEval.getFloat("opacity", item.optDouble("opacity", 100.0).toFloat(), localTimeSec) / 100f

                                    val baseCm = buildW3cColorMatrix(dynF, dynB, dynC, dynS, dynH, dynT)

                                    for (effectItem in effectElements) {
                                        val startSec = effectItem.optDouble("start", 0.0).toFloat()
                                        val effectDurSec = effectItem.optDouble("dur", 0.0).toFloat()
                                        val endSec = startSec + effectDurSec

                                        if (globalTimeSec in startSec..endSec) {
                                            val effectLocalTimeSec = globalTimeSec - startSec
                                            val effectCurve = effectItem.optString("curveType", "ease-in-out")
                                            val effectKfEval = KeyframeEvaluator(effectItem.optJSONObject("keyframes"), effectCurve)

                                            val effectDynOp = effectKfEval.getFloat("opacity", effectItem.optDouble("opacity", 100.0).toFloat(), effectLocalTimeSec)
                                            val effectDynF = effectKfEval.getString("filter", effectItem.optString("filter", "none"), effectLocalTimeSec)

                                            val effectCm = buildW3cColorMatrix(effectDynF, 100f, 100f, 100f, 0f, 100f)
                                            val strength = effectDynOp / 100f

                                            val blendedArray = FloatArray(20)
                                            val idArray = ColorMatrix().array
                                            for (i in 0 until 20) {
                                                blendedArray[i] = idArray[i] + (effectCm.array[i] - idArray[i]) * strength
                                            }

                                            baseCm.postConcat(ColorMatrix(blendedArray))
                                        }
                                    }

                                    var colorMult = dynOpacity
                                    var colorOff = 0f

                                    if (transInType != "none" && transInDurUs > 0 && localTimeUs < transInDurUs) {
                                        val progress = (localTimeUs.toFloat() / transInDurUs.toFloat()).coerceIn(0f, 1f)
                                        if (transInType == "fade") colorMult = progress * dynOpacity
                                        else if (transInType == "flash") colorOff = 1f - progress
                                    }
                                    if (transOutType != "none" && transOutDurUs > 0 && localTimeUs > durUs - transOutDurUs) {
                                        val progress = ((localTimeUs - (durUs - transOutDurUs)).toFloat() / transOutDurUs.toFloat()).coerceIn(0f, 1f)
                                        if (transOutType == "fade") colorMult = (1f - progress) * dynOpacity
                                        else if (transOutType == "flash") colorOff = progress
                                    }

                                    val cmArray = baseCm.array
                                    val glMatrix = FloatArray(16)
                                    glMatrix[0] = cmArray[0] * colorMult; glMatrix[4] = cmArray[1] * colorMult; glMatrix[8] = cmArray[2] * colorMult; glMatrix[12] = (cmArray[4] / 255f) * colorMult + colorOff
                                    glMatrix[1] = cmArray[5] * colorMult; glMatrix[5] = cmArray[6] * colorMult; glMatrix[9] = cmArray[7] * colorMult; glMatrix[13] = (cmArray[9] / 255f) * colorMult + colorOff
                                    glMatrix[2] = cmArray[10] * colorMult; glMatrix[6] = cmArray[11] * colorMult; glMatrix[10] = cmArray[12] * colorMult; glMatrix[14] = (cmArray[14] / 255f) * colorMult + colorOff
                                    glMatrix[3] = cmArray[15]; glMatrix[7] = cmArray[16]; glMatrix[11] = cmArray[17]; glMatrix[15] = cmArray[18] * colorMult

                                    return glMatrix
                                }
                            }
                            videoEffectsBuilder.add(colorMatrixEffect)

                            val activeOverlays = mutableListOf<TextureOverlay>()
                            for (po in processedOverlays) {
                                if (po.type == "global_text" || (po.startUs < videoEndUs && po.endUs > videoStartUs)) {
                                    activeOverlays.add(po.createMedia3Overlay(videoStartUs))
                                }
                            }
                            if (activeOverlays.isNotEmpty()) {
                                videoEffectsBuilder.add(OverlayEffect(ImmutableList.copyOf(activeOverlays)))
                            }

                            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                                .setEffects(Effects(audioProcessorsList, videoEffectsBuilder.build()))
                                .build()

                            trackSequences.getOrPut(trackId) { mutableListOf() }.add(editedMediaItem)
                        }

                        "audio" -> {
                            val cleanPath = java.net.URLDecoder.decode(item.getString("exportPath"), "UTF-8").replace("file://", "")
                            val isContentUri = cleanPath.startsWith("content://")
                            if (isContentUri || File(cleanPath).exists()) {
                                val uri = if (isContentUri) Uri.parse(cleanPath) else Uri.fromFile(File(cleanPath))

                                val speed = item.optDouble("speed", 1.0).toFloat()
                                val originalDurationMs = ((durUs / 1000) * speed).toLong()

                                val curveType = item.optString("curveType", "ease-in-out")
                                val audioKfEval = KeyframeEvaluator(item.optJSONObject("keyframes"), curveType)

                                val finalAudioProcessor = LomioAudioProcessor(
                                    kfEval = audioKfEval,
                                    baseVolume = item.optDouble("volume", 100.0).toFloat(),
                                    fadeInDurationUs = (item.optDouble("fadeIn", 0.0) * 1000000).toLong(),
                                    fadeOutDurationUs = (item.optDouble("fadeOut", 0.0) * 1000000).toLong(),
                                    totalDurationUs = durUs
                                )

                                val audioProcessorsList = mutableListOf<AudioProcessor>(finalAudioProcessor)
                                if (speed != 1.0f) {
                                    val sonicProcessor = androidx.media3.common.audio.SonicAudioProcessor()
                                    sonicProcessor.setSpeed(speed)
                                    sonicProcessor.setPitch(1f)
                                    audioProcessorsList.add(sonicProcessor)
                                }

                                val audioItem = MediaItem.Builder()
                                    .setUri(uri)
                                    .setClippingConfiguration(
                                        MediaItem.ClippingConfiguration.Builder()
                                            .setEndPositionMs(originalDurationMs)
                                            .build()
                                    )
                                    .build()

                                val editedAudioItem = EditedMediaItem.Builder(audioItem)
                                    .setEffects(Effects(audioProcessorsList, listOf()))
                                    .setRemoveVideo(true)
                                    .build()

                                trackSequences.getOrPut(item.optInt("track", 3)) { mutableListOf() }.add(editedAudioItem)
                            }
                        }
                    }
                }

                for ((_, items) in trackSequences) {
                    allSequences.add(EditedMediaItemSequence(ImmutableList.copyOf(items)))
                }

                if (allSequences.isEmpty()) {
                    Handler(Looper.getMainLooper()).post { onError("لا يوجد مقاطع فيديو أو صوت لاستخراج الكلمات منها!") }
                    return@Thread
                }

                val globalVideoEffects = ImmutableList.of<Effect>(
                    Presentation.createForAspectRatio(videoWidth.toFloat() / videoHeight.toFloat(), Presentation.LAYOUT_SCALE_TO_FIT)
                )

                val composition = Composition.Builder(ImmutableList.copyOf(allSequences))
                    .setEffects(Effects(ImmutableList.of(), globalVideoEffects))
                    .build()

                val tempOutputFile = File(context.cacheDir, "final_lomio_pro_${System.currentTimeMillis()}.mp4")

                Handler(Looper.getMainLooper()).post {
                    val transformer = Transformer.Builder(context)
                        .setAudioMimeType("audio/mp4a-latm")
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(comp: Composition, result: ExportResult) {
                                Thread {
                                    saveVideoToGallery(tempOutputFile)
                                    Handler(Looper.getMainLooper()).post { onComplete(" تم التصدير بنجاح! التدرج والفلاتر أصبحت مدمجة بصلب الفيديو!") }
                                }.start()
                            }

                            override fun onError(comp: Composition, result: ExportResult, e: ExportException) {
                                Handler(Looper.getMainLooper()).post { onError("خطأ رندرة: ${e.message}") }
                            }
                        }).build()

                    transformer.start(composition, tempOutputFile.absolutePath)
                }

            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onError(e.message ?: "خطأ غير معروف") }
            }
        }.start()
    }

    private fun saveVideoToGallery(videoFile: File) {
        val filename = "Lomio_Pro_${System.currentTimeMillis()}.mp4"
        val videoDetails = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LomioEditor")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoDetails)
        uri?.let {
            resolver.openOutputStream(it).use { out -> FileInputStream(videoFile).use { input -> input.copyTo(out!!) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                videoDetails.clear()
                videoDetails.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(it, videoDetails, null, null)
            }
            videoFile.delete()
        }
    }

    private fun uploadAudioToApi(audioFile: File, apiUrl: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val connection = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 60000
        connection.readTimeout = 180000
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setRequestProperty("Accept", "application/json")

        try {
            val outputStream = java.io.DataOutputStream(connection.outputStream)
            outputStream.writeBytes("--$boundary\r\n")
            outputStream.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n")
            outputStream.writeBytes("Content-Type: audio/mp4\r\n\r\n")

            val inputStream = java.io.FileInputStream(audioFile)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) { outputStream.write(buffer, 0, bytesRead) }
            outputStream.writeBytes("\r\n")
            outputStream.writeBytes("--$boundary--\r\n")
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                onSuccess(response)
            } else {
                onError("فشل الاتصال بالخادم، كود: $responseCode")
            }
        } catch (e: Exception) { onError("خطأ في إرسال الملف: ${e.message}") }
    }

    fun generateAutoCaptions(projectDataJson: String, apiUrl: String, onComplete: (String) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                Log.d("LomioEngine", " بدء دمج صوت التايم لاين للكابشن...")
                val projectData = JSONObject(projectDataJson)
                val elements = projectData.getJSONArray("elements")

                val allSequences = mutableListOf<EditedMediaItemSequence>()
                val trackSequences = mutableMapOf<Int, MutableList<EditedMediaItem>>()

                for (k in 0 until elements.length()) {
                    val item = elements.getJSONObject(k)
                    val type = item.getString("type")
                    if (type != "video" && type != "audio") continue

                    val trackId = item.optInt("track", 1)
                    val cleanPath = java.net.URLDecoder.decode(item.getString("exportPath"), "UTF-8").replace("file://", "")
                    val isContentUri = cleanPath.startsWith("content://")
                    if (!isContentUri && !File(cleanPath).exists()) continue

                    val sourceOffsetMs = (item.optDouble("sourceOffset", 0.0) * 1000).toLong()
                    val durationMs = (item.optDouble("dur", 0.0) * 1000).toLong()

                    val uri = if (isContentUri) Uri.parse(cleanPath) else Uri.fromFile(File(cleanPath))
                    val mediaItem = MediaItem.Builder()
                        .setUri(uri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(sourceOffsetMs)
                                .setEndPositionMs(sourceOffsetMs + durationMs)
                                .build()
                        ).build()

                    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                        .setRemoveVideo(true)
                        .build()

                    trackSequences.getOrPut(trackId) { mutableListOf() }.add(editedMediaItem)
                }

                for ((_, items) in trackSequences) {
                    allSequences.add(EditedMediaItemSequence(ImmutableList.copyOf(items)))
                }

                if (allSequences.isEmpty()) {
                    Handler(Looper.getMainLooper()).post { onError("لا يوجد مقاطع فيديو أو صوت لاستخراج الكلمات منها!") }
                    return@Thread
                }

                val composition = Composition.Builder(ImmutableList.copyOf(allSequences)).build()
                val tempAudioFile = File(context.cacheDir, "lomio_timeline_audio_${System.currentTimeMillis()}.m4a")

                Handler(Looper.getMainLooper()).post {
                    val transformer = Transformer.Builder(context)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(comp: Composition, result: ExportResult) {
                                Thread {
                                    uploadAudioToApi(tempAudioFile, apiUrl, { responseJson ->
                                        tempAudioFile.delete()
                                        Handler(Looper.getMainLooper()).post { onComplete(responseJson) }
                                    }, { errorMsg ->
                                        tempAudioFile.delete()
                                        Handler(Looper.getMainLooper()).post { onError(errorMsg) }
                                    })
                                }.start()
                            }

                            override fun onError(comp: Composition, result: ExportResult, e: ExportException) {
                                tempAudioFile.delete()
                                Handler(Looper.getMainLooper()).post { onError("خطأ في تجميع الصوت: ${e.message}") }
                            }
                        }).build()

                    transformer.start(composition, tempAudioFile.absolutePath)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { onError(e.message ?: "خطأ غير معروف") }
            }
        }.start()
    }
}