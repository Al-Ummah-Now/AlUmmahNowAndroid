package net.alummahnow.player.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import java.util.Locale
import net.alummahnow.design.format.localizedDecimal
import net.alummahnow.design.format.localizedDigits
import net.alummahnow.player.PlaybackSpeeds
import net.alummahnow.player.R
import net.alummahnow.player.TrackOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(player: Player, ui: PlayerUiState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PlayerSettingsContent(player, ui)
    }
}

@Composable
fun PlayerSettingsContent(player: Player, ui: PlayerUiState) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        val qualities = TrackOptions.qualities(ui.tracks)
        val selectedQuality = TrackOptions.selectedQuality(ui.params, ui.tracks)
        SectionTitle(stringResource(R.string.aun_player_quality))
        qualities.forEach { q ->
            val label = if (q.isAuto) {
                stringResource(
                    R.string.aun_player_quality_auto
                )
            } else {
                localizedDigits(stringResource(R.string.aun_player_quality_p, q.height!!))
            }
            Choice(label, selected = q.height == selectedQuality.height) {
                player.trackSelectionParameters = TrackOptions.applyQuality(player.trackSelectionParameters, ui.tracks, q)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle(stringResource(R.string.aun_player_speed))
        PlaybackSpeeds.forEach { s ->
            val label = if (s == 1f) {
                stringResource(R.string.aun_player_speed_normal)
            } else {
                localizedDecimal(stringResource(R.string.aun_player_speed_x, "%.2f".format(Locale.ROOT, s).trimEnd('0').trimEnd('.')))
            }
            Choice(label, selected = ui.speed == s) { player.setPlaybackSpeed(s) }
        }
        val subtitles = TrackOptions.subtitles(ui.tracks)
        if (subtitles.size > 1) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.aun_player_subtitles))
            val selected = TrackOptions.selectedSubtitle(ui.params, ui.tracks)
            subtitles.forEach { option ->
                val label = option.label ?: option.language ?: stringResource(R.string.aun_player_subtitles_off)
                Choice(label, selected = option.language == selected.language) {
                    player.trackSelectionParameters = TrackOptions.applySubtitle(player.trackSelectionParameters, option)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
    }
}
