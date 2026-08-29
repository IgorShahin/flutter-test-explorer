package dev.igorshahin.discovery

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.igorshahin.model.DartTestKind
import io.flutter.dart.DartSyntax
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
) : Disposable {
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

    @Synchronized
    fun watch(files: List<VirtualFile>) {
        if (disposed) return
        val paths = files.map { it.path }.toSet()
        val server = FlutterDartAnalysisServer.getInstance(project)
        waitingForConnection = !server.isServerConnected
        if (waitingForConnection) return
        (if (reconnectPending) listeners.keys.toSet() else listeners.keys - paths).forEach { path ->
            server.removeOutlineListener(server.analysisService.getLocalFileUri(path), listeners.remove(path)!!)
            snapshots.remove(path)
            awaitingAnalysis.remove(path)
            requestedSourceDigests.remove(path)
        }
        reconnectPending = false
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

    fun testCalls(file: PsiFile): Map<Int, DartTestKind> {
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
        return mapTestCalls(file, outline)
    }

    private fun pending(path: String, reason: String): Map<Int, DartTestKind> {
        awaitingAnalysis += path
        LOG.debug("Test Explorer waiting for analyzer outline ($reason): $path")
        return emptyMap()
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

        internal fun mapTestCalls(file: PsiFile, outline: AnalyzerOutline): Map<Int, DartTestKind> {
            val result = mutableMapOf<Int, DartTestKind>()
            fun visit(node: AnalyzerOutline) {
                val kind = when (node.elementKind) {
                    "UNIT_TEST_TEST" -> DartTestKind.TEST
                    "UNIT_TEST_GROUP" -> DartTestKind.GROUP
                    else -> null
                }
                if (kind != null) {
                    val offset = if (file.textLength == outline.length) node.offset else
                        DartAnalysisServerService.getInstance(file.project).getConvertedOffset(file.virtualFile, node.offset)
                    val call = file.findElementAt(offset)?.let(DartSyntax::findClosestEnclosingFunctionCall)
                    if (call != null) {
                        result[call.textOffset] = if (kind == DartTestKind.TEST &&
                            call.expression?.text?.substringAfterLast('.') == "testWidgets") {
                            DartTestKind.TEST_WIDGETS
                        } else kind
                    }
                }
                node.children.forEach(::visit)
            }
            visit(outline)
            return result
        }
    }
}

/** Loader-independent snapshot; no PSI and no plugin-owned protocol objects escape the adapter. */
internal data class AnalyzerOutline(val offset: Int, val length: Int, val elementKind: String?, val children: List<AnalyzerOutline>) {
    companion object {
        fun fromJson(json: JsonObject): AnalyzerOutline = AnalyzerOutline(
            json.get("offset").asInt, json.get("length").asInt,
            json.getAsJsonObject("dartElement")?.get("kind")?.asString,
            json.getAsJsonArray("children")?.map { fromJson(it.asJsonObject) }.orEmpty(),
        )
    }
}
