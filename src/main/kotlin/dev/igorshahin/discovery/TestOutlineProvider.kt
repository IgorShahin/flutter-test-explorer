package dev.igorshahin.discovery

import com.jetbrains.lang.dart.psi.DartFile
import dev.igorshahin.model.DartTestKind

/**
 * Discovery-side view of Dart Analysis Server test outlines.
 *
 * This boundary deliberately contains no run configuration or PSI call-expression contract:
 * analyzer recognition builds the model, while the execution package decides how to launch it.
 */
internal interface TestOutlineProvider {
    fun prepare(files: List<DartFile>) {}
    fun testItems(file: DartFile): List<DiscoveredTestOutline>
}

internal data class DiscoveredTestOutline(
    val kind: DartTestKind,
    val name: String,
    val offset: Int,
    val children: List<DiscoveredTestOutline> = emptyList(),
    val nameIsStatic: Boolean = true,
    val runtimeNameKnown: Boolean = nameIsStatic,
)
