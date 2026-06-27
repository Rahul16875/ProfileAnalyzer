package com.example.profiledetection

/** What the user wants help with when they tap the bubble. */
enum class Mode { PROFILE, CHAT }

/** One generated suggestion: its tone, language, what it references, and the text itself. */
data class Suggestion(
    val tone: String,
    val lang: String,      // "Hinglish" or "English"
    val reference: String, // profile: "Photo 2" / "Prompt: …"; chat: "reply" / "starter"
    val message: String,
)
