# Profile Copilot

A personal Android app that suggests **personalized opening messages** for dating
profiles (e.g. Hinge). Tap a floating bubble while viewing a profile — the app scrolls
through it, screenshots it, sends the images to **Gemini (vision)**, and shows 5 ready-to-send
openers in a panel. Tap one to copy it, then paste it into the dating app yourself.

> Built for personal use only. It **suggests** messages — it never sends anything automatically.

## How it works

1. A floating **AI bubble** sits on top of any app (via an overlay).
2. On tap, an **AccessibilityService** scrolls the profile to the top, then captures up to
   6 clean screenshots while scrolling down (so it sees *all* photos + prompts).
3. The screenshots are sent to **Gemini** in one request, which returns 5 openers — each
   tagged with what it references (a prompt, "Photo 2", an interest) and a tone
   (Flirty / Romantic / Cheeky / Smooth / Playful).
4. Results show in a panel; tap any opener to copy it.

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
  (high free daily limit). Other options: `gemini-2.5-flash` (better quality, ~20/day free),
  `gemini-2.5-pro` (best, lowest free limit).
- **Tone / style:** edit the `PROMPT` constant in `GeminiClient.kt`.
- **Screenshots per capture:** `MAX_SHOTS` in `CopilotAccessibilityService.kt`.

## Notes & limits

- **Free Gemini tier is small** (e.g. ~20–500 requests/day depending on model). For heavier
  use, enable Billing on your Google AI key — limits jump and cost is fractions of a cent/tap.
- **Android only.** iOS doesn't allow this kind of screen reading.
- Uninstalling resets the granted permissions — re-grant after a fresh install.
