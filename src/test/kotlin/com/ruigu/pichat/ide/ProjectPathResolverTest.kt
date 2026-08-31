package com.ruigu.pichat.ide

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectPathResolverTest {
    @Test
    fun `resolves a relative diff path within the project`() {
        val root = createTempDirectory("pi-diff-project")
        try {
            val target = root.resolve("doc/investigation/report.md")
            target.parent.createDirectories()
            target.writeText("report")

            assertEquals(
                target.toFile().canonicalFile,
                resolveProjectPathWithinBase("doc/investigation/report.md", root.toString()),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects paths that escape the project`() {
        val root = createTempDirectory("pi-diff-project")
        try {
            assertNull(resolveProjectPathWithinBase("../outside.md", root.toString()))
            assertNull(resolveProjectPathWithinBase(root.resolveSibling("other/report.md").toString(), root.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
