package io.github.workeron9.ozmoz.lineage.ir

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolchainTest {
    @Test
    fun runsOnJdk21() {
        assertEquals(21, Runtime.version().feature())
    }
}
