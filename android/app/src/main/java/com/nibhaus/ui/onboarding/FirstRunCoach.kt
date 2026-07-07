package com.nibhaus.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nibhaus.pen.PenConnState
import com.nibhaus.ui.Eyebrow
import com.nibhaus.ui.riseIn
import com.nibhaus.ui.steelCard
import kotlinx.coroutines.delay

/** One step of the first-run coach: an eyebrow ("STEP 1 OF 3"), a headline, and one line of body copy. */
private data class CoachStep(val eyebrow: String, val title: String, val body: String)

private val COACH_STEPS = listOf(
    CoachStep(
        eyebrow = "STEP 1 OF 3",
        title = "Connect your pen",
        body = "Tap the connect tile on this screen to pair your smart pen over Bluetooth.",
    ),
    CoachStep(
        eyebrow = "STEP 2 OF 3",
        title = "Just write",
        body = "Write on any page of your smart notebook. It appears here live, with no scanning needed.",
    ),
    CoachStep(
        eyebrow = "STEP 3 OF 3",
        title = "Tap the printed buttons",
        body = "The Share and Email icons printed at the top of the page work. Tap them with the pen.",
    ),
)

/**
 * First-run guided coach (#1): a 3-step, Next-driven card shown once over the Pens screen after the
 * splash. [pen] ties step 1 to the real connection state — a pen actually connecting while this is
 * open celebrates and auto-advances instead of waiting for a tap. [onDone] fires exactly once, from
 * finishing the last step, tapping Skip, or checking "Don't show this again" — all three mean the
 * same thing (never show this again), so they share one callback.
 */
@Composable
fun FirstRunCoach(pen: PenConnState, onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var celebrating by remember { mutableStateOf(false) }
    fun dismissCurrentStep() {
        if (step < COACH_STEPS.lastIndex) step += 1 else onDone()
    }

    // Step 1 (connect your pen): a real pen connecting while the coach is open is a better signal
    // than any tap could be — celebrate briefly, then move on to "Just write" on its own.
    LaunchedEffect(pen, step) {
        if (step == 0 && pen is PenConnState.Connected && !celebrating) {
            celebrating = true
            delay(900)
            step = 1
            celebrating = false
        }
    }

    Dialog(onDismissRequest = { dismissCurrentStep() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().steelCard(radius = 20.dp).padding(20.dp)) {
                val current = COACH_STEPS[step]
                Row(Modifier.fillMaxWidth().riseIn(0), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow(current.eyebrow)
                    TextButton(onClick = onDone) { Text("Skip") }
                }
                Text(
                    current.title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 6.dp).riseIn(1),
                )
                Text(
                    if (step == 0 && celebrating) "Pen connected. Let's keep going." else current.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp).riseIn(2),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp).riseIn(3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = false, onCheckedChange = { if (it) onDone() })
                    Spacer(Modifier.width(2.dp))
                    Text("Don't show this again", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).riseIn(4),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (step > 0) {
                        TextButton(onClick = { step -= 1 }) { Text("Back") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = { if (step < COACH_STEPS.lastIndex) step += 1 else onDone() }) {
                        Text(if (step < COACH_STEPS.lastIndex) "Next" else "Got it")
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
