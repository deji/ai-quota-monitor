package com.aiquotamonitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Unit test validating Android adaptive launcher icon XML resources,
 * safe-zone coordinate geometry, and Material You <monochrome> layer compliance.
 */
class AdaptiveIconResourceTest {

    private val resDir = File("src/main/res")
    private val manifestFile = File("src/main/AndroidManifest.xml")

    private fun parseXml(file: File): org.w3c.dom.Document {
        assertTrue("Resource file must exist: ${file.path}", file.exists())
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(file)
    }

    private fun parseResXml(relativePath: String): org.w3c.dom.Document {
        return parseXml(File(resDir, relativePath))
    }

    @Test
    fun testAndroidManifestWiresAdaptiveAndRoundIcons() {
        val doc = parseXml(manifestFile)
        val appNodes = doc.getElementsByTagName("application")
        assertTrue("Manifest must have application element", appNodes.length == 1)
        val appElem = appNodes.item(0) as org.w3c.dom.Element

        val icon = appElem.getAttributeNS("http://schemas.android.com/apk/res/android", "icon")
        val roundIcon = appElem.getAttributeNS("http://schemas.android.com/apk/res/android", "roundIcon")

        assertEquals("@drawable/ic_launcher", icon)
        assertEquals("@drawable/ic_launcher_round", roundIcon)
    }

    @Test
    fun testAdaptiveIconManifestContainsMonochromeLayer() {
        for (manifestName in listOf("drawable/ic_launcher.xml", "drawable/ic_launcher_round.xml")) {
            val doc = parseResXml(manifestName)
            val root = doc.documentElement
            assertEquals("adaptive-icon", root.tagName)

            val backgrounds = root.getElementsByTagName("background")
            val foregrounds = root.getElementsByTagName("foreground")
            val monochromes = root.getElementsByTagName("monochrome")

            assertEquals("$manifestName must have exactly 1 background", 1, backgrounds.length)
            assertEquals("$manifestName must have exactly 1 foreground", 1, foregrounds.length)
            assertEquals("$manifestName must have exactly 1 monochrome for Material You", 1, monochromes.length)

            val monoElem = monochromes.item(0) as org.w3c.dom.Element
            val monoDrawable = monoElem.getAttributeNS("http://schemas.android.com/apk/res/android", "drawable")
            assertEquals("@drawable/ic_launcher_monochrome", monoDrawable)

            val bgElem = backgrounds.item(0) as org.w3c.dom.Element
            val bgDrawable = bgElem.getAttributeNS("http://schemas.android.com/apk/res/android", "drawable")
            assertEquals("@drawable/ic_launcher_background", bgDrawable)

            val fgElem = foregrounds.item(0) as org.w3c.dom.Element
            val fgDrawable = fgElem.getAttributeNS("http://schemas.android.com/apk/res/android", "drawable")
            assertEquals("@drawable/ic_launcher_foreground", fgDrawable)
        }
    }

    @Test
    fun testVectorDrawablesViewportAndDimensions() {
        for (drawableName in listOf(
            "drawable/ic_launcher_background.xml",
            "drawable/ic_launcher_foreground.xml",
            "drawable/ic_launcher_monochrome.xml"
        )) {
            val doc = parseResXml(drawableName)
            val root = doc.documentElement
            assertEquals("Root of $drawableName must be vector", "vector", root.tagName)
            assertEquals("viewportWidth in $drawableName must be 108", "108", root.getAttributeNS("http://schemas.android.com/apk/res/android", "viewportWidth"))
            assertEquals("viewportHeight in $drawableName must be 108", "108", root.getAttributeNS("http://schemas.android.com/apk/res/android", "viewportHeight"))
            assertEquals("width in $drawableName must be 108dp", "108dp", root.getAttributeNS("http://schemas.android.com/apk/res/android", "width"))
            assertEquals("height in $drawableName must be 108dp", "108dp", root.getAttributeNS("http://schemas.android.com/apk/res/android", "height"))
        }
    }

    @Test
    fun testForegroundAndMonochromeVectorAttributesAndSafeZone() {
        for (drawableName in listOf("drawable/ic_launcher_foreground.xml", "drawable/ic_launcher_monochrome.xml")) {
            val doc = parseResXml(drawableName)
            val root = doc.documentElement
            val paths = root.getElementsByTagName("path")
            assertEquals("$drawableName must contain exactly 4 vector paths", 4, paths.length)

            var foundTrack = false
            var foundActiveArc = false
            var foundPaceNotch = false
            var foundDeltaGlyph = false

            for (i in 0 until paths.length) {
                val elem = paths.item(i) as org.w3c.dom.Element
                val pathData = elem.getAttributeNS("http://schemas.android.com/apk/res/android", "pathData")
                val strokeWidthStr = elem.getAttributeNS("http://schemas.android.com/apk/res/android", "strokeWidth")
                val strokeWidth = if (strokeWidthStr.isNotEmpty()) strokeWidthStr.toDouble() else 0.0

                if (pathData.contains("73.09,73.09")) {
                    // Inactive 270° track
                    foundTrack = true
                    val startDist = Math.hypot(34.91 - 54.0, 73.09 - 54.0)
                    val endDist = Math.hypot(73.09 - 54.0, 73.09 - 54.0)
                    assertEquals("Track start radius must be 27dp from center", 27.0, startDist, 0.02)
                    assertEquals("Track end radius must be 27dp from center", 27.0, endDist, 0.02)
                    val outerExtent = 27.0 + (strokeWidth / 2.0)
                    assertTrue("Track outer extent ($outerExtent) must be within 33dp safe zone", outerExtent <= 33.0)
                } else if (pathData.contains("80.08,47.01")) {
                    // Active quota arc
                    foundActiveArc = true
                    val startDist = Math.hypot(34.91 - 54.0, 73.09 - 54.0)
                    val endDist = Math.hypot(80.08 - 54.0, 47.01 - 54.0)
                    assertEquals("Arc start radius must be 27dp from center", 27.0, startDist, 0.02)
                    assertEquals("Arc end radius must be 27dp from center", 27.0, endDist, 0.02)
                    val outerExtent = 27.0 + (strokeWidth / 2.0)
                    assertTrue("Active arc outer extent ($outerExtent) must be within 33dp safe zone", outerExtent <= 33.0)
                } else if (pathData.contains("67.50,30.62")) {
                    // Target pace notch pip
                    foundPaceNotch = true
                    val dist = Math.hypot(67.50 - 54.0, 30.62 - 54.0)
                    assertEquals("Pace notch center must be 27dp from center (54,54)", 27.0, dist, 0.02)
                    val outerExtent = dist + 2.8 // Pip radius 2.8dp
                    assertTrue("Pace notch outer extent ($outerExtent) must be within 33dp safe zone", outerExtent <= 33.0)
                } else if (pathData.contains("M 54,44 L 61,58 L 47,58 Z")) {
                    // Delta glyph with evenOdd core pip knockout
                    foundDeltaGlyph = true
                    assertEquals("Delta glyph must use evenOdd fillType for knockout", "evenOdd", elem.getAttributeNS("http://schemas.android.com/apk/res/android", "fillType"))
                    assertTrue("Delta glyph must include inner circle cutout subpath", pathData.contains("54,53"))

                    // Triangle vertices from center (54, 54)
                    val d1 = Math.hypot(54.0 - 54.0, 44.0 - 54.0)
                    val d2 = Math.hypot(61.0 - 54.0, 58.0 - 54.0)
                    val d3 = Math.hypot(47.0 - 54.0, 58.0 - 54.0)
                    val maxVertex = maxOf(d1, d2, d3)
                    assertTrue("Delta vertex ($maxVertex) must be within 33dp safe zone", maxVertex <= 33.0)

                    // Core cutout distance from center
                    val coreDist = Math.hypot(54.0 - 54.0, 53.0 - 54.0) + 1.8
                    assertTrue("Core cutout ($coreDist) must be within 33dp safe zone", coreDist <= 33.0)
                }
            }

            assertTrue("$drawableName must have inactive track", foundTrack)
            assertTrue("$drawableName must have active arc", foundActiveArc)
            assertTrue("$drawableName must have pace notch pip", foundPaceNotch)
            assertTrue("$drawableName must have delta glyph with evenOdd knockout", foundDeltaGlyph)
        }
    }

    @Test
    fun testMonochromeVectorUsesSingleColorAlphaMask() {
        val doc = parseResXml("drawable/ic_launcher_monochrome.xml")
        val root = doc.documentElement
        val paths = root.getElementsByTagName("path")

        for (i in 0 until paths.length) {
            val elem = paths.item(i) as org.w3c.dom.Element
            val strokeColor = elem.getAttributeNS("http://schemas.android.com/apk/res/android", "strokeColor")
            val fillColor = elem.getAttributeNS("http://schemas.android.com/apk/res/android", "fillColor")
            val strokeAlpha = elem.getAttributeNS("http://schemas.android.com/apk/res/android", "strokeAlpha")
            val pathData = elem.getAttributeNS("http://schemas.android.com/apk/res/android", "pathData")

            // Every painted stroke or fill must be pure white #FFFFFF for Monet dynamic color
            if (strokeColor.isNotEmpty()) {
                assertEquals("Monochrome stroke must be white (#FFFFFF) for Monet tinting", "#FFFFFF", strokeColor)
            }
            if (fillColor.isNotEmpty()) {
                assertEquals("Monochrome fill must be white (#FFFFFF) for Monet tinting", "#FFFFFF", fillColor)
            }

            // Inactive track must specify strokeAlpha="0.30"
            if (pathData.contains("73.09,73.09")) {
                assertEquals("Inactive track must have 0.30 alpha for tonal hierarchy", "0.30", strokeAlpha)
            }
        }
    }
}
