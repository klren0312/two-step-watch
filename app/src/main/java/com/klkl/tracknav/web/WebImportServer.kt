package com.klkl.tracknav.web

import android.util.Log
import com.klkl.tracknav.BuildConfig
import com.klkl.tracknav.data.ImportReport
import com.klkl.tracknav.data.RouteRepository
import com.klkl.tracknav.data.RouteStorage
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 局域网导入服务的连接信息。
 *
 * @property ip 当前局域网 IPv4；未连 Wi-Fi 时为 null
 * @property port 实际监听端口；启动失败时为 0
 * @property token 每次启动重新生成的一次性路径令牌
 */
data class WebImportInfo(
    val ip: String?,
    val port: Int,
    val token: String,
) {
    /** 完整访问地址，形如 http://192.168.1.5:8765/<token>/。 */
    val url: String?
        get() = ip?.let { "http://$it:$port/$token/" }
}

/**
 * 手机/电脑上传 GPX 用的极简 HTTP 服务，仅监听局域网。
 *
 * 安全约束：
 * - 所有路径都带一次性 token，token 错误一律 404，不暴露存在性；
 * - 只接受 multipart 中的单个 .gpx 文件，单文件上限 20 MB；
 * - 只读取 Content-Length 指定长度的请求体，不信任 chunked；
 * - [stop] 时关闭监听并强制断开所有客户端，离开页面即失效。
 */
class WebImportServer(private val repository: RouteRepository) {

    private companion object {
        const val TAG = "WebImportServer"
        const val DEFAULT_PORT = 8765
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_GPX_BYTES = RouteStorage.MAX_GPX_BYTES
        const val MAX_BODY_BYTES = MAX_GPX_BYTES + 1024 * 1024
    }

    private var token = generateToken()
    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    /** 已接入的客户端，用于 stop 时强制关闭，避免 keep-alive 拖住服务。 */
    private val clientSockets = mutableSetOf<Socket>()

    /**
     * 启动监听。重复调用返回现有信息，不重复开端口。
     *
     * 端口从 8765 开始向后尝试 10 个，尽量避开系统或其他应用占用。
     */
    fun start(): WebImportInfo {
        if (serverSocket != null) return currentInfo()
        token = generateToken()
        val socket = (DEFAULT_PORT..DEFAULT_PORT + 10).firstNotNullOfOrNull { port ->
            runCatching { ServerSocket(port) }.getOrNull()
        }
        if (socket == null) {
            return WebImportInfo(ip = findLanIp(), port = 0, token = token)
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        serverSocket = socket
        acceptJob = newScope.launch {
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                synchronized(clientSockets) { clientSockets += client }
                // 每个连接单独协程处理，慢请求不会阻塞 accept 循环。
                launch {
                    try {
                        client.use { handle(it) }
                    } finally {
                        synchronized(clientSockets) { clientSockets -= client }
                    }
                }
            }
        }
        val info = currentInfo()
        if (BuildConfig.DEBUG) {
            runCatching { Log.d(TAG, "web import url=${info.url ?: "no-lan-ip"} port=${info.port}") }
        }
        return info
    }

    fun currentInfo(): WebImportInfo = WebImportInfo(
        ip = findLanIp(),
        port = serverSocket?.localPort ?: 0,
        token = token,
    )

    /** 关闭监听、取消协程并断开所有已接入客户端。 */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        val clients = synchronized(clientSockets) {
            clientSockets.toList().also { clientSockets.clear() }
        }
        clients.forEach { client -> runCatching { client.close() } }
    }

    private fun handle(client: Socket) {
        // 15 s 超时防止客户端半开连接长期占用协程。
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) {
            respondHtml(output, 400, "Bad Request", errorPage("请求格式错误"))
            return
        }
        val method = parts[0].uppercase(Locale.US)
        // 查询串不参与路由，避免 ?x=1 绕开 token 校验。
        val target = parts[1].substringBefore('?')
        val headers = try {
            readHeaders(input)
        } catch (exception: IOException) {
            respondHtml(output, 431, "Request Header Fields Too Large", errorPage("请求头过大"))
            return
        }

        val pagePath = "/$token/"
        if (method == "GET" && (target == pagePath || target == "/$token")) {
            respondHtml(output, 200, "OK", uploadPage())
            return
        }
        if (method == "POST" && target == "/$token/upload") {
            handleUpload(input, output, headers)
            return
        }
        // token 不匹配与路径不存在统一返回 404，不泄露服务信息。
        respondHtml(output, 404, "Not Found", errorPage("页面不存在或 token 已失效"))
    }

    /**
     * 处理上传：先校验长度与类型，再按 Content-Length 读满整个请求体。
     *
     * 失败分支都会返回具体中文原因，方便用户在手机浏览器上直接看到问题。
     */
    private fun handleUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
    ) {
        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0) {
            respondHtml(output, 411, "Length Required", errorPage("缺少 Content-Length"))
            return
        }
        if (contentLength > MAX_BODY_BYTES) {
            respondHtml(output, 413, "Payload Too Large", errorPage("文件超过 20 MB"))
            return
        }
        // 浏览器可能先发 Expect: 100-continue 等待许可，再发请求体。
        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        val body = readExactly(input, contentLength.toInt())
        if (body == null) {
            respondHtml(output, 400, "Bad Request", errorPage("请求体不完整"))
            return
        }
        val contentType = headers["content-type"].orEmpty()
        // 从 Content-Type 里取出 boundary 参数，可能带双引号。
        val boundary = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(contentType)
            ?.groupValues
            ?.get(1)
        if (boundary == null) {
            respondHtml(output, 400, "Bad Request", errorPage("不是 multipart 上传"))
            return
        }
        val uploaded = MultipartParser.extractFirstFile(body, boundary)
        if (uploaded == null) {
            respondHtml(output, 400, "Bad Request", errorPage("没有找到文件字段"))
            return
        }
        if (!uploaded.fileName.endsWith(".gpx", ignoreCase = true)) {
            respondHtml(output, 400, "Bad Request", errorPage("只接受 .gpx 文件"))
            return
        }
        if (uploaded.bytes.isEmpty()) {
            respondHtml(output, 400, "Bad Request", errorPage("文件为空"))
            return
        }
        if (uploaded.bytes.size > MAX_GPX_BYTES) {
            respondHtml(output, 413, "Payload Too Large", errorPage("文件超过 20 MB"))
            return
        }

        val report = try {
                // 导入会读写文件，这里同步等待结果，以便返回导入明细页。
            runBlocking { repository.importUploaded(uploaded.fileName, uploaded.bytes) }
        } catch (exception: Exception) {
            Log.w(TAG, "upload import failed", exception)
            respondHtml(
                output,
                500,
                "Internal Server Error",
                errorPage("导入失败：${escapeHtml(exception.message ?: "未知错误")}"),
            )
            return
        }
        respondHtml(output, 200, "OK", resultPage(report))
    }

    /** 上传页：表单 action 已内嵌 token，离开页面后该地址即失效。 */
    private fun uploadPage(): String = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>轨迹导航 · 导入 GPX</title>
          <style>
            :root { color-scheme: light dark; }
            body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                   background: #101010; color: #f2f2f2; }
            main { max-width: 560px; margin: 0 auto; padding: 32px 20px 48px; }
            h1 { color: #00e676; font-size: 28px; margin: 0 0 12px; }
            .lead { color: #cfcfcf; line-height: 1.6; }
            form { margin: 24px 0; padding: 20px; border-radius: 16px; background: #1c1c1c;
                   display: grid; gap: 16px; }
            label.file { display: grid; gap: 10px; font-weight: 600; }
            input[type=file] { color: #f2f2f2; }
            button { border: 0; border-radius: 999px; padding: 14px 20px; background: #00e676;
                     color: #00210d; font-size: 16px; font-weight: 700; }
            .hint { color: #9e9e9e; font-size: 13px; line-height: 1.6; }
            a.button { display: inline-block; margin-top: 16px; padding: 12px 20px; border-radius: 999px;
                       background: #00e676; color: #00210d; font-weight: 700; text-decoration: none; }
            .error { color: #ffb74d; }
          </style>
        </head>
        <body>
        <main>
          <h1>轨迹导航</h1>
          <p class="lead">从手机或电脑上传两步路导出的 GPX，上传后会自动导入到手表。</p>
          <form method="post" enctype="multipart/form-data" action="/$token/upload">
            <label class="file">选择 GPX 文件
              <input type="file" name="file" accept=".gpx,application/gpx+xml" required>
            </label>
            <button type="submit">上传并导入</button>
          </form>
          <p class="hint">单个文件不超过 20 MB；手表与手机需在同一 Wi-Fi；只接受 .gpx。</p>
          <p class="hint">地址包含一次性 token，离开手表上的网页导入页后立即失效。</p>
        </main>
        </body>
        </html>
    """.trimIndent()

    private fun resultPage(report: ImportReport): String {
        val imported = report.imported.joinToString("、") { escapeHtml(it) }
        val importedLine = if (imported.isNotEmpty()) {
            "<p>已导入：$imported</p>"
        } else {
            "<p>没有新增轨迹（文件可能已存在）。</p>"
        }
        val failureLine = report.failed.firstOrNull()?.let {
            "<p class=\"error\">失败：${escapeHtml(it.fileName)}（${escapeHtml(it.message)}）</p>"
        }.orEmpty()
        val skippedLine = if (report.skipped.isNotEmpty()) {
            "<p class=\"hint\">已存在或重复：${report.skipped.joinToString("、") { escapeHtml(it) }}</p>"
        } else {
            ""
        }
        val warningLine = if (report.warnings.isNotEmpty()) {
            "<p class=\"warning\">警告 ${report.warnings.size} 条：${report.warnings.joinToString("；") { escapeHtml(it) }}</p>"
        } else {
            ""
        }
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>轨迹导航 · 导入结果</title>
              <style>
                body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                       background: #101010; color: #f2f2f2; }
                main { max-width: 560px; margin: 0 auto; padding: 32px 20px 48px; }
                h1 { color: #00e676; font-size: 28px; margin: 0 0 12px; }
                p { line-height: 1.7; }
                .hint { color: #9e9e9e; font-size: 13px; }
                .error { color: #ffb74d; }
                .warning { color: #ffb74d; }
                a.button { display: inline-block; margin-top: 16px; padding: 12px 20px; border-radius: 999px;
                           background: #00e676; color: #00210d; font-weight: 700; text-decoration: none; }
              </style>
            </head>
            <body>
            <main>
              <h1>导入完成</h1>
              <p>已导入 ${report.imported.size} 条；跳过 ${report.skipped.size} 条；失败 ${report.failed.size} 条。</p>
              $importedLine
              $skippedLine
              $failureLine
              $warningLine
              <p class="hint">回到手表路线库查看新轨迹。</p>
              <a class="button" href="/$token/">继续上传</a>
            </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun errorPage(message: String): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>轨迹导航 · 错误</title>
        <style>body { background: #101010; color: #f2f2f2; font-family: sans-serif; padding: 32px; }
        h1 { color: #ffb74d; }</style></head>
        <body><h1>无法上传</h1><p>$message</p></body></html>
    """.trimIndent()

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            // 累计长度限制防止恶意超长请求头耗尽内存。
            total += line.length
            if (total > MAX_HEADER_BYTES) throw IOException("headers too large")
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
        }
        return headers
    }

    /** 按 CRLF 读一行（兼容裸 LF），返回 ISO-8859-1 字符串以保留原始字节。 */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
            if (buffer.size() > MAX_HEADER_BYTES) throw IOException("header line too long")
        }
        return buffer.toString("ISO-8859-1")
    }

    /** 读满 length 字节；连接提前关闭时返回 null。 */
    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) return null
            offset += read
        }
        return buffer
    }

    /** 统一输出 HTML 响应；写失败（客户端已断开）时静默忽略。 */
    private fun respondHtml(
        output: OutputStream,
        status: Int,
        reason: String,
        html: String,
    ) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            output.write(headers.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }

    /**
     * 找出适合展示给用户的局域网 IPv4 地址。
     *
     * 优先 wlan* 接口（手表通常走 Wi-Fi），跳过回环与 169.254 链路本地地址。
     */
    private fun findLanIp(): String? = runCatching {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.lowercase(Locale.US).startsWith("wlan") }
        interfaces
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .firstOrNull()
    }.getOrNull()

    /** 生成 12 位十六进制令牌（6 字节随机数），仅本次监听有效。 */
    private fun generateToken(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 转义 HTML 特殊字符，防止路线名/文件名注入标记。 */
    private fun escapeHtml(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }
}
