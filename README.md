# Profile Copilot

A personal Android app that suggests **personalized messages** while you use a dating app
(e.g. Hinge). Tap a floating bubble and pick what you need — opening lines for a profile, or
replies for an ongoing chat. The app scrolls the screen, screenshots it, sends the images to
**Gemini (vision)**, and shows 5 ready-to-send suggestions in a panel. Tap one to copy it,
then paste it into the dating app yourself.

> Built for personal use only. It **suggests** messages — it never sends anything automatically.

## Two modes

Tapping the bubble opens a quick chooser:

- **👤 Openers (from her profile)** — scrolls the whole profile (all photos + prompts) and
  suggests opening lines. Each is tagged with what it references (a prompt, "Photo 2", an interest).
  *Openers are always in simple English.*
- **💬 Reply (from your chat)** — scrolls the conversation, works out who said what, and suggests
  replies to her last message (or conversation starters if it's quiet).
  *Replies come as a mix of Hinglish and simple English so you can choose.*

Each suggestion is tagged with its tone + language, e.g. `FLIRTY · HINGLISH · reply`.
Flirty / romantic suggestions are listed first.

## Refining suggestions

Below the 5 suggestions:

- **Re-roll chips** — *Funnier · Bolder · Shorter · Flirtier*. Tap one to re-run the same mode
  with that twist applied to all 5 (a targeted nudge instead of a full reset).
- **Regenerate** — a completely fresh set.
- **Avoid repeats** — the app remembers recently suggested lines and asks the model not to repeat
  or reword them, so re-rolls and regenerates stay fresh.

## How it works

1. A floating **bubble** sits on top of any app (via an overlay).
2. On tap, you choose a mode; an **AccessibilityService** scrolls to the top, then captures up
   to 6 clean screenshots while scrolling down (so it sees the whole profile/chat).
3. The screenshots go to **Gemini** in one request, which returns 5 suggestions.
4. Results show in a panel; tap any one to copy it.

## Tech

- Kotlin + **Jetpack Compose** (setup screen *and* the overlay UI)
- `AccessibilityService` — `takeScreenshot()` + `dispatchGesture()` for capture & scroll
- `WindowManager` overlay hosting Compose (custom lifecycle owners)
- Gemini `generateContent` REST API (no SDK, no backend) — key read from `BuildConfig`

## Setup

1. **Clone & open** in Android Studio.
2. **Create `local.properties`** in the project root (it's gitignored — never commit it):
   ```properties
   sdk.dir=/Users/you/Library/Android/sdk
   GEMINI_API_KEY=your_gemini_api_key
   ```
   Get a free key at [aistudio.google.com](https://aistudio.google.com/apikey) using a
   personal Google account.
3. **Build & run** on a device/emulator with **Android 11+** (API 30+ — required for
   `takeScreenshot()`).
4. **Grant two permissions** (the in-app setup screen guides you):
   - *Display over other apps* (overlay)
   - *Accessibility* → enable **Profile Copilot** under "Downloaded apps"

## Configuration

- **Model:** set `MODEL` in `app/.../GeminiClient.kt`. Default `gemini-3.1-flash-lite`
  (high free daily limit). Other options: `gemini-2.5-flash`, `gemini-2.5-pro` (best quality,
  lower free limit).
- **Tone / style / language:** edit the `PROFILE_PROMPT` and `CHAT_PROMPT` constants in
  `GeminiClient.kt`.
- **Screenshots per capture:** `MAX_SHOTS` in `CopilotAccessibilityService.kt`.

## Notes & limits

- **Free Gemini tier is small** (e.g. ~20–500 requests/day depending on model). For heavier
  use, enable Billing on your Google AI key — limits jump and cost is fractions of a cent/tap.
- **Android only.** iOS doesn't allow this kind of screen reading.
- Uninstalling resets the granted permissions — re-grant after a fresh install.
