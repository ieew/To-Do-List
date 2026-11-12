package com.todo.android.core.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 架构门禁 C1 / C2：
 * - core/domain 不得 import android / androidx / retrofit2 / okhttp3；
 * - core/domain 不得读取物理时钟（含 java.time 与 kotlinx.datetime Clock）。
 *
 * 用源码扫描实现，效果等价于 Konsist 规则；新增违规会直接让测试失败。
 */
class ArchitectureTest {
    private val sourceRoot = File("src/main/kotlin")

    private val forbiddenImports = listOf(
        "import android.",
        "import androidx.",
        "import retrofit2",
        "import okhttp3",
        "import com.squareup.",
        "import kotlinx.coroutines.Dispatchers",
    )

    private val forbiddenClockUsages = listOf(
        "System.currentTimeMillis",
        "System.nanoTime",
        "java.time",
        "kotlinx.datetime",
        "LocalDateTime.now",
        "ZonedDateTime.now",
        "Instant.now",
        "Clock.System.now",
    )

    @Test
    fun `领域层没有 Android 与网络依赖`() {
        val violations = scan(forbiddenImports)
        assertTrue("core/domain 出现禁止的 import：$violations", violations.isEmpty())
    }

    @Test
    fun `领域层不读取物理时钟`() {
        val violations = scan(forbiddenClockUsages)
        assertTrue("core/domain 读取了物理时钟：$violations", violations.isEmpty())
    }

    private fun scan(patterns: List<String>): List<String> {
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("未找到领域层源码目录 $sourceRoot", files.isNotEmpty())
        return files.flatMap { file ->
            val text = file.readText()
            patterns.filter { text.contains(it) }.map { "${file.name}: $it" }
        }
    }
}
