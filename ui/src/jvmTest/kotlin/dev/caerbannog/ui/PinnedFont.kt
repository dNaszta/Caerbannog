package dev.caerbannog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * M2-21: one known, deliberately wide font for the layout tests, so that a Compose UI layout assertion measures the same on the Mac, on Fedora and on the CI
 * runner. The product ships no font and sets no theme (it draws with the system sans face: SF/Helvetica on the Mac, DejaVu Sans on a plain Ubuntu or Fedora
 * install), so the pin is the widest realistic face, DejaVu Sans 2.37 (Book and Bold, as in the Debian package `fonts-dejavu-core`). A layout that holds
 * with this face holds with the narrower ones. The two TTF files are test resources (`ui/src/jvmTest/resources/fonts`, with their licence text); they do not ship in
 * the app, so `THIRD_PARTY_NOTICES.md` is not touched.
 */
internal object PinnedFont {
    private fun bytes(name: String): ByteArray =
        checkNotNull(PinnedFont::class.java.getResourceAsStream("/fonts/$name")) { "test font $name is missing from ui/src/jvmTest/resources/fonts" }.use { it.readBytes() }

    val family: FontFamily by lazy {
        FontFamily(
            Font("DejaVuSans", bytes("DejaVuSans.ttf"), FontWeight.Normal, FontStyle.Normal),
            Font("DejaVuSans-Bold", bytes("DejaVuSans-Bold.ttf"), FontWeight.Bold, FontStyle.Normal),
        )
    }

    /** The Material 3 default typography with every style set in [family]; sizes and line heights stay the product's. */
    val typography: Typography by lazy {
        val t = Typography()
        Typography(
            displayLarge = t.displayLarge.copy(fontFamily = family), displayMedium = t.displayMedium.copy(fontFamily = family), displaySmall = t.displaySmall.copy(fontFamily = family),
            headlineLarge = t.headlineLarge.copy(fontFamily = family), headlineMedium = t.headlineMedium.copy(fontFamily = family), headlineSmall = t.headlineSmall.copy(fontFamily = family),
            titleLarge = t.titleLarge.copy(fontFamily = family), titleMedium = t.titleMedium.copy(fontFamily = family), titleSmall = t.titleSmall.copy(fontFamily = family),
            bodyLarge = t.bodyLarge.copy(fontFamily = family), bodyMedium = t.bodyMedium.copy(fontFamily = family), bodySmall = t.bodySmall.copy(fontFamily = family),
            labelLarge = t.labelLarge.copy(fontFamily = family), labelMedium = t.labelMedium.copy(fontFamily = family), labelSmall = t.labelSmall.copy(fontFamily = family),
        )
    }
}

/** [content] in the pinned font. Nothing else of the theme changes (the product sets none, so this is the Material 3 default look). */
@Composable
internal fun PinnedFontTheme(content: @Composable () -> Unit) = MaterialTheme(typography = PinnedFont.typography, content = content)

/** `setContent` with the pinned font. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.setPinnedContent(content: @Composable () -> Unit) = setContent { PinnedFontTheme(content) }
