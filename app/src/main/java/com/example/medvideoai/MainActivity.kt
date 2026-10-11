package com.example.medvideoai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicalVideoScreen() {
    var topic by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("Tamil") }
    var duration by remember { mutableStateOf("1 minute") }
    var style by remember { mutableStateOf("Medical animation") }
    var status by remember { mutableStateOf("") }
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val topics = listOf(
        "COPD", "Asthma", "Meniere disease",
        "Pulmonary hypertension", "Anatomy", "Pathology",
        "ENT", "Ophthalmology", "Forensic medicine"
    )

    Scaffold(topBar = { TopAppBar(title = { Text("MedVideo AI") }) }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("AI Medical Video Creator", style = MaterialTheme.typography.headlineSmall)
            Text("Create a short educational video from a medical topic.")

            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it; status = ""; videoUrl = null },
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
                            onClick = { topic = item; status = ""; videoUrl = null },
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

            Text("Preferred final duration", style = MaterialTheme.typography.titleMedium)
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
                enabled = !isGenerating,
                onClick = {
                    if (topic.isBlank()) {
                        status = "Please enter a medical topic."
                    } else if (BuildConfig.HIGGSFIELD_API_KEY.isBlank()) {
                        status = "API key not found. Add HIGGSFIELD_API_KEY to the project-root local.properties file, then rebuild."
                    } else {
                        isGenerating = true
                        videoUrl = null
                        status = "Sending a 5-second test video request. This may use paid Higgsfield API credits."
                        scope.launch {
                            try {
                                videoUrl = generateTestVideo(topic.trim(), language, style)
                                status = "Video generated. Tap Open video to view it."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                status = "Generation failed: ${e.message ?: "Unknown error"}. Check API key, API balance, and model access."
                            } finally {
                                isGenerating = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (isGenerating) "Generating..." else "Generate Test Video (5 sec)") }

            if (isGenerating) CircularProgressIndicator()

            videoUrl?.let { link ->
                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open video") }
            }

            if (status.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(status, modifier = Modifier.padding(16.dp))
                }
            }

            Text(
                "Prototype only: the API key is packaged in the APK and can be extracted. Do not share this APK or publish it to Play Store. This test generates a 5-second clip; the selected duration and style are used as prompt guidance, not as guaranteed provider settings. Check medical facts against trusted clinical references.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private suspend fun generateTestVideo(topic: String, language: String, style: String): String =
    withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.HIGGSFIELD_API_KEY.trim()
        val prompt = "Create a medically educational video about: $topic. " +
            "Language preference: $language. Visual style: $style. " +
            "For medical student learning, use clear visuals and avoid unsupported medical claims."

        val requestBody = JSONObject()
            .put("prompt", prompt)
            .put("duration", 5)
            .put("resolution", "720p")
            .put("aspect_ratio", "16:9")
            .put("generate_audio", false)

        val submitConnection = (URL("https://api.higgsfield.ai/bytedance/seedance-2.0/text-to-video")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Key $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val submitText: String
        try {
            submitConnection.outputStream.use {
                it.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }
            val code = submitConnection.responseCode
            val stream = if (code in 200..299) submitConnection.inputStream else submitConnection.errorStream
            submitText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    val errorJson = JSONObject(submitText)
                    listOf("message", "detail", "error_description", "error")
                        .map { key -> errorJson.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                        .firstOrNull { it != null }
                        .orEmpty()
                }.getOrNull().orEmpty()
                val diagnostic = if (message.isNotBlank()) message else submitText.take(400)
                throw IllegalStateException(
                    "Higgsfield HTTP $code${if (diagnostic.isNotBlank()) ": $diagnostic" else ""}"
                )
            }
        } finally {
            submitConnection.disconnect()
        }

        val submitted = JSONObject(submitText)
        val requestId = submitted.optString("request_id")
        if (requestId.isBlank()) {
            val directUrl = submitted.optJSONObject("video")?.optString("url").orEmpty()
            if (directUrl.startsWith("https://")) return@withContext directUrl
            throw IllegalStateException("API response did not include request_id.")
        }

        val statusUrl = submitted.optString("status_url").ifBlank {
            "https://api.higgsfield.ai/requests/$requestId/status"
        }
        repeat(60) {
            delay(5000)
            val pollConnection = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 20000
                setRequestProperty("Authorization", "Key $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            val pollText: String
            try {
                val code = pollConnection.responseCode
                val stream = if (code in 200..299) pollConnection.inputStream else pollConnection.errorStream
                pollText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("Status check HTTP $code")
            } finally {
                pollConnection.disconnect()
            }

            val json = JSONObject(pollText)
            when (json.optString("status").lowercase()) {
                "completed" -> {
                    val url = json.optJSONObject("video")?.optString("url").orEmpty()
                    if (url.startsWith("https://")) return@withContext url
                    throw IllegalStateException("Generation completed but no video URL was returned.")
                }
                "failed", "nsfw", "canceled", "cancelled" ->
                    throw IllegalStateException("Higgsfield status: ${json.optString("status")}")
            }
        }
        throw IllegalStateException("Timed out waiting for video. Check the Higgsfield dashboard for request status.")
    }
