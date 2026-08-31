package com.ruigu.pichat.ui

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reproduces Pi's enabledModels scope for the model picker.
 *
 * Pi's get_available_models RPC intentionally returns the complete model
 * catalogue. The interactive client applies enabledModels afterwards, so the
 * plugin has to apply the same provider/model and glob matching before
 * publishing the picker contents.
 */
internal object PiModelScope {
    data class ModelRef(val provider: String, val id: String)

    private val validThinkingLevels = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

    /**
     * Reads the effective enabledModels setting. A project setting overrides
     * the global setting, matching Pi's settings precedence; a missing key is
     * represented by null while an empty array means no scope is configured.
     */
    fun loadPatterns(globalSettings: Path, projectSettings: Path?): List<String>? {
        val project = projectSettings?.let(::readPatterns)
        return project ?: readPatterns(globalSettings)
    }

    /**
     * Resolves configured patterns to provider/id keys in pattern order.
     * When no pattern matches, Pi treats the scope as empty (all models).
     */
    fun resolveAllowedKeys(models: List<ModelRef>, patterns: List<String>?): Set<String> {
        if (patterns.isNullOrEmpty()) return models.mapTo(LinkedHashSet(), ::key)

        val allowed = LinkedHashSet<String>()
        for (configured in patterns) {
            val pattern = configured.trim()
            if (pattern.isEmpty()) continue

            // Pi first tries the complete value as an exact model reference,
            // which matters for providers whose IDs contain a colon.
            val exact = models.firstOrNull { matchesExact(it, pattern) }
            if (exact != null) {
                allowed.add(key(exact))
                continue
            }

            val glob = stripThinkingSuffix(pattern)
            val matching = if (hasGlob(glob)) {
                val regex = globToRegex(glob)
                models.filter { regex.matches("${it.provider}/${it.id}") || regex.matches(it.id) }
            } else {
                models.filter { matchesExact(it, glob) }
            }
            matching.forEach { allowed.add(key(it)) }
        }

        return if (allowed.isEmpty()) models.mapTo(LinkedHashSet(), ::key) else allowed
    }

    private fun readPatterns(path: Path): List<String>? {
        if (!Files.isRegularFile(path)) return null
        return try {
            val root = JsonParser.parseString(Files.readString(path))
            if (!root.isJsonObject || !root.asJsonObject.has("enabledModels")) return null
            val value = root.asJsonObject.get("enabledModels")
            if (!value.isJsonArray) return null
            value.asJsonArray.mapNotNull { element ->
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun key(model: ModelRef): String = "${model.provider}\u0000${model.id}"

    private fun matchesExact(model: ModelRef, pattern: String): Boolean =
        (pattern.contains('/') &&
            model.provider.equals(pattern.substringBefore('/'), ignoreCase = true) &&
            model.id.equals(pattern.substringAfter('/'), ignoreCase = true)) ||
            model.id.equals(pattern, ignoreCase = true)

    private fun stripThinkingSuffix(pattern: String): String {
        val colon = pattern.lastIndexOf(':')
        if (colon <= 0) return pattern
        val suffix = pattern.substring(colon + 1).lowercase()
        return if (suffix in validThinkingLevels) pattern.substring(0, colon) else pattern
    }

    private fun hasGlob(pattern: String): Boolean =
        pattern.any { it == '*' || it == '?' || it == '[' }

    /** Converts the minimatch subset used by model patterns to a safe regex. */
    private fun globToRegex(pattern: String): Regex {
        val out = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when (val ch = pattern[i]) {
                '*' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        out.append(".*")
                        i++
                    } else {
                        out.append("[^/]*")
                    }
                }
                '?' -> out.append("[^/]")
                '[' -> {
                    val end = pattern.indexOf(']', i + 1)
                    if (end > i + 1) {
                        val characterClass = pattern.substring(i + 1, end)
                        if (characterClass.startsWith("!")) {
                            out.append("[^").append(characterClass.substring(1)).append("]")
                        } else {
                            out.append("[").append(characterClass).append("]")
                        }
                        i = end
                    } else {
                        out.append("\\[")
                    }
                }
                else -> out.append(Regex.escape(ch.toString()))
            }
            i++
        }
        return Regex("$out$", setOf(RegexOption.IGNORE_CASE))
    }
}
