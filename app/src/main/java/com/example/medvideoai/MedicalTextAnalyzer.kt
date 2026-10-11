package com.example.medvideoai

/**
 * Offline, rule-based medical text analysis.
 * This is a transparent fallback, not a claim of full AI understanding.
 */
enum class MedicalVisualType { KIDNEY, HEART, LUNGS, EAR, BRAIN, MICROBES, EYE, GENERIC }

data class MedicalScene(
    val title: String,
    val caption: String,
    val visualType: MedicalVisualType,
    val stepLabel: String
)

object MedicalTextAnalyzer {
    fun analyze(text: String): List<MedicalScene> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()
        return splitIntoSentences(cleaned).take(12).mapIndexed { index, sentence ->
            val type = detectVisualType(sentence)
            MedicalScene(titleFor(type), sentence, type, "Scene ${index + 1}")
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('；', ';')
        val parts = normalized.split(Regex("(?<=[.!?。])\\s+|[\\n;]+"))
            .map { it.trim().trim(',', '，', ':', '：') }
            .filter { it.isNotBlank() }
        if (parts.size > 1) return parts
        val commaParts = normalized.split(Regex("(?<=[,，])\\s+"))
            .map { it.trim().trim(',', '，') }
            .filter { it.isNotBlank() }
        return if (normalized.length > 110 && commaParts.size > 1) commaParts else listOf(normalized)
    }

    private fun detectVisualType(sentence: String): MedicalVisualType {
        val s = sentence.lowercase()
        return when {
            hasAny(s, "kidney", "renal", "nephron", "urine", "urinary", "filtration",
                "சிறுநீரகம்", "சிறுநீர்", "நெஃப்ரான்", "சிறுநீர்ப்பை", "சிறுநீர்க் குழாய்") -> MedicalVisualType.KIDNEY
            hasAny(s, "heart", "cardiac", "cardio", "blood pressure", "இதயம்", "இருதயம்", "இதயத்") -> MedicalVisualType.HEART
            hasAny(s, "lung", "pulmonary", "respiratory", "asthma", "copd", "airway", "breathing",
                "நுரையீரல்", "மூச்சு", "ஆஸ்துமா", "சுவாசம்", "சளி") -> MedicalVisualType.LUNGS
            hasAny(s, "ear", "meniere", "cochlea", "vestibular", "hearing", "காது", "மெனியர்", "கேள்வி") -> MedicalVisualType.EAR
            hasAny(s, "brain", "neuron", "nerve", "cerebral", "மூளை", "நரம்பு") -> MedicalVisualType.BRAIN
            hasAny(s, "bacteria", "virus", "infection", "microbe", "pathogen", "பாக்டீரியா", "வைரஸ்", "தொற்று", "கிருமி") -> MedicalVisualType.MICROBES
            hasAny(s, "eye", "retina", "cornea", "vision", "கண்", "விழித்திரை", "கருவிழி") -> MedicalVisualType.EYE
            else -> MedicalVisualType.GENERIC
        }
    }

    private fun titleFor(type: MedicalVisualType): String = when (type) {
        MedicalVisualType.KIDNEY -> "Kidney / சிறுநீரகம்"
        MedicalVisualType.HEART -> "Heart / இதயம்"
        MedicalVisualType.LUNGS -> "Lungs / நுரையீரல்"
        MedicalVisualType.EAR -> "Ear / காது"
        MedicalVisualType.BRAIN -> "Brain / மூளை"
        MedicalVisualType.MICROBES -> "Microbes / கிருமிகள்"
        MedicalVisualType.EYE -> "Eye / கண்"
        MedicalVisualType.GENERIC -> "Medical concept / மருத்துவக் கருத்து"
    }

    private fun hasAny(text: String, vararg terms: String): Boolean = terms.any { text.contains(it) }
}
