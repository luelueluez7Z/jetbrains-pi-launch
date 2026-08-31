package com.ruigu.pichat.ui

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class PiModelScopeTest {
    private val models = listOf(
        PiModelScope.ModelRef("volcano", "glm-5.3-flash"),
        PiModelScope.ModelRef("volcano", "glm-5.3"),
        PiModelScope.ModelRef("openai-codex", "gpt-5.6-sol"),
        PiModelScope.ModelRef("openai-codex", "gpt-5.5"),
    )

    @Test
    fun `empty scope keeps every model`() {
        assertEquals(4, PiModelScope.resolveAllowedKeys(models, null).size)
        assertEquals(4, PiModelScope.resolveAllowedKeys(models, emptyList()).size)
    }

    @Test
    fun `provider glob and bare model glob are matched case insensitively`() {
        val allowed = PiModelScope.resolveAllowedKeys(models, listOf("VOLCANO/*", "gpt-5.6*"))
        assertEquals(
            setOf("volcano\u0000glm-5.3-flash", "volcano\u0000glm-5.3", "openai-codex\u0000gpt-5.6-sol"),
            allowed,
        )
    }

    @Test
    fun `invalid patterns fall back to all models like pi`() {
        assertEquals(4, PiModelScope.resolveAllowedKeys(models, listOf("missing/*")).size)
    }

    @Test
    fun `project enabledModels overrides global setting`() {
        val root = createTempDirectory("pi-model-scope")
        val global = root.resolve("global.json").apply {
            writeText("{\"enabledModels\":[\"volcano/*\"]}")
        }
        val project = root.resolve("project.json").apply {
            writeText("{\"enabledModels\":[\"openai-codex/gpt-5.6-sol\"]}")
        }
        assertEquals(
            listOf("openai-codex/gpt-5.6-sol"),
            PiModelScope.loadPatterns(global, project),
        )
    }
}
