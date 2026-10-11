package com.example.medvideoai

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MedicalVideoScreen() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedicalVideoScreen() {
    var topic by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("Both") }
    var duration by remember { mutableStateOf("1 minute") }
    var showScenes by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scenes = remember(topic) { ScenePlanner.createStoryboard(topic) }

    Scaffold(topBar = { TopAppBar(title = { Text("MedVideo AI — Free Mode") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Text → Medical storyboard → Animation", style = MaterialTheme.typography.headlineSmall)
            Text("தமிழ் அல்லது English-ல் மருத்துவ விளக்கத்தை எழுதுங்கள். App அதைச் சிறு காட்சிகளாகப் பிரித்து, பொருத்தமான அடிப்படை medical visual-ஐத் தேர்வு செய்யும்.")
            OutlinedTextField(
                value = topic, onValueChange = { topic = it; showScenes = false; status = "" },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Medical text / மருத்துவ விளக்கம்") },
                placeholder = { Text("உதா: சிறுநீரகத்தில் சிறுநீர் உருவாகி சிறுநீர்ப்பைக்குச் செல்லும் முறை...") }
            )
            Text("Examples", style = MaterialTheme.typography.titleMedium)
            listOf("சிறுநீரகத்தில் சிறுநீர் உருவாகும் முறை", "How the heart pumps blood", "COPD and airway narrowing", "Meniere disease").forEach { example ->
                OutlinedButton(onClick = { topic = example; showScenes = false }, modifier = Modifier.fillMaxWidth()) { Text(example) }
            }
            Text("Language / மொழி")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tamil", "English", "Both").forEach { FilterChip(language == it, { language = it }, label = { Text(it) }) }
            }
            Text("Video duration")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("30 seconds", "1 minute", "3 minutes").forEach { FilterChip(duration == it, { duration = it }, label = { Text(it) }) }
            }
            Button(onClick = { showScenes = topic.isNotBlank(); status = if (topic.isBlank()) "முதலில் மருத்துவ Text எழுதுங்கள்." else "" },
                enabled = topic.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Analyze Text & Preview Scenes") }

            if (showScenes && scenes.isNotEmpty()) {
                Text("Storyboard (${scenes.size} scenes)", style = MaterialTheme.typography.titleLarge)
                Text("முதல் கட்டத்தில் scene selection offline rules மூலம் நடக்கிறது; இது முழுமையான AI medical understanding அல்ல.", style = MaterialTheme.typography.bodySmall)
                scenes.forEachIndexed { index, scene ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${scene.stepLabel} • ${scene.title}", style = MaterialTheme.typography.titleMedium)
                            MedicalSceneCanvas(scene, index)
                            Text(scene.caption)
                        }
                    }
                }
                Button(
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        exporting = true
                        status = "MP4 உருவாக்கப்படுகிறது..."
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { exportMedicalVideo(context, topic.trim(), language, duration) }
                                status = "MP4 சேமிக்கப்பட்டது: Movies/MedVideoAI"
                            } catch (e: Exception) {
                                status = "Export failed: ${e.message ?: "Unknown error"}"
                            } finally { exporting = false }
                        }
                    }
                ) { Text(if (exporting) "Exporting..." else "Export Storyboard MP4") }
            }
            if (status.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(status, Modifier.padding(14.dp)) }
            Text("Free mode: no paid API is called. This version uses offline rules and drawn educational visuals. Narration, imported/generated medical images, and AI integration are planned next. Verify medical facts with trusted references.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MedicalSceneCanvas(scene: MedicalScene, phase: Int) {
    val transition = rememberInfiniteTransition(label = "scene-$phase")
    val pulse by transition.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(950), RepeatMode.Reverse), label = "pulse")
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        when (scene.visualType) {
            MedicalVisualType.KIDNEY -> {
                drawOval(Color(0xFFB85C65), Offset(cx - 78f * pulse, cy - 45f), Size(65f * pulse, 90f * pulse))
                drawOval(Color(0xFFB85C65), Offset(cx + 13f, cy - 45f), Size(65f * pulse, 90f * pulse))
                drawLine(Color(0xFFFFB74D), Offset(cx - 12f, cy + 12f), Offset(cx + 26f, cy + 58f), 5f)
                drawCircle(Color(0xFFFFB74D), 6f, Offset(cx + 26f, cy + 58f))
            }
            MedicalVisualType.HEART -> {
                drawCircle(Color(0xFFE85D75), 42f * pulse, Offset(cx - 22f, cy - 8f))
                drawCircle(Color(0xFFE85D75), 42f * pulse, Offset(cx + 22f, cy - 8f))
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(cx - 60f, cy); cubicTo(cx - 45f, cy + 42f, cx - 12f, cy + 60f, cx, cy + 65f); cubicTo(cx + 16f, cy + 48f, cx + 50f, cy + 24f, cx + 60f, cy) }
                drawPath(p, Color(0xFFE85D75))
            }
            MedicalVisualType.LUNGS -> {
                drawLine(Color(0xFF6D4CAF), Offset(cx, cy - 55f), Offset(cx, cy + 15f), 8f)
                drawLine(Color(0xFF6D4CAF), Offset(cx, cy - 3f), Offset(cx - 35f, cy + 13f), 6f)
                drawLine(Color(0xFF6D4CAF), Offset(cx, cy - 3f), Offset(cx + 35f, cy + 13f), 6f)
                drawOval(Color(0xFF80CBC4), Offset(cx - 78f * pulse, cy - 32f), Size(70f * pulse, 95f * pulse))
                drawOval(Color(0xFF80CBC4), Offset(cx + 8f, cy - 32f), Size(70f * pulse, 95f * pulse))
            }
            MedicalVisualType.EAR -> {
                drawOval(Color(0xFFFFB28A), Offset(cx - 40f, cy - 58f), Size(80f, 116f), style = Stroke(10f))
                drawOval(Color(0xFFFFB28A), Offset(cx - 18f, cy - 30f), Size(42f, 65f), style = Stroke(6f))
                drawCircle(Color(0xFF6D4CAF), 12f * pulse, Offset(cx + 12f, cy + 12f))
            }
            MedicalVisualType.BRAIN -> {
                drawCircle(Color(0xFFE9A4B5), 43f * pulse, Offset(cx - 23f, cy))
                drawCircle(Color(0xFFE9A4B5), 43f * pulse, Offset(cx + 23f, cy))
            }
            MedicalVisualType.MICROBES -> repeat(8) { i ->
                val angle = i * Math.PI / 4.0 + phase * 0.1
                drawCircle(if (i % 2 == 0) Color(0xFFEF5350) else Color(0xFF66BB6A), 11f * pulse,
                    Offset(cx + cos(angle).toFloat() * 48f, cy + sin(angle).toFloat() * 35f))
            }
            MedicalVisualType.EYE -> {
                drawOval(Color(0xFF80CBC4), Offset(cx - 68f, cy - 35f), Size(136f, 70f), style = Stroke(6f))
                drawCircle(Color(0xFF3F51B5), 26f * pulse, Offset(cx, cy))
                drawCircle(Color.Black, 11f, Offset(cx, cy))
            }
            MedicalVisualType.GENERIC -> {
                drawCircle(Color(0xFF80CBC4), 42f * pulse, Offset(cx, cy), style = Stroke(7f))
                drawLine(Color(0xFF6D4CAF), Offset(cx - 62f, cy), Offset(cx + 62f, cy), 5f)
                drawLine(Color(0xFF6D4CAF), Offset(cx, cy - 60f), Offset(cx, cy + 60f), 5f)
            }
        }
    }
}

private fun exportMedicalVideo(context: Context, topic: String, language: String, durationChoice: String) {
    val seconds = when (durationChoice) { "30 seconds" -> 30; "3 minutes" -> 180; else -> 60 }
    val width = 320
    val height = 568
    val fps = 10
    val frameCount = seconds * fps
    val file = File(context.cacheDir, "medvideo_${System.currentTimeMillis()}.mp4")
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
        setInteger(MediaFormat.KEY_BIT_RATE, 650_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    }
    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    var muxer: MediaMuxer? = null
    var started = false
    try {
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var frame = 0
        var inputDone = false
        var outputDone = false
        var track = -1
        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = encoder.getInputBuffer(inputIndex) ?: error("Encoder input buffer unavailable")
                    input.clear()
                    if (frame < frameCount) {
                        val bitmap = drawMedicalFrame(width, height, topic, language, frame / fps, seconds)
                        val yuv = bitmapToI420(bitmap)
                        bitmap.recycle()
                        input.put(yuv)
                        encoder.queueInputBuffer(inputIndex, 0, yuv.size, frame * 1_000_000L / fps, 0)
                        frame++
                    } else {
                        encoder.queueInputBuffer(inputIndex, 0, 0, frameCount * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }
            val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                }
                outputIndex >= 0 -> {
                    val buffer = encoder.getOutputBuffer(outputIndex) ?: error("Encoder output buffer unavailable")
                    if (info.size > 0) {
                        check(started) { "Muxer has not started" }
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    } finally {
        runCatching { encoder.stop() }
        encoder.release()
        if (started) runCatching { muxer?.stop() }
        muxer?.release()
    }

    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "MedVideoAI_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MedVideoAI")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create output video")
    try {
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("Cannot open output video")
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    } finally { file.delete() }
}

private fun drawMedicalFrame(width: Int, height: Int, topic: String, language: String, elapsed: Int, duration: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(250, 247, 255))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.rgb(100, 70, 160)
    canvas.drawRoundRect(RectF(18f, 20f, width - 18f, 76f), 18f, 18f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 22f
    paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
    canvas.drawText("MedVideo AI", 30f, 55f, paint)

    val scenes = ScenePlanner.createStoryboard(topic).ifEmpty { listOf(MedicalScene("Medical", topic, MedicalVisualType.GENERIC, "Scene 1")) }
    val index = ((elapsed.toLong() * scenes.size) / duration.coerceAtLeast(1)).toInt().coerceIn(0, scenes.lastIndex)
    val scene = scenes[index]
    paint.color = android.graphics.Color.rgb(35, 35, 45)
    paint.textSize = 15f
    var line = ""
    var y = 105f
    scene.caption.split(Regex("\\s+")).forEach { word ->
        val candidate = if (line.isBlank()) word else "$line $word"
        if (paint.measureText(candidate) > width - 40f && line.isNotBlank()) {
            canvas.drawText(line, 20f, y, paint); y += 20f; line = word
        } else line = candidate
    }
    if (line.isNotBlank()) canvas.drawText(line, 20f, y, paint)

    val cx = width / 2f
    val cy = height * 0.49f
    val pulse = 1f + 0.07f * sin(elapsed * 1.4).toFloat()
    when (scene.visualType) {
        MedicalVisualType.KIDNEY -> {
            paint.color = android.graphics.Color.rgb(184, 92, 101)
            canvas.drawOval(RectF(cx - 78f * pulse, cy - 48f * pulse, cx - 15f, cy + 48f * pulse), paint)
            canvas.drawOval(RectF(cx + 15f, cy - 48f * pulse, cx + 78f * pulse, cy + 48f * pulse), paint)
            paint.color = android.graphics.Color.rgb(255, 183, 77); paint.strokeWidth = 6f
            canvas.drawLine(cx - 15f, cy + 14f, cx + 28f, cy + 78f, paint)
        }
        MedicalVisualType.HEART -> {
            paint.color = android.graphics.Color.rgb(220, 70, 90)
            canvas.drawCircle(cx - 22f, cy - 10f, 40f * pulse, paint)
            canvas.drawCircle(cx + 22f, cy - 10f, 40f * pulse, paint)
            val p = android.graphics.Path().apply { moveTo(cx - 58f, cy); cubicTo(cx - 42f, cy + 42f, cx - 10f, cy + 58f, cx, cy + 65f); cubicTo(cx + 16f, cy + 45f, cx + 48f, cy + 24f, cx + 58f, cy) }
            canvas.drawPath(p, paint)
        }
        MedicalVisualType.LUNGS -> {
            paint.color = android.graphics.Color.rgb(109, 76, 175); paint.strokeWidth = 8f
            canvas.drawLine(cx, cy - 65f, cx, cy + 15f, paint)
            canvas.drawLine(cx, cy - 5f, cx - 40f, cy + 15f, paint)
            canvas.drawLine(cx, cy - 5f, cx + 40f, cy + 15f, paint)
            paint.color = android.graphics.Color.rgb(128, 203, 196)
            canvas.drawOval(RectF(cx - 86f * pulse, cy - 52f, cx - 12f, cy + 78f * pulse), paint)
            canvas.drawOval(RectF(cx + 12f, cy - 52f, cx + 86f * pulse, cy + 78f * pulse), paint)
        }
        MedicalVisualType.EAR -> {
            paint.color = android.graphics.Color.rgb(255, 178, 138); paint.style = Paint.Style.STROKE; paint.strokeWidth = 12f
            canvas.drawOval(RectF(cx - 55f, cy - 70f, cx + 45f, cy + 70f), paint)
            canvas.drawOval(RectF(cx - 25f, cy - 35f, cx + 18f, cy + 40f), paint)
            paint.style = Paint.Style.FILL
        }
        MedicalVisualType.BRAIN -> {
            paint.color = android.graphics.Color.rgb(233, 164, 181)
            canvas.drawCircle(cx - 22f, cy, 43f * pulse, paint); canvas.drawCircle(cx + 22f, cy, 43f * pulse, paint)
        }
        MedicalVisualType.MICROBES -> repeat(8) { i ->
            val a = i * Math.PI / 4.0 + elapsed * 0.25
            paint.color = if (i % 2 == 0) android.graphics.Color.rgb(239, 83, 80) else android.graphics.Color.rgb(102, 187, 106)
            canvas.drawCircle(cx + cos(a).toFloat() * 45f, cy + sin(a).toFloat() * 40f, 12f * pulse, paint)
        }
        MedicalVisualType.EYE -> {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 7f; paint.color = android.graphics.Color.rgb(128, 203, 196)
            canvas.drawOval(RectF(cx - 70f, cy - 36f, cx + 70f, cy + 36f), paint)
            paint.style = Paint.Style.FILL; paint.color = android.graphics.Color.rgb(63, 81, 181); canvas.drawCircle(cx, cy, 25f * pulse, paint)
        }
        MedicalVisualType.GENERIC -> {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f; paint.color = android.graphics.Color.rgb(128, 203, 196)
            canvas.drawCircle(cx, cy, 45f * pulse, paint)
            paint.color = android.graphics.Color.rgb(109, 76, 175); canvas.drawLine(cx - 60f, cy, cx + 60f, cy, paint)
            paint.style = Paint.Style.FILL
        }
    }
    paint.color = android.graphics.Color.rgb(70, 60, 90); paint.textSize = 13f
    canvas.drawText(if (language == "Tamil") "தமிழ் மருத்துவக் காட்சி" else "Medical learning visual", 20f, height - 80f, paint)
    canvas.drawText("${index + 1}/${scenes.size} • ${scene.title.take(22)}", 20f, height - 56f, paint)
    paint.color = android.graphics.Color.rgb(109, 76, 175)
    canvas.drawRoundRect(RectF(20f, height - 36f, 20f + (width - 40f) * (elapsed.toFloat() / duration).coerceIn(0f, 1f), height - 25f), 6f, 6f, paint)
    return bitmap
}

private fun bitmapToI420(bitmap: Bitmap): ByteArray {
    val w = bitmap.width; val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val frameSize = w * h
    val out = ByteArray(frameSize * 3 / 2)
    var yi = 0; var ui = frameSize; var vi = frameSize + frameSize / 4
    for (row in 0 until h) for (col in 0 until w) {
        val c = pixels[row * w + col]
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        out[yi++] = ((((66*r + 129*g + 25*b + 128) shr 8) + 16).coerceIn(0,255)).toByte()
        if (row % 2 == 0 && col % 2 == 0) {
            out[ui++] = ((((-38*r - 74*g + 112*b + 128) shr 8) + 128).coerceIn(0,255)).toByte()
            out[vi++] = ((((112*r - 94*g - 18*b + 128) shr 8) + 128).coerceIn(0,255)).toByte()
        }
    }
    return out
}
