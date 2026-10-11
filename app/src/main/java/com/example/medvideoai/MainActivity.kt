package com.example.medvideoai

import android.os.Bundle
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
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MedicalVideoScreen()
                }
            }
        }
    }
}

@Composable
fun MedicalVideoScreen() {
    var topic by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("Both") }
    var duration by remember { mutableStateOf("1 minute") }
    var style by remember { mutableStateOf("Medical animation") }
    var showAnimation by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val topics = listOf(
        "COPD", "Asthma", "Meniere disease",
        "Pulmonary hypertension", "Anatomy", "Pathology",
        "ENT", "Ophthalmology", "Forensic medicine"
    )

    Scaffold(topBar = { TopAppBar(title = { Text("MedVideo AI — Free Mode") }) }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Free offline medical animation", style = MaterialTheme.typography.headlineSmall)
            Text("Runs on your phone. No Higgsfield API, payment, or internet connection needed for this preview.")

            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it; showAnimation = false },
                label = { Text("Medical topic") },
                placeholder = { Text("e.g. COPD or Meniere disease") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Text("Quick topics", style = MaterialTheme.typography.titleMedium)
            topics.chunked(3).forEach { group ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    group.forEach { item ->
                        OutlinedButton(
                            onClick = { topic = item; showAnimation = false },
                            modifier = Modifier.weight(1f)
                        ) { Text(item, style = MaterialTheme.typography.labelSmall) }
                    }
                    repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Text("Language", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tamil", "English", "Both").forEach { option ->
                    FilterChip(
                        selected = language == option,
                        onClick = { language = option },
                        label = { Text(option) }
                    )
                }
            }

            Text("Preferred lesson duration", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("30 seconds", "1 minute", "3 minutes").forEach { option ->
                    FilterChip(
                        selected = duration == option,
                        onClick = { duration = option },
                        label = { Text(option) }
                    )
                }
            }

            Text("Visual style", style = MaterialTheme.typography.titleMedium)
            listOf("Medical animation", "Presenter", "Slides with narration").forEach { option ->
                FilterChip(
                    selected = style == option,
                    onClick = { style = option },
                    label = { Text(option) }
                )
            }

            Button(
                onClick = { showAnimation = topic.isNotBlank() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Preview Animation")
            }

            Button(
                enabled = !exporting && topic.isNotBlank(),
                onClick = {
                    exporting = true
                    exportStatus = "Creating MP4 on your phone. This may take a few minutes..."
                    scope.launch {
                        try {
                            val fileUri = withContext(Dispatchers.IO) {
                                exportMedicalVideo(context, topic.trim(), language, duration)
                            }
                            exportStatus = "MP4 saved to Movies/MedVideoAI. URI: $fileUri"
                        } catch (e: Exception) {
                            exportStatus = "Export failed: ${e.message ?: "Unknown error"}"
                        } finally {
                            exporting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (exporting) "Exporting MP4..." else "Export Free MP4")
            }
            if (exportStatus.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(exportStatus, modifier = Modifier.padding(16.dp))
                }
            }

            if (topic.isBlank() && showAnimation) {
                Text("Please enter a medical topic.")
            }

            if (showAnimation && topic.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(topic, style = MaterialTheme.typography.titleLarge)
                        Text("MEDICAL ANIMATION • FREE OFFLINE PREVIEW")
                        MedicalAnimationCanvas()
                        Text(
                            when (language) {
                                "Tamil" -> "தமிழ்: $topic பற்றிய கற்றல் காட்சி. இது பொதுவான விளக்க மாதிரி; மருத்துவ உண்மைகளைப் பாடநூலுடன் சரிபார்க்கவும்."
                                "English" -> "Educational visual for $topic. Verify clinical details with trusted medical references."
                                else -> "$topic — மருத்துவக் கற்றல் காட்சி / Educational learning visual. Verify clinical details with trusted medical references."
                            }
                        )
                        Text("Selected lesson length: $duration")
                        Text("Style: $style")
                        Text(
                            "MP4 export creates a simple captioned medical-learning animation locally, without paid APIs. This first export version does not include spoken narration or detailed topic-specific medical diagrams.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Text(
                "Free mode: no paid API or API key is used. Exported videos use a general animated medical template and captions; verify medical facts against trusted references.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MedicalAnimationCanvas() {
    val transition = rememberInfiniteTransition(label = "medical-animation")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing-pulse"
    )

    Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val lungWidth = size.width * 0.22f
        val lungHeight = size.height * 0.56f
        val top = centerY - lungHeight / 2f

        drawLine(
            color = Color(0xFF6D4CAF),
            start = Offset(centerX, top - 18f),
            end = Offset(centerX, centerY + lungHeight * 0.35f),
            strokeWidth = 12f
        )
        drawLine(
            color = Color(0xFF6D4CAF),
            start = Offset(centerX, centerY - 10f),
            end = Offset(centerX - lungWidth * 0.72f, centerY + 8f),
            strokeWidth = 8f
        )
        drawLine(
            color = Color(0xFF6D4CAF),
            start = Offset(centerX, centerY - 10f),
            end = Offset(centerX + lungWidth * 0.72f, centerY + 8f),
            strokeWidth = 8f
        )

        drawOval(
            color = Color(0xFF80CBC4),
            topLeft = Offset(centerX - lungWidth * 1.15f * pulse, top),
            size = Size(lungWidth * pulse, lungHeight * pulse)
        )
        drawOval(
            color = Color(0xFF80CBC4),
            topLeft = Offset(centerX + lungWidth * 0.15f, top),
            size = Size(lungWidth * pulse, lungHeight * pulse)
        )
        drawCircle(
            color = Color(0xFFFFB74D),
            radius = 9f + 7f * pulse,
            center = Offset(centerX, top - 18f),
            style = Stroke(width = 4f)
        )
        drawCircle(
            color = Color(0xFFEF5350),
            radius = 5f + 3f * pulse,
            center = Offset(centerX - lungWidth * 1.4f, centerY + lungHeight * 0.2f)
        )
        drawCircle(
            color = Color(0xFFEF5350),
            radius = 5f + 3f * pulse,
            center = Offset(centerX + lungWidth * 1.4f, centerY + lungHeight * 0.2f)
        )
    }
}


private fun exportMedicalVideo(
    context: Context,
    topic: String,
    language: String,
    durationChoice: String
): String {
    val durationSeconds = when (durationChoice) {
        "30 seconds" -> 30
        "3 minutes" -> 180
        else -> 60
    }
    val width = 320
    val height = 568
    val fps = 10
    val frameCount = durationSeconds * fps
    val outputFile = File(context.cacheDir, "medvideo_${System.currentTimeMillis()}.mp4")
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
        setInteger(MediaFormat.KEY_BIT_RATE, 650_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    }
    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    var muxer: MediaMuxer? = null
    var trackIndex = -1
    var muxerStarted = false
    try {
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var frame = 0
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("Encoder input buffer unavailable")
                    inputBuffer.clear()
                    if (frame < frameCount) {
                        val bitmap = drawMedicalFrame(width, height, topic, language, frame / fps, durationSeconds)
                        val yuv = bitmapToI420(bitmap)
                        bitmap.recycle()
                        if (yuv.size > inputBuffer.remaining()) {
                            throw IllegalStateException("Video frame exceeds encoder buffer capacity")
                        }
                        inputBuffer.put(yuv)
                        val ptsUs = frame * 1_000_000L / fps
                        encoder.queueInputBuffer(inputIndex, 0, yuv.size, ptsUs, 0)
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
                    if (muxerStarted) throw IllegalStateException("Encoder output format changed twice")
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        ?: throw IllegalStateException("Encoder output buffer unavailable")
                    if (info.size > 0) {
                        if (!muxerStarted) throw IllegalStateException("Muxer has not started")
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, info)
                    }
                    outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    } finally {
        runCatching { encoder.stop() }
        encoder.release()
        if (muxerStarted) runCatching { muxer?.stop() }
        muxer?.release()
    }

    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "MedVideoAI_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MedVideoAI")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Could not create output video in Movies/MedVideoAI")
    try {
        resolver.openOutputStream(uri)?.use { output ->
            outputFile.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open output video")
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    } finally {
        outputFile.delete()
    }
    return uri.toString()
}

private fun drawMedicalFrame(
    width: Int,
    height: Int,
    topic: String,
    language: String,
    elapsedSeconds: Int,
    totalSeconds: Int
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(250, 245, 255))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.rgb(100, 70, 160)
    canvas.drawRoundRect(RectF(18f, 20f, width - 18f, 82f), 18f, 18f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 23f
    paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
    canvas.drawText("MedVideo AI", 32f, 58f, paint)

    paint.color = android.graphics.Color.rgb(35, 35, 45)
    paint.textSize = 22f
    val topicWords = topic.split(" ")
    var topicLine = ""
    var y = 125f
    for (word in topicWords) {
        val candidate = if (topicLine.isBlank()) word else "$topicLine $word"
        if (paint.measureText(candidate) > width - 44f) {
            canvas.drawText(topicLine, 22f, y, paint)
            y += 28f
            topicLine = word
        } else topicLine = candidate
    }
    if (topicLine.isNotBlank()) canvas.drawText(topicLine, 22f, y, paint)

    val cx = width / 2f
    val cy = height * 0.49f
    val pulse = 1f + 0.06f * kotlin.math.sin(elapsedSeconds * 1.5).toFloat()
    paint.color = android.graphics.Color.rgb(109, 76, 175)
    paint.strokeWidth = 10f
    canvas.drawLine(cx, cy - 80f, cx, cy + 40f, paint)
    canvas.drawLine(cx, cy - 10f, cx - 45f, cy + 15f, paint)
    canvas.drawLine(cx, cy - 10f, cx + 45f, cy + 15f, paint)
    paint.color = android.graphics.Color.rgb(128, 203, 196)
    canvas.drawOval(RectF(cx - 88f * pulse, cy - 65f * pulse, cx - 12f, cy + 100f * pulse), paint)
    canvas.drawOval(RectF(cx + 12f, cy - 65f * pulse, cx + 88f * pulse, cy + 100f * pulse), paint)

    paint.color = android.graphics.Color.rgb(255, 183, 77)
    canvas.drawCircle(cx, cy - 88f, 13f + 4f * pulse, paint)
    paint.color = android.graphics.Color.rgb(70, 60, 90)
    paint.textSize = 15f
    val caption = when (language) {
        "Tamil" -> "மருத்துவக் கற்றல் காட்சி"
        "English" -> "Medical learning visual"
        else -> "மருத்துவக் கற்றல் / Medical learning"
    }
    canvas.drawText(caption, 20f, height - 86f, paint)
    paint.color = android.graphics.Color.rgb(110, 95, 130)
    paint.textSize = 13f
    canvas.drawText("Educational template - verify clinical facts", 20f, height - 58f, paint)
    canvas.drawRoundRect(RectF(20f, height - 38f, width - 20f, height - 27f), 6f, 6f, paint)
    paint.color = android.graphics.Color.rgb(109, 76, 175)
    val progress = elapsedSeconds.toFloat() / totalSeconds.coerceAtLeast(1)
    canvas.drawRoundRect(RectF(20f, height - 38f, 20f + (width - 40f) * progress, height - 27f), 6f, 6f, paint)
    return bitmap
}

private fun bitmapToI420(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val frameSize = width * height
    val output = ByteArray(frameSize * 3 / 2)
    var yIndex = 0
    var uIndex = frameSize
    var vIndex = frameSize + frameSize / 4
    for (row in 0 until height) {
        for (col in 0 until width) {
            val color = pixels[row * width + col]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
            output[yIndex++] = y.toByte()
            if (row % 2 == 0 && col % 2 == 0) {
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                output[uIndex++] = u.toByte()
                output[vIndex++] = v.toByte()
            }
        }
    }
    return output
}
