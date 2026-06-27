package com.example.profiledetection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Purple = Color(0xFF7C4DFF)
private val Pink = Color(0xFFFF4081)
private val PanelBg = Color(0xFF1E1B2E)
private val CardBg = Color(0xFF272340)
private val CardStroke = Color(0xFF3D3759)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB9B4D0)
private val TextFaint = Color(0xFF7E78A0)

/** The floating bubble. Tap to analyze, drag to reposition. */
@Composable
fun BubbleContent(
    loading: Boolean,
    onTap: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Purple, Pink)))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .pointerInput(loading) {
                detectTapGestures(onTap = { if (!loading) onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = Color.White,
                strokeWidth = 2.5.dp,
            )
        } else {
            Text("AI", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Full-screen scrim + suggestions card. Rendered into the panel window. */
@Composable
fun PanelContent(
    state: OverlayState,
    onClose: () -> Unit,
    onChoose: (Mode) -> Unit,
    onRegenerate: () -> Unit,
    onCopy: (Suggestion) -> Unit,
) {
    if (!state.panelOpen) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(PanelBg)
                // Swallow taps so they don't reach the dismiss scrim behind.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(16.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.showChooser) "What do you need?" else "Suggestions",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕",
                    color = TextSecondary,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onClose() }
                        .padding(8.dp),
                )
            }

            Spacer(Modifier.size(12.dp))

            when {
                state.showChooser -> Chooser(onChoose)
                state.loading -> LoadingRow()
                state.error != null -> Text(
                    state.error!!,
                    color = Pink,
                    fontSize = 14.sp,
                )
                state.suggestions.isEmpty() -> Text(
                    "Nothing to suggest — make sure the profile or chat is on screen, then try again.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
                else -> Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.suggestions.forEach { suggestion ->
                        SuggestionItem(suggestion, onCopy)
                        Spacer(Modifier.size(10.dp))
                    }
                }
            }

            // Regenerate only makes sense once we're showing results/error, not the chooser.
            if (!state.loading && !state.showChooser) {
                Spacer(Modifier.size(4.dp))
                Button(
                    onClick = onRegenerate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple),
                ) {
                    Text("Regenerate", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun Chooser(onChoose: (Mode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { onChoose(Mode.PROFILE) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Purple),
        ) {
            Text("👤  Openers (from her profile)", color = Color.White)
        }
        Button(
            onClick = { onChoose(Mode.CHAT) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Pink),
        ) {
            Text("💬  Reply (from your chat)", color = Color.White)
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = Purple,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text("Reading the screen…", color = TextSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun SuggestionItem(suggestion: Suggestion, onCopy: (Suggestion) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .clickable { onCopy(suggestion) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // e.g. "FLIRTY · HINGLISH · Photo 2"
            val label = buildString {
                append(suggestion.tone.uppercase())
                if (suggestion.lang.isNotEmpty()) append("  ·  ${suggestion.lang.uppercase()}")
                if (suggestion.reference.isNotEmpty()) append("  ·  ${suggestion.reference}")
            }
            Text(
                label,
                color = Pink,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(suggestion.message, color = TextPrimary, fontSize = 15.sp)
        Text("Tap to copy", color = TextFaint, fontSize = 11.sp)
    }
}
