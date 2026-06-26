package com.example.profiledetection

/** One generated opener: its tone, what it references, and the message itself. */
data class Suggestion(
    val tone: String,
    val reference: String, // e.g. "Prompt: The way to win me over is…" or "Photo 2"
    val message: String,
)
