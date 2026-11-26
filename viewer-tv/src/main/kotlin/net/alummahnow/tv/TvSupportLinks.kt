package net.alummahnow.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import net.alummahnow.core.bridge.SupportLinks

internal enum class TvSupportLink(val label: Int, val resolveUrl: (String, String) -> String) {
    Help(R.string.support_help, SupportLinks::helpUrl),
    Contact(R.string.support_contact, SupportLinks::contactUrl),
    Privacy(R.string.support_privacy, SupportLinks::privacyUrl),
    Terms(R.string.support_terms, SupportLinks::termsUrl)
}

@Composable
internal fun TvSupportLinks() {
    var selected by rememberSaveable { mutableStateOf(TvSupportLink.Help) }
    val language = LocalConfiguration.current.locales[0].language
    val url = selected.resolveUrl(language, BuildConfig.WEB_HOST)
    Text(stringResource(R.string.support_links), style = MaterialTheme.typography.headlineLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvSupportLink.entries.forEach { link ->
                TvAction(
                    stringResource(link.label),
                    modifier = Modifier.fillMaxWidth(),
                    initialFocus = link == TvSupportLink.Help,
                    selected = link == selected
                ) { selected = link }
            }
        }
        Column(
            Modifier.weight(2f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(url, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr))
            SupportQrCode(url)
            Text(stringResource(R.string.support_scan), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
internal fun TvAction(
    label: String,
    modifier: Modifier = Modifier,
    initialFocus: Boolean = false,
    selected: Boolean? = null,
    onClick: () -> Unit
) {
    val requester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.focusRequester(requester).focusProperties { canFocus = true }.semantics {
            if (selected != null) this.selected = selected
        },
        interactionSource = interaction,
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) colors.primary else colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected == true) colors.secondaryContainer else colors.surface,
            contentColor = if (selected == true) colors.onSecondaryContainer else colors.onSurface
        )
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
    LaunchedEffect(initialFocus) {
        if (initialFocus) requester.requestFocus()
    }
}

@Composable
private fun SupportQrCode(url: String) {
    val matrix = remember(url) { QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(Modifier.size(216.dp).testTag("support-qr")) {
        // Keep whole-pixel modules and the encoder's four-module white quiet zone.
        val moduleSize = (size.minDimension / matrix.width).toInt().toFloat()
        val origin = ((size.minDimension - matrix.width * moduleSize) / 2).toInt()
        drawRect(Color.White)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Color.Black, Offset(origin + x * moduleSize, origin + y * moduleSize), Size(moduleSize, moduleSize))
                }
            }
        }
    }
}
