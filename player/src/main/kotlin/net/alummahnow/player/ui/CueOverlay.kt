package net.alummahnow.player.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.Locale
import net.alummahnow.core.bridge.media.QURAN_CREDIT_AR
import net.alummahnow.core.bridge.media.QURAN_CREDIT_EN
import net.alummahnow.core.bridge.media.QuranCaptionCredit
import net.alummahnow.player.R

/** Renders active cues from the same listener that updates the player chrome. */
@Composable
internal fun CueOverlay(
    cues: List<QuranCaptionCredit>,
    linkCredits: Boolean,
    modifier: Modifier,
    bottomPadding: Dp,
    creditBottomPadding: Dp,
    onOpenCredit: (Context, String) -> Unit
) {
    if (!linkCredits) {
        val text = cues.joinToString("\n") { it.text }
        if (text.isNotBlank()) CaptionText(text, modifier.padding(bottom = bottomPadding, start = 24.dp, end = 24.dp))
        return
    }

    Column(
        modifier.zIndex(1f).padding(bottom = creditBottomPadding, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (cue in cues) {
            if (cue.credited) {
                CreditedCue(cue, onOpenCredit)
            } else if (cue.text.isNotBlank()) {
                CaptionText(cue.text)
            }
        }
    }
}

@Composable
private fun CaptionText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun CreditedCue(cue: QuranCaptionCredit, onOpenCredit: (Context, String) -> Unit) {
    val language = checkNotNull(cue.language)
    val url = checkNotNull(cue.url)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val description = remember(context, configuration, language) {
        val config = Configuration(configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        context.createConfigurationContext(config).getString(R.string.aun_player_quran_credit_browser)
    }
    val arabic = language == "ar"
    Column(horizontalAlignment = if (arabic) AbsoluteAlignment.Left else AbsoluteAlignment.Right) {
        if (cue.text.isNotBlank()) CaptionText(cue.text)
        Box(
            Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable { onOpenCredit(context, url) }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = if (arabic) QURAN_CREDIT_AR else QURAN_CREDIT_EN,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.8f), MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            )
        }
    }
}

internal fun openQuranCredit(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            // Some devices have no browser installed.
        }
    }
}
