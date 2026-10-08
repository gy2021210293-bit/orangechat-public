/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginQuickEntryTest {

    @Test
    fun onlySelectedHealthyPluginsWithSupportedUiAreListed() {
        assertNull(plugin(isEnabled = false, ui = PluginUIDeclaration()).toQuickEntryOrNull())
        assertNull(
            plugin(
                showInQuickEntry = false,
                ui = PluginUIDeclaration(),
            ).toQuickEntryOrNull()
        )
        assertNull(plugin(loadError = "load failed", ui = PluginUIDeclaration()).toQuickEntryOrNull())
        assertNull(plugin().toQuickEntryOrNull())
    }

    @Test
    fun disabledPluginCanBeSelectedForQuickEntry() {
        val entry = plugin(
            isEnabled = false,
            showInQuickEntry = true,
            ui = PluginUIDeclaration(),
        ).toQuickEntryOrNull()

        assertNotNull(entry)
        assertFalse(entry!!.isEnabled)
    }

    @Test
    fun declarativeUiHasPriority() {
        val entry = plugin(
            ui = PluginUIDeclaration(),
            webView = PluginWebViewPageConfig("ui/index.html"),
            customPage = "memory_bank",
        ).toQuickEntryOrNull()

        assertTrue(entry?.target is PluginQuickEntryTarget.DeclarativeUi)
    }

    @Test
    fun webViewEntryKeepsItsEntryPath() {
        val entry = plugin(
            webView = PluginWebViewPageConfig("ui/index.html"),
        ).toQuickEntryOrNull()

        assertEquals(
            PluginQuickEntryTarget.WebView("ui/index.html"),
            entry?.target,
        )
    }

    @Test
    fun supportedBuiltInPageGetsDirectEntry() {
        val entry = plugin(customPage = "memory_bank").toQuickEntryOrNull()

        assertEquals(PluginQuickEntryTarget.MemoryBank, entry?.target)
    }

    private fun plugin(
        isEnabled: Boolean = true,
        showInQuickEntry: Boolean = isEnabled,
        loadError: String? = null,
        ui: PluginUIDeclaration? = null,
        webView: PluginWebViewPageConfig? = null,
        customPage: String? = null,
    ): PluginInfo = PluginInfo(
        manifest = PluginManifest(
            id = "com.example.test",
            name = "Test Plugin",
            description = "",
            version = "1.0.0",
            author = "Test",
            icon = "🧩",
            entry = "index.js",
            ui = ui,
            customPageWebView = webView,
            customPage = customPage,
        ),
        directory = File("."),
        isEnabled = isEnabled,
        showInQuickEntry = showInQuickEntry,
        loadError = loadError,
    )
}
