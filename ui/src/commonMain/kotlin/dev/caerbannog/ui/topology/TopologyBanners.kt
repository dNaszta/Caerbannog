package dev.caerbannog.ui.topology

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.topology.TopologyBanner
import dev.caerbannog.core.topology.TopologyBannerKind
import dev.caerbannog.ui.connections.AppIcon
import dev.caerbannog.ui.connections.ConnectionIcon
import dev.caerbannog.ui.connections.SmallIconSize

// The mockup's --warn-container (amber), as in the profile card: a declaration is a claim, not an error.
private val WarnContainer = Color(0xFFFFE2B0)
private val OnWarnContainer = Color(0xFF3B2700)

private class BannerLook(val icon: AppIcon, val container: Color, val content: Color)

@Composable
private fun TopologyBannerKind.look(): BannerLook = when (this) {
    TopologyBannerKind.ReadOnlyLevel ->
        BannerLook(AppIcon.Lock, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    TopologyBannerKind.NoConfigure, TopologyBannerKind.NotReadable ->
        BannerLook(AppIcon.Lock, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    TopologyBannerKind.DeclaredNotConfirmed -> BannerLook(AppIcon.Info, WarnContainer, OnWarnContainer)
    TopologyBannerKind.OtherVhost ->
        BannerLook(AppIcon.Dns, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
}

/**
 * Why this screen is read-only (the one reason the caller picks). Information only: no button, so it never looks like something to act on.
 * The row carries the whole text itself and the visible texts are hidden from the tree, so a screen reader says it once.
 */
@Composable
internal fun TopologyReasonBanner(banner: TopologyBanner) {
    val look = banner.kind.look()
    val full = listOfNotNull(banner.headline, banner.detail).joinToString(" ")
    Surface(color = look.container, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .testTag("topologyBanner:${banner.kind.name}")
                .semantics { text = AnnotatedString(full); liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionIcon(look.icon, SmallIconSize, tint = look.content)
            Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(banner.headline, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = look.content)
                banner.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = look.content) }
            }
        }
    }
}
