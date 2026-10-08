/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools
 
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
 
private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
 
val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
)
 
fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false
 
suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)
 
    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")
 
    return listOf(
        createReadFileTool(workspaceId, needsApproval("workspace_read_file"), workspaceRepository),
        createWriteFileTool(workspaceId, needsApproval("workspace_write_file"), workspaceRepository),
        createEditFileTool(workspaceId, needsApproval("workspace_edit_file"), workspaceRepository),
        createShellTool(workspaceId, needsApproval("workspace_shell"), workspaceRepository, shellCwd),
    )
}
 
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
 
internal fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
 
private fun createReadFileTool(
    workspaceId: String,
    needsApproval: Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("start_line", buildJsonObject {
                    put("type", "integer")
                    put("description", "Starting line number (1-based, defaults to 1)")
                })
                put("start_column", buildJsonObject {
                    put("type", "integer")
                    put("description", "Starting Unicode character column offset in start_line (0-based, defaults to 0)")
                })
                put("line_count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of lines to read (defaults to 200, max 500)")
                })
            },
            required = listOf("path"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val startLine = params.optionalInt("start_line", default = 1)
            val startColumn = params.optionalInt("start_column", default = 0)
            val lineCount = params.optionalInt("line_count", default = DEFAULT_WORKSPACE_READ_LINE_COUNT)

            val text = workspaceRepository.readTextInRootfs(workspaceId, path)
            val page = paginateWorkspaceText(
                path = path,
                text = text,
                startLine = startLine,
                startColumn = startColumn,
                lineCount = lineCount,
            )
            listOf(
                UIMessagePart.Text(page.toJsonString())
            )
        }
    },
)
 
private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)
 
private fun createEditFileTool(
    workspaceId: String,
    needsApproval: Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }
 
        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
            ).apply {
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                if (diff != null) {
                    metadata = buildJsonObject { put("diff", diff) }
                }
            }
        )
    },
)
 
private fun createShellTool(
    workspaceId: String,
    needsApproval: Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = needsApproval,
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)
 
private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun kotlinx.serialization.json.JsonObject.optionalInt(name: String, default: Int): Int {
    val element = this[name] ?: return default
    val content = element.jsonPrimitive.contentOrNull ?: error("Invalid integer for parameter '$name'")
    return content.toIntOrNull() ?: error("Invalid integer for parameter '$name': '$content'")
}

internal const val MAX_WORKSPACE_PAGE_TEXT_BYTES = 24 * 1024
internal const val MAX_WORKSPACE_PAGE_JSON_BYTES = 32 * 1024
internal const val DEFAULT_WORKSPACE_READ_LINE_COUNT = 200
internal const val MAX_WORKSPACE_READ_LINE_COUNT = 500

internal data class WorkspaceTextPage(
    val path: String,
    val text: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val totalLines: Int,
    val truncated: Boolean,
    val nextStartLine: Int? = null,
    val nextStartColumn: Int? = null,
)

internal fun WorkspaceTextPage.toJsonObject(): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("path", path)
    put("text", text)
    put("start_line", startLine)
    put("start_column", startColumn)
    put("end_line", endLine)
    put("total_lines", totalLines)
    put("truncated", truncated)
    if (truncated) {
        nextStartLine?.let { put("next_start_line", it) }
        nextStartColumn?.let { put("next_start_column", it) }
    }
}

internal fun WorkspaceTextPage.toJsonString(): String = toJsonObject().toString()

private data class WorkspaceLine(
    val content: String,
    val delimiter: String,
)

private fun parseWorkspaceLines(text: String): List<WorkspaceLine> {
    if (text.isEmpty()) return emptyList()
    val lines = mutableListOf<WorkspaceLine>()
    var i = 0
    val len = text.length
    while (i < len) {
        var j = i
        var delimiter = ""
        var nextI = len
        while (j < len) {
            val ch = text[j]
            if (ch == '\r') {
                if (j + 1 < len && text[j + 1] == '\n') {
                    delimiter = "\r\n"
                    nextI = j + 2
                } else {
                    delimiter = "\r"
                    nextI = j + 1
                }
                break
            } else if (ch == '\n') {
                delimiter = "\n"
                nextI = j + 1
                break
            }
            j++
        }
        if (delimiter.isNotEmpty()) {
            lines.add(WorkspaceLine(content = text.substring(i, j), delimiter = delimiter))
            i = nextI
        } else {
            lines.add(WorkspaceLine(content = text.substring(i, len), delimiter = ""))
            i = len
        }
    }
    return lines
}

internal fun paginateWorkspaceText(
    path: String,
    text: String,
    startLine: Int = 1,
    startColumn: Int = 0,
    lineCount: Int = DEFAULT_WORKSPACE_READ_LINE_COUNT,
): WorkspaceTextPage {
    require(startLine >= 1) { "start_line must be greater than or equal to 1, got $startLine" }
    require(startColumn >= 0) { "start_column must be greater than or equal to 0, got $startColumn" }
    require(lineCount in 1..MAX_WORKSPACE_READ_LINE_COUNT) {
        "line_count must be between 1 and $MAX_WORKSPACE_READ_LINE_COUNT, got $lineCount"
    }

    val lines = parseWorkspaceLines(text)
    val totalLines = lines.size

    if (totalLines == 0) {
        return WorkspaceTextPage(
            path = path,
            text = "",
            startLine = startLine,
            startColumn = startColumn,
            endLine = 0,
            totalLines = 0,
            truncated = false,
        )
    }

    if (startLine > totalLines) {
        return WorkspaceTextPage(
            path = path,
            text = "",
            startLine = startLine,
            startColumn = startColumn,
            endLine = totalLines,
            totalLines = totalLines,
            truncated = false,
        )
    }

    val lineIdx = startLine - 1
    val startLineObj = lines[lineIdx]
    val startLineCodePoints = startLineObj.content.codePointCount(0, startLineObj.content.length)
    require(startColumn <= startLineCodePoints) {
        "start_column ($startColumn) exceeds line $startLine length ($startLineCodePoints)"
    }

    fun fitsBudget(
        candidateText: String,
        candidateEndLine: Int,
        candidateTruncated: Boolean,
        candidateNextLine: Int?,
        candidateNextCol: Int?,
    ): Boolean {
        if (candidateText.toByteArray(Charsets.UTF_8).size > MAX_WORKSPACE_PAGE_TEXT_BYTES) {
            return false
        }
        val candidatePage = WorkspaceTextPage(
            path = path,
            text = candidateText,
            startLine = startLine,
            startColumn = startColumn,
            endLine = candidateEndLine,
            totalLines = totalLines,
            truncated = candidateTruncated,
            nextStartLine = candidateNextLine,
            nextStartColumn = candidateNextCol,
        )
        return candidatePage.toJsonString().toByteArray(Charsets.UTF_8).size <= MAX_WORKSPACE_PAGE_JSON_BYTES
    }

    val textBuilder = StringBuilder()
    var linesReadCount = 0
    var endLine = startLine
    var truncated = false
    var nextStartLine: Int? = null
    var nextStartColumn: Int? = null

    for (idx in lineIdx until totalLines) {
        val lineNum = idx + 1
        val line = lines[idx]
        val content = line.content
        val delimiter = line.delimiter
        val colStart = if (idx == lineIdx) startColumn else 0
        val totalLineCodePoints = content.codePointCount(0, content.length)

        val charStart = content.offsetByCodePoints(0, colStart)
        val lineRemainingText = content.substring(charStart) + delimiter
        val candidateText = textBuilder.toString() + lineRemainingText

        val wouldBeTruncatedByLineCount = (linesReadCount + 1 >= lineCount) && (lineNum < totalLines)
        val tentativeNextLine = if (wouldBeTruncatedByLineCount) lineNum + 1 else null
        val tentativeNextCol = if (wouldBeTruncatedByLineCount) 0 else null

        if (fitsBudget(candidateText, lineNum, wouldBeTruncatedByLineCount, tentativeNextLine, tentativeNextCol)) {
            textBuilder.append(lineRemainingText)
            endLine = lineNum
            linesReadCount++

            if (linesReadCount == lineCount) {
                if (lineNum < totalLines) {
                    truncated = true
                    nextStartLine = lineNum + 1
                    nextStartColumn = 0
                } else {
                    truncated = false
                }
                break
            }
        } else {
            if (textBuilder.isNotEmpty()) {
                truncated = true
                nextStartLine = lineNum
                nextStartColumn = colStart
                break
            } else {
                var low = colStart + 1
                var high = totalLineCodePoints
                var best = colStart

                while (low <= high) {
                    val mid = (low + high) ushr 1
                    val cStart = content.offsetByCodePoints(0, colStart)
                    val cMid = content.offsetByCodePoints(0, mid)
                    val contentSlice = content.substring(cStart, cMid)
                    val isEntireLine = (mid == totalLineCodePoints)
                    val candidateSlice = if (isEntireLine) contentSlice + delimiter else contentSlice
                    val nextLine = if (isEntireLine) lineNum + 1 else lineNum
                    val nextCol = if (isEntireLine) 0 else mid

                    if (fitsBudget(candidateSlice, lineNum, true, nextLine, nextCol)) {
                        best = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }

                val cStart = content.offsetByCodePoints(0, colStart)
                val cBest = content.offsetByCodePoints(0, best)
                val contentSlice = content.substring(cStart, cBest)
                val isEntireLine = (best == totalLineCodePoints)
                val slice = if (isEntireLine) contentSlice + delimiter else contentSlice

                textBuilder.append(slice)
                endLine = lineNum
                truncated = true
                if (isEntireLine) {
                    nextStartLine = lineNum + 1
                    nextStartColumn = 0
                } else {
                    nextStartLine = lineNum
                    nextStartColumn = best
                }
                break
            }
        }
    }

    return WorkspaceTextPage(
        path = path,
        text = textBuilder.toString(),
        startLine = startLine,
        startColumn = startColumn,
        endLine = endLine,
        totalLines = totalLines,
        truncated = truncated,
        nextStartLine = if (truncated) nextStartLine else null,
        nextStartColumn = if (truncated) nextStartColumn else null,
    )
}
 
private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String {
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    val size = fileSize(workspaceId, area, relativePath)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    val buffer = ByteArrayOutputStream(size.toInt())
    exportFile(workspaceId, area, relativePath, buffer)
    return buffer.toString(Charsets.UTF_8.name())
}
 
private fun rootfsPathToAreaAndRelative(path: String): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}
 
private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val (area, relativePath) = rootfsPathToAreaAndRelative(path)
    val buffer = ByteArrayOutputStream()
    exportFile(workspaceId, area, relativePath, buffer)
    val bytes = buffer.toByteArray()
 
    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}
 
private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}
 
private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}
 
private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}
 
private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")
 
private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}
 
private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}
 
private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }
 
private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"
 
private fun Boolean.shellFlag(): Int = if (this) 1 else 0
 
private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}
 
private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}