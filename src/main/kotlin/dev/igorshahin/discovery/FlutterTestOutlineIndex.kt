package dev.igorshahin.discovery

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.lang.dart.psi.DartFile
import dev.igorshahin.model.DartTestKind
import io.flutter.dart.FlutterDartAnalysisServer
import io.flutter.dart.FlutterOutlineListener
import com.google.gson.JsonObject
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.util.HexFormat
import com.google.dart.server.AnalysisServerListenerAdapter
import com.jetbrains.lang.dart.analyzer.DartAnalysisServerService

/** Same analyzer entities as gutter markers, including unopened files. The mapping mirrors the
 * private CommonTestConfigUtils.OutlineCache in Flutter 95.0.0; see the compatibility notes.
 */
internal class FlutterTestOutlineIndex(
    private val project: Project,
    private val changed: (String?) -> Unit,
    private val outlineRequestOverride: ((String) -> Unit)?,
) : Disposable, TestOutlineProvider {
    constructor(project: Project, changed: (String?) -> Unit) : this(project, changed, null)
    private data class Snapshot(val outline: AnalyzerOutline, val sourceDigest: String, val revision: Long)
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val awaitingAnalysis = ConcurrentHashMap.newKeySet<String>()
    private val requestedSourceDigests = ConcurrentHashMap<String, String>()
    private val listeners = mutableMapOf<String, FlutterOutlineListener>()
    @Volatile private var disposed = false
    @Volatile private var waitingForConnection = false
    @Volatile private var reconnectPending = false
    private val revisionCounter = java.util.concurrent.atomic.AtomicLong()
    private val serverListener = object : AnalysisServerListenerAdapter() {
        override fun serverConnected(version: String) {
            // addOutlineListener is a no-op before the server connects. Retry discovery then.
            if (!disposed) {
                reconnectPending = true
                changed(null)
            }
        }
        override fun serverStatus(status: org.dartlang.analysis.server.protocol.AnalysisStatus?,
                                  pubStatus: org.dartlang.analysis.server.protocol.PubStatus?) {
            if (!disposed && waitingForConnection) changed(null)
        }
    }

    init {
        DartAnalysisServerService.getInstance(project).addAnalysisServerListener(serverListener)
    }

    /**
     * Declares the complete candidate set: subscribes to [files] and releases every other
     * subscription. Only the owner of candidate reconciliation may call this.
     */
    @Synchronized
    fun watch(files: List<VirtualFile>) {
        if (disposed) return
        val server = connectedServer() ?: return
        val paths = files.map { it.path }.toSet()
        (if (reconnectPending) listeners.keys.toSet() else listeners.keys - paths).forEach { path ->
            server.removeOutlineListener(server.analysisService.getLocalFileUri(path), listeners.remove(path)!!)
            snapshots.remove(path)
            awaitingAnalysis.remove(path)
            requestedSourceDigests.remove(path)
        }
        reconnectPending = false
        subscribe(server, files)
    }

    /**
     * Discovery of a subset - a single cache miss, or one file in a fixture - only has to make
     * those outlines available. It must never speak for the candidate set: releasing the files it
     * was not asked about would drop their snapshots, and the analyzer does not resend an outline
     * for a source that did not change.
     */
    @Synchronized
    override fun prepare(files: List<DartFile>) {
        if (disposed) return
        val server = connectedServer() ?: return
        subscribe(server, files.mapNotNull { it.virtualFile })
    }

    private fun connectedServer(): FlutterDartAnalysisServer? {
        val server = FlutterDartAnalysisServer.getInstance(project)
        waitingForConnection = !server.isServerConnected
        return server.takeUnless { waitingForConnection }
    }

    private fun subscribe(server: FlutterDartAnalysisServer, files: List<VirtualFile>) {
        files.forEach { file ->
            if (file.path in listeners) return@forEach
            val subscribedPath = file.path
            // Dart and Flutter both bundle FlutterOutline under the SAME package name. A typed
            // Kotlin SAM links the wrong copy under some dependency orders and breaks Flutter.
            // Keep that protocol type entirely within its owning loader; cross via public JSON.
            val listener = Proxy.newProxyInstance(FlutterOutlineListener::class.java.classLoader,
                arrayOf(FlutterOutlineListener::class.java)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "TestExplorerOutlineListener(${file.name})"
                    "outlineUpdated" -> {
                        if (!disposed && file.isValid && file.path == subscribedPath) {
                            val outline = decodeOutline(requireNotNull(args?.get(1)))
                            ReadAction.run<RuntimeException> {
                                com.intellij.psi.PsiManager.getInstance(project).findFile(file)?.let { recordOutline(it, outline) }
                            }
                        }
                        null
                    }
                    else -> null
                }
            } as FlutterOutlineListener
            listeners[file.path] = listener
            LOG.debug("Test Explorer subscribing to analyzer outline for ${file.name}")
            server.addOutlineListener(FileUtil.toSystemDependentName(file.path), listener)
        }
    }

    fun revision(path: String): Long = snapshots[path]?.revision ?: 0L
    fun isAwaitingAnalysis(path: String): Boolean = path in awaitingAnalysis

    /** Loader-independent boundary shared by analyzer callbacks and regression fixtures. */
    internal fun recordOutline(file: PsiFile, outline: AnalyzerOutline) {
        val path = file.virtualFile.path
        val digest = sourceDigest(file)
        requestedSourceDigests.remove(path)
        val previous = snapshots[path]
        if (previous?.outline != outline || previous.sourceDigest != digest) {
            snapshots[path] = Snapshot(outline, digest, revisionCounter.incrementAndGet())
            LOG.debug("Test Explorer received analyzer outline for ${file.name}")
            changed(path)
        }
    }

    private fun currentOutline(file: PsiFile): AnalyzerOutline? {
        val path = file.virtualFile.path
        val snapshot = snapshots[path]
        val outline = if (snapshot == null) {
            val active = io.flutter.editor.ActiveEditorsOutlineService.getInstance(project)
            val activeOutline = active.javaClass.getMethod("getIfUpdated", PsiFile::class.java).invoke(active, file)
            if (activeOutline == null) return pending(path, "no outline")
            decodeOutline(activeOutline).also { recordOutline(file, it) }
        } else {
            // PsiFileImpl.clearCaches() increments modificationStamp even when no source changed.
            // The analyzer does not resend outlines for a PSI-only invalidation. Bind snapshots to
            // source contents instead; also reject same-length unsaved edits (length alone is unsafe).
            val currentDigest = sourceDigest(file)
            if (snapshot.sourceDigest != currentDigest) {
                requestFreshOutline(path, currentDigest)
                return pending(path, "source changed")
            }
            snapshot.outline
        }
        if (file.textLength != outline.length) {
            val convertedLength = DartAnalysisServerService.getInstance(project)
                .getConvertedOffset(file.virtualFile, outline.length)
            if (file.textLength != convertedLength) return pending(path, "outline length mismatch")
        }
        awaitingAnalysis.remove(path)
        return outline
    }

    private fun pending(path: String, reason: String): AnalyzerOutline? {
        awaitingAnalysis += path
        LOG.debug("Test Explorer waiting for analyzer outline ($reason): $path")
        return null
    }

    /**
     * Builds the explorer hierarchy directly from the official analyzer outline. This is the
     * same source used by Dart-Code and Flutter gutter markers, so wrappers annotated with
     * `@isTest`/`@isTestGroup`, tear-offs and registrations outside a particular `main()` shape
     * do not have to be reverse-engineered from PSI.
     */
    override fun testItems(file: DartFile): List<DiscoveredTestOutline> {
        val outline = currentOutline(file) ?: return emptyList()
        return collectTestChildren(file, outline.children, outline.length)
    }

    /** Re-subscribe one stale source so the official Flutter analysis service sends its current
     * outline even when an editor update was coalesced without a notification. This is a targeted
     * analyzer request, not a project rescan, and at most one request is made per source digest. */
    @Synchronized
    private fun requestFreshOutline(path: String, digest: String) {
        if (disposed || requestedSourceDigests.put(path, digest) == digest) return
        outlineRequestOverride?.let { request ->
            request(path)
            return
        }
        val listener = listeners[path]
        val server = FlutterDartAnalysisServer.getInstance(project)
        if (listener == null || !server.isServerConnected) {
            requestedSourceDigests.remove(path, digest)
            return
        }
        try {
            server.removeOutlineListener(server.analysisService.getLocalFileUri(path), listener)
            server.addOutlineListener(FileUtil.toSystemDependentName(path), listener)
            LOG.debug("Test Explorer requested a fresh analyzer outline for $path")
        } catch (error: RuntimeException) {
            requestedSourceDigests.remove(path, digest)
            LOG.warn("Unable to request a fresh Dart/Flutter outline for $path", error)
        }
    }

    @Synchronized
    override fun dispose() {
        disposed = true
        DartAnalysisServerService.getInstance(project).removeAnalysisServerListener(serverListener)
        val server = FlutterDartAnalysisServer.getInstance(project)
        listeners.forEach { (path, listener) ->
            server.removeOutlineListener(server.analysisService.getLocalFileUri(path), listener)
        }
        listeners.clear()
        snapshots.clear()
        awaitingAnalysis.clear()
        requestedSourceDigests.clear()
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(FlutterTestOutlineIndex::class.java)
        private fun sourceDigest(file: PsiFile): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest((com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                .getCachedDocument(file.virtualFile)?.immutableCharSequence?.toString() ?: file.text).toByteArray(Charsets.UTF_8)))
        private fun decodeOutline(outline: Any): AnalyzerOutline =
            AnalyzerOutline.fromJson(outline.javaClass.getMethod("toJson").invoke(outline) as JsonObject)

        /**
         * Lifts the analyzer's `UNIT_TEST_GROUP` / `UNIT_TEST_TEST` nesting into the explorer
         * hierarchy. Everything else the analyzer reports around a test - functions, classes,
         * extensions - is not part of the test hierarchy, so its test descendants are hoisted
         * rather than shown, which is how VS Code renders the same outline.
         */
        internal fun collectTestChildren(
            file: PsiFile,
            nodes: List<AnalyzerOutline>,
            rootLength: Int,
            ancestorNamesKnown: Boolean = true,
        ): List<DiscoveredTestOutline> = buildList {
            nodes.forEach { node ->
                val kind = when (node.elementKind) {
                    "UNIT_TEST_TEST" -> if (node.elementName?.substringBefore('(')
                            ?.substringAfterLast('.') == "testWidgets") DartTestKind.TEST_WIDGETS else DartTestKind.TEST
                    "UNIT_TEST_GROUP" -> DartTestKind.GROUP
                    else -> null
                }
                if (kind == null) {
                    addAll(collectTestChildren(file, node.children, rootLength, ancestorNamesKnown))
                    return@forEach
                }
                val parsed = parseTestName(node.elementName) ?: run {
                    // A test entity the analyzer reports without any identity cannot be labelled.
                    // Keep its descendants visible, but the runner still prefixes their names with
                    // this group, so no descendant may claim an exact runtime name any more.
                    addAll(collectTestChildren(file, node.children, rootLength, ancestorNamesKnown = false))
                    return@forEach
                }
                val offset = if (file.textLength == rootLength) node.offset else
                    DartAnalysisServerService.getInstance(file.project)
                        .getConvertedOffset(file.virtualFile, node.offset)
                add(DiscoveredTestOutline(kind, parsed.text, offset,
                    // Ancestry is combined per level by the tree builder, which owns the logical
                    // path. Only a dropped ancestor has to be carried down from here.
                    collectTestChildren(file, node.children, rootLength, ancestorNamesKnown),
                    runtimeNameKnown = ancestorNamesKnown && parsed.isExactRuntimeName))
            }
        }

        /**
         * The analyzer reports a test's identity as `callee("<text>")` - always double-quoted,
         * never the raw source spelling. Verified against Dart 3.6.0 / analysis server on 232 real
         * `UNIT_TEST_*` nodes plus a purpose-built probe:
         *
         *  - when the first argument has a statically known String value, `<text>` IS that value,
         *    with escapes, raw-string semantics, triple-quote folding and adjacent-literal
         *    concatenation already applied. `r'raw\name'` arrives as `test("raw\name")`.
         *  - otherwise `<text>` is the argument's source text. An un-evaluable *string literal*
         *    therefore arrives with its original quotes intact - `test('a $b')` becomes
         *    `test("'a $b'")` - which is the signal that the name is assembled at run time.
         *
         * Dart-Code reads the label out of the same field, relying on that guaranteed quoting.
         *
         * Execution needs one thing more than a label: whether `<text>` IS the name the test
         * runner will report. It is, unless the analyzer handed back an un-evaluated literal.
         */
        internal fun parseTestName(elementName: String?): OutlineTestName? {
            if (elementName.isNullOrBlank()) return null
            val openParen = elementName.indexOf('(')
            val closeParen = elementName.lastIndexOf(')')
            if (openParen == -1 || openParen >= closeParen) return null
            val reported = elementName.substring(openParen + 1, closeParen).trim()
            // The analyzer wraps its own payload in double quotes; Dart-Code strips exactly this.
            val text = if (reported.length >= 2 && reported.first() == '"' && reported.last() == '"') {
                reported.substring(1, reported.length - 1)
            } else reported
            if (text.isEmpty()) return null
            return OutlineTestName(text, isExactRuntimeName = !isUnevaluatedLiteral(text))
        }

        /**
         * True when the analyzer gave back the source text of a string literal instead of its
         * value, which happens only when the value is not statically known - interpolation being
         * the case that reaches real projects. Such a name exists at run time but is not this
         * text, so it must never become a `--name` selector or a run identity.
         *
         * A first argument that is not a string literal at all (a variable, a call) is reported
         * as bare source text and cannot be told apart from a literal of the same characters.
         * Dart-Code shares that blind spot; the Hot Restart controller rejects an id that is
         * absent from the runtime manifest, so such a target fails instead of running something
         * else.
         */
        private fun isUnevaluatedLiteral(text: String): Boolean {
            val body = text.removePrefix("r")
            val quote = body.firstOrNull()?.takeIf { it == '\'' || it == '"' } ?: return false
            return body.length >= 2 && body.last() == quote
        }

    }
}

/**
 * A test name as the analyzer spells it, plus whether that spelling is also the name the Dart
 * test runner will report. Only an exact name may become a `--name` selector or a run identity.
 */
internal data class OutlineTestName(val text: String, val isExactRuntimeName: Boolean)

/** Loader-independent snapshot; no PSI and no plugin-owned protocol objects escape the adapter. */
internal data class AnalyzerOutline(
    val offset: Int,
    val length: Int,
    val elementKind: String?,
    val elementName: String?,
    val children: List<AnalyzerOutline>,
) {
    constructor(offset: Int, length: Int, elementKind: String?, children: List<AnalyzerOutline>) :
        this(offset, length, elementKind, null, children)

    companion object {
        fun fromJson(json: JsonObject): AnalyzerOutline = AnalyzerOutline(
            json.get("offset").asInt, json.get("length").asInt,
            json.getAsJsonObject("dartElement")?.get("kind")?.asString,
            json.getAsJsonObject("dartElement")?.get("name")?.asString,
            json.getAsJsonArray("children")?.map { fromJson(it.asJsonObject) }.orEmpty(),
        )
    }
}
