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

    // Shared voice rules used by both modes (language rules differ per mode).
    private const val VOICE = """
VOICE — sound like a confident, charming, flirty MAN texting, NOT an AI:
- Mature and grounded — the calm confidence of a grown man, not a boy. Never cheesy, hyper,
  needy, or trying too hard. Subtle and self-assured beats loud and over-the-top.
- Flirty and a little romantic with real charm — warm and a bit bold, never thirsty or creepy.
- Genuinely witty — playful teasing and clever lines that show personality and create a spark.
- Casual texting style: contractions, relaxed grammar, lowercase fine. Short, punchy, easy to reply to.

BANNED (these scream "AI" or "cringe") — never do any of these:
- Starting with "Hey", "Hi", "So", or her name.
- Pickup-line clichés ("are you a…", "running through my mind", "caught my eye", "must be tired…").
- Generic compliments ("you seem fun/cool/interesting", "love your vibe", "you're gorgeous").
- Corporate-polite or over-explained phrasing, em dashes, emoji-stuffing (one emoji max, only if it lands).
"""

    // Profile openers: always plain English (safer first impression).
    private const val LANG_PROFILE = """
LANGUAGE — write ALL 5 openers in SIMPLE everyday English (basic common words anyone can
understand, no fancy/rare words, no poetry). Do NOT use Hindi or Hinglish. Set "lang" to "English"
for every one.
"""

    // Chat replies: a mix so I can choose.
    private const val LANG_CHAT = """
LANGUAGE — give a MIX so I can choose:
- Make about half the suggestions "Hinglish" and half "English".
- Hinglish = Hindi written in Roman/English letters mixed naturally with English, the way young
  Indians actually text (e.g. "arre tum bhi coffee lover ho? chalo kabhi perfect cup dhoondte hain").
  Keep it casual and natural, NOT formal/shudh Hindi, NOT Devanagari script.
- English = SIMPLE everyday English — basic common words anyone can understand, no fancy/rare words.
- Tag each suggestion's lang as exactly "Hinglish" or "English".
"""

    private val PROFILE_PROMPT = """
You're my smoothest, most charming friend helping me win over a girl I matched with.
GOAL: make her genuinely interested in ME — spark attraction, make her smile, make her want to
reply fast. The images are consecutive screenshots scrolled through ONE girl's profile — all her
photos, prompts, answers and interests. Treat them as one continuous profile.

Read EVERYTHING first — every photo and every prompt+answer — then write 5 opening messages.
$VOICE$LANG_PROFILE
- Each opener references a SPECIFIC detail from HER profile (a prompt answer, a hobby, something in a
  photo) so it's obvious I actually looked. Never a line that could be copy-pasted to anyone.
- Go for a DISTINCTIVE or overlooked detail — the thing most guys would skip, not the obvious
  "nice smile" everyone comments on. Standing out beats playing safe.
- Make at least one a light, easy QUESTION she'll enjoy answering — questions get replies.
- A couple can playfully hint at meeting up tied to something she likes (don't bluntly ask her out yet).

For EACH opener give a short "reference" (under ~40 chars) for what it replies to:
- A prompt → "Prompt: <question in a few words>".
- A photo → number photos top-to-bottom: "Photo 2 (hiking)".
- An interest/bio detail → name it briefly.

Give 5 with a range of tones (Flirty, Romantic, Cheeky, Smooth, Playful). Each message under ~160 chars.
If the images aren't a profile or there's too little to work with, return an empty array.
Return ONLY the JSON described by the schema.
"""

    private val CHAT_PROMPT = """
You're my smoothest, most charming friend helping me text a girl I matched with. The images are
consecutive screenshots of our CHAT conversation, scrolled from top (oldest) to bottom (newest).
Work out who said what from the layout — MY messages are usually right-aligned, HERS left-aligned.

Read the whole conversation, then:
- If she has sent messages, write 5 great REPLIES to her LATEST message that keep things going,
  move the vibe forward, and make her want to reply. Set "reference" to "reply".
- If the chat is empty or only I have texted, write 5 conversation STARTERS / follow-ups that
  re-spark it. Set "reference" to "starter".
$VOICE$LANG_CHAT
- Match the energy, topic and STAGE of the conversation. Don't repeat what's already been said.
- PACING — chat like a real person building a connection, not someone rushing. DEFAULT to keeping
  the conversation flowing: tease her, banter, be playful, react to what she said, ask something
  fun. Do NOT ask for a date, her number, or to "meet up" early — pushing too soon kills it.
  Only if the chat is ALREADY several real exchanges deep AND she's clearly warm / flirting back
  may ONE suggestion lightly hint at meeting up, and even then keep it casual, never a formal ask.
- HUMOR & FLIRT — this is the main thing. Show a genuinely great, mature, ADULT sense of humor:
  sharp, clever, a little cheeky, the kind of line that actually makes her laugh and flirt back.
  Effortless, confident flirting. Never corny, never cringe, never boyish or childish jokes.
- KEEP HER ENGAGED — if the conversation is going stale, repetitive, dry, or one-word, do NOT keep
  flogging the same dead topic. Pivot to something fresh: bring up a new, interesting topic or a
  playful/curious question (tie it to her profile or something said earlier when you can) that's
  easy and fun for her to run with and re-sparks her interest. Set "reference" to "new topic" for
  any suggestion that changes the subject. Always give her something to reply to, never a dead end.
- READ HER INTEREST — if she's clearly into it, escalate the flirt/banter a notch; if she's dry,
  short, or hasn't replied, re-hook with something fun and confident — never needy or apologetic.
- Match her ENERGY and message length, and span the 5 from safe to bold so I can pick my comfort level.

Give 5 with a range of tones (Flirty, Funny, Cheeky, Playful, plus at least one fresh "new topic").
Each message under ~160 chars.
If the images aren't a chat conversation, return an empty array.
Return ONLY the JSON described by the schema.
"""

    /**
     * Calls the model with one or more screenshots.
     * @param twist optional re-roll instruction (e.g. "make them funnier"), null for a fresh run.
     * @param avoid previously-suggested lines the model must not repeat.
     */
    suspend fun generateSuggestions(
        jpegShots: List<ByteArray>,
        apiKey: String,
        mode: Mode,
        twist: String? = null,
        avoid: List<String> = emptyList(),
    ): List<Suggestion> =
        withContext(Dispatchers.IO) {
            val basePrompt = if (mode == Mode.CHAT) CHAT_PROMPT else PROFILE_PROMPT
            val prompt = buildString {
                append(basePrompt.trim())
                if (!twist.isNullOrBlank()) {
                    append("\n\nEXTRA INSTRUCTION (applies to all 5): ").append(twist.trim())
                }
                if (avoid.isNotEmpty()) {
                    append("\n\nDo NOT repeat or barely reword any of these already-suggested lines:")
                    avoid.takeLast(25).forEach { append("\n- ").append(it) }
                }
            }
            // Parts: the instruction text first, then every screenshot as an image part.
            val parts = JSONArray().put(JSONObject().put("text", prompt))
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
                        .put("lang", JSONObject().put("type", "STRING"))
                        .put("reference", JSONObject().put("type", "STRING"))
                        .put("message", JSONObject().put("type", "STRING"))
                )
                .put(
                    "required",
                    JSONArray().put("tone").put("lang").put("reference").put("message")
                )
                .put(
                    "propertyOrdering",
                    JSONArray().put("tone").put("lang").put("reference").put("message")
                )
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
                        lang = o.optString("lang").trim(),
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

