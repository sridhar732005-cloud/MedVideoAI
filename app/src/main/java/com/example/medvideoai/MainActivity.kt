package com.example.medvideoai

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
    GeminiModelOption("Auto — try fastest available", "auto", "Tries lighter models first and falls back if unavailable"),
    GeminiModelOption("Gemini 3.5 Flash-Lite — Fast / light", "gemini-3.5-flash-lite", "Lower-latency option when enabled for your API key"),
    GeminiModelOption("Gemini 3.6 Flash — Balanced", "gemini-3.6-flash", "Balance of speed and answer quality"),
    GeminiModelOption("Gemini 3.8 Flash — Quality", "gemini-3.8-flash", "Higher-capability option when enabled for your API key")
)

private data class NoteSection(val title: String, val lines: List<String>, val flowchart: Boolean = false)
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
    var topic by remember { mutableStateOf("") }
    var sourceText by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(geminiModelOptions.first()) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf(geminiModelOptions) }
    var notes by remember { mutableStateOf<MedicalNotes?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pdfBytes by remember { mutableStateOf<ByteArray?>(null) }
    val context = LocalContext.current
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
                    topic = ""; sourceText = ""; apiKey = ""; selectedModel = geminiModelOptions.first()
                    availableModels = geminiModelOptions; modelMenuExpanded = false; notes = null
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
                            status = "Model list refreshed: ${models.size} Gemini model(s) support text generation for this API key. Free quota varies by account."
                        } catch (e: Exception) {
                            status = "Could not refresh model list: ${e.message ?: "unknown error"}. Existing choices are still available."
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("Refresh models available to my API key") }
            Text("AI mode sends the pasted text to Google Gemini only after you tap the AI button. The prompt asks it to use only your text, but outputs can still be wrong; check every medical detail. The offline button never sends text anywhere. The API key is held only in screen memory and is not saved by this app.", style = MaterialTheme.typography.bodySmall)
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
                enabled = sourceText.isNotBlank() && apiKey.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    status = "Sending text for AI summarization..."
                    val input = sourceText.trim()
                    val title = topic.trim().ifBlank { inferTopic(input) }
                    scope.launch {
                        try {
                            val summarized = withContext(Dispatchers.IO) {
                                summarizeWithGemini(apiKey, title, input, selectedModel.id)
                            }
                            notes = formatMedicalNotes(title, restoreExactDefinition(title, input, summarized))
                            status = "AI summary received. Compare the preview with your source before saving."
                        } catch (e: Exception) {
                            status = "AI summary failed: ${e.message ?: "unknown error"}. You can still use Offline Short Notes."
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
            notes?.let { generated ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(generated.topic, style = MaterialTheme.typography.titleLarge)
                        generated.sections.forEachIndexed { sectionIndex, section ->
                            val sectionColors = listOf(
                                Color(0xFFE8F1FF), Color(0xFFE6F7EE), Color(0xFFFFF2D9),
                                Color(0xFFF3E8FF), Color(0xFFFFE8EC), Color(0xFFE4F7F8)
                            )
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(sectionColors[sectionIndex % sectionColors.size], RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(section.title, style = MaterialTheme.typography.titleMedium, color = Color(0xFF17365D))
                                section.lines.forEachIndexed { index, line ->
                                    Text(
                                        if (section.flowchart && index < section.lines.lastIndex) "$line  ↓"
                                        else if (section.flowchart) line else "• $line",
                                        color = Color(0xFF202124)
                                    )
                                }
                            }
                        }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        pdfBytes = createPdf(generated)
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
            throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "HTTP $statusCode")
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
            GeminiModelOption(displayName, id, "Available to this API key; free quota is not guaranteed")
        }.distinctBy { it.id }.sortedBy { it.label.lowercase(Locale.ROOT) }
        if (options.isEmpty()) throw IllegalStateException("No Gemini models supporting generateContent were returned")
        return options
    } finally {
        connection.disconnect()
    }
}

private fun summarizeWithGemini(apiKey: String, topic: String, sourceText: String, modelId: String): String {
    if (modelId == "auto") {
        val candidates = listOf("gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash")
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                return summarizeWithGemini(apiKey, topic, sourceText, candidate)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("Auto mode tried the fast, balanced, and quality models but none succeeded. Last error: ${lastError?.message ?: "unknown error"}")
    }
    val prompt = """
        You are formatting a medical student's notes.
        Use ONLY the SOURCE TEXT below. Do not use outside sources, web browsing, or facts from your general knowledge.
        Do not translate. Do not invent, correct, or supplement medical facts.
        Preserve every definition verbatim exactly as it appears in the source (copy the definition wording and punctuation; do not paraphrase it).
        Condense other sections into concise exam notes using only source-supported statements.
        Keep causes/etiology as short bullet points. Preserve numbers, units, negations, uncertainty, qualifiers, drug names, doses, contraindications, and exceptions.
        For Mechanism of Action and Pathogenesis, write a step-by-step sequence joined by ->, without adding steps.
        Keep headings when present. If a fact is unclear, retain its original wording instead of guessing.
        Return plain text only, with no introduction or conclusion.
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
        val generationConfig = JSONObject().put("maxOutputTokens", 8192)
        // thinkingLevel is only sent to Gemini 3.x models; older model families may reject it.
        if (modelId.startsWith("gemini-3.")) {
            generationConfig.put(
                "thinkingConfig",
                JSONObject().put("thinkingLevel", if (modelId.contains("lite")) "low" else "high")
            )
        }
        val requestBody = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
            .put("generationConfig", generationConfig)
        connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (statusCode !in 200..299) {
            val detail = runCatching { JSONObject(responseText).getJSONObject("error").optString("message") }.getOrNull()
            throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "HTTP $statusCode")
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

private fun inferTopic(text: String): String {
    val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    return first.take(70).ifBlank { "Medical Notes" }.removeSuffix(":")
}

private fun formatMedicalNotes(topic: String, raw: String): MedicalNotes {
    val known = listOf(
        "Definition" to listOf("definition", "meaning"),
        "Etiology / Causes" to listOf("etiology / causes", "etiology", "causes", "cause", "risk factors"),
        "Classification" to listOf("classification", "types", "type"),
        "Mechanism of Action" to listOf("mechanism of action", "mechanism", "pathophysiology"),
        "Pathogenesis" to listOf("pathogenesis"),
        "Clinical Features" to listOf("clinical features", "symptoms", "signs", "presentation"),
        "Investigations" to listOf("investigations", "diagnosis", "tests"),
        "Treatment" to listOf("treatment", "management", "therapy"),
        "Complications" to listOf("complications", "sequelae"),
        "Prevention" to listOf("prevention", "prophylaxis")
    )
    val buckets = linkedMapOf<String, MutableList<String>>()
    var current = "Important Points"
    buckets[current] = mutableListOf()

    raw.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { originalLine ->
        val clean = originalLine.trimStart('#', '*', '-', '•', ' ', '\t')
            .replace(Regex("^\\d+[.)]\\s*"), "").trim()
        if (clean.isBlank()) return@forEach
        val lower = clean.lowercase(Locale.ROOT)
        val matched = known.firstNotNullOfOrNull { (sectionName, aliases) ->
            aliases.firstNotNullOfOrNull { alias ->
                when {
                    lower == alias || lower == "$alias:" -> Triple(sectionName, alias, "")
                    lower.startsWith("$alias:") -> Triple(sectionName, alias, clean.substringAfter(':').trim())
                    lower.startsWith("$alias -") -> Triple(sectionName, alias, clean.substringAfter('-').trim())
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

        // Keep every supplied definition line exactly as written after removing the heading/bullet marker.
        // All other sections use extractive sentence selection: no new words or facts are generated.
        val isDefinition = name == "Definition"
        val flow = name == "Mechanism of Action" || name == "Pathogenesis"
        val units = if (isDefinition) {
            rawLines
        } else {
            rawLines.flatMap { line ->
                val arrowParts = line.split(Regex("\\s*(?:→|->|⟶)\\s*")).filter { it.isNotBlank() }
                if (arrowParts.size > 1) arrowParts
                else splitSentences(line)
            }
        }
        val cleanedUnits = units.map { it.trim().trimStart('•', '-', '*').trim() }.filter { it.isNotBlank() }
        val preserveStructuredPoints = rawLines.size > 1 && rawLines.all { it.length <= 180 }
        val preserveAllSectionPoints = name == "Etiology / Causes" || name == "Classification" || name == "Treatment" || name == "Prevention"
        val selected = if (isDefinition || flow || preserveStructuredPoints || preserveAllSectionPoints) cleanedUnits else selectKeySentences(name, cleanedUnits)
        val finalLines = if (flow) normalizeFlowchart(selected) else selected
        if (finalLines.isEmpty()) null else NoteSection(name, finalLines, flow)
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
    // Keep every detected sentence rather than trimming notes to a fraction.
    // Practical limits are device memory and upstream AI request/token quotas.
    return sentences
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

private fun createPdf(notes: MedicalNotes): ByteArray {
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
    fun drawWrapped(text: String, paint: Paint, indent: Float = 0f, sectionBg: Int? = null) {
        val maxWidth = pageWidth - margin * 2 - indent
        val words = text.split(Regex("\\s+"))
        var line = ""
        fun drawLine(lineText: String) {
            ensureSpace(17f)
            if (sectionBg != null) {
                sectionBgPaint.color = sectionBg
                canvas!!.drawRoundRect(margin - 5f, y - 12f, pageWidth - margin + 5f, y + 4f, 4f, 4f, sectionBgPaint)
            }
            canvas!!.drawText(lineText, margin + indent, y, paint)
            y += 15f
        }
        words.forEach { word ->
            val candidate = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotBlank()) {
                drawLine(line)
                line = word
            } else line = candidate
        }
        if (line.isNotBlank()) drawLine(line)
    }

    startPage()
    notes.sections.forEachIndexed { sectionIndex, section ->
        val sectionColor = sectionBackgroundColors[sectionIndex % sectionBackgroundColors.size]
        ensureSpace(28f)
        y += 8f
        sectionBgPaint.color = sectionColor
        canvas!!.drawRoundRect(margin - 5f, y - 14f, pageWidth - margin + 5f, y + 6f, 5f, 5f, sectionBgPaint)
        canvas!!.drawText(section.title, margin, y, sectionTextPaint)
        y += 20f
        section.lines.forEachIndexed { index, line ->
            val text = when {
                section.flowchart && index < section.lines.lastIndex -> "$line  ↓"
                section.flowchart -> line
                else -> "•  $line"
            }
            drawWrapped(text, bodyPaint, if (section.flowchart) 12f else 0f, sectionColor)
        }
    }
    finishPage()
    val output = ByteArrayOutputStream()
    document.writeTo(output)
    document.close()
    return output.toByteArray()
}
