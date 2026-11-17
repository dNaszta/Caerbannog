package dev.caerbannog.ui.planner

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/** M9-11 step 4: opt-in screenshots (tag `screenshot`) of the headers rows of the binding form, `ui/build/screenshots/planner-headers-*.png`. Asserts nothing about the picture. */
@Tag("screenshot")
@OptIn(ExperimentalTestApi::class)
class HeadersBindingScreenshotTest {
    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private fun DesktopComposeUiTest.capture(name: String) {
        waitForIdle()
        outDir.mkdirs()
        check(ImageIO.write(onAllNodes(androidx.compose.ui.test.isRoot()).onLast().captureToImage().toAwtImage(), "png", File(outDir, "planner-headers-$name.png")))
    }

    private fun shoot(prefix: String, w: Int, h: Int) = runDesktopComposeUiTest(w, h) {
        val r = UiRig { queue("reports"); exchange("documents", "headers") }
        showPlanner(r)
        node("planner:addStepBtn").performClick(); node("planWizard:kind:Binding").performClick(); node("planWizard:next").performClick()
        node("planForm:exchange").performTextInput("documents"); node("planForm:destination").performTextInput("reports")
        capture("$prefix-1-empty")
        node("planForm:addHeader").performScrollTo().performClick()
        node("planForm:addHeader").performScrollTo().performClick()
        node("planForm:addHeader").performScrollTo().performClick()
        node("planForm:header:0:name").performScrollTo().performTextInput("format")
        node("planForm:header:0:value").performScrollTo().performTextInput("pdf")
        node("planForm:header:1:type:number").performScrollTo().performClick()
        node("planForm:header:1:name").performScrollTo().performTextInput("pages")
        node("planForm:header:1:value").performScrollTo().performTextInput("12x")
        node("planForm:header:2:type:flag").performScrollTo().performClick()
        node("planForm:header:2:name").performScrollTo().performTextInput("format")
        capture("$prefix-2-three-rows-errors")
        node("planForm:error:headers").performScrollTo()
        capture("$prefix-2b-error-scrolled")
        node("planForm:matchRule:any").performScrollTo().performClick()
        capture("$prefix-3-any")
        r.close()
    }

    @Test fun `700 x 700`() = shoot("700", 700, 700)
    @Test fun `1280 x 800`() = shoot("1280", 1280, 800)

    @Test fun `summary and graph of a headers binding`() = runDesktopComposeUiTest(1280, 900) {
        val r = UiRig { queue("reports"); exchange("documents", "headers") }
        showPlanner(r)
        node("planner:addStepBtn").performClick(); node("planWizard:kind:Binding").performClick(); node("planWizard:next").performClick()
        node("planForm:exchange").performTextInput("documents"); node("planForm:destination").performTextInput("reports")
        node("planForm:addHeader").performScrollTo().performClick()
        node("planForm:header:0:name").performScrollTo().performTextInput("format")
        node("planForm:header:0:value").performScrollTo().performTextInput("pdf")
        node("planWizard:next").performClick()
        capture("summary")
        node("planWizard:add").performClick()
        capture("graph")
        r.close()
    }
}
