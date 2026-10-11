package com.example.medvideoai

import android.graphics.Paint
import android.graphics.BitmapFactory
import android.util.Base64
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MedicalNotesScreen() } } }
    }
}

private data class GeminiModelOption(val label: String, val id: String, val description: String)

private val geminiModelOptions = listOf(
    GeminiModelOption("Auto — use available models", "auto", "Checks available model IDs and tries alternatives on temporary errors")
)

private data class NoteSection(
    val title: String,
    val lines: List<String>,
    val flowchart: Boolean = false,
    val mindMap: Boolean = false,
    val important: Boolean = false,
    val visualHint: String? = null
)

private data class MedicalVisual(val title: String, val sourceUrl: String, val bitmap: android.graphics.Bitmap)
private data class SelectedMedicalImage(val uri: Uri, val mimeType: String, val base64Data: String, val previewBitmap: android.graphics.Bitmap)
private data class MedicalNotes(val topic: String, val sections: List<NoteSection>) {
    fun plainText(): String = buildString {
        append(topic.trim().ifBlank { "Medical Notes" }).append("\n\n")
        sections.forEach { section ->
            append(section.title.uppercase(Locale.ROOT)).append("\n")
            section.lines.forEachIndexed { index, line ->
                if (section.flowchart) {
                    append(line.trim())
                    if (index < section.lines.lastIndex) append("  ↓")
                    append("\n")
                } else {
                    append("• ").append(line.trim()).append("\n")
                }
            }
            append("\n")
        }
    }.trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedicalNotesScreen() {
    val context = LocalContext.current
    val draftPrefs = remember { context.getSharedPreferences("mednotes_draft", android.content.Context.MODE_PRIVATE) }
    var topic by remember { mutableStateOf(draftPrefs.getString("topic", "").orEmpty()) }
    var sourceText by remember { mutableStateOf(draftPrefs.getString("sourceText", "").orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(geminiModelOptions.first()) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf(geminiModelOptions) }
    var notes by remember { mutableStateOf(draftPrefs.getString("savedNotes", null)?.let { decodeMedicalNotes(it) }) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pdfBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedImage by remember { mutableStateOf<SelectedMedicalImage?>(null) }
    var imageRestoreComplete by remember { mutableStateOf(false) }
    var visualLookupBusy by remember { mutableStateOf(false) }
    var visualLookupStatus by remember { mutableStateOf("") }
    var topicVisuals by remember { mutableStateOf<List<MedicalVisual>>(emptyList()) }
    val pickMedicalImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            status = "Image selection cancelled."
        } else {
            try {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                require(mime == "image/jpeg" || mime == "image/png" || mime == "image/webp") {
                    "Choose a JPG, PNG, or WEBP image."
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read selected image")
                require(bytes.isNotEmpty()) { "Selected image is empty." }
                selectedImage = SelectedMedicalImage(
                    uri = uri,
                    mimeType = mime,
                    base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    previewBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("Could not decode image")
                )
                notes = null
                status = "Image selected. Tap AI Summarize to create notes from the image."
            } catch (e: Exception) {
                selectedImage = null
                draftPrefs.edit().remove("imageUri").apply()
                status = "Could not load image: ${e.message ?: "unknown error"}"
            }
        }
    }
    LaunchedEffect(Unit) {
        val savedUri = draftPrefs.getString("imageUri", null)
        if (!savedUri.isNullOrBlank()) {
            runCatching {
                val uri = Uri.parse(savedUri)
                withContext(Dispatchers.IO) {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Saved image is no longer available")
                    SelectedMedicalImage(
                        uri = uri,
                        mimeType = mime,
                        base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        previewBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("Could not decode saved image")
                    )
                }
            }.onSuccess { selectedImage = it }
             .onFailure { draftPrefs.edit().remove("imageUri").apply() }
        }
        imageRestoreComplete = true
    }
    LaunchedEffect(topic, sourceText) {
        draftPrefs.edit()
            .putString("topic", topic)
            .putString("sourceText", sourceText)
            .apply()
    }
    LaunchedEffect(selectedImage?.uri, imageRestoreComplete) {
        if (imageRestoreComplete) {
            val imageUri = selectedImage?.uri?.toString()
            if (imageUri.isNullOrBlank()) draftPrefs.edit().remove("imageUri").apply()
            else draftPrefs.edit().putString("imageUri", imageUri).apply()
        }
    }
    LaunchedEffect(notes) {
        if (notes == null) draftPrefs.edit().remove("savedNotes").apply()
        else draftPrefs.edit().putString("savedNotes", encodeMedicalNotes(notes!!)).apply()
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val bytes = pdfBytes
        if (uri != null && bytes != null) {
            try {
                // The selected destination is controlled by the user through Android's document picker.
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open PDF destination")
                status = "PDF saved successfully."
            } catch (e: Exception) {
                status = "Could not save PDF: ${e.message ?: "unknown error"}"
            }
        } else if (uri == null) status = "PDF save cancelled."
    }

    Scaffold(topBar = { TopAppBar(title = { Text("MedNotes PDF") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Medical Text → Exam Notes → PDF", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    topic = ""; sourceText = ""; selectedImage = null; apiKey = ""; selectedModel = geminiModelOptions.first()
                    availableModels = geminiModelOptions; modelMenuExpanded = false; notes = null
                    draftPrefs.edit().clear().apply()
                    busy = false; status = ""; pdfBytes = null
                }) { Text("↻ Refresh") }
            }
            Text("Paste long English medical text. There is no fixed 500-character app limit. Notes and PDF can span multiple pages; Gemini may still apply request/token limits. Offline mode preserves all detected sentences. Definitions are kept verbatim; causes and mechanisms are shown as mind maps.")
            OutlinedTextField(
                value = topic, onValueChange = { topic = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Topic / title") }, placeholder = { Text("e.g. Hypertension") }
            )
            OutlinedTextField(
                value = sourceText, onValueChange = { sourceText = it; notes = null; status = "" },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), minLines = 7,
                label = { Text("Paste English medical text") },
                placeholder = { Text("Paste your topic text here in English...") }
            )

            Text("Input source — use text, an image, or both.", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickMedicalImage.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, enabled = !busy) {
                    Text(if (selectedImage == null) "Choose Image" else "Change Image")
                }
                TextButton(onClick = { selectedImage = null; notes = null; status = "Image removed." }, enabled = selectedImage != null && !busy) {
                    Text("Remove Image")
                }
            }
            selectedImage?.let { picture ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(
                        bitmap = picture.previewBitmap.asImageBitmap(),
                        contentDescription = "Selected medical image preview",
                        modifier = Modifier.size(112.dp)
                    )
                    Column {
                        Text("Image ready for AI analysis", style = MaterialTheme.typography.titleSmall)
                        Text(picture.mimeType, style = MaterialTheme.typography.bodySmall)
                        Text("Image content is sent only when you tap AI Summarize.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it.trim(); status = "" },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Optional Gemini API key (not saved)") },
                placeholder = { Text("Enter your own API key for AI summarization") }
            )
            Text("Choose a Gemini model. Switch models if one is slow or its quota is unavailable. Free access and rate limits depend on your Google AI Studio account.", style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth()) {
                TextButton(onClick = { modelMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("AI model: ${selectedModel.label}  ▾")
                }
                DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                    availableModels.forEach { option ->
                        DropdownMenuItem(
                            text = { Column { Text(option.label); Text(option.description, style = MaterialTheme.typography.bodySmall) } },
                            onClick = { selectedModel = option; modelMenuExpanded = false; status = "Selected ${option.label}." }
                        )
                    }
                }
            }
            TextButton(
                enabled = apiKey.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    status = "Checking models available to this API key..."
                    scope.launch {
                        try {
                            val models = withContext(Dispatchers.IO) { fetchAvailableGeminiModels(apiKey) }
                            availableModels = listOf(geminiModelOptions.first()) + models
                            if (availableModels.none { it.id == selectedModel.id }) selectedModel = availableModels.first()
                            status = if (models.isEmpty()) {
                                "Google returned no compatible Gemini models for this API key."
                            } else {
                                "Model list refreshed: ${models.size} exact model ID(s) returned by Google. Auto will try available Flash models first."
                            }
                        } catch (e: Exception) {
                            status = "Could not refresh model list: ${e.message ?: "unknown error"}. Existing choices are still available."
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("Refresh models available to my API key") }
            TextButton(
                enabled = !busy,
                onClick = {
                    topic = ""
                    sourceText = ""
                    selectedImage = null
                    apiKey = ""
                    selectedModel = geminiModelOptions.first()
                    availableModels = geminiModelOptions
                    modelMenuExpanded = false
                    notes = null
                    draftPrefs.edit().clear().apply()
                    status = "Ready for new notes."
                    pdfBytes = null
                }
            ) { Text("Clear / New Notes") }
            Text("AI mode sends the pasted text to Google Gemini only after you tap the AI button. The prompt asks it to use only your text, but outputs can still be wrong; check every medical detail. The offline button never sends text anywhere. Topic, pasted text, selected image reference, and generated notes are auto-saved on this device. The API key is held only in screen memory and is not saved by this app.", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = sourceText.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    status = "Selecting key sentences from your text..."
                    val input = sourceText.trim()
                    val title = topic.trim().ifBlank { inferTopic(input) }
                    notes = formatMedicalNotes(title, input)
                    status = "Short notes created using only your text. Check the preview for context, then save the PDF."
                    busy = false
                }
            ) { Text("Make Short Notes (Offline)") }
            Button(
                enabled = (sourceText.isNotBlank() || selectedImage != null) && apiKey.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    status = "Sending text for AI summarization..."
                    val input = sourceText.trim()
                    val title = topic.trim().ifBlank {
                        when {
                            input.isNotBlank() -> inferTopic(input)
                            selectedImage != null -> "Image Medical Notes"
                            else -> "Medical Notes"
                        }
                    }
                    val picture = selectedImage
                    scope.launch {
                        try {
                            val summarized = withContext(Dispatchers.IO) {
                                summarizeWithGemini(apiKey, title, input, selectedModel.id, picture?.mimeType, picture?.base64Data)
                            }
                            notes = formatMedicalNotes(title, if (input.isBlank()) summarized else restoreExactDefinition(title, input, summarized))
                            status = "AI notes received. Finding suitable medical illustrations..."
                            visualLookupBusy = true
                            topicVisuals = emptyList()
                            try {
                                val hints = notes!!.sections.mapNotNull { it.visualHint }.distinct().take(3)
                                topicVisuals = withContext(Dispatchers.IO) { findMedicalVisuals(hints) }
                                visualLookupStatus = if (topicVisuals.isEmpty()) {
                                    "No suitable online illustrations found; notes and PDF are still available."
                                } else {
                                    "Found ${topicVisuals.size} medical illustration(s). They will be added to the PDF."
                                }
                                status = "AI notes ready. ${visualLookupStatus} Review medical accuracy before saving."
                            } catch (visualError: Exception) {
                                visualLookupStatus = "Illustrations unavailable: ${visualError.message ?: "network error"}"
                                status = "AI notes ready. ${visualLookupStatus}"
                            } finally {
                                visualLookupBusy = false
                            }
                        } catch (e: Exception) {
                            val detail = e.message ?: "unknown error"
                            status = when {
                                detail.contains("HTTP 503") || detail.contains("high demand", ignoreCase = true) ->
                                    "Gemini server is overloaded (503). Auto tried available models and retried temporary errors. Wait 30–60 seconds and try again; if it continues, tap Refresh models and choose another available model. This server error cannot be fully fixed in app code."
                                detail.contains("HTTP 429") || detail.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
                                    "Gemini rate/quota limit reached (429). Wait for the limit to reset or check Google AI Studio usage and selected-model limits. Details: $detail"
                                detail.contains("HTTP 400") || detail.contains("HTTP 401") || detail.contains("HTTP 403") ->
                                    "Gemini request/key/model access problem. Check the API key, selected model, and Google AI Studio project. Details: $detail"
                                else -> "AI summary failed: $detail. You can still use Offline Short Notes."
                            }
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("AI Summarize (Gemini API)") }
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("Formatting notes...")
            }
            if (visualLookupBusy) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Finding relevant medical illustrations...")
                }
            }
            if (visualLookupStatus.isNotBlank()) Text(visualLookupStatus, style = MaterialTheme.typography.bodySmall)
            if (topicVisuals.isNotEmpty()) {
                Text("Relevant illustrations", style = MaterialTheme.typography.titleMedium)
                topicVisuals.forEach { visual ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(bitmap = visual.bitmap.asImageBitmap(), contentDescription = visual.title, modifier = Modifier.size(112.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(visual.title, style = MaterialTheme.typography.titleSmall)
                            Text("Wikimedia Commons · online image", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            notes?.let { generated ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(generated.topic, style = MaterialTheme.typography.titleLarge, color = Color(0xFF23416E))
                        generated.sections.forEachIndexed { sectionIndex, section ->
                            val sectionColors = listOf(
                                Color(0xFFE8F1FF), Color(0xFFE6F7EE), Color(0xFFFFF2D9),
                                Color(0xFFF3E8FF), Color(0xFFFFE8EC), Color(0xFFE4F7F8)
                            )
                            val sectionColor = sectionColors[sectionIndex % sectionColors.size]
                            val importantBackground = Color(0xFFFFD580)
                            val headingColor = if (section.important) Color(0xFF6D3B00) else Color(0xFF17365D)
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(if (section.important) importantBackground else sectionColor, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    if (section.important) "★  ${section.title}" else section.title,
                                    style = if (section.important) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                    color = headingColor
                                )
                                if (section.flowchart) {
                                    section.lines.forEachIndexed { index, line ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(9.dp),
                                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                                containerColor = sectionColors[(index + sectionIndex) % sectionColors.size]
                                            )
                                        ) {
                                            Text(
                                                line,
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color(0xFF203047)
                                            )
                                        }
                                        if (index < section.lines.lastIndex) {
                                            Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                                Text("↓", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF345282))
                                            }
                                        }
                                    }
                                } else if (section.mindMap && section.lines.isNotEmpty()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(9.dp),
                                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = sectionColors[(sectionIndex + 1) % sectionColors.size])
                                    ) {
                                        Text(
                                            "●  ${section.lines.first()}",
                                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = headingColor
                                        )
                                    }
                                    section.lines.drop(1).forEachIndexed { branchIndex, branch ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = sectionColors[(branchIndex + sectionIndex + 2) % sectionColors.size])
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(10.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text("↳", color = Color(0xFF345282))
                                                Text(branch, modifier = Modifier.weight(1f), color = Color(0xFF202124))
                                            }
                                        }
                                    }
                                } else {
                                    section.lines.forEachIndexed { lineIndex, line ->
                                        val itemBackground = if (section.important) Color(0xFFFFE3A3)
                                            else sectionColors[(sectionIndex + lineIndex + 1) % sectionColors.size]
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = itemBackground)
                                        ) {
                                            Text(
                                                "${if (section.important) "★" else "•"}  $line",
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
                                                style = if (section.important) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                                color = if (section.important) Color(0xFF5B3200) else Color(0xFF202124)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        pdfBytes = createPdf(generated, topicVisuals)
                        savePdf.launch(safeFileName(generated.topic) + ".pdf")
                    }
                ) { Text("Save PDF") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Offline notes now keep all detected sentences instead of selecting only a fraction. There is no fixed 500-character app limit, but device memory and Gemini request/token quotas still apply. Review medical content before exams.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


private fun containsTamil(text: String): Boolean = text.any { it.code in 0x0B80..0x0BFF }

private fun restoreExactDefinition(topic: String, sourceText: String, summaryText: String): String {
    val exactDefinition = formatMedicalNotes(topic, sourceText).sections
        .firstOrNull { it.title == "Definition" } ?: return summaryText
    val lines = summaryText.lines().toMutableList()
    val definitionHeading = Regex("^\\s*(?:#+\\s*)?definition\\s*:?(?:\\s+.*)?$", RegexOption.IGNORE_CASE)
    val start = lines.indexOfFirst { definitionHeading.matches(it.trim()) }
    val sectionHeading = Regex(
        "^\\s*(?:#+\\s*)?(definition|etiology(?:\\s*/\\s*causes)?|causes?|risk factors|classification|types?|mechanism(?: of action)?|pathogenesis|clinical features|symptoms|signs|investigations|diagnosis|tests|treatment|management|therapy|complications|prevention|prophylaxis)\\s*:?\\s*$",
        RegexOption.IGNORE_CASE
    )
    if (start < 0) {
        return "Definition:\n" + exactDefinition.lines.joinToString("\n") + "\n\n" + summaryText
    }
    var end = lines.size
    for (i in (start + 1) until lines.size) {
        if (sectionHeading.matches(lines[i]) && !definitionHeading.matches(lines[i])) {
            end = i
            break
        }
    }
    val replacement = mutableListOf("Definition:").apply { addAll(exactDefinition.lines) }
    lines.subList(start, end).clear()
    lines.addAll(start, replacement)
    return lines.joinToString("\n")
}

private fun fetchAvailableGeminiModels(apiKey: String): List<GeminiModelOption> {
    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models?key=" +
        URLEncoder.encode(apiKey, "UTF-8")
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 20000
    }
    try {
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (statusCode !in 200..299) {
            val detail = runCatching { JSONObject(responseText).getJSONObject("error").optString("message") }.getOrNull()
            val serverMessage = detail?.takeIf { it.isNotBlank() } ?: "No error details from server"
            throw IllegalStateException("HTTP $statusCode: $serverMessage")
        }
        val models = JSONObject(responseText).optJSONArray("models") ?: JSONArray()
        val options = (0 until models.length()).mapNotNull { index ->
            val item = models.optJSONObject(index) ?: return@mapNotNull null
            val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
            val supportsTextGeneration = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            val rawName = item.optString("name")
            if (!supportsTextGeneration || !rawName.startsWith("models/gemini-")) return@mapNotNull null
            val id = rawName.removePrefix("models/")
            val displayName = item.optString("displayName").ifBlank { id }
            val normalizedId = id.lowercase(Locale.ROOT)
            // Prefer stable Flash models and list only exact IDs returned by Google's API.
            val priority = when {
                normalizedId.contains("flash-lite") -> "Fast"
                normalizedId.contains("flash") -> "Balanced"
                normalizedId.contains("pro") -> "Quality"
                else -> "Other"
            }
            GeminiModelOption(id, id, "$priority — $displayName; server availability and quota can vary")
        }.distinctBy { it.id }.sortedWith(
            compareBy<GeminiModelOption> {
                when {
                    it.id.contains("flash-lite", ignoreCase = true) -> 0
                    it.id.contains("flash", ignoreCase = true) -> 1
                    it.id.contains("pro", ignoreCase = true) -> 2
                    else -> 3
                }
            }.thenBy { it.id }
        )
        if (options.isEmpty()) throw IllegalStateException("No Gemini models supporting generateContent were returned")
        return options
    } finally {
        connection.disconnect()
    }
}

private fun summarizeWithGemini(apiKey: String, topic: String, sourceText: String, modelId: String, imageMimeType: String? = null, imageBase64: String? = null): String {
    if (modelId == "auto") {
        val available = fetchAvailableGeminiModels(apiKey)
        val candidates = available
            .filter { it.id.contains("flash", ignoreCase = true) }
            .ifEmpty { available }
            .sortedWith(
                compareBy<GeminiModelOption> {
                    when {
                        it.id.contains("flash-lite", ignoreCase = true) -> 0
                        it.id.contains("flash", ignoreCase = true) -> 1
                        it.id.contains("pro", ignoreCase = true) -> 2
                        else -> 3
                    }
                }.thenBy { it.id }
            )
            .map { it.id }
            .distinct()
            .take(5)
        if (candidates.isEmpty()) {
            throw IllegalStateException("Google returned no Gemini models that support generateContent. Tap Refresh models available to my API key and check the API key/project.")
        }
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                return summarizeWithGeminiWithRetry(apiKey, topic, sourceText, candidate, imageMimeType, imageBase64)
            } catch (e: Exception) {
                lastError = e
                val message = e.message.orEmpty()
                // Do not continue through models for invalid key, access, billing, or malformed requests.
                if (message.contains("HTTP 400") || message.contains("HTTP 401") ||
                    message.contains("HTTP 402") || message.contains("HTTP 403")) throw e
            }
        }
        throw IllegalStateException("All available candidate models failed. ${lastError?.message ?: "unknown error"}. Try again shortly or select a model from the refreshed list.")
    }
    return summarizeWithGeminiWithRetry(apiKey, topic, sourceText, modelId, imageMimeType, imageBase64)
}

private fun summarizeWithGeminiWithRetry(
    apiKey: String,
    topic: String,
    sourceText: String,
    modelId: String,
    imageMimeType: String?,
    imageBase64: String?
): String {
    var lastError: Exception? = null
    for (attempt in 0..3) {
        try {
            return summarizeWithGeminiSingleAttempt(apiKey, topic, sourceText, modelId, imageMimeType, imageBase64)
        } catch (e: Exception) {
            lastError = e
            val message = e.message.orEmpty()
            val temporary = message.contains("HTTP 408") || message.contains("HTTP 429") ||
                message.contains("HTTP 500") || message.contains("HTTP 502") ||
                message.contains("HTTP 503") || message.contains("HTTP 504") ||
                message.contains("timed out", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true)
            if (!temporary || attempt == 3) throw e
            // Exponential backoff plus small jitter: 1s, 2s, 4s, then stop.
            val delayMs = (1000L shl attempt) + (0L..500L).random()
            Thread.sleep(delayMs)
        }
    }
    throw IllegalStateException(lastError?.message ?: "Gemini request failed")
}

private fun summarizeWithGeminiSingleAttempt(
    apiKey: String,
    topic: String,
    sourceText: String,
    modelId: String,
    imageMimeType: String?,
    imageBase64: String?
): String {
    val prompt = """
        You are an MBBS exam-note analyst. Analyze the topic and source before writing; identify what each fact belongs to, then place it under the correct heading.
        GOAL: produce only high-yield, topic-relevant exam points, not a long essay and not a copy of the source.
        LENGTH AND EXPLANATION: create concise but understandable MBBS revision notes, not an essay and not cryptic fragments. Usually give 2–4 relevant points per heading and about 8–14 meaningful points overall, depending on topic complexity. A point may use one or two short sentences when explanation is needed. Briefly explain how/why important signs, test findings, mechanisms and treatment effects occur when the source supports it. Do not repeat facts or add filler. Preserve essential definitions, diagnostic criteria, staging/classification, key mechanisms, source-mentioned management, important contraindications and exam differentiators. Do not make notes artificially short at the cost of understanding.
        SOURCE ACCURACY: treat provided text/image as the primary source. Preserve a supplied definition verbatim. Do not invent doses, lab values, treatment recommendations, or unreadable image details. If clarification is essential, label it "Brief clarification" and keep it to one line.
        INPUT ANALYSIS: carefully read all supplied text and image content. For images, interpret legible labels, tables, arrows, and diagram relationships; do not guess unclear material. Combine text+image without duplication.
        OUTPUT RULES: plain text only, compact bullets, no long paragraphs. Use only relevant headings from this set:
        Definition: original wording if present.
        Etiology / Causes: key causes, grouped logically.
        Classification: numbered/type-wise, with defining distinctions.
        Pathophysiology / Pathogenesis: show as a step-by-step arrow flowchart (cause → intermediate change → final effect/manifestation).
        Mechanism of Action: drug/intervention → target → action → effect flowchart.
        Clinical Features: major symptoms/signs; add a brief mechanism only when important and well-supported.
        Investigations: test → key finding/what it demonstrates.
        Treatment / Management: intervention → target/action → intended result; only include source-mentioned treatments unless an essential clarification is explicitly labelled.
        Complications: highest-yield outcomes only.
        Important Points: examination traps, differentiating features, red flags, key numbers/criteria.
        Prevention / Prognosis: only if relevant and supported.
        FLOWCHARTS: use arrows (→) whenever sequence, cause-and-effect, mechanism, progression, diagnostic decision, or treatment response is being explained. Do not force a flowchart for simple lists or definitions.
        PRIORITY: correctness and topic analysis first; concise high-yield points second; attractive formatting is handled by the app. Return notes only, no opening or closing commentary.
        TOPIC: $topic

        SOURCE TEXT START
        $sourceText
        SOURCE TEXT END
    """.trimIndent()
    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=" +
        URLEncoder.encode(apiKey, "UTF-8")
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20000
        readTimeout = 60000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
    }
    try {
        val generationConfig = JSONObject().put("maxOutputTokens", 4096)
        // thinkingLevel is only sent to Gemini 3.x models; older model families may reject it.
        if (modelId.startsWith("gemini-3.")) {
            generationConfig.put(
                "thinkingConfig",
                JSONObject().put("thinkingLevel", "low")
            )
        }
        val requestParts = JSONArray().put(JSONObject().put("text", prompt))
        if (!imageMimeType.isNullOrBlank() && !imageBase64.isNullOrBlank()) {
            requestParts.put(
                JSONObject().put("inline_data",
                    JSONObject().put("mime_type", imageMimeType).put("data", imageBase64)
                )
            )
        }
        val requestBody = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", requestParts)))
            .put("generationConfig", generationConfig)
        connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (statusCode !in 200..299) {
            val detail = runCatching { JSONObject(responseText).getJSONObject("error").optString("message") }.getOrNull()
            val serverMessage = detail?.takeIf { it.isNotBlank() } ?: "No error details from server"
            throw IllegalStateException("HTTP $statusCode: $serverMessage")
        }
        val json = JSONObject(responseText)
        val candidates = json.optJSONArray("candidates") ?: throw IllegalStateException("No summary returned")
        val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("The model returned no text")
        val result = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
            .filter { it.isNotBlank() }.joinToString("\n").trim()
        if (result.isBlank()) throw IllegalStateException("The model returned an empty summary")
        return result
    } finally {
        connection.disconnect()
    }
}

private fun encodeMedicalNotes(notes: MedicalNotes): String {
    val sections = JSONArray()
    notes.sections.forEach { section ->
        sections.put(JSONObject()
            .put("title", section.title)
            .put("lines", JSONArray(section.lines))
            .put("flowchart", section.flowchart)
            .put("mindMap", section.mindMap)
            .put("important", section.important)
            .put("visualHint", section.visualHint ?: ""))
    }
    return JSONObject().put("topic", notes.topic).put("sections", sections).toString()
}

private fun decodeMedicalNotes(jsonText: String): MedicalNotes? = runCatching {
    val json = JSONObject(jsonText)
    val array = json.getJSONArray("sections")
    val sections = (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val linesJson = item.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until linesJson.length()).map { linesJson.optString(it) }.filter { it.isNotBlank() }
        if (lines.isEmpty()) null else NoteSection(
            title = item.optString("title", "Important Points"),
            lines = lines,
            flowchart = item.optBoolean("flowchart"),
            mindMap = item.optBoolean("mindMap"),
            important = item.optBoolean("important"),
            visualHint = item.optString("visualHint").takeIf { it.isNotBlank() }
        )
    }
    MedicalNotes(json.optString("topic", "Medical Notes"), sections)
}.getOrNull()

private fun findMedicalVisuals(hints: List<String>): List<MedicalVisual> {
    if (hints.isEmpty()) return emptyList()
    val found = mutableListOf<MedicalVisual>()
    for (hint in hints.take(3)) {
        try {
            val search = URLEncoder.encode(hint, "UTF-8")
            val apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=$search&gsrnamespace=6&gsrlimit=5&prop=imageinfo&iiprop=url&iiurlwidth=420&format=json"
            val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "MedNotesPDF/1.0 (educational medical notes)")
            }
            try {
                if (connection.responseCode !in 200..299) continue
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val pages = JSONObject(response).optJSONObject("query")?.optJSONObject("pages") ?: continue
                val ids = pages.keys()
                var addedForHint = false
                while (ids.hasNext() && !addedForHint) {
                    val pageObj = pages.optJSONObject(ids.next()) ?: continue
                    val title = pageObj.optString("title").removePrefix("File:")
                    val info = pageObj.optJSONObject("imageinfo") ?: pageObj.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                    val imageUrl = info.optString("thumburl").ifBlank { info.optString("url") }
                    if (!imageUrl.startsWith("https://")) continue
                    val bytes = downloadImageBytes(imageUrl) ?: continue
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    if (bitmap.width < 100 || bitmap.height < 80) continue
                    found.add(MedicalVisual(title, info.optString("descriptionurl").ifBlank { imageUrl }, bitmap))
                    addedForHint = true
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            // Online illustrations are optional: failed searches must not block note creation or PDF export.
        }
    }
    return found
}

private fun downloadImageBytes(imageUrl: String): ByteArray? {
    val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 15000
    }
    return try {
        if (connection.responseCode !in 200..299) null
        else {
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 5 * 1024 * 1024) return null
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

private fun inferVisualHint(sectionName: String, topic: String): String? {
    val heading = sectionName.lowercase(Locale.ROOT)
    val hint = when {
        heading.contains("pathogenesis") -> "medical pathogenesis diagram"
        heading.contains("mechanism") -> "medical mechanism of action diagram"
        heading.contains("classification") -> "medical classification diagram"
        heading.contains("anatomy") -> "medical anatomy illustration"
        heading.contains("investigation") -> "medical diagnostic illustration"
        else -> return null
    }
    val cleanTopic = topic.trim().take(80)
    if (cleanTopic.isBlank()) return null
    return "$cleanTopic $hint"
}

private fun inferTopic(text: String): String {
    val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    return first.take(70).ifBlank { "Medical Notes" }.removeSuffix(":")
}

private fun flowSectionFor(name: String): Boolean =
    name == "Mechanism of Action" || name == "Pathogenesis"

private fun formatMedicalNotes(topic: String, raw: String): MedicalNotes {
    val known = listOf(
        "Definition" to listOf("definition", "meaning"),
        "Etiology / Causes" to listOf("etiology / causes", "etiology", "causes", "cause", "risk factors"),
        "Classification" to listOf("classification", "types", "type"),
        "Pathogenesis" to listOf("pathophysiology / pathogenesis", "pathophysiology", "pathogenesis", "pathophysiology of disease"),
        "Mechanism of Action" to listOf("mechanism of action", "mechanism"),
        "Clinical Features" to listOf("clinical features", "clinical presentation", "symptoms", "signs", "presentation"),
        "Investigations" to listOf("investigations", "diagnosis", "diagnostic tests", "tests"),
        "Treatment" to listOf("treatment / management", "treatment", "management", "therapy"),
        "Complications" to listOf("complications", "sequelae"),
        "Prevention" to listOf("prevention", "prophylaxis"),
        "Important Points" to listOf("important points", "high-yield points", "key points", "important", "exam points")
    )
    val buckets = linkedMapOf<String, MutableList<String>>()
    var current = "Important Points"
    buckets[current] = mutableListOf()

    raw.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { originalLine ->
        val clean = originalLine.trim()
            .replace(Regex("^\\s*#{1,6}\\s*"), "")
            .replace(Regex("^\\s*(?:[-•]\\s+|\\d+[.)]\\s*)"), "")
            .replace("**", "")
            .replace("__", "")
            .replace("*", "")
            .trim()
        if (clean.isBlank()) return@forEach
        val lower = clean.lowercase(Locale.ROOT)
        val matched = known.firstNotNullOfOrNull { (sectionName, aliases) ->
            aliases.firstNotNullOfOrNull { alias ->
                when {
                    lower == alias || lower == "$alias:" -> Triple(sectionName, alias, "")
                    lower.startsWith("$alias:") -> Triple(sectionName, alias, clean.substringAfter(':').trim())
                    lower.startsWith("$alias -") -> Triple(sectionName, alias, clean.substringAfter('-').trim())
                    lower.startsWith("$alias —") -> Triple(sectionName, alias, clean.substringAfter('—').trim())
                    else -> null
                }
            }
        }
        if (matched != null) {
            current = matched.first
            buckets.getOrPut(current) { mutableListOf() }
            if (matched.third.isNotBlank()) buckets.getValue(current).add(matched.third)
        } else {
            buckets.getOrPut(current) { mutableListOf() }.add(clean)
        }
    }

    val sections = buckets.mapNotNull { (name, rawLines) ->
        if (rawLines.isEmpty()) return@mapNotNull null

        val isDefinition = name == "Definition"
        val isMindMap = name == "Etiology / Causes" || name == "Classification" ||
            name == "Mechanism of Action" || name == "Pathogenesis"
        val isImportant = name == "Important Points"
        val flow = flowSectionFor(name)
        val units = if (isDefinition) rawLines else rawLines.flatMap { line ->
            val arrowParts = line.split(Regex("\\s*(?:→|->|⟶)\\s*")).filter { it.isNotBlank() }
            if (flow && arrowParts.size > 1) arrowParts else splitSentences(line)
        }
        val cleanedUnits = units.map { it.trim().trimStart('•', '-', '*').trim() }.filter { it.isNotBlank() }
        val selected = if (isDefinition || isMindMap || isImportant || name == "Treatment" || name == "Prevention") cleanedUnits
            else selectKeySentences(name, cleanedUnits)
        val finalLines = if (flow) normalizeFlowchart(selected) else selected
        if (finalLines.isEmpty()) null else NoteSection(
            name,
            finalLines,
            flowchart = flow,
            mindMap = isMindMap,
            important = isImportant,
            visualHint = inferVisualHint(name, topic)
        )
    }
    return MedicalNotes(topic.ifBlank { "Medical Notes" }, sections.ifEmpty {
        listOf(NoteSection("Important Points", listOf("No text detected.")))
    })
}

private fun splitSentences(text: String): List<String> {
    val cleaned = text.trim()
    if (cleaned.isBlank()) return emptyList()
    // Split prose into sentence-sized units while retaining the original sentence wording and punctuation.
    val pieces = cleaned.split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9(])"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    return if (pieces.isEmpty()) listOf(cleaned) else pieces
}

private fun selectKeySentences(section: String, sentences: List<String>): List<String> {
    // Offline mode cannot understand medical relevance like Gemini, so keep a small,
    // deterministic selection instead of showing every sentence in long source paragraphs.
    val unique = sentences.map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim() }
    if (unique.size <= 2) return unique
    val priorityWords = when (section) {
        "Etiology / Causes" -> listOf("cause", "due to", "risk factor", "etiology", "because")
        "Clinical Features" -> listOf("characterized", "presents", "symptom", "sign", "feature", "manifest")
        "Investigations" -> listOf("diagnos", "test", "shows", "reveals", "criteria", "level")
        "Treatment" -> listOf("treat", "management", "first line", "drug", "therapy", "indicated")
        "Complications" -> listOf("complication", "failure", "shock", "death", "progress")
        "Prevention" -> listOf("prevent", "prophylaxis", "vaccin", "avoid")
        else -> listOf("is defined", "defined as", "most common", "characterized", "important", "diagnos", "caused by", "leads to", "results in")
    }
    val scored = unique.mapIndexed { index, sentence ->
        var score = 0
        val lower = sentence.lowercase(Locale.ROOT)
        if (priorityWords.any { lower.contains(it) }) score += 4
        if (Regex("\\d").containsMatchIn(sentence)) score += 2
        if (listOf("must", "only", "first-line", "contraindicated", "criterion", "criteria", "hallmark", "pathognomonic").any { lower.contains(it) }) score += 3
        if (sentence.length in 35..180) score += 1
        if (sentence.length > 260) score -= 2
        index to score
    }
    val limit = when (section) {
        "Definition" -> 1
        "Etiology / Causes", "Classification", "Mechanism of Action", "Pathogenesis",
        "Clinical Features", "Investigations", "Treatment", "Complications", "Prevention" -> 3
        else -> 4
    }
    return scored.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
        .take(limit).map { unique[it.first] }
        .sortedBy { unique.indexOf(it) }
}

private val summarizerStopWords = setOf(
    "the", "and", "for", "with", "from", "that", "this", "these", "those", "are", "was", "were",
    "been", "being", "have", "has", "had", "can", "could", "may", "might", "will", "would", "should",
    "into", "onto", "over", "under", "between", "among", "through", "during", "after", "before", "when",
    "where", "which", "while", "who", "whom", "whose", "what", "how", "why", "than", "then", "also",
    "such", "its", "their", "there", "they", "them", "his", "her", "our", "you", "your", "all", "any",
    "not", "but", "per", "each", "other", "more", "most", "some", "many", "much", "very", "often",
    "usually", "generally", "about", "because", "due", "used", "use", "using", "following", "including"
)

private fun normalizeFlowchart(lines: List<String>): List<String> =
    lines.map { it.removeSuffix("↓").trim() }.filter { it.isNotBlank() }

private fun safeFileName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "Medical_Notes" }.take(70)

private fun createPdf(notes: MedicalNotes, visuals: List<MedicalVisual> = emptyList()): ByteArray {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val margin = 42f
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(35, 65, 110)
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(35, 65, 110)
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(35, 35, 35)
        textSize = 10.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    val sectionBackgroundColors = intArrayOf(
        android.graphics.Color.rgb(232, 241, 255),
        android.graphics.Color.rgb(230, 247, 238),
        android.graphics.Color.rgb(255, 242, 217),
        android.graphics.Color.rgb(243, 232, 255),
        android.graphics.Color.rgb(255, 232, 236),
        android.graphics.Color.rgb(228, 247, 248)
    )
    val sectionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val importantBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 213, 128)
        style = Paint.Style.FILL
    }
    val flowNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    val flowArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(52, 82, 130)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    val sectionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(23, 54, 93)
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var canvas: android.graphics.Canvas? = null
    var y = 0f

    fun startPage() {
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page!!.canvas
        y = 48f
        canvas!!.drawText(notes.topic.take(70), margin, y, titlePaint)
        y += 28f
        canvas!!.drawLine(margin, y, pageWidth - margin, y, headingPaint)
        y += 22f
    }
    fun finishPage() {
        page?.let { document.finishPage(it) }
        page = null
        canvas = null
    }
    fun ensureSpace(required: Float) {
        if (y + required > pageHeight - 42f) {
            finishPage()
            startPage()
        }
    }
    fun wrapForWidth(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var line = ""
        words.forEach { word ->
            val candidate = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotBlank()) {
                lines.add(line)
                line = word
            } else line = candidate
        }
        if (line.isNotBlank()) lines.add(line)
        return lines.ifEmpty { listOf("") }
    }

    fun drawWrapped(text: String, paint: Paint, indent: Float = 0f, sectionBg: Int? = null) {
        val maxWidth = pageWidth - margin * 2 - indent
        val lines = wrapForWidth(text, paint, maxWidth)
        lines.forEach { lineText ->
            ensureSpace(18f)
            if (sectionBg != null) {
                sectionBgPaint.color = sectionBg
                canvas!!.drawRoundRect(margin - 4f, y - 12f, pageWidth - margin + 4f, y + 5f, 4f, 4f, sectionBgPaint)
            }
            canvas!!.drawText(lineText, margin + indent, y, paint)
            y += 16f
        }
    }

    fun drawBulletCard(text: String, paint: Paint, backgroundColor: Int, leftBullet: String = "•") {
        val paddingX = 11f
        val lineHeight = 16f
        val maxWidth = pageWidth - margin * 2 - paddingX * 2 - paint.measureText("$leftBullet ")
        val lines = wrapForWidth(text, paint, maxWidth)
        val cardHeight = lines.size * lineHeight + 15f
        ensureSpace(cardHeight + 10f)
        val top = y - 11f
        val bottom = y + cardHeight - 7f
        sectionBgPaint.color = backgroundColor
        canvas!!.drawRoundRect(margin - 4f, top, pageWidth - margin + 4f, bottom, 7f, 7f, sectionBgPaint)
        canvas!!.drawText(leftBullet, margin + paddingX, y + 5f, paint)
        lines.forEachIndexed { index, lineText ->
            canvas!!.drawText(
                lineText,
                margin + paddingX + paint.measureText("$leftBullet "),
                y + 5f + index * lineHeight,
                paint
            )
        }
        y += cardHeight + 7f
    }
    fun drawFlowNode(text: String, color: Int) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.rgb(29, 48, 75)
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val maxTextWidth = pageWidth - margin * 2 - 48f
        val lines = wrapForWidth(text, textPaint, maxTextWidth)
        val lineHeight = 15f
        val nodeHeight = (lines.size * lineHeight + 18f).coerceAtLeast(36f)
        ensureSpace(nodeHeight + 20f)
        val left = margin + 12f
        val right = pageWidth - margin - 12f
        sectionBgPaint.color = color
        canvas!!.drawRoundRect(left, y - 12f, right, y - 12f + nodeHeight, 8f, 8f, sectionBgPaint)
        lines.forEachIndexed { index, nodeLine ->
            val textWidth = textPaint.measureText(nodeLine)
            canvas!!.drawText(nodeLine, (pageWidth - textWidth) / 2f, y + 5f + index * lineHeight, textPaint)
        }
        y += nodeHeight + 2f
    }

    fun drawFlowArrow() {
        ensureSpace(20f)
        val centerX = pageWidth / 2f
        val top = y + 2f
        val bottom = y + 13f
        canvas!!.drawLine(centerX, top, centerX, bottom, flowArrowPaint)
        canvas!!.drawLine(centerX - 4f, bottom - 4f, centerX, bottom, flowArrowPaint)
        canvas!!.drawLine(centerX + 4f, bottom - 4f, centerX, bottom, flowArrowPaint)
        y += 19f
    }
    startPage()
    var visualIndex = 0
    notes.sections.forEachIndexed { sectionIndex, section ->
        val sectionColor = sectionBackgroundColors[sectionIndex % sectionBackgroundColors.size]
        ensureSpace(34f)
        y += 8f
        sectionBgPaint.color = if (section.important) android.graphics.Color.rgb(255, 213, 128) else sectionColor
        canvas!!.drawRoundRect(margin - 5f, y - 14f, pageWidth - margin + 5f, y + 7f, 5f, 5f, sectionBgPaint)
        val headerPaint = if (section.important) titlePaint else sectionTextPaint
        canvas!!.drawText(section.title, margin, y, headerPaint)
        y += if (section.important) 24f else 20f
        if (section.visualHint != null && visualIndex < visuals.size) {
            val visual = visuals[visualIndex++]
            val bitmap = visual.bitmap
            val maxW = pageWidth - margin * 2 - 20f
            val maxH = 130f
            val scale = minOf(maxW / bitmap.width.toFloat(), maxH / bitmap.height.toFloat(), 1f)
            val drawW = bitmap.width * scale
            val drawH = bitmap.height * scale
            ensureSpace(drawH + 25f)
            val left = (pageWidth - drawW) / 2f
            sectionBgPaint.color = sectionBackgroundColors[(sectionIndex + 2) % sectionBackgroundColors.size]
            canvas!!.drawRoundRect(left - 5f, y - 12f, left + drawW + 5f, y + drawH + 8f, 7f, 7f, sectionBgPaint)
            canvas!!.drawBitmap(bitmap, null, android.graphics.RectF(left, y - 7f, left + drawW, y + drawH - 7f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            y += drawH + 5f
            drawWrapped("Image source: Wikimedia Commons — ${visual.title.take(85)}", bodyPaint, indent = 4f)
            y += 5f
        }
        if (section.flowchart) {
            section.lines.forEachIndexed { index, line ->
                drawFlowNode(line, sectionBackgroundColors[index % sectionBackgroundColors.size])
                if (index < section.lines.lastIndex) drawFlowArrow()
            }
            y += 5f
        } else if (section.mindMap && section.lines.isNotEmpty()) {
            drawBulletCard(section.lines.first(), sectionTextPaint, sectionColor, leftBullet = "●")
            section.lines.drop(1).forEach { branch ->
                drawBulletCard(branch, bodyPaint, sectionColor, leftBullet = "↳")
            }
        } else {
            section.lines.forEach { line ->
                val cardColor = if (section.important) android.graphics.Color.rgb(255, 227, 163) else sectionColor
                drawBulletCard(
                    line,
                    if (section.important) headingPaint else bodyPaint,
                    cardColor,
                    leftBullet = if (section.important) "★" else "•"
                )
            }
        }
    }
    finishPage()
    val output = ByteArrayOutputStream()
    document.writeTo(output)
    document.close()
    return output.toByteArray()
}
