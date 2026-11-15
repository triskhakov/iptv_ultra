package com.iptv.ultrapro.data.parser

import com.iptv.ultrapro.data.db.ChannelEntity
import com.iptv.ultrapro.data.db.EpgProgramEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object StreamUrlResolver {

    fun resolveLiveUrl(channel: ChannelEntity, udpProxyUrl: String): String {
        val rawUrl = channel.streamUrl.trim()
        val proxy = udpProxyUrl.trim().trimEnd('/')
        if (proxy.isNotEmpty() && (rawUrl.startsWith("udp://", true) || rawUrl.startsWith("rtp://", true))) {
            val proto = if (rawUrl.startsWith("udp://", true)) "udp" else "rtp"
            val address = rawUrl.substringAfter("://").removePrefix("@")
            return "$proxy/$proto/$address"
        }
        return rawUrl
    }

    fun resolveCatchupUrl(channel: ChannelEntity, program: EpgProgramEntity, udpProxyUrl: String): String {
        val baseLiveUrl = resolveLiveUrl(channel, udpProxyUrl)
        val startSec = program.startTime / 1000L
        val endSec = program.endTime / 1000L
        val durationSec = (endSec - startSec).coerceAtLeast(60L)
        val utcNowSec = System.currentTimeMillis() / 1000L

        val customSource = channel.catchupSource
        if (!customSource.isNullOrBlank()) {
            return applyTemplateVariables(customSource, startSec, endSec, durationSec, utcNowSec)
        }

        return when (channel.catchupType?.lowercase()) {
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> {
                if (baseLiveUrl.contains("index.m3u8")) {
                    baseLiveUrl.replace("index.m3u8", "index-$startSec-$durationSec.m3u8")
                } else if (baseLiveUrl.contains("mono.m3u8")) {
                    baseLiveUrl.replace("mono.m3u8", "mono-$startSec-$durationSec.m3u8")
                } else {
                    "$baseLiveUrl?utc=$startSec&lutc=$utcNowSec"
                }
            }
            "shift", "default", "append" -> {
                val separator = if (baseLiveUrl.contains("?")) "&" else "?"
                "$baseLiveUrl${separator}utc=$startSec&lutc=$utcNowSec"
            }
            "xc" -> {
                val regex = Regex("""^(https?://[^/]+)/live/([^/]+)/([^/]+)/(\d+)\.(ts|m3u8)$""")
                val match = regex.find(baseLiveUrl)
                if (match != null) {
                    val (host, user, pass, streamId, ext) = match.destructured
                    val durMin = (durationSec / 60L).coerceAtLeast(1L)
                    val sdf = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val formattedStart = sdf.format(Date(program.startTime))
                    "$host/timeshift/$user/$pass/$durMin/$formattedStart/$streamId.$ext"
                } else {
                    "$baseLiveUrl?utc=$startSec&lutc=$utcNowSec"
                }
            }
            else -> baseLiveUrl
        }
    }

    private fun applyTemplateVariables(
        template: String,
        startSec: Long,
        endSec: Long,
        durationSec: Long,
        utcNowSec: Long
    ): String {
        val date = Date(startSec * 1000L)
        fun fmt(pattern: String): String =
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

        return template
            .replace("\${start}", startSec.toString())
            .replace("\${end}", endSec.toString())
            .replace("\${duration}", durationSec.toString())
            .replace("\${timestamp}", utcNowSec.toString())
            .replace("{utc}", startSec.toString())
            .replace("{lutc}", utcNowSec.toString())
            .replace("{start}", startSec.toString())
            .replace("{end}", endSec.toString())
            .replace("{duration}", durationSec.toString())
            .replace("\${(b)Y}", fmt("yyyy"))
            .replace("\${(b)m}", fmt("MM"))
            .replace("\${(b)d}", fmt("dd"))
            .replace("\${(b)H}", fmt("HH"))
            .replace("\${(b)M}", fmt("mm"))
            .replace("\${(b)S}", fmt("ss"))
    }
}
