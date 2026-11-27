package net.alummahnow.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.alummahnow.core.bridge.SupportLinks
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsSheet(
    theme: ThemeMode?,
    language: String?,
    languageSwitchSupported: Boolean,
    onTheme: (ThemeMode) -> Unit,
    onLanguage: (String?) -> Unit,
    onDismiss: () -> Unit,
    onOpenUrl: ((String) -> Unit)? = null,
    onOpenBlockedList: (() -> Unit)? = null,
    webHost: String,
    telemetryEnabled: Boolean = false,
    onTelemetryChanged: ((Boolean) -> Unit)? = null,
    telemetryWarning: String? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        DisplaySettingsSheetContent(
            theme = theme,
            language = language,
            languageSwitchSupported = languageSwitchSupported,
            onTheme = onTheme,
            onLanguage = onLanguage,
            onDismiss = onDismiss,
            onOpenUrl = onOpenUrl,
            onOpenBlockedList = onOpenBlockedList,
            webHost = webHost,
            telemetryEnabled = telemetryEnabled,
            onTelemetryChanged = onTelemetryChanged,
            telemetryWarning = telemetryWarning
        )
    }
}

@Composable
internal fun DisplaySettingsSheetContent(
    theme: ThemeMode?,
    language: String?,
    languageSwitchSupported: Boolean,
    onTheme: (ThemeMode) -> Unit,
    onLanguage: (String?) -> Unit,
    onDismiss: () -> Unit,
    onOpenUrl: ((String) -> Unit)? = null,
    onOpenBlockedList: (() -> Unit)? = null,
    webHost: String,
    telemetryEnabled: Boolean = false,
    onTelemetryChanged: ((Boolean) -> Unit)? = null,
    telemetryWarning: String? = null
) {
    val langCode = language ?: currentLocale().language
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Title(stringResource(R.string.settings_theme))
        Option(stringResource(R.string.settings_theme_dark), theme == ThemeMode.DARK) { onTheme(ThemeMode.DARK) }
        Option(stringResource(R.string.settings_theme_light), theme == ThemeMode.LIGHT) { onTheme(ThemeMode.LIGHT) }
        if (languageSwitchSupported) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Title(stringResource(R.string.settings_language))
            Option(stringResource(R.string.settings_language_system), language == null) { onLanguage(null) }
            // Language names are shown in their own script so either speaker can find theirs.
            Option("العربية", language == "ar") { onLanguage("ar") }
            Option("English", language == "en") { onLanguage("en") }
        }
        if (onTelemetryChanged != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { onTelemetryChanged(!telemetryEnabled) }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .testTag("settings-diagnostics"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        stringResource(DesignR.string.aun_diagnostics_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(DesignR.string.aun_diagnostics_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!telemetryWarning.isNullOrEmpty()) {
                        Text(
                            telemetryWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp).testTag("settings-diagnostics-warning")
                        )
                    }
                }
                Switch(checked = telemetryEnabled, onCheckedChange = onTelemetryChanged)
            }
        }
        if (onOpenBlockedList != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpenBlockedList() }
                    .padding(horizontal = 24.dp, vertical = 14.dp)
                    .testTag("settings-blocked-list"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_blocked_list),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (onOpenUrl != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LinkRow(stringResource(R.string.settings_help), "settings-help") {
                onDismiss()
                onOpenUrl(SupportLinks.helpUrl(langCode, webHost))
            }
            LinkRow(stringResource(R.string.settings_contact), "settings-contact") {
                onDismiss()
                onOpenUrl(SupportLinks.contactUrl(langCode, webHost))
            }
            LinkRow(stringResource(R.string.settings_privacy_policy), "settings-privacy") {
                onDismiss()
                onOpenUrl(SupportLinks.privacyUrl(langCode, webHost))
            }
            LinkRow(stringResource(R.string.settings_terms_of_service), "settings-terms") {
                onDismiss()
                onOpenUrl(SupportLinks.termsUrl(langCode, webHost))
            }
        }
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(
            selected,
            role = Role.RadioButton,
            onClick = onClick
        ).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp))
    }
}

@Composable
private fun LinkRow(label: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
