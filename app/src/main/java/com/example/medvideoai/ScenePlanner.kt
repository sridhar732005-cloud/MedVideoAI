package com.example.medvideoai

/**
 * Produces a small storyboard from the text analysis. The output is deterministic and works
 * without a network connection. An AI provider can be added later behind this interface.
 */
object ScenePlanner {
    fun createStoryboard(text: String): List<MedicalScene> {
        val analyzed = MedicalTextAnalyzer.analyze(text)
        if (analyzed.isEmpty()) return emptyList()

        return analyzed.mapIndexed { index, scene ->
            scene.copy(
                stepLabel = "Scene ${index + 1} of ${analyzed.size}",
                title = scene.title.ifBlank { "Medical scene ${index + 1}" }
            )
        }
    }
}
