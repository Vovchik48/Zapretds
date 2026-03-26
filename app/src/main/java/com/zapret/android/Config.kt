package com.zapret.android

object Config {
    
    enum class OperationMode {
        NORMAL,
        GAME_FILTER,
        STEALTH
    }
    
    enum class BypassMethod {
        NONE,
        TTL_CHANGE,
        TCP_FRAGMENT,
        TCP_SPLIT,
        TLS_SPOOF,
        HTTP_FAKE,
        MSS_CLAMP
    }
    
    data class ServiceProfile(
        val name: String,
        val domains: List<String>,
        val ipRanges: List<String>,
        val methods: List<BypassMethod>,
        val ttl: Int = 64,
        val fragmentSize: Int = 100,
        val fakeSni: String = "www.google.com"
    )
    
    val profiles = mapOf(
        "telegram" to ServiceProfile(
            name = "Telegram",
            domains = listOf("*.telegram.org", "*.t.me"),
            ipRanges = listOf("149.154.160.0/20", "91.108.4.0/22"),
            methods = listOf(BypassMethod.TTL_CHANGE, BypassMethod.TCP_FRAGMENT)
        ),
        "youtube" to ServiceProfile(
            name = "YouTube",
            domains = listOf("*.youtube.com", "*.googlevideo.com"),
            ipRanges = listOf("142.250.0.0/15", "172.217.0.0/16"),
            methods = listOf(BypassMethod.TLS_SPOOF, BypassMethod.TCP_SPLIT),
            fakeSni = "www.google.com"
        ),
        "discord" to ServiceProfile(
            name = "Discord",
            domains = listOf("*.discord.com", "*.discordapp.com"),
            ipRanges = listOf("162.159.128.0/17"),
            methods = listOf(BypassMethod.TTL_CHANGE, BypassMethod.TLS_SPOOF)
        )
    )
    
    var activeProfiles = setOf("telegram", "youtube", "discord")
    var currentMode = OperationMode.NORMAL
    var bytesProcessed: Long = 0
    var packetsBypassed: Long = 0
    
    const val PACKET_BUFFER_SIZE = 32768
}
