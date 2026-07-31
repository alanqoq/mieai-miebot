package com.mieai.bot.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.bot.history.ImageAttachment

class AttachmentParser(private val json: ObjectMapper) {
    fun parse(rawPayload: String): List<ImageAttachment> = runCatching {
        val root = json.readTree(rawPayload)
        val attachments = root.path("d").path("attachments")
        if (!attachments.isArray) return emptyList()
        buildList {
            attachments.forEachIndexed { index, node ->
                val url = node.path("url").asText().trim()
                val contentType = node.path("content_type").asText(null)?.trim()?.lowercase()
                val filename = node.path("filename").asText("").trim().lowercase()
                if (url.isNotEmpty() && isImage(contentType, filename)) {
                    add(ImageAttachment(index, url, contentType))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun isImage(contentType: String?, filename: String): Boolean {
        if (contentType?.startsWith("image/") == true) return true
        if (contentType?.startsWith("video/") == true || contentType?.startsWith("audio/") == true) return false
        return filename.substringAfterLast('.', "") in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }
}
