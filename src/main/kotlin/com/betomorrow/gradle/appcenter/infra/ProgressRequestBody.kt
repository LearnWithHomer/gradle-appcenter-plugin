package com.betomorrow.gradle.appcenter.infra

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import okio.BufferedSink
import okhttp3.RequestBody
import okio.source
import okio.buffer
import java.io.File

class ProgressRequestBody(
    private val file: File,
    private val range: LongRange,
    private val contentType: String
) : RequestBody() {

    private val contentLength: Long by lazy {
        (range.last - range.first).coerceAtMost(file.length() - range.first)
    }

    override fun contentLength(): Long {
        return contentLength
    }

    override fun contentType(): MediaType? {
        return contentType.toMediaTypeOrNull()
    }

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        file.source().buffer().use { source ->
            source.skip(range.first)
            var total = 0L
            while (total < contentLength) {
                val read = source.read(sink.buffer, contentLength)
                if (read >= 0) {
                    total += read
                } else {
                    break
                }
            }
        }
    }
}
