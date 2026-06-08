package com.example

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.MarkdownDatabase
import com.example.data.RecentFile
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MarkdownViewModel
import com.example.viewmodel.MarkdownViewModelFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    // Activity-scoped ViewModel created eagerly via the viewModels() delegate, so it
    // is ready in onCreate() BEFORE handleIntent() runs. (Previously it was assigned
    // inside setContent's composition, which had not executed yet when an incoming
    // VIEW/SEND intent tried to use it -> UninitializedPropertyAccessException -> crash.)
    private val viewModel: MarkdownViewModel by viewModels {
        MarkdownViewModelFactory(MarkdownDatabase.getDatabase(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val isEditing by viewModel.isEditing.collectAsStateWithLifecycle()

            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0.dp)
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (!isEditing) {
                            HomeScreen(viewModel = viewModel)
                        } else {
                            WorkspaceScreen(viewModel = viewModel, onBack = {
                                viewModel.returnToHome() // return to home
                            })
                        }
                    }
                }
            }
        }

        // Handle file open from external intent (Default file opener & shared context)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        try {
            val action = intent.action
            val dataUri = intent.data

            if (Intent.ACTION_VIEW == action && dataUri != null) {
                viewModel.loadFile(this, dataUri, intent.flags)
            } else if (Intent.ACTION_SEND == action) {
                // Shared file (EXTRA_STREAM) or plain shared text (EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    streamUri != null -> viewModel.loadFile(this, streamUri, intent.flags)
                    sharedText != null -> {
                        viewModel.createNewFile(this)
                        viewModel.updateContent(sharedText)
                    }
                }
            }
        } catch (e: Exception) {
            // Never let a malformed external intent crash the app on launch.
            e.printStackTrace()
        }
    }
}

/**
 * Render the given Markdown in a dedicated off-screen WebView (so it works no
 * matter which editor tab is active) and hand it to Android's print pipeline,
 * where the user can pick "Save as PDF" or a physical printer.
 */
private fun exportToPdf(activity: ComponentActivity, baseName: String, markdown: String) {
    val webView = WebView(activity)
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = true
        allowContentAccess = true
    }
    // Attach at 1x1 so the page performs a real layout before printing.
    val root = activity.window.decorView as ViewGroup
    webView.layoutParams = ViewGroup.LayoutParams(1, 1)
    root.addView(webView)

    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val quoted = org.json.JSONObject.quote(markdown)
            view.evaluateJavascript("updateMarkdownContent($quoted);", null)
            // Give KaTeX / Mermaid a moment to finish async rendering, then print.
            view.postDelayed({
                val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val jobName = baseName.ifBlank { "Markdown" }
                val adapter = view.createPrintDocumentAdapter(jobName)
                printManager.print(jobName, adapter, PrintAttributes.Builder().build())
                // Detach the helper view well after the print dialog has taken over.
                view.postDelayed({ root.removeView(view) }, 120_000)
            }, 1200)
        }
    }
    webView.loadUrl("file:///android_asset/reader.html")
}

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_OUTPUT_DIR = "MarkdownReader"

private fun isTermuxInstalled(context: Context): Boolean =
    try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

private fun openTermuxOutputFolder(context: Context) {
    val outputUri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:${Environment.DIRECTORY_DOWNLOADS}/$TERMUX_OUTPUT_DIR"
    )

    val directFolderIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(outputUri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (startActivityIfAvailable(context, directFolderIntent)) return

    val pickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, outputUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (startActivityIfAvailable(context, pickerIntent)) return

    Toast.makeText(context, "无法打开文件管理器，请手动进入 下载/$TERMUX_OUTPUT_DIR", Toast.LENGTH_LONG).show()
}

private fun startActivityIfAvailable(context: Context, intent: Intent): Boolean {
    return try {
        if (context !is ComponentActivity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Convert the current Markdown to DOCX using REAL Pandoc running inside Termux,
 * through Termux's RUN_COMMAND API. The .md is written to shared Downloads so
 * Termux can read it by path; Pandoc writes the .docx back to the same folder
 * for the user to open with any Office app.
 *
 * One-time Termux setup the user must do:
 *   pkg install pandoc
 *   echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings
 *   termux-setup-storage      (so Termux can access /storage/emulated/0)
 */
private fun exportViaTermuxPandoc(activity: ComponentActivity, baseName: String, markdown: String) {
    if (!isTermuxInstalled(activity)) {
        Toast.makeText(activity, "未检测到 Termux。请先安装 Termux 并 pkg install pandoc", Toast.LENGTH_LONG).show()
        return
    }

    val dir = TERMUX_OUTPUT_DIR
    val safeBase = baseName.ifBlank { "output" }
    val inputPath = "/storage/emulated/0/Download/$dir/input.md"
    val outputPath = "/storage/emulated/0/Download/$dir/$safeBase.docx"
    val pandocCmd = "pandoc \"$inputPath\" -o \"$outputPath\""

    // Always write the input file first (this part is known to work).
    try {
        writeToDownloads(activity, dir, "input.md", "text/markdown", markdown.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(activity, "写入 input.md 失败：${e.localizedMessage}", Toast.LENGTH_LONG).show()
        return
    }

    // 1) Try the fully-automatic RUN_COMMAND path.
    try {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/pandoc")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(inputPath, "-o", outputPath))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        intent.putExtra(
            "com.termux.RUN_COMMAND_PENDING_INTENT",
            PendingIntent.getBroadcast(activity, 0, Intent(activity, TermuxResultReceiver::class.java), piFlags)
        )
        ContextCompat.startForegroundService(activity, intent)
        Toast.makeText(activity, "已请求 Termux 自动转换。完成后见 下载/$dir/$safeBase.docx", Toast.LENGTH_LONG).show()
        return
    } catch (svc: Exception) {
        // Many OEM ROMs / Android versions forbid starting another app's service
        // ("Not allowed to start service Intent"). Fall back to semi-automatic.
        svc.printStackTrace()
    }

    // 2) Semi-automatic fallback: copy the ready-to-run command and open Termux.
    try {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("pandoc", pandocCmd))
    } catch (_: Exception) {}
    try {
        activity.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(it)
        }
    } catch (_: Exception) {}
    Toast.makeText(
        activity,
        "系统禁止自动调用 Termux。已写好 input.md 并把 pandoc 命令复制到剪贴板——切到 Termux 长按粘贴回车即可生成 docx。",
        Toast.LENGTH_LONG
    ).show()
}

/** Write bytes into shared Downloads/<subDir>/<name> on any supported API level. */
private fun writeToDownloads(context: Context, subDir: String, name: String, mime: String, bytes: ByteArray) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relPath = "${Environment.DIRECTORY_DOWNLOADS}/$subDir"
        // Replace any previous file of the same name so the path stays deterministic.
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, "$relPath/")
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法在下载目录创建 $name")
        resolver.openOutputStream(uri).use { it!!.write(bytes) }
    } else {
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(base, subDir)
        if (!folder.exists()) folder.mkdirs()
        File(folder, name).writeBytes(bytes)
    }
}

@Composable
fun HomeScreen(viewModel: MarkdownViewModel) {
    val context = LocalContext.current
    val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val statusMessage by viewModel.exportStatus.collectAsStateWithLifecycle()

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.resetExportStatus()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.loadFile(
                context,
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    // Filtered recents based on query
    val filteredFiles = remember(recentFiles, searchQuery) {
        if (searchQuery.isBlank()) {
            recentFiles
        } else {
            recentFiles.filter { it.fileName.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        // App title styled beautifully
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = "App Logo",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Markdown Reader",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "支持 Math LaTeX 公式与 Mermaid 逻辑图",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Large Quick action Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                onClick = { viewModel.createNewFile(context) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .weight(1f)
                    .height(120.dp)
                    .testTag("create_new_card"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Document",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "新建文档",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Card(
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "text/markdown",
                            "text/plain",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .weight(1f)
                    .height(120.dp)
                    .testTag("open_file_card"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Open Document",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "打开文件",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { openTermuxOutputFolder(context) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("termux_output_folder_button"),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = "Termux Output Folder")
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "打开 Termux 输出文件夹")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Open demo notebook template quickly on first view
        Button(
            onClick = { viewModel.initWelcomeFile(context) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("welcome_showcase_button"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Help, contentDescription = "Help")
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "打开演示说明与排版示例 (包含公式与流程图)")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Recent Files list header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "最近打开",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (recentFiles.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearAllRecentFiles() }) {
                    Text("清除历史", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Search Bar inside HomePage
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("搜索最近文档...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "No Files",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isEmpty()) "暂无历史记录" else "未找到匹配的文件",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredFiles) { file ->
                    RecentFileItem(file = file, onClick = {
                        val fileUri = Uri.parse(file.filePath)
                        viewModel.loadFile(context, fileUri)
                    }, onDelete = {
                        viewModel.deleteRecentFileByPath(file.filePath)
                    })
                }
            }
        }
    }
}

@Composable
fun RecentFileItem(file: RecentFile, onClick: () -> Unit, onDelete: () -> Unit) {
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val timeString = formatter.format(Date(file.lastOpened))

    val sizeText = remember(file.fileSize) {
        if (file.fileSize < 1024) "${file.fileSize} B"
        else if (file.fileSize < 1024 * 1024) String.format(Locale.US, "%.1f KB", file.fileSize / 1024.0)
        else String.format(Locale.US, "%.1f MB", file.fileSize / (1024.0 * 1024.0))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recent_item_${file.fileName}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Article,
                contentDescription = "File icon",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = timeString, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Text(text = sizeText, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除历史",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(viewModel: MarkdownViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val content by viewModel.markdownContent.collectAsStateWithLifecycle()
    val activeFileName by viewModel.activeFileName.collectAsStateWithLifecycle()
    val isModified by viewModel.isModified.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    var activeTab by remember { mutableStateOf(0) } // 0: Edit, 1: Preview
    var showMenu by remember { mutableStateOf(false) }
    var renameDialogOpen by remember { mutableStateOf(false) }

    // Dynamic split preview for tablets or landscape mode
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTablet = configuration.screenWidthDp >= 600
    val renderSplitScreen = isLandscape || isTablet

    // Keep dynamic track of local WebView instance to call native printing
    var webViewReference by remember { mutableStateOf<WebView?>(null) }

    // System "create document" picker for DOCX export — works on every API level
    // and requires no storage permission.
    val docxExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
    ) { uri: Uri? -> uri?.let { viewModel.exportToDocx(context, it) } }

    // Text Editor state containing the cursors
    var textValue by remember(content) {
        // Keeps textValue updated only if programmatic loads happen, preserving cursor during rapid typing!
        mutableStateOf(TextFieldValue(text = content, selection = TextRange(content.length)))
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(exportStatus) {
        exportStatus?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.resetExportStatus()
        }
    }

    // Modal dialog to quickly rename the currently loaded file
    if (renameDialogOpen) {
        var tempName by remember { mutableStateOf(activeFileName) }
        Dialog(onDismissRequest = { renameDialogOpen = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("重命名文档", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = { renameDialogOpen = false }) {
                            Text("取消")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            viewModel.saveFile(context) {
                                // Save inside the save sequence
                            }
                            renameDialogOpen = false
                        }) {
                            Text("确定")
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { renameDialogOpen = true }
                    ) {
                        Text(
                            text = activeFileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isModified) {
                            Spacer(modifier = Modifier.width(6.dp))
                            // Simple visual badge for modified files
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Red)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!renderSplitScreen) {
                        WorkspaceModeIconButton(
                            selected = activeTab == 0,
                            onClick = { activeTab = 0 },
                            icon = { tint ->
                                Icon(
                                    imageVector = Icons.Default.Create,
                                    contentDescription = "编辑",
                                    tint = tint
                                )
                            }
                        )
                        WorkspaceModeIconButton(
                            selected = activeTab == 1,
                            onClick = { activeTab = 1 },
                            icon = { tint ->
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = "预览",
                                    tint = tint
                                )
                            }
                        )
                    }

                    // Quick Save Button if content has changed
                    if (isModified) {
                        IconButton(onClick = { viewModel.saveFile(context) }) {
                            Icon(
                                imageVector = Icons.Default.Done,
                                contentDescription = "保存",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Share button
                    IconButton(onClick = {
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, content)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "发送 Markdown 内容"))
                    }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "分享")
                    }

                    // More Actions menu
                    IconButton(onClick = { showMenu = true }) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "菜单")
                    }

                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("保存文档") },
                            onClick = {
                                showMenu = false
                                viewModel.saveFile(context)
                            },
                            leadingIcon = { Icon(Icons.Default.Save, contentDescription = "Save") }
                        )
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                showMenu = false
                                renameDialogOpen = true
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                        )
                        DropdownMenuItem(
                            text = { Text("导出为 PDF") },
                            onClick = {
                                showMenu = false
                                (context as? ComponentActivity)?.let { act ->
                                    exportToPdf(
                                        act,
                                        activeFileName.removeSuffix(".md").removeSuffix(".markdown"),
                                        content
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = "PDF") }
                        )
                        DropdownMenuItem(
                            text = { Text("导出为 Word（内置 · 离线）") },
                            onClick = {
                                showMenu = false
                                docxExportLauncher.launch(viewModel.suggestedDocxName())
                            },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = "DOCX") }
                        )
                        DropdownMenuItem(
                            text = { Text("导出为 Word（Termux Pandoc · 高质量）") },
                            onClick = {
                                showMenu = false
                                (context as? ComponentActivity)?.let { act ->
                                    exportViaTermuxPandoc(
                                        act,
                                        activeFileName.removeSuffix(".md").removeSuffix(".markdown"),
                                        content
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = "Termux Pandoc") }
                        )
                        DropdownMenuItem(
                            text = { Text("查看 Markdown 排版指南") },
                            onClick = {
                                showMenu = false
                                viewModel.initWelcomeFile(context)
                            },
                            leadingIcon = { Icon(Icons.Default.Help, contentDescription = "Help") }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // If on tablet / landscape, render side by side pane with zero tabs necessary!
                if (renderSplitScreen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // Left Pane: Core Editor
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(end = 4.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                EditingTextField(
                                    value = textValue,
                                    onValueChange = {
                                        textValue = it
                                        viewModel.updateContent(it.text)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                )
                                MarkdownEditorHelperToolbar(
                                    currentValue = textValue,
                                    onInsert = { updatedValue ->
                                        textValue = updatedValue
                                        viewModel.updateContent(updatedValue.text)
                                    }
                                )
                            }
                        }

                        // Split divider lines
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )

                        // Right Pane: WebView rendering live equations/diagrams
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(start = 4.dp)
                        ) {
                            HtmlMarkdownRenderer(
                                markdownText = content,
                                onWebViewCreated = { webViewReference = it }
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (activeTab == 0) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                EditingTextField(
                                    value = textValue,
                                    onValueChange = {
                                        textValue = it
                                        viewModel.updateContent(it.text)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                )
                                MarkdownEditorHelperToolbar(
                                    currentValue = textValue,
                                    onInsert = { updatedValue ->
                                        textValue = updatedValue
                                        viewModel.updateContent(updatedValue.text)
                                    }
                                )
                            }
                        } else {
                            HtmlMarkdownRenderer(
                                markdownText = content,
                                onWebViewCreated = { webViewReference = it }
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
fun WorkspaceModeIconButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    val iconTint = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
    ) {
        icon(iconTint)
    }
}

@Composable
fun EditingTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("输入您的 Markdown 文档...") },
        modifier = modifier.testTag("md_editor_text_field"),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        textStyle = LocalTextStyle.current.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
    )
}

@Composable
fun HtmlMarkdownRenderer(
    markdownText: String,
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Feed text once on page ready
                        val quoted = org.json.JSONObject.quote(markdownText)
                        evaluateJavascript("updateMarkdownContent($quoted);", null)
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    allowFileAccess = true
                    allowContentAccess = true
                }
                loadUrl("file:///android_asset/reader.html")
                onWebViewCreated(this)
            }
        },
        update = { webView ->
            // Re-render HTML with updated text instantly when composing value modifications in runtime!
            val quoted = org.json.JSONObject.quote(markdownText)
            webView.evaluateJavascript("updateMarkdownContent($quoted);", null)
        },
        modifier = modifier
            .fillMaxSize()
            .testTag("md_webview_renderer")
    )
}

/**
 * Modern, scrollable bar containing quick markdown syntax helper injections
 */
@Composable
fun MarkdownEditorHelperToolbar(
    currentValue: TextFieldValue,
    onInsert: (TextFieldValue) -> Unit
) {
    val context = LocalContext.current
    val helpers = remember {
        listOf(
            MarkdownHelperItem("H1", "# ", ""),
            MarkdownHelperItem("H2", "## ", ""),
            MarkdownHelperItem("H3", "### ", ""),
            MarkdownHelperItem("粗体", "**", "**"),
            MarkdownHelperItem("斜体", "*", "*"),
            MarkdownHelperItem("行内代码", "`", "`"),
            MarkdownHelperItem("代码块", "```kotlin\n", "\n```"),
            MarkdownHelperItem("行内公式", "$", "$"),
            MarkdownHelperItem("块公式", "$$\n", "\n$$"),
            MarkdownHelperItem("列表", "- ", ""),
            MarkdownHelperItem("流程图", "```mermaid\ngraph TD\n  A[开始] --> B[步骤1]\n  B --> C[完成]\n```\n", ""),
            MarkdownHelperItem("表格", "| 列1 | 列2 |\n| ---- | ---- |\n| 单元格1 | 单元格2 |\n", ""),
            MarkdownHelperItem("链接", "[提示](", ")"),
            MarkdownHelperItem("图片", "![描述](", ")")
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(helpers) { item ->
                AssistChip(
                    onClick = {
                        val text = currentValue.text
                        val selection = currentValue.selection
                        val start = selection.start
                        val end = selection.end

                        val selectedText = text.substring(start, end)
                        val insertText = "${item.prefix}$selectedText${item.suffix}"
                        
                        val newText = text.replaceRange(start, end, insertText)
                        val newSelectionStart = start + item.prefix.length
                        val newSelectionEnd = newSelectionStart + selectedText.length

                        onInsert(
                            TextFieldValue(
                                text = newText,
                                selection = TextRange(newSelectionStart, newSelectionEnd)
                            )
                        )
                    },
                    label = { Text(item.label, fontSize = 12.sp) }
                )
            }
        }
    }
}

data class MarkdownHelperItem(
    val label: String,
    val prefix: String,
    val suffix: String
)
