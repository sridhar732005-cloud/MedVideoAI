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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import java.io.ByteArrayOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MedicalNotesScreen() } } }
    }
}

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
    var notes by remember { mutableStateOf<MedicalNotes?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pdfBytes by remember { mutableStateOf<ByteArray?>(null) }
    val context = LocalContext.current
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
            Text("Medical Text → Exam Notes → PDF", style = MaterialTheme.typography.headlineSmall)
            Text("Paste English medical text. Offline extractive summarization selects important sentences from your text only—no translation, textbook lookup, or added medical facts. Definitions are kept verbatim; mechanism/pathogenesis are shown as flowcharts.")
            OutlinedTextField(
                value = topic, onValueChange = { topic = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Topic / title") }, placeholder = { Text("e.g. Hypertension") }
            )
            OutlinedTextField(
                value = sourceText, onValueChange = { sourceText = it; notes = null; status = "" },
                modifier = Modifier.fillMaxWidth(), minLines = 7,
                label = { Text("Paste English medical text") },
                placeholder = { Text("Paste your topic text here in English...") }
            )
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
            ) { Text("Make Short Notes") }
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("Formatting notes...")
            }
            notes?.let { generated ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(generated.topic, style = MaterialTheme.typography.titleLarge)
                        generated.sections.forEach { section ->
                            Text(section.title, style = MaterialTheme.typography.titleMedium)
                            section.lines.forEachIndexed { index, line ->
                                Text(if (section.flowchart && index < section.lines.lastIndex) "$line  ↓" else if (section.flowchart) line else "• $line")
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
                "Offline extractive summarization: selected sentences are copied from your text, not rewritten or supplemented. The Definition section is kept verbatim. Sentence selection can omit context, so review every section before using it for exams.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


private fun containsTamil(text: String): Boolean = text.any { it.code in 0x0B80..0x0BFF }

private fun inferTopic(text: String): String {
    val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    return first.take(70).ifBlank { "Medical Notes" }.removeSuffix(":")
}

private fun formatMedicalNotes(topic: String, raw: String): MedicalNotes {
    val known = listOf(
        "Definition" to listOf("definition", "meaning"),
        "Etiology / Causes" to listOf("etiology", "causes", "cause", "risk factors"),
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
        val selected = if (isDefinition || flow) cleanedUnits else selectKeySentences(name, cleanedUnits)
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
    if (sentences.size <= 4) return sentences
    val wordsBySentence = sentences.map { sentence ->
        Regex("[A-Za-z][A-Za-z0-9'-]{2,}")
            .findAll(sentence.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it !in summarizerStopWords }
            .toList()
    }
    val frequencies = mutableMapOf<String, Int>()
    wordsBySentence.flatten().forEach { word -> frequencies[word] = (frequencies[word] ?: 0) + 1 }
    val sectionBoosts = when (section) {
        "Etiology / Causes" -> setOf("cause", "causes", "due", "because", "risk", "factor", "associated", "secondary")
        "Classification" -> setOf("type", "types", "class", "classified", "stage", "stages")
        "Clinical Features" -> setOf("symptoms", "signs", "presents", "characterized", "features", "pain", "fever")
        "Investigations" -> setOf("test", "tests", "diagnosis", "shows", "reveals", "levels", "criteria")
        "Treatment" -> setOf("treatment", "management", "administer", "given", "therapy", "avoid", "first-line")
        "Complications" -> setOf("complication", "complications", "may", "leads", "result", "results")
        else -> emptySet()
    }
    val scored = sentences.mapIndexed { index, sentence ->
        val words = wordsBySentence[index]
        val termScore = if (words.isEmpty()) 0.0 else words.sumOf { (frequencies[it] ?: 0).toDouble() } / words.size
        val sectionScore = if (words.any { it in sectionBoosts }) 1.2 else 0.0
        val safetyScore = if (Regex("\\b(no|not|never|without|only|except|contraindicated|increased|decreased|acute|chronic|severe|normal|abnormal)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sentence)) 1.0 else 0.0
        val positionScore = if (index == 0) 1.0 else 0.0
        val lengthPenalty = if (sentence.length > 260) 0.8 else 0.0
        index to (termScore + sectionScore + safetyScore + positionScore - lengthPenalty)
    }
    val targetCount = maxOf(4, kotlin.math.ceil(sentences.size * 0.65).toInt()).coerceAtMost(sentences.size)
    val selectedIndices = scored.sortedByDescending { it.second }.take(targetCount).map { it.first }.toSet()
    return sentences.filterIndexed { index, _ -> index in selectedIndices }
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
    fun drawWrapped(text: String, paint: Paint, indent: Float = 0f) {
        val maxWidth = pageWidth - margin * 2 - indent
        val words = text.split(Regex("\\s+"))
        var line = ""
        words.forEach { word ->
            val candidate = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotBlank()) {
                ensureSpace(15f)
                canvas!!.drawText(line, margin + indent, y, paint)
                y += 14f
                line = word
            } else line = candidate
        }
        if (line.isNotBlank()) {
            ensureSpace(15f)
            canvas!!.drawText(line, margin + indent, y, paint)
            y += 14f
        }
    }

    startPage()
    notes.sections.forEach { section ->
        ensureSpace(24f)
        y += 7f
        canvas!!.drawText(section.title, margin, y, headingPaint)
        y += 18f
        section.lines.forEachIndexed { index, line ->
            val text = when {
                section.flowchart && index < section.lines.lastIndex -> "${line}  ↓"
                section.flowchart -> line
                else -> "•  $line"
            }
            drawWrapped(text, bodyPaint, if (section.flowchart) 12f else 0f)
        }
    }
    finishPage()
    val output = ByteArrayOutputStream()
    document.writeTo(output)
    document.close()
    return output.toByteArray()
}
