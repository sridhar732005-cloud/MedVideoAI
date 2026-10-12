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
        val settings = getSharedPreferences("mednotes_settings", android.content.Context.MODE_PRIVATE)
        val darkModeState = mutableStateOf(settings.getBoolean("dark_mode", false))
        setContent {
            val isDarkMode by darkModeState
            MaterialTheme(
                colorScheme = if (isDarkMode) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) {
                    MedicalNotesScreen(
                        isDarkMode = isDarkMode,
                        onDarkModeChange = { enabled ->
                            darkModeState.value = enabled
                            settings.edit().putBoolean("dark_mode", enabled).apply()
                        }
                    )
                }
            }
        }
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
private fun MedicalNotesScreen(isDarkMode: Boolean, onDarkModeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val draftPrefs = remember { context.getSharedPreferences("mednotes_draft", android.content.Context.MODE_PRIVATE) }
    var topic by remember { mutableStateOf(draftPrefs.getString("topic", "").orEmpty()) }
    var sourceText by remember { mutableStateOf(draftPrefs.getString("sourceText", "").orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(geminiModelOptions.first()) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf(geminiModelOptions) }
    var notes by remember { mutableStateOf(draftPrefs.getString("savedNotes", null)?.let { decodeMedicalNotes(it) }) }
    var generatedTopicNotes by remember { mutableStateOf<List<MedicalNotes>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pdfBytes by remember { mutableStateOf<ByteArray?>(null) }
    var imagePreviewMode by remember { mutableStateOf(false) }
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
                val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Could not decode image")
                val analysisBitmap = resizeForFastAnalysis(originalBitmap)
                val encodedStream = ByteArrayOutputStream()
                analysisBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, encodedStream)
                selectedImage = SelectedMedicalImage(
                    uri = uri,
                    mimeType = "image/jpeg",
                    base64Data = Base64.encodeToString(encodedStream.toByteArray(), Base64.NO_WRAP),
                    previewBitmap = originalBitmap
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
                    val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("Could not decode saved image")
                    val analysisBitmap = resizeForFastAnalysis(originalBitmap)
                    val encodedStream = ByteArrayOutputStream()
                    analysisBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, encodedStream)
                    SelectedMedicalImage(
                        uri = uri,
                        mimeType = "image/jpeg",
                        base64Data = Base64.encodeToString(encodedStream.toByteArray(), Base64.NO_WRAP),
                        previewBitmap = originalBitmap
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

    var settingsMenuExpanded by remember { mutableStateOf(false) }
    var settingsSubmenuExpanded by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                Box {
                    TextButton(onClick = { settingsMenuExpanded = true }) { Text("☰") }
                    DropdownMenu(
                        expanded = settingsMenuExpanded,
                        onDismissRequest = { settingsMenuExpanded = false; settingsSubmenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { settingsSubmenuExpanded = !settingsSubmenuExpanded }
                        )
                        if (settingsSubmenuExpanded) {
                            DropdownMenuItem(
                                text = { Text("☀ Light Mode", color = if (!isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                onClick = { onDarkModeChange(false); settingsMenuExpanded = false; settingsSubmenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("☾ Dark Mode", color = if (isDarkMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                onClick = { onDarkModeChange(true); settingsMenuExpanded = false; settingsSubmenuExpanded = false }
                            )
                        }
                    }
                }
            },
            title = { Text("Med Notes") }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Create Medical Notes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    topic = ""; sourceText = ""; selectedImage = null; apiKey = ""; selectedModel = geminiModelOptions.first()
                    availableModels = geminiModelOptions; modelMenuExpanded = false; notes = null
                    draftPrefs.edit().clear().apply()
                    busy = false; status = ""; pdfBytes = null
                }) { Text("↻ Refresh") }
            }
            Text("Paste medical text or choose an image to create exam-ready notes.")
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
                        Text("Selected image", style = MaterialTheme.typography.titleSmall)
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
            Button(
                enabled = sourceText.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    status = "Selecting key sentences from your text..."
                    val input = sourceText.trim()
                    val title = topic.trim().ifBlank { inferTopic(input) }
                    notes = formatMedicalNotes(title, input)
                    generatedTopicNotes = listOfNotNull(notes)
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
                            val parsedTopics = splitMedicalTopics(summarized, title, input.isBlank(), input)
                            val safeTopics = if (parsedTopics.isNotEmpty()) parsedTopics else {
                                listOf(formatMedicalNotes(title, if (input.isBlank()) summarized else restoreExactDefinition(title, input, summarized)))
                            }
                            generatedTopicNotes = safeTopics
                            notes = safeTopics.first()
                            topicVisuals = emptyList()
                            visualLookupStatus = ""
                            visualLookupBusy = false
                            status = "AI notes ready using only your supplied text/image. Review before saving the PDF."
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
            if (notes != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { imagePreviewMode = true }
                    ) { Text("Image") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val selected = notes
                            if (selected != null) {
                                pdfBytes = createInfographicPdf(selected)
                                savePdf.launch(safeFileName(selected.topic) + "_Infographic.pdf")
                            }
                        }
                    ) { Text("PDF") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { imagePreviewMode = false }
                    ) { Text("Notes") }
                }
            }
            notes?.let { generated ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(onClick = { notes = null; imagePreviewMode = false; status = "Returned to Home. Generated notes are preserved." }) { Text("← Home") }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                TextButton(onClick = { imagePreviewMode = !imagePreviewMode }) {
                                    Text(if (imagePreviewMode) "Text Preview" else "Image Preview")
                                }
                                Text("Notes Preview", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (generatedTopicNotes.size > 1) {
                            Text("Topics found in the reference image", style = MaterialTheme.typography.titleSmall)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                generatedTopicNotes.forEachIndexed { topicIndex, topicNote ->
                                    Button(
                                        onClick = { notes = topicNote },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = if (topicNote.topic == generated.topic) Color(0xFF174A82) else Color(0xFFE5EDF7),
                                            contentColor = if (topicNote.topic == generated.topic) Color.White else Color(0xFF17365D)
                                        )
                                    ) {
                                        Text("${topicIndex + 1}. ${topicNote.topic}")
                                    }
                                }
                            }
                        }
                        if (imagePreviewMode) {
                            val infographic = remember(generated) { createInfographicBitmap(generated) }
                            Image(
                                bitmap = infographic.asImageBitmap(),
                                contentDescription = "Single-image medical infographic preview",
                                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                            )
                            Text("Single-image infographic • Scroll to read; use Text Preview to return to section cards.", style = MaterialTheme.typography.bodySmall)
                        } else {
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
                                if (section.title.contains("differentiation", ignoreCase = true) || section.lines.any { it.count { ch -> ch == '|' } >= 1 }) {
                                    section.lines.forEachIndexed { rowIndex, line ->
                                        val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                                        if (cells.size >= 2) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                cells.forEachIndexed { cellIndex, cell ->
                                                    Card(
                                                        modifier = Modifier.weight(1f),
                                                        shape = RoundedCornerShape(6.dp),
                                                        colors = androidx.compose.material3.CardDefaults.cardColors(
                                                            containerColor = if (rowIndex == 0) Color(0xFFD5E7FF) else sectionColors[(rowIndex + cellIndex + sectionIndex) % sectionColors.size]
                                                        )
                                                    ) {
                                                        Text(
                                                            cell,
                                                            modifier = Modifier.fillMaxWidth().padding(7.dp),
                                                            style = if (rowIndex == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                                                            color = Color(0xFF203047)
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            Text(line, color = Color(0xFF202124), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                } else if (section.flowchart) {
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
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        pdfBytes = if (imagePreviewMode) createInfographicPdf(generated) else createPdf(generated, topicVisuals)
                        savePdf.launch(safeFileName(generated.topic) + if (imagePreviewMode) "_Infographic.pdf" else ".pdf")
                    }
                ) { Text(if (imagePreviewMode) "Save Infographic PDF" else "Save PDF") }
            }
            if (notes != null && status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}


private fun resizeForFastAnalysis(source: android.graphics.Bitmap, maxDimension: Int = 1800): android.graphics.Bitmap {
    val largest = maxOf(source.width, source.height)
    if (largest <= maxDimension) return source
    val scale = maxDimension.toFloat() / largest.toFloat()
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    return android.graphics.Bitmap.createScaledBitmap(source, width, height, true)
}

private fun containsTamil(text: String): Boolean = text.any { it.code in 0x0B80..0x0BFF }

private fun restoreExactDefinition(topic: String, sourceText: String, summaryText: String): String {
    val exactDefinition = formatMedicalNotes(topic, sourceText).sections
        .firstOrNull { it.title == "Definition" } ?: return summaryText
    val lines = summaryText.lines().toMutableList()
    val definitionHeading = Regex("^\\s*(?:#+\\s*)?definition\\s*:?(?:\\s+.*)?$", RegexOption.IGNORE_CASE)
    val start = lines.indexOfFirst { definitionHeading.matches(it.trim().replace(Regex("[*_\\\\`]+"), "")) }
    val sectionHeading = Regex(
        "^\\s*(?:#+\\s*)?(definition|etiology(?:\\s*/\\s*causes)?|causes?|risk factors|classification|types?|mechanism(?: of action)?|pathophysiology|pathogenesis|clinical features|clinical presentation|symptoms|signs|investigations|diagnosis|tests|treatment(?:\\s*/\\s*management)?|management|therapy|complications|prevention|prophylaxis|important points|high-yield points|key points)\\s*:?\\s*$",
        RegexOption.IGNORE_CASE
    )
    if (start < 0) {
        return "Definition:\n" + exactDefinition.lines.joinToString("\n") + "\n\n" + summaryText
    }
    var end = lines.size
    for (i in (start + 1) until lines.size) {
        val normalizedHeading = lines[i].trim().replace(Regex("[*_\\\\`]+"), "")
        if (sectionHeading.matches(normalizedHeading) && !definitionHeading.matches(normalizedHeading)) {
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
            .take(2)
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
    for (attempt in 0..1) {
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
            // Keep retries short so image notes do not appear to hang.
            val delayMs = 400L + (0L..200L).random()
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
        GOAL: make exam notes ONLY from the user-provided text and/or selected image. Never search the internet, consult other books, add external references, or include unrelated illustrations.
        LENGTH AND COVERAGE: make medium-short, easy-to-understand exam notes. Preserve every source-listed point in the same order and keep the original count (5 symptoms means 5 concise symptom points; 10 causes means 10 cause points; 4 tests means those same 4 tests). Do not copy long source paragraphs word-for-word except the definition when legible. Rewrite explanations in simpler everyday English without changing medical meaning. Do not combine separate symptoms/causes into one point or omit any point.
        SOURCE ACCURACY: use only information visible/readable in the supplied image or pasted text. Keep a readable definition verbatim. Never invent doses, lab values, treatments, causes, symptoms, or unreadable details. Under Symptoms and Clinical Features, write each symptom/sign as one short line using simple wording; add a very brief "why it happens" explanation only when the source supports it. Keep technical terms and test names accurate, but simplify their explanation.
        INPUT ANALYSIS: inspect the complete image/text, including title, headings, subheadings, numbering, bullets, tables and diagram labels. Preserve the exact main title and each heading exactly as printed, including singular/plural, spelling, order and hierarchy. Keep "Symptoms" as "Symptoms" and never rename it to "Clinical Features"; do not rename Causes, Etiology, Stages, Classification, Investigations or Tests. Preserve every readable listed item and its order. Mark only unreadable material [unclear in source] rather than guessing.
        MULTIPLE MAIN TOPICS: if the supplied image/text contains two or more distinct major medical topic headings (for example Respiratory Failure and Pneumonia), split them into separate independent topic outputs. Start each topic with exactly this delimiter on its own line: === TOPIC: exact main heading ===. Then include only that topics own source headings and points until the next delimiter. Preserve the exact main heading and do not mix points between topics. If there is only one main topic, still use one delimiter. Do not treat subheadings such as Definition, Causes, Symptoms, Investigations, or Treatment as separate main topics.
        OUTPUT RULES: after each topic delimiter, copy headings exactly, but simplify the explanatory sentences under them; do not copy full paragraphs verbatim (except Definition). Preserve all points, numbering and sequence. Do not add external facts, references or unrelated headings. For a source-provided differentiation/comparison, use rows formatted Feature | Topic A | Topic B and simplify cell wording without changing meaning. For classification, keep exact type names and simplify explanations. For pathogenesis/mechanism, keep every source step in order and show separate boxes connected by arrows.
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


private fun splitMedicalTopics(response: String, fallbackTopic: String, imageOnly: Boolean, sourceText: String): List<MedicalNotes> {
    val marker = Regex("^\\s*===\\s*TOPIC:\\s*(.+?)\\s*===\\s*$", RegexOption.IGNORE_CASE)
    val blocks = mutableListOf<Pair<String, MutableList<String>>>()
    var currentTitle: String? = null
    var currentLines = mutableListOf<String>()
    response.lines().forEach { line ->
        val match = marker.matchEntire(line)
        if (match != null) {
            if (currentTitle != null) blocks.add(currentTitle!! to currentLines)
            currentTitle = match.groupValues[1].trim()
            currentLines = mutableListOf()
        } else if (currentTitle != null) {
            currentLines.add(line)
        }
    }
    if (currentTitle != null) blocks.add(currentTitle!! to currentLines)
    if (blocks.isEmpty()) {
        val lines = response.lines()
        val title = if (imageOnly) {
            lines.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("#")?.trim()?.trimEnd(':')
                ?.takeIf { it.length in 2..100 && !it.endsWith(".") } ?: fallbackTopic
        } else fallbackTopic
        val body = if (imageOnly) lines.dropWhile { it.isBlank() }.drop(1).joinToString("\n").trim() else response
        return listOf(formatMedicalNotes(title, body))
    }
    return blocks.mapNotNull { (title, lines) ->
        val body = lines.joinToString("\n").trim()
        if (body.isBlank()) null else formatMedicalNotes(title, if (imageOnly) body else restoreExactDefinition(title, sourceText, body))
    }.distinctBy { it.topic.trim().lowercase(Locale.ROOT) }
}

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
    // Unstructured/plain input is not automatically treated as high-priority Important Points.
    // If headings are detected, their own buckets will be used instead.
    var current = "Key Points"
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
            // Keep the heading label as the source/Gemini wrote it; use canonical meaning only for styling.
            current = clean.substringBefore(':').substringBefore(" -").substringBefore(" —").trim()
                .ifBlank { matched.first }
            buckets.getOrPut(current) { mutableListOf() }
            if (matched.third.isNotBlank()) buckets.getValue(current).add(matched.third)
        } else if (
            clean.endsWith(":") && clean.length <= 55 &&
            !clean.startsWith("http", ignoreCase = true)
        ) {
            current = clean.removeSuffix(":").trim()
            buckets.getOrPut(current) { mutableListOf() }
        } else {
            buckets.getOrPut(current) { mutableListOf() }.add(clean)
        }
    }

    val sections = buckets.mapNotNull { (name, rawLines) ->
        if (rawLines.isEmpty()) return@mapNotNull null

        val normalizedName = name.lowercase(Locale.ROOT).trim()
        val isDefinition = normalizedName == "definition" || normalizedName == "meaning"
        val isMindMap = normalizedName in setOf(
            "etiology / causes", "etiology", "causes", "cause", "classification", "types", "type",
            "mechanism of action", "mechanism", "pathogenesis", "pathophysiology"
        )
        val isImportant = normalizedName in setOf("important points", "high-yield points", "key points", "important")
        val flow = normalizedName in setOf("mechanism of action", "mechanism", "pathogenesis", "pathophysiology")
        val units = if (isDefinition) rawLines else rawLines.flatMap { line ->
            val arrowParts = line.split(Regex("\\s*(?:→|->|⟶)\\s*")).filter { it.isNotBlank() }
            if (flow && arrowParts.size > 1) arrowParts else splitSentences(line)
        }
        val cleanedUnits = units.map { it.trim().trimStart('•', '-', '*').trim() }.filter { it.isNotBlank() }
        val preserveAll = isDefinition || isMindMap || isImportant ||
            normalizedName.contains("treatment") || normalizedName.contains("management") ||
            normalizedName.contains("prevention") || normalizedName.contains("symptom") ||
            normalizedName.contains("clinical") || normalizedName.contains("sign") ||
            normalizedName.contains("stage") || normalizedName.contains("investigation") ||
            normalizedName.contains("test") || normalizedName.contains("classification") ||
            normalizedName.contains("cause") || normalizedName.contains("etiology") ||
            normalizedName.contains("complication") ||
            normalizedName.contains("differentiation") || normalizedName.contains("comparison")
        val selected = if (preserveAll) cleanedUnits else selectKeySentences(name, cleanedUnits)
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


private fun createInfographicBitmap(notes: MedicalNotes): android.graphics.Bitmap {
    val width = 1600
    val margin = 36f
    val gap = 20f
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textSize = 48f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(20, 48, 85); textSize = 31f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(25, 35, 50); textSize = 27f
    }
    val colors = intArrayOf(
        android.graphics.Color.rgb(255, 232, 236), android.graphics.Color.rgb(220, 241, 255),
        android.graphics.Color.rgb(231, 249, 231), android.graphics.Color.rgb(255, 244, 207),
        android.graphics.Color.rgb(241, 231, 255), android.graphics.Color.rgb(224, 247, 246)
    )
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        var line = ""
        text.split(Regex("\\s+")).forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                result.add(line); line = word
            } else line = candidate
        }
        if (line.isNotBlank()) result.add(line)
        return result.ifEmpty { listOf("") }
    }
    var height = 130f
    val contentWidth = width - margin * 2 - 36f
    notes.sections.forEachIndexed { index, section ->
        height += 30f + 48f
        height += section.lines.fold(0f) { total, lineText -> total + wrap(lineText, bodyPaint, contentWidth - 36f).size.toFloat() * 35f + 12f }
        if (section.flowchart && section.lines.size > 1) height += (section.lines.size - 1) * 28f
        height += gap
    }
    height = height.coerceAtLeast(600f)
    val bitmap = android.graphics.Bitmap.createBitmap(width, height.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(250, 252, 255))
    fillPaint.color = android.graphics.Color.rgb(15, 61, 119)
    canvas.drawRoundRect(margin, 20f, width - margin, 105f, 20f, 20f, fillPaint)
    canvas.drawText(notes.topic.uppercase(Locale.ROOT).take(58), width / 2f, 77f, titlePaint)
    var y = 130f
    notes.sections.forEachIndexed { index, section ->
        val color = if (section.important) android.graphics.Color.rgb(255, 224, 153) else colors[index % colors.size]
        val left = margin
        val right = width - margin
        val titleLines = wrap(section.title, headingPaint, contentWidth)
        val lineItems = section.lines.map { wrap(it, bodyPaint, contentWidth - 40f) }
        val cardHeight = 28f + titleLines.size * 38f + 14f +
            lineItems.sumOf { it.size * 35 + 12 }.toFloat() +
            if (section.flowchart && section.lines.size > 1) (section.lines.size - 1) * 28f else 0f
        fillPaint.color = color
        canvas.drawRoundRect(left, y, right, y + cardHeight, 18f, 18f, fillPaint)
        borderPaint.color = android.graphics.Color.rgb(105, 145, 190)
        canvas.drawRoundRect(left, y, right, y + cardHeight, 18f, 18f, borderPaint)
        var textY = y + 42f
        titleLines.forEach { line ->
            canvas.drawText(line, left + 24f, textY, headingPaint); textY += 38f
        }
        textY += 5f
        section.lines.forEachIndexed { lineIndex, line ->
            if (section.flowchart) {
                fillPaint.color = colors[(index + lineIndex + 1) % colors.size]
                val nodeLines = wrap(line, bodyPaint, contentWidth - 70f)
                val nodeH = nodeLines.size * 35f + 18f
                canvas.drawRoundRect(left + 20f, textY - 24f, right - 20f, textY + nodeH - 12f, 12f, 12f, fillPaint)
                nodeLines.forEach { canvas.drawText(it, left + 38f, textY, bodyPaint); textY += 35f }
                textY += 14f
                if (lineIndex < section.lines.lastIndex) {
                    val cx = width / 2f
                    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = android.graphics.Color.rgb(30, 80, 140); strokeWidth = 4f
                    }
                    canvas.drawLine(cx, textY - 4f, cx, textY + 12f, arrowPaint)
                    canvas.drawText("↓", cx + 10f, textY + 22f, bodyPaint)
                    textY += 28f
                }
            } else {
                val bullet = if (section.important) "★ " else "• "
                val wrapped = wrap(bullet + line, bodyPaint, contentWidth - 35f)
                wrapped.forEach { canvas.drawText(it, left + 24f, textY, bodyPaint); textY += 35f }
                textY += 12f
            }
        }
        y += cardHeight + gap
    }
    return bitmap
}

private fun createInfographicPdf(notes: MedicalNotes): ByteArray {
    val bitmap = createInfographicBitmap(notes)
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create())
    page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    document.finishPage(page)
    val output = ByteArrayOutputStream()
    document.writeTo(output)
    document.close()
    bitmap.recycle()
    return output.toByteArray()
}

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
        textSize = 11.5f
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
        val lineHeight = 17f
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
    fun drawComparisonRow(text: String, backgroundColor: Int) {
        val cells = text.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (cells.size < 2) {
            drawBulletCard(text, bodyPaint, backgroundColor)
            return
        }
        val cellPaint = Paint(bodyPaint).apply { textSize = 10.5f }
        val cellWidth = (pageWidth - margin * 2 - 8f) / cells.size
        val horizontalPadding = 6f
        val lineHeight = 14f
        val wrapped = cells.map { wrapForWidth(it, cellPaint, cellWidth - horizontalPadding * 2) }
        val rowHeight = (wrapped.maxOf { it.size } * lineHeight + 14f).coerceAtLeast(32f)
        ensureSpace(rowHeight + 8f)
        cells.indices.forEach { index ->
            val left = margin + index * cellWidth
            sectionBgPaint.color = backgroundColor
            canvas!!.drawRoundRect(left, y - 10f, left + cellWidth - 3f, y - 10f + rowHeight, 4f, 4f, sectionBgPaint)
            wrapped[index].forEachIndexed { lineIndex, value ->
                canvas!!.drawText(value, left + horizontalPadding, y + 5f + lineIndex * lineHeight, cellPaint)
            }
        }
        y += rowHeight + 7f
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
        y += 28f
    }
    startPage()
    var visualIndex = 0
    notes.sections.forEachIndexed { sectionIndex, section ->
        val sectionColor = sectionBackgroundColors[sectionIndex % sectionBackgroundColors.size]
        val reserveSpace = if (section.visualHint != null && visualIndex < visuals.size) 220f
            else if (section.flowchart) 100f else 80f
        ensureSpace(reserveSpace)
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
        if (section.title.contains("differentiation", ignoreCase = true) || section.lines.any { it.count { ch -> ch == '|' } >= 1 }) {
            section.lines.forEachIndexed { index, line ->
                drawComparisonRow(line, sectionBackgroundColors[(sectionIndex + index) % sectionBackgroundColors.size])
            }
        } else if (section.flowchart) {
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
