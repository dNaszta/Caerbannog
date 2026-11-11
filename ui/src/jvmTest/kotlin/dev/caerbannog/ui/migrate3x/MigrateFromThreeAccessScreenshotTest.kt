package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.GenerationReport
import dev.caerbannog.core.migrate3x.GenerationState
import dev.caerbannog.core.migrate3x.How
import dev.caerbannog.core.migrate3x.LeftOutRow
import dev.caerbannog.core.migrate3x.ReviewState
import dev.caerbannog.core.migrate3x.SavedFile
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

/** M8-12b: opt-in screenshot (tag `screenshot`) of mockup section 10b of `docs/ux/Migrate3x.dc.html`: a saved result with the access file, the warning and the two lists. File `migrate3x-access-01-saved.png`. */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeAccessScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    @Test
    fun `saved result with access data`() = runDesktopComposeUiTest(1280, 1000) {
        val warning = "This file contains password hashes. Anyone who has it can try to guess the passwords offline. Keep it secret: do not commit it to git or put it in a shared or synced folder."
        val report = GenerationReport(
            How.Saved, "2 migrations saved",
            listOf(SavedFile("0001", "access", "Access migrated from RabbitMQ 3.x legacy-eu-definitions.json", 9, "/Users/dana/work/migrations/0001.json"), SavedFile("0002", "/", "Migrated from RabbitMQ 3.x legacy-eu-definitions.json vhost /", 214, "/Users/dana/work/migrations/0002.json")),
            emptyList(), listOf("The default virtual host / already exists on 4.x and is not changed"), null,
            "Access: 1 virtual host, 4 users, 6 permissions, 1 topic permission",
            listOf(LeftOutRow("guest", "not created; it has a publicly known password"), LeftOutRow("old", "set a new password on the broker; its password hash uses rabbit_password_hashing_bcrypt, which 4.x does not accept"), LeftOutRow("ldap-user", "no password hash in the export; set a password on the broker if it needs one")),
            listOf(LeftOutRow("guest on /", "the user is not migrated"), LeftOutRow("old on orders", "the user is not migrated")),
            warning,
        )
        show(MigrateScreenFiles.report().copy(review = ReviewState(1, 0, 1, 0, 0, "Every item is decided", null, false, null, GenerationState.Done(report))))
        waitForIdle()
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, "migrate3x-access-01-saved.png")
        check(ImageIO.write(image, "png", file)) { "no PNG writer" }
        assertTrue(file.length() > 0)
    }
}
