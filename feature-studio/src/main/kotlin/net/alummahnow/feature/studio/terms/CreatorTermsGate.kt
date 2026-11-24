package net.alummahnow.feature.studio.terms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.alummahnow.core.bridge.SupportLinks
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.components.FanousMaterialKind
import net.alummahnow.design.components.fanousMaterial
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/**
 * Terms of Service and Community Guidelines agreement shown once per account before the first upload
 * (Google Play UGC policy, AlUmmahNowAndroid#65).
 */
@Composable
fun CreatorTermsGate(
    saving: Boolean,
    error: String?,
    onOpenUrl: (String) -> Unit,
    onAgree: () -> Unit,
    modifier: Modifier = Modifier,
    initialChecked: Boolean = false
) {
    var checked by remember { mutableStateOf(initialChecked) }
    val lang = currentLocale().language
    Box(
        modifier = modifier
            .fillMaxSize()
            .fanousMaterial(FanousMaterialKind.Page, RectangleShape)
            .verticalScroll(rememberScrollState())
            .padding(FanousTokens.Spacing.lg)
            .testTag("creator-terms-gate"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .fanousMaterial(FanousMaterialKind.Card, RoundedCornerShape(FanousTokens.Radius.xl))
                .padding(FanousTokens.Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            Text(
                stringResource(R.string.studio_terms_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                stringResource(R.string.studio_terms_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)) {
                TextButton(
                    onClick = { onOpenUrl(SupportLinks.termsUrl(lang)) },
                    modifier = Modifier.testTag("creator-terms-link")
                ) { Text(stringResource(R.string.studio_terms_of_service)) }
                TextButton(
                    onClick = { onOpenUrl(SupportLinks.communityGuidelinesUrl(lang)) },
                    modifier = Modifier.testTag("creator-guidelines-link")
                ) { Text(stringResource(R.string.studio_community_guidelines)) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        enabled = !saving,
                        role = Role.Checkbox,
                        onValueChange = { checked = it }
                    )
                    .padding(vertical = FanousTokens.Spacing.xs)
                    .testTag("creator-terms-checkbox"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = !saving)
                Text(
                    stringResource(R.string.studio_terms_checkbox),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (!error.isNullOrBlank()) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .testTag("creator-terms-error")
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
            FanousButton(
                text = stringResource(if (saving) R.string.studio_terms_saving else R.string.studio_terms_agree),
                onClick = onAgree,
                kind = FanousButtonKind.Primary,
                enabled = checked && !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("creator-terms-agree")
            )
        }
    }
}

/** Shown while Creator Terms acceptance status is loading (fail closed). */
@Composable
fun CreatorTermsLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .fanousMaterial(FanousMaterialKind.Page, RectangleShape)
            .testTag("creator-terms-loading"),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/** Shown when Creator Terms acceptance status could not be loaded (fail closed with Retry). */
@Composable
fun CreatorTermsError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .fanousMaterial(FanousMaterialKind.Page, RectangleShape)
            .padding(FanousTokens.Spacing.lg)
            .testTag("creator-terms-failed"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .fanousMaterial(FanousMaterialKind.Card, RoundedCornerShape(FanousTokens.Radius.xl))
                .padding(FanousTokens.Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            Text(
                stringResource(R.string.studio_terms_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FanousButton(
                text = stringResource(net.alummahnow.design.R.string.aun_retry),
                onClick = onRetry,
                kind = FanousButtonKind.Primary,
                modifier = Modifier.testTag("creator-terms-retry")
            )
        }
    }
}
