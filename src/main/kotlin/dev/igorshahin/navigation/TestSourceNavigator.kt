package dev.igorshahin.navigation

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.igorshahin.model.SourceLocation

class TestSourceNavigator(
    private val project: Project,
) {
    fun navigate(location: SourceLocation) {
        val file = LocalFileSystem.getInstance().findFileByPath(location.filePath) ?: return
        if (!file.isValid || file.isDirectory) return
        OpenFileDescriptor(project, file, location.offset).navigate(true)
    }
}
