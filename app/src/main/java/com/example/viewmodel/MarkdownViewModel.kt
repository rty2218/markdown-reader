package com.example.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.MarkdownDatabase
import com.example.data.RecentFile
import com.example.exporter.DocxExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MarkdownViewModel(private val database: MarkdownDatabase) : ViewModel() {

    private val userDao = database.recentFileDao()

    // Recent files list
    val recentFiles = userDao.getAllRecentFiles()

    // Editor States
    private val _activeFilePath = MutableStateFlow<String?>(null)
    val activeFilePath: StateFlow<String?> = _activeFilePath.asStateFlow()

    // Whether the editor/workspace is currently open. This is independent of
    // activeFilePath because a brand-new document has no path yet but should
    // still show the editor. Navigation must key off this, not the path.
    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    private val _activeFileName = MutableStateFlow("未命名.md")
    val activeFileName: StateFlow<String> = _activeFileName.asStateFlow()

    private val _markdownContent = MutableStateFlow("")
    val markdownContent: StateFlow<String> = _markdownContent.asStateFlow()

    private val _isModified = MutableStateFlow(false)
    val isModified: StateFlow<Boolean> = _isModified.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Loading / Export States
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    fun resetExportStatus() {
        _exportStatus.value = null
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateContent(newContent: String) {
        _markdownContent.value = newContent
        _isModified.value = true
    }

    // Load welcome file or create new empty file
    fun initWelcomeFile(context: Context) {
        viewModelScope.launch {
            val welcomeFile = File(context.filesDir, "欢迎使用 Markdown 阅读器.md")
            if (!welcomeFile.exists()) {
                withContext(Dispatchers.IO) {
                    try {
                        welcomeFile.writeText(getWelcomeMarkdown())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            loadFile(context, Uri.fromFile(welcomeFile))
        }
    }

    fun createNewFile(context: Context) {
        _activeFilePath.value = null
        _activeFileName.value = "未命名.md"
        _markdownContent.value = "# 未命名\n\n在此输入您的 Markdown 内容..."
        _isModified.value = false
        _isEditing.value = true
    }

    // Leave the editor and go back to the home screen, clearing editor state.
    fun returnToHome() {
        _activeFilePath.value = null
        _activeFileName.value = "未命名.md"
        _markdownContent.value = ""
        _isModified.value = false
        _isEditing.value = false
    }

    // Load file content from raw Uri
    fun loadFile(
        context: Context,
        uri: Uri,
        grantFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    ) {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                persistUriPermissionIfPossible(context, uri, grantFlags)
                val content = withContext(Dispatchers.IO) {
                    readTextFromUri(context, uri)
                }
                
                val name = getFileNameFromUri(context, uri)
                _activeFilePath.value = uri.toString()
                _activeFileName.value = name
                _markdownContent.value = content
                _isModified.value = false
                _isEditing.value = true

                // Add to recent files database
                withContext(Dispatchers.IO) {
                    val size = getFileSizeFromUri(context, uri)
                    userDao.insertRecentFile(
                        RecentFile(
                            filePath = uri.toString(),
                            fileName = name,
                            lastOpened = System.currentTimeMillis(),
                            fileSize = size
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _exportStatus.value = if (e is SecurityException) {
                    "历史文件权限已失效，请点“打开文件”重新选择一次"
                } else {
                    "无法加载文件：${e.localizedMessage ?: "请重新选择一次文件以恢复访问权限"}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Save active file content
    fun saveFile(context: Context, onSaveSuccess: () -> Unit = {}) {
        val pathStr = _activeFilePath.value
        _isLoading.value = true
        viewModelScope.launch {
            try {
                if (pathStr == null) {
                    // Save novel file: requires picker or we autosave in private files space
                    val internalDir = File(context.filesDir, "Documents")
                    if (!internalDir.exists()) internalDir.mkdirs()
                    
                    val safeName = _activeFileName.value.let { name ->
                        if (name.endsWith(".md")) name else "$name.md"
                    }
                    val localFile = File(internalDir, safeName)
                    withContext(Dispatchers.IO) {
                        localFile.writeText(_markdownContent.value)
                    }
                    
                    val fileUri = Uri.fromFile(localFile)
                    _activeFilePath.value = fileUri.toString()
                    _activeFileName.value = safeName
                    
                    // Add to Database
                    withContext(Dispatchers.IO) {
                        userDao.insertRecentFile(
                            RecentFile(
                                filePath = fileUri.toString(),
                                fileName = safeName,
                                lastOpened = System.currentTimeMillis(),
                                fileSize = localFile.length()
                            )
                        )
                    }
                } else {
                    val uri = Uri.parse(pathStr)
                    val success = withContext(Dispatchers.IO) {
                        writeTextToUri(context, uri, _markdownContent.value)
                    }
                    if (success) {
                        // Update size in database
                        withContext(Dispatchers.IO) {
                            val size = getFileSizeFromUri(context, uri)
                            userDao.insertRecentFile(
                                RecentFile(
                                    filePath = pathStr,
                                    fileName = _activeFileName.value,
                                    lastOpened = System.currentTimeMillis(),
                                    fileSize = size
                                )
                            )
                        }
                    } else {
                        throw Exception("写入文件失败")
                    }
                }
                
                _isModified.value = false
                _exportStatus.value = "文件保存成功"
                onSaveSuccess()
            } catch (e: Exception) {
                e.printStackTrace()
                _exportStatus.value = "保存失败: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Suggested file name used by the system "create document" picker.
    fun suggestedDocxName(): String =
        _activeFileName.value.removeSuffix(".md").removeSuffix(".markdown") + ".docx"

    // Export active file as DOCX into a user-chosen location (Storage Access
    // Framework Uri). Works on every API level and needs no storage permission.
    fun exportToDocx(context: Context, destUri: Uri) {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destUri)?.use { os ->
                        DocxExporter.exportToDocx(context, _markdownContent.value, os)
                    } ?: false
                }
                _exportStatus.value = if (success) "DOCX 导出成功" else "DOCX 导出失败"
            } catch (e: Exception) {
                e.printStackTrace()
                _exportStatus.value = "DOCX 导出失败: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteRecentFileByPath(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            userDao.deleteByPath(path)
        }
    }

    fun clearAllRecentFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            userDao.clearAll()
        }
    }

    // Helper functions to read/write via content resolution or local files
    private suspend fun readTextFromUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
        } ?: ""
    }

    private fun persistUriPermissionIfPossible(context: Context, uri: Uri, grantFlags: Int) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return

        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val writeFlag = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val usableFlags = grantFlags and (readFlag or writeFlag)
        if (usableFlags == 0) return

        val resolver = context.contentResolver
        if ((usableFlags and writeFlag) != 0) {
            val bothResult = runCatching {
                resolver.takePersistableUriPermission(uri, usableFlags)
            }
            if (bothResult.isSuccess) return
        }

        if ((usableFlags and readFlag) != 0) {
            runCatching {
                resolver.takePersistableUriPermission(uri, readFlag)
            }
        }
    }

    private suspend fun writeTextToUri(context: Context, uri: Uri, text: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "rwt")?.use { outputStream ->
                outputStream.bufferedWriter().use { it.write(text) }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "未命名.md"
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        } else if (uri.scheme == "file") {
            name = uri.lastPathSegment ?: "未命名.md"
        }
        return name
    }

    private fun getFileSizeFromUri(context: Context, uri: Uri): Long {
        var size: Long = 0
        if (uri.scheme == "content") {
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex(OpenableColumns.SIZE)
                        if (index != -1) {
                            size = it.getLong(index)
                        }
                    }
                }
            } catch (ignored: Exception) {}
        } else if (uri.scheme == "file") {
            uri.path?.let {
                val f = File(it)
                if (f.exists()) size = f.length()
            }
        }
        return size
    }

    private fun getWelcomeMarkdown(): String {
        return """# 🌟 欢迎使用 Markdown 阅读器 (Typora 级体验)

这是一个功能齐全的高清 Markdown 阅读器 & 编辑器。支持 **Math LaTeX 公式**、**Mermaid 流程图** 和 **Prism 高亮代码块**。

---

## 🎨 核心功能展示

### 1. 🧮 完整 LaTeX 数学公式
支持标准的 LaTeX 数学表达式。

- **行内公式**：如爱因斯坦质能方程：${'$'}E = mc^2${'$'}，或者二次方程求根公式：${'$'}x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}${'$'}。
- **块级公式**：

${'$'}${'$'}\int_{a}^{b} f(x) \,dx = F(b) - F(a)${'$'}${'$'}

或者经典的麦克斯韦方程组：

${'$'}${'$'}\begin{aligned}
\nabla \cdot \mathbf{E} &= \frac{\rho}{\varepsilon_0} \\
\nabla \cdot \mathbf{B} &= 0 \\
\nabla \times \mathbf{E} &= -\frac{\partial \mathbf{B}}{\partial t} \\
\nabla \times \mathbf{B} &= \mu_0\mathbf{J} + \mu_0\varepsilon_0\frac{\partial \mathbf{E}}{\partial t}
\end{aligned}${'$'}${'$'}

---

### 2. 📊 Mermaid 矢量流程图
支持电脑 Typora 级别的多种 Mermaid 图表渲染。

**流程图 (Flowchart)**：
```mermaid
graph TD
    A[开始编辑 Markdown] --> B{是否有公式与图表?}
    B -- 是 --> C[LaTeX 与 Mermaid 解析渲染]
    B -- 否 --> D[普通 Markdown 排版]
    C --> E[完美呈现 & 极速预览]
    D --> E
    E --> F[导出为 PDF 格式]
    F --> G[分享到其他应用]
```

**时序图 (Sequence Diagram)**:
```mermaid
sequenceDiagram
    Alice->>Bob: 你好，Bob！
    Bob-->>Alice: 收到！Markdown 支持真棒。
```

---

### 3. 💻 Prism 语法高亮
支持各种主流编程语言的精准高亮显示：

```kotlin
fun main() {
    val message = "Hello Markdown Reader!"
    println(message)
}
```

---

### 4. 📝 交互说明
- **阅读模式**：在顶部切换到 **"预览"** 模式。
- **编辑模式**：在顶部切换到 **"编辑"** 模式，即可实时输入修改。
- **底栏工具**：在编辑模式下，可使用底栏丰富的排版格式按钮（如加粗、斜体、插入标题、生成逻辑图、数学公式块等）一键辅助插入代码。
- **导出共享**：点击右上角菜单，一键在后台导出排版完美的 **PDF 或者是 Word (DOCX)** 文档，或者拷贝并分享原始 Markdown 文档！
"""
    }
}

class MarkdownViewModelFactory(private val database: MarkdownDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MarkdownViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MarkdownViewModel(database) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
