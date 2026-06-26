package com.example.profiledetection

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the Gemini generateContent REST API. Sends a screenshot (JPEG)
 * plus an instruction and gets back a JSON array of openers. No SDK, no backend —
 * the API key is read from BuildConfig and the call goes straight to Google.
 */
object GeminiClient {

    private const val MODEL = "gemini-3.1-flash-lite"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private const val MAX_ATTEMPTS = 2
    // Only retry genuine server overload. NEVER retry 429 — that just burns more quota.
    private val RETRYABLE = setOf(500, 502, 503, 504)

    private const val PROMPT = """
You're my smoothest, most charming friend helping me win over a girl I matched with on Hinge.
GOAL: make her genuinely interested in ME — spark attraction, make her smile, make her want to
reply fast, and set up the vibe that leads to a date. The images are consecutive screenshots
scrolled through ONE girl's profile — all her photos, prompts, answers and interests. Treat them
as one continuous profile.

Read EVERYTHING first — every photo and every prompt+answer — then write 5 opening messages.

VOICE — sound like a confident, charming, flirty MAN texting, NOT an AI:
- Mature and grounded — flirt with the calm confidence of a grown man, not a boy. Never cheesy,
  hyper, needy, or trying too hard. Subtle and self-assured beats loud and over-the-top.
- Flirty and romantic with real charm — make her feel noticed and a little swept up, smoothly.
  Warm and a bit bold, never thirsty, desperate, or creepy.
- SIMPLE, everyday English — basic common words anyone can understand, including someone whose
  first language isn't English. No fancy or literary vocabulary, no big or rare words, no poetry.
- Genuinely witty — playful teasing and clever lines that show personality and create a spark.
- Casual texting style: contractions, relaxed grammar, lowercase fine. Short, punchy, easy to reply to.
- Build intrigue — leave her curious and wanting to keep talking. A couple of them can playfully
  hint at meeting up or a date idea tied to something she likes (don't bluntly ask her out yet).
- Each opener references a SPECIFIC detail from HER profile (a prompt answer, a hobby, something in a
  photo) so it's obvious I actually looked. Never a line that could be copy-pasted to anyone.

BANNED (these scream "AI" or "cringe") — never do any of these:
- Starting with "Hey", "Hi", "So", or her name.
- Pickup-line clichés ("are you a…", "running through my mind", "caught my eye", "must be tired…").
- Generic compliments ("you seem fun/cool/interesting", "love your vibe", "you're gorgeous").
- Corporate-polite or over-explained phrasing, em dashes, and emoji-stuffing (one emoji max, only if it lands).

For EACH opener also give a short "reference" saying exactly what on her profile it replies to:
- If it's about a prompt, write: Prompt: <the prompt question in a few words>
- If it's about a photo, number the photos in the order they appear top-to-bottom and write:
  Photo 1, Photo 2, etc. (add 2-3 words on what's in it, e.g. "Photo 2 (hiking)").
- If it's about an interest/bio detail, name it briefly.
Keep the reference under ~40 characters.

Give 5 with a range: Flirty, Romantic, Cheeky, Smooth, Playful (one each). Keep each message under ~160 characters.
If the images aren't a dating profile or there's too little to work with, return an empty array.
Return ONLY the JSON described by the schema.
"""

    /** Calls Gemini with one or more screenshots. Throws on network/HTTP/parse errors. */
    suspend fun generateOpeners(jpegShots: List<ByteArray>, apiKey: String): List<Suggestion> =
        withContext(Dispatchers.IO) {
            // Parts: the instruction text first, then every screenshot as an image part.
            val parts = JSONArray().put(JSONObject().put("text", PROMPT.trim()))
            for (jpeg in jpegShots) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    )
                )
            }

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 1.35)
                        .put("responseMimeType", "application/json")
                        .put("responseSchema", responseSchema())
                )
            }.toString()

            // Gemini returns 503 (overloaded) / 429 / 5xx transiently. Retry with backoff.
            var lastCode = 0
            var lastBody = ""
            for (attempt in 1..MAX_ATTEMPTS) {
                val conn = (URL("$ENDPOINT?key=$apiKey").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                    if (code in 200..299) return@withContext parseSuggestions(response)

                    lastCode = code
                    lastBody = response
                    if (code !in RETRYABLE || attempt == MAX_ATTEMPTS) {
                        throw RuntimeException(friendlyError(code, response))
                    }
                } finally {
                    conn.disconnect()
                }
                delay(900L * attempt) // 0.9s, 1.8s — let the overload pass
            }
            throw RuntimeException(friendlyError(lastCode, lastBody))
        }

    private fun responseSchema(): JSONObject = JSONObject().apply {
        put("type", "ARRAY")
        put(
            "items",
            JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("tone", JSONObject().put("type", "STRING"))
                        .put("reference", JSONObject().put("type", "STRING"))
                        .put("message", JSONObject().put("type", "STRING"))
                )
                .put(
                    "required",
                    JSONArray().put("tone").put("reference").put("message")
                )
                .put("propertyOrdering", JSONArray().put("tone").put("reference").put("message"))
        )
    }

    private fun parseSuggestions(response: String): List<Suggestion> {
        val root = JSONObject(response)
        val candidates = root.optJSONArray("candidates")
            ?: throw RuntimeException("No candidates in response")
        if (candidates.length() == 0) throw RuntimeException("Empty response from model")

        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw RuntimeException("Model returned no content (possibly blocked)")

        val text = buildString {
            for (i in 0 until parts.length()) {
                append(parts.getJSONObject(i).optString("text", ""))
            }
        }.trim()

        if (text.isEmpty()) return emptyList()

        val arr = JSONArray(text)
        val out = ArrayList<Suggestion>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val message = humanize(o.optString("message"))
            if (message.isNotEmpty()) {
                out.add(
                    Suggestion(
                        tone = o.optString("tone", "Opener").trim(),
                        reference = o.optString("reference").trim(),
                        message = message,
                    )
                )
            }
        }
        return out
    }

    /** Strip "AI tells" from the text — mainly em/en dashes and double hyphens. */
    private fun humanize(raw: String): String {
        return raw
            .replace('—', ',')                     // — em dash → comma
            .replace('–', ',')                     // – en dash → comma
            .replace(Regex("\\s*--+\\s*"), ", ")   // "--" → comma
            .replace(Regex("\\s+([,.!?])"), "$1")  // " ," → ","
            .replace(Regex(",\\s*,"), ",")         // double commas
            .replace(Regex(",\\s*([.!?])"), "$1")  // ", ." → "."
            .replace(Regex("\\s{2,}"), " ")        // collapse spaces
            .trim()
            .trim(',')
            .trim()
    }

    private fun friendlyError(code: Int, body: String): String = when (code) {
        503, 500, 502, 504 -> "Gemini is busy right now (overloaded). Tap to try again."
        429 -> "Hit the free-tier rate limit. Wait a minute and try again."
        else -> "Gemini HTTP $code: ${shortError(body)}"
    }

    private fun shortError(body: String): String {
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(200)
        } catch (_: Exception) {
            body.take(200)
        }
    }
}

