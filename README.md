# Markdown Reader

![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)
![Min SDK](https://img.shields.io/badge/minSdk-24-blue)

Markdown Reader 是一款 Android Markdown 阅读器与编辑器，面向需要在手机或平板上查看、编辑和导出技术文档的用户。它内置离线渲染资源，支持 LaTeX 数学公式、Mermaid 图表、代码高亮、实时预览、PDF 导出和 DOCX 导出。

## 功能特性

- Markdown 阅读与编辑：支持新建、打开、保存和分享 Markdown 文本。
- 实时预览：使用 WebView 渲染 GitHub 风格 Markdown，并在编辑时即时刷新。
- 离线渲染：Marked、GitHub Markdown CSS、KaTeX、Mermaid 和 Prism 均打包在应用内。
- 数学公式：支持 `$...$`、`$$...$$`、`\(...\)` 和 `\[...\]`。
- Mermaid 图表：支持在 fenced code block 中编写 `mermaid` 图表。
- 代码高亮：内置 Prism，覆盖 Kotlin、Java、JavaScript、TypeScript、Python、Go、Rust、Bash、JSON、YAML、SQL 等常用语言。
- 编辑辅助栏：一键插入标题、粗体、斜体、代码块、公式、列表、表格、链接、图片和 Mermaid 模板。
- 横屏/平板分栏：大屏或横屏时自动展示编辑区与预览区双栏布局。
- 最近文件：通过 Room 记录最近打开的文件，并支持搜索、删除和清空。
- 文件入口：支持系统文件选择器、外部文件管理器 `VIEW` Intent 和分享 `SEND` Intent。
- 导出 PDF：通过 Android Print Framework 生成 PDF 或发送到打印机。
- 导出 DOCX：提供内置离线 DOCX 导出，也支持通过 Termux + Pandoc 手动生成更高保真的 Word 文件。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Android WebView
- Room
- Kotlin Coroutines + Flow
- Marked
- KaTeX
- Mermaid
- Prism
- Android Storage Access Framework

## 项目结构

```text
.
├── app/
│   ├── src/main/java/com/example/
│   │   ├── MainActivity.kt                 # Compose UI、文件入口、PDF/Termux 导出
│   │   ├── TermuxResultReceiver.kt         # Termux 自动化尝试的结果回调
│   │   ├── data/RecentFile.kt              # Room 最近文件数据层
│   │   ├── exporter/DocxExporter.kt        # 离线 Markdown -> DOCX 导出器
│   │   └── viewmodel/MarkdownViewModel.kt  # 文件读写、编辑状态、导出状态
│   ├── src/main/assets/
│   │   ├── reader.html                     # Markdown 渲染入口
│   │   └── vendor/                         # 离线前端渲染依赖
│   └── build.gradle.kts
├── gradle/libs.versions.toml               # 版本目录
├── build.gradle.kts
└── settings.gradle.kts
```

## 环境要求

- Android Studio，需支持当前 Android Gradle Plugin 版本。
- Android SDK：项目当前 `compileSdk` 为 36.1，`minSdk` 为 24。
- JDK：建议使用 Android Studio 随附的 JDK。
- 可选：安装 Gradle CLI，用于在没有 Gradle Wrapper 的情况下从命令行构建。

> 当前仓库未包含 `gradlew` / `gradlew.bat`。如果要提交到 GitHub 并配置 CI，建议先添加 Gradle Wrapper。

## 本地运行

1. 克隆项目：

   ```bash
   git clone https://github.com/<your-name>/markdown-reader.git
   cd markdown-reader
   ```

2. 使用 Android Studio 打开项目根目录。

3. 等待 Gradle Sync 完成。

4. 选择模拟器或真机，运行 `app` 模块。

如果本机已安装 Gradle，也可以使用命令行构建：

```bash
gradle assembleDebug
```

生成的调试包位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 测试

运行单元测试：

```bash
gradle testDebugUnitTest
```

当前测试重点覆盖离线 DOCX 导出器，确保生成的 OOXML 包结构完整、XML 可解析，并覆盖标题、列表、引用、代码块、表格和常见行内样式。

## DOCX 导出说明

应用提供两种 DOCX 导出方式：

### 内置离线导出

内置导出不依赖网络和外部应用，支持常见 Markdown 元素：

- 标题
- 段落
- 粗体、斜体、行内代码、删除线、链接
- 无序列表和有序列表
- 引用块
- fenced code block
- GFM pipe table
- 分割线

这种方式适合快速生成可打开、可编辑的 Word 文档。

### Termux + Pandoc 导出（当前推荐流程）

如果需要更接近 Pandoc 的完整 Markdown 语义和更高质量的 Word 输出，可以使用 Termux + Pandoc。当前实现采用半自动流程：应用负责准备 Markdown 文件、复制 Pandoc 命令并跳转到 Termux；用户在 Termux 中粘贴并运行命令后得到 `.docx` 文件。

1. 从 F-Droid 官网下载并安装最新版 Termux。

2. 打开 Termux，获取存储权限：

   ```bash
   termux-setup-storage
   ```

3. 更新源并安装 Pandoc：

   ```bash
   pkg update && pkg install pandoc
   ```

4. 在 Markdown Reader 中打开或编辑要转换的 Markdown 文档。

5. 点击菜单中的 `导出为 Word（Termux Pandoc · 高质量）`。

6. 应用会自动完成两件事：

   - 将当前 Markdown 写入 `Download/MarkdownReader/input.md`
   - 把类似下面的 Pandoc 命令复制到剪贴板，并跳转到 Termux

   ```bash
   pandoc input.md -o output.docx
   ```

   实际复制到剪贴板的命令可能会带有完整的 Android 存储路径，以应用内生成的命令为准。

7. 在 Termux 中长按粘贴剪贴板中的命令并回车运行。

8. 转换完成后，回到 Markdown Reader 主页，点击 `打开 Termux 输出文件夹`，即可浏览生成的新 Word 文件。

当前文件交接目录为：

```text
Download/MarkdownReader/input.md
```

输出文件会保存在同一目录下：

```text
Download/MarkdownReader/<当前文件名>.docx
```

> 说明：自动调用 Termux `RUN_COMMAND` 是后续目标。当前稳定可用的方案是手动在 Termux 中粘贴并运行应用提前复制好的 Pandoc 命令。

## 权限说明

- `INTERNET`：仅用于加载 Markdown 文档中引用的远程图片。核心渲染库都已内置，可离线使用。
- `com.termux.permission.RUN_COMMAND`：为后续自动化调用 Termux/Pandoc 预留；当前稳定流程仍以手动粘贴命令为主。
- `WRITE_EXTERNAL_STORAGE`：仅面向 Android 9 及以下，用于 Termux 文件交接。

## 开发备注

- 核心渲染逻辑位于 `app/src/main/assets/reader.html`。
- Android 端通过 `evaluateJavascript("updateMarkdownContent(...)")` 将 Markdown 内容传给 WebView。
- 内置 DOCX 导出器位于 `app/src/main/java/com/example/exporter/DocxExporter.kt`，不依赖 Android API 的核心转换逻辑可在 JVM 单元测试中验证。
- 最近文件记录存储在 Room 数据库 `markdown_database` 中。
- 当前 Markdown 功能不需要 Gemini API Key。

## 已知限制

- 内置 DOCX 导出器覆盖常用 Markdown，但并不等价于完整 Pandoc。复杂脚注、目录、图片嵌入、扩展语法等场景建议使用 Termux + Pandoc。
- 远程图片需要网络权限和可访问的图片 URL。
- Termux + Pandoc 目前是半自动流程，需要用户在 Termux 中手动粘贴并运行命令。
- 当前项目尚未包含 GitHub Actions、Release 流程和应用商店发布配置。

## License

当前仓库尚未包含开源许可证。公开发布前建议添加 `LICENSE` 文件，并在此处写明授权协议。
