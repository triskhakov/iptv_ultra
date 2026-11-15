package com.iptv.ultrapro.data.parser

import android.util.Xml
import com.iptv.ultrapro.data.db.EpgProgramEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

class XmlTvStreamParser {

    private val dateFormats = listOf(
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
    )

    fun parse(rawStream: InputStream, isGzipHint: Boolean = false): Flow<EpgProgramEntity> = flow {
        val buffered = BufferedInputStream(rawStream, 65536)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()

        val isGzip = isGzipHint || (b1 == 0x1f && b2 == 0x8b)
        val inputStream = if (isGzip) GZIPInputStream(buffered) else buffered

        val now = System.currentTimeMillis()
        val minKeepTime = now - 7L * 24 * 3600 * 1000
        val maxKeepTime = now + 4L * 24 * 3600 * 1000

        inputStream.use { stream ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, "UTF-8")

            var eventType = parser.eventType
            var currentChannel: String? = null
            var currentStart: Long = 0L
            var currentStop: Long = 0L
            var currentTitle: String? = null
            var currentDesc: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "programme" -> {
                                currentChannel = parser.getAttributeValue(null, "channel")
                                currentStart = parseXmlTvDate(parser.getAttributeValue(null, "start"))
                                currentStop = parseXmlTvDate(parser.getAttributeValue(null, "stop"))
                                currentTitle = null
                                currentDesc = null
                            }
                            "title" -> {
                                if (currentChannel != null) {
                                    currentTitle = parser.nextText()
                                }
                            }
                            "desc" -> {
                                if (currentChannel != null) {
                                    currentDesc = parser.nextText()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme" && !currentChannel.isNullOrBlank() && !currentTitle.isNullOrBlank()) {
                            if (currentStop >= minKeepTime && currentStart <= maxKeepTime) {
                                emit(
                                    EpgProgramEntity(
                                        channelTvgId = currentChannel!!,
                                        title = currentTitle!!,
                                        description = currentDesc,
                                        startTime = currentStart,
                                        endTime = currentStop
                                    )
                                )
                            }
                            currentChannel = null
                        }
                    }
                }
                eventType = parser.next()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun parseXmlTvDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.trim()
        for (fmt in dateFormats) {
            try {
                return fmt.parse(cleaned)?.time ?: 0L
            } catch (_: Exception) { }
        }
        return 0L
    }
}
