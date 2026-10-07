package com.strata.app.ui.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.strata.app.ui.theme.CobaltDeep
import com.strata.app.ui.theme.SeriesPalette
import com.strata.app.ui.theme.StrataTheme

data class LockUi(
    val firstRun: Boolean,
    val relock: Boolean = false,
    val error: String? = null,
    /** The Keystore key was invalidated (for example after removing the screen lock). */
    val keyLost: Boolean = false,
)

@Composable
fun LockScreen(state: LockUi, onUnlock: () -> Unit, onReset: () -> Unit) {
    val colors = StrataTheme.colors
    Box(Modifier.fillMaxSize().background(CobaltDeep)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            StrataArt(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp))
            Text("Strata", style = MaterialTheme.typography.displayLarge.copy(fontFamily = com.strata.app.ui.theme.Urbanist), color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    state.keyLost -> "This phone's security settings changed and the key that protected your data is gone."
                    state.firstRun -> "Every account, every institution, one private ledger on this phone."
                    else -> "Your ledger is locked."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onHeroMuted,
            )
            if (state.error != null) {
                Spacer(Modifier.height(10.dp))
                Text(state.error, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFC4CC))
            }
            Spacer(Modifier.height(28.dp))
            if (state.keyLost) {
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = CobaltDeep),
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                ) { Text("Erase and start over", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "You can restore an exported backup from Setup afterwards.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onHeroMuted,
                )
            } else {
                Button(
                    onClick = onUnlock,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = CobaltDeep),
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                ) {
                    Icon(Icons.Rounded.Fingerprint, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (state.firstRun) "Create my encrypted ledger" else "Unlock", style = MaterialTheme.typography.titleMedium)
                }
                if (state.firstRun) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "A key is created in the phone's security chip. Only your fingerprint, face or PIN can open it.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onHeroMuted,
                    )
                }
            }
        }
    }
}

/** Offset bands of the series palette, like exposed rock layers. */
@Composable
private fun StrataArt(modifier: Modifier) {
    val bands = SeriesPalette.swatches.take(8).map { it.light }
    // Fractions of the width each layer covers, alternating which edge it runs off.
    val widths = listOf(0.62f, 0.78f, 0.5f, 0.86f, 0.58f, 0.72f, 0.44f, 0.66f)
    Canvas(modifier) {
        val pitch = minOf(size.height / bands.size, 46.dp.toPx())
        val thickness = pitch * 0.8f
        val top = (size.height - pitch * bands.size) / 2
        // The art bleeds past the 28dp page margin to the screen edges.
        val bleed = 28.dp.toPx()
        bands.forEachIndexed { i, c ->
            val width = (size.width + bleed * 2) * widths[i]
            val left = if (i % 2 == 0) size.width + bleed - width else -bleed
            drawRoundRect(
                color = c,
                topLeft = Offset(left, top + pitch * i + (pitch - thickness) / 2),
                size = Size(width, thickness),
                cornerRadius = CornerRadius(thickness / 2),
            )
        }
    }
}
