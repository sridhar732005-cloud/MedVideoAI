package com.example.medvideoai

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
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicalVideoScreen() {
    var topic by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("Tamil") }
    var duration by remember { mutableStateOf("1 minute") }
    var style by remember { mutableStateOf("Medical animation") }
    var status by remember { mutableStateOf("") }

    val topics = listOf(
        "COPD", "Asthma", "Meniere disease",
        "Pulmonary hypertension", "Anatomy", "Pathology",
        "ENT", "Ophthalmology", "Forensic medicine"
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("MedVideo AI") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("AI Medical Video Creator",
                style = MaterialTheme.typography.headlineSmall)
            Text("Create educational video plans from medical topics.")

            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it; status = "" },
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
                            onClick = { topic = item; status = "" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(item, style = MaterialTheme.typography.labelSmall)
                        }
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

            Text("Duration", style = MaterialTheme.typography.titleMedium)
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
                onClick = {
                    status = if (topic.isBlank()) {
                        "Please enter a medical topic."
                    } else {
                        "Video request prepared\n\n" +
                            "Topic: $topic\nLanguage: $language\n" +
                            "Duration: $duration\nVisual style: $style\n\n" +
                            "AI video generation is not connected yet. " +
                            "Connect a secure backend and video provider to generate MP4."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Prepare Video")
            }

            if (status.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(status, modifier = Modifier.padding(16.dp))
                }
            }

            Text(
                "Educational use only. Verify medical facts against trusted clinical references.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
