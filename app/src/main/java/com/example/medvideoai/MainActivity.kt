package com.example.medvideoai

import android.os.Bundle
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
                Text("Create Free Animation Preview")
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
                            "This is a locally animated preview, not an exported MP4 file. It uses no paid API. MP4 export and generated narration are not included in this free-preview version.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Text(
                "Free mode: no API key is used by this screen. The animation is a visual template, not a diagnosis or a substitute for verified medical teaching content.",
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
