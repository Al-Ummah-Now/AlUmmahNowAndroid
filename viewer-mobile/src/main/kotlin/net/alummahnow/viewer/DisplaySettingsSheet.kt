package net.alummahnow.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.alummahnow.design.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsSheet(
    theme: ThemeMode,
    language: String?,
    languageSwitchSupported: Boolean,
    onTheme: (ThemeMode) -> Unit,
    onLanguage: (String?) -> Unit,
    onDismiss: () -> Unit,
    account: @Composable () -> Unit = {},
    coreVersion: String? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            account()
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Title(stringResource(R.string.settings_theme))
            Option(stringResource(R.string.settings_theme_system), theme == ThemeMode.SYSTEM) { onTheme(ThemeMode.SYSTEM) }
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
            coreVersion?.let {
                Text(
                    stringResource(R.string.settings_core_version, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
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
