package dev.igorshahin.discovery

import com.jetbrains.lang.dart.psi.DartFile
import dev.igorshahin.model.DartTestKind

/**
 * Discovery-side view of Dart Analysis Server test outlines, and the only source of the test
 * hierarchy. The analyzer decides what a test is and how tests nest; discovery maps that answer
 * into the explorer model without re-deriving it from Dart syntax.
 *
 * This boundary deliberately carries no run-configuration and no PSI call-expression contract:
 * analyzer recognition builds the model, and the execution package decides how to launch it.
 */
internal interface TestOutlineProvider {
    /**
     * Makes the outlines of [files] available. This is a request about those files only - never a
     * statement that they are the complete candidate set - so a provider must not release the
     * files it was not asked about.
     */
    fun prepare(files: List<DartFile>) {}

    /** The test entities the analyzer currently reports for [file], or empty while it has none. */
    fun testItems(file: DartFile): List<DiscoveredTestOutline>
}

/** One analyzer test entity, already classified and nested as the analyzer reported it. */
internal data class DiscoveredTestOutline(
    val kind: DartTestKind,
    val name: String,
    val offset: Int,
    val children: List<DiscoveredTestOutline> = emptyList(),
    /**
     * True only when this entity's own reported name is the name the test runner will report.
     * Ancestry is combined per level by the tree builder, which owns the logical path.
     */
    val runtimeNameKnown: Boolean = true,
)
