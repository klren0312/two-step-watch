package com.klkl.tracknav.web

data class UploadedFile(
    val fileName: String,
    val bytes: ByteArray,
)

object MultipartParser {

    private val CRLF = byteArrayOf(13, 10)
    private val HEADER_END = byteArrayOf(13, 10, 13, 10)
    private val FILE_NAME_REGEX = Regex("filename=\"([^\"]*)\"")

    fun extractFirstFile(body: ByteArray, boundary: String): UploadedFile? {
        if (boundary.isBlank()) return null
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val first = indexOf(body, delimiter, 0) ?: return null
        var headerStart = first + delimiter.size
        if (matches(body, headerStart, CRLF)) headerStart += CRLF.size
        val headerEnd = indexOf(body, HEADER_END, headerStart) ?: return null
        val headers = String(body, headerStart, headerEnd - headerStart, Charsets.UTF_8)
        val contentDisposition = headers.lineSequence()
            .firstOrNull { it.trimStart().startsWith("Content-Disposition:", ignoreCase = true) }
            ?: return null
        if (!contentDisposition.contains("name=\"file\"", ignoreCase = true)) return null
        val fileName = FILE_NAME_REGEX.find(contentDisposition)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: "upload.gpx"

        val dataStart = headerEnd + HEADER_END.size
        val terminator = byteArrayOf(13, 10) + delimiter
        val dataEnd = indexOf(body, terminator, dataStart) ?: return null
        return UploadedFile(
            fileName = fileName,
            bytes = body.copyOfRange(dataStart, dataEnd),
        )
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        val lastStart = haystack.size - needle.size
        for (index in start.coerceAtLeast(0)..lastStart) {
            if (matches(haystack, index, needle)) return index
        }
        return null
    }

    private fun matches(haystack: ByteArray, offset: Int, needle: ByteArray): Boolean {
        if (offset < 0 || offset + needle.size > haystack.size) return false
        for (index in needle.indices) {
            if (haystack[offset + index] != needle[index]) return false
        }
        return true
    }
}
