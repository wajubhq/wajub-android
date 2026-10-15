package com.wajub.mobile.readme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every README sample compiles: each of its lines is in ReadmeSamples.kt, which the tests compile. */
class ReadmeSamplesTest {
    private fun normalize(line: String) = line.trim().replace(Regex("\\s+"), " ")

    @Test
    fun `the README's Kotlin samples are compiled`() {
        val readme = File("../README.md").readText()
        val samples = File("src/test/kotlin/com/wajub/mobile/readme/ReadmeSamples.kt").readLines().map(::normalize).toSet()
        val blocks = Regex("```kotlin\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(readme)
            .map { it.groupValues[1] }
            // The Gradle snippet is build script, not app code.
            .filterNot { "implementation(\"com.wajub" in it }
            .toList()
        assertTrue(blocks.size >= 5, "found ${blocks.size} samples")
        for (block in blocks) {
            for (line in block.lines().map(::normalize).filter { it.isNotEmpty() }) {
                assertTrue(line in samples, "README line not in ReadmeSamples.kt: $line")
            }
        }
    }
}
