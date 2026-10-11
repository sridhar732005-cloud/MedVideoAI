package com.example.medvideoai

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException

private data class MedicalImageAsset(
    val fileName: String,
    val title: String,
    val tags: String
)

private object MedicalImageLibraryStore {
    private const val PREFS = "medical_image_library"
    private const val KEY_ASSETS = "assets"

    fun load(context: Context): List<MedicalImageAsset> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ASSETS, "") ?: ""
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 3) null else MedicalImageAsset(
                decode(parts[0]), decode(parts[1]), decode(parts[2])
            )
        }.filter { File(imageDir(context), it.fileName).isFile }
    }

    @Throws(IOException::class)
    fun import(context: Context, uri: Uri, title: String, tags: String): MedicalImageAsset {
        val dir = imageDir(context)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create image folder")
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extension = when {
            mime.contains("png", true) -> ".png"
            mime.contains("webp", true) -> ".webp"
            else -> ".jpg"
        }
        val fileName = "medical_${System.currentTimeMillis()}$extension"
        val destination = File(dir, fileName)
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("Could not read selected image")
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        val asset = MedicalImageAsset(
            fileName = fileName,
            title = title.trim().ifBlank { "Imported medical image" },
            tags = tags.trim()
        )
        val all = load(context) + asset
        val encoded = all.joinToString("\n") {
            "${encode(it.fileName)}|${encode(it.title)}|${encode(it.tags)}"
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ASSETS, encoded).apply()
        return asset
    }

    fun file(context: Context, asset: MedicalImageAsset): File = File(imageDir(context), asset.fileName)

    private fun imageDir(context: Context) = File(context.filesDir, "medical_images")
    private fun encode(value: String) = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun decode(value: String) = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}

@Composable
fun MedicalImageLibraryPanel() {
    val context = LocalContext.current
    val assets = remember { mutableStateListOf<MedicalImageAsset>().apply { addAll(MedicalImageLibraryStore.load(context)) } }
    var imageTitle by remember { mutableStateOf("") }
    var imageTags by remember { mutableStateOf("") }
    var filterText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val asset = MedicalImageLibraryStore.import(context, uri, imageTitle, imageTags)
                assets.clear()
                assets.addAll(MedicalImageLibraryStore.load(context))
                imageTitle = ""
                imageTags = ""
                message = "Image added to this device's private library."
            } catch (e: Exception) {
                message = "Import failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    val filtered = assets.filter {
        val query = filterText.trim()
        query.isBlank() || it.title.contains(query, true) || it.tags.contains(query, true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Medical Image Library", style = MaterialTheme.typography.titleLarge)
        Text(
            "Gallery-லிருந்து உன் medical images-ஐ import செய்து, title மற்றும் topic keywords சேர்க்கலாம். படங்கள் இந்த app-ன் private storage-ல் சேமிக்கப்படும்.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = imageTitle, onValueChange = { imageTitle = it },
            label = { Text("Image title / படத்தின் பெயர்") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = imageTags, onValueChange = { imageTags = it },
            label = { Text("Medical topic keywords (comma-separated)") },
            placeholder = { Text("COPD, lung, airway, நுரையீரல்") },
            modifier = Modifier.fillMaxWidth(), minLines = 2
        )
        Button(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("Import Image from Gallery")
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = filterText, onValueChange = { filterText = it },
            label = { Text("Search imported images by title or keyword") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Text("Saved images: ${assets.size}", style = MaterialTheme.typography.titleMedium)
        if (filtered.isEmpty()) {
            Text("இன்னும் படங்கள் இல்லை அல்லது பொருத்தமான படம் கிடைக்கவில்லை.", style = MaterialTheme.typography.bodySmall)
        } else {
            filtered.forEach { asset ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val bitmap = remember(asset.fileName) {
                            runCatching { BitmapFactory.decodeFile(MedicalImageLibraryStore.file(context, asset).absolutePath) }.getOrNull()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = asset.title,
                                modifier = Modifier.fillMaxWidth().height(150.dp)
                            )
                        }
                        Text(asset.title, style = MaterialTheme.typography.titleMedium)
                        if (asset.tags.isNotBlank()) Text("Keywords: ${asset.tags}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
