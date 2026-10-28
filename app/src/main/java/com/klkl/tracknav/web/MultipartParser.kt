package com.klkl.tracknav.web

/** 从 multipart 请求体中抽出的单个文件。 */
data class UploadedFile(
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * 极简 multipart/form-data 解析器：只取第一个 name="file" 的文件字段。
 *
 * 手表端只需要接收一个 GPX，因此不引入完整 multipart 实现；全部在字节数组上
 * 做子串查找，避免把二进制内容按字符串处理导致损坏。
 */
object MultipartParser {

    private val CRLF = byteArrayOf(13, 10)
    private val HEADER_END = byteArrayOf(13, 10, 13, 10)
    private val FILE_NAME_REGEX = Regex("filename=\"([^\"]*)\"")

    fun extractFirstFile(body: ByteArray, boundary: String): UploadedFile? {
        if (boundary.isBlank()) return null
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        // 跳过前导分隔行，定位到第一个 part 的头部起点。
        val first = indexOf(body, delimiter, 0) ?: return null
        var headerStart = first + delimiter.size
        if (matches(body, headerStart, CRLF)) headerStart += CRLF.size
        val headerEnd = indexOf(body, HEADER_END, headerStart) ?: return null
        val headers = String(body, headerStart, headerEnd - headerStart, Charsets.UTF_8)
        val contentDisposition = headers.lineSequence()
            .firstOrNull { it.trimStart().startsWith("Content-Disposition:", ignoreCase = true) }
            ?: return null
        if (!contentDisposition.contains("name=\"file\"", ignoreCase = true)) return null
        // 浏览器可能不提供 filename，此时用默认名占位（后续仍会校验 .gpx 后缀）。
        val fileName = FILE_NAME_REGEX.find(contentDisposition)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: "upload.gpx"

        val dataStart = headerEnd + HEADER_END.size
        // part 内容以 CRLF + "--boundary" 结束，该终止符不属于文件内容。
        val terminator = byteArrayOf(13, 10) + delimiter
        val dataEnd = indexOf(body, terminator, dataStart) ?: return null
        return UploadedFile(
            fileName = fileName,
            bytes = body.copyOfRange(dataStart, dataEnd),
        )
    }

    /** 朴素子串查找；请求体最多 21 MB，无需 KMP 等高级算法。 */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        val lastStart = haystack.size - needle.size
        for (index in start.coerceAtLeast(0)..lastStart) {
            if (matches(haystack, index, needle)) return index
        }
        return null
    }

    /** 逐字节比较，边界越界时返回 false 而不抛异常。 */
    private fun matches(haystack: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > haystack.size) return false
        for (index in needle.indices) {
            if (haystack[offset + index] != needle[index]) return false
        }
        return true
    }
}
