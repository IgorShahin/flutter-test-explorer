package dev.igorshahin.discovery

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

/** The editor document is authoritative, including unsaved edits. PSI cache clears are not edits. */
internal object TestSourceStamp {
    fun current(file: VirtualFile): Long =
        FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp
}
