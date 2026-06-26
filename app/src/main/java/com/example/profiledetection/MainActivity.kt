package com.example.profiledetection

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.example.profiledetection.ui.theme.ProfileDetectionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProfileDetectionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SetupScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bumped on every ON_RESUME to re-read permission state when returning from Settings.
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    @Suppress("UNUSED_EXPRESSION") refresh // read so recomposition re-evaluates the checks
    val hasApiKey = BuildConfig.GEMINI_API_KEY.isNotBlank()
    val hasOverlay = Settings.canDrawOverlays(context)
    val hasAccessibility = isAccessibilityEnabled(context)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Profile Copilot", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "AI openers for the dating profile on your screen. Open a profile, tap the floating bubble, copy an opener.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StepCard(
            number = 1,
            title = "Gemini API key",
            done = hasApiKey,
            description = if (hasApiKey)
                "Key found. You're set."
            else
                "Add GEMINI_API_KEY=your_key to local.properties (project root), then rebuild. Get a free key at aistudio.google.com.",
        )

        StepCard(
            number = 2,
            title = "Display over other apps",
            done = hasOverlay,
            description = "Lets the bubble float on top of Hinge.",
            buttonText = if (hasOverlay) null else "Grant",
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            },
        )

        StepCard(
            number = 3,
            title = "Accessibility service",
            done = hasAccessibility,
            description = "Lets the app capture the current screen when you tap the bubble. Find \"Profile Copilot\" in the list and turn it on.",
            buttonText = if (hasAccessibility) null else "Open settings",
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )

        Spacer(Modifier.size(4.dp))

        val allReady = hasApiKey && hasOverlay && hasAccessibility
        Text(
            if (allReady)
                "✅ All set. Open Hinge, land on a profile, and tap the AI bubble."
            else
                "Finish the steps above to start.",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun StepCard(
    number: Int,
    title: String,
    done: Boolean,
    description: String,
    buttonText: String? = null,
    onClick: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (done) "✓" else "$number",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(CircleShape)
                        .padding(horizontal = 4.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (buttonText != null && !done) {
                Button(onClick = onClick) { Text(buttonText) }
            }
        }
    }
}

fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${CopilotAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expected, ignoreCase = true)) return true
    }
    return false
}
