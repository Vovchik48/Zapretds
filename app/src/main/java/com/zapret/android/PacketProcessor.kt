package com.zapret.android

import java.net.InetAddress
import kotlin.math.min

class PacketProcessor {
    
    fun processPacket(packet: ByteArray, mode: Config.OperationMode): ByteArray {
        if (packet.size < 20) return packet
        
        val version = (packet[0].toInt() shr 4) and 0x0F
        return if (version == 4) {
            processIPv4(packet, mode)
        } else {
            packet
        }
    }
    
    private fun processIPv4(packet: ByteArray, mode: Config.OperationMode): ByteArray {
        val headerLen = ((packet[0].toInt() and 0x0F) * 4).coerceAtMost(packet.size)
        if (headerLen < 20) return packet
        
        val protocol = packet[9].toInt() and 0xFF
        val destIpBytes = packet.copyOfRange(16, 20)
        val destIp = try {
            InetAddress.getByAddress(destIpBytes).hostAddress
        } catch (e: Exception) {
            return packet
        }
        
        val service = findServiceByIp(destIp) ?: return packet
        val profile = Config.profiles[service] ?: return packet
        
        val destPort = getDestinationPort(packet, headerLen, protocol)
        
        return applyBypassMethods(packet, headerLen, protocol, destPort, profile, mode)
    }
    
    private fun findServiceByIp(ip: String): String? {
        for ((serviceName, profile) in Config.profiles) {
            for (cidr in profile.ipRanges) {
                if (isIpInCidr(ip, cidr)) {
                    return serviceName
                }
            }
        }
        return null
    }
    
    private fun isIpInCidr(ip: String, cidr: String): Boolean {
        return try {
            val (network, prefixLenStr) = cidr.split("/")
            val prefixLen = prefixLenStr.toInt()
            
            val ipAddr = InetAddress.getByName(ip)
            val netAddr = InetAddress.getByName(network)
            
            if (ipAddr.address.size != netAddr.address.size) return false
            
            val ipBytes = ipAddr.address
            val netBytes = netAddr.address
            
            var bitsChecked = 0
            for (i in ipBytes.indices) {
                if (bitsChecked >= prefixLen) break
                
                val bitsToCheck = min(8, prefixLen - bitsChecked)
                val mask = (0xFF shl (8 - bitsToCheck)).toByte()
                
                if ((ipBytes[i] and mask) != (netBytes[i] and mask)) {
                    return false
                }
                bitsChecked += bitsToCheck
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun getDestinationPort(packet: ByteArray, ipHeaderLen: Int, protocol: Int): Int {
        return try {
            when (protocol) {
                6 -> { // TCP
                    if (packet.size < ipHeaderLen + 4) return 0
                    val tcpHeaderStart = ipHeaderLen
                    ((packet[tcpHeaderStart + 2].toInt() and 0xFF) shl 8) or
                            (packet[tcpHeaderStart + 3].toInt() and 0xFF)
                }
                17 -> { // UDP
                    if (packet.size < ipHeaderLen + 4) return 0
                    val udpHeaderStart = ipHeaderLen
                    ((packet[udpHeaderStart + 2].toInt() and 0xFF) shl 8) or
                            (packet[udpHeaderStart + 3].toInt() and 0xFF)
                }
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    private fun applyBypassMethods(
        packet: ByteArray,
        ipHeaderLen: Int,
        protocol: Int,
        destPort: Int,
        profile: Config.ServiceProfile,
        mode: Config.OperationMode
    ): ByteArray {
        var result = packet
        
        if (mode == Config.OperationMode.GAME_FILTER) {
            return modifyTTL(result, ipHeaderLen, profile.ttl)
        }
        
        for (method in profile.methods) {
            result = when (method) {
                Config.BypassMethod.TTL_CHANGE -> modifyTTL(result, ipHeaderLen, profile.ttl)
                Config.BypassMethod.TCP_FRAGMENT -> fragmentTCP(result, ipHeaderLen, protocol, profile.fragmentSize)
                Config.BypassMethod.TCP_SPLIT -> splitTCP(result, ipHeaderLen, protocol)
                Config.BypassMethod.TLS_SPOOF -> spoofTLS(result, ipHeaderLen, protocol, destPort, profile.fakeSni)
                else -> result
            }
        }
        
        Config.packetsBypassed++
        return result
    }
    
    private fun modifyTTL(packet: ByteArray, ipHeaderLen: Int, newTtl: Int): ByteArray {
        val modified = packet.copyOf()
        modified[8] = newTtl.toByte()
        recalculateIpChecksum(modified, ipHeaderLen)
        return modified
    }
    
    private fun fragmentTCP(packet: ByteArray, ipHeaderLen: Int, protocol: Int, fragmentSize: Int): ByteArray {
        if (protocol != 6) return packet
        
        val tcpHeaderLen = ((packet[ipHeaderLen + 12].toInt() shr 4) and 0x0F) * 4
        val dataStart = ipHeaderLen + tcpHeaderLen
        val dataLen = packet.size - dataStart
        
        if (dataLen <= fragmentSize) return packet
        
        return packet.copyOf(dataStart + fragmentSize).also { frag ->
            val flagsOffset = 6
            val flags = ((frag[flagsOffset].toInt() and 0xFF) shl 8) or
                    (frag[flagsOffset + 1].toInt() and 0xFF)
            val newFlags = flags or 0x2000
            frag[flagsOffset] = (newFlags shr 8).toByte()
            frag[flagsOffset + 1] = newFlags.toByte()
            recalculateIpChecksum(frag, ipHeaderLen)
        }
    }
    
    private fun splitTCP(packet: ByteArray, ipHeaderLen: Int, protocol: Int): ByteArray {
        if (protocol != 6) return packet
        
        val tcpHeaderLen = ((packet[ipHeaderLen + 12].toInt() shr 4) and 0x0F) * 4
        val dataStart = ipHeaderLen + tcpHeaderLen
        
        return packet.copyOf(dataStart + 1)
    }
    
    private fun spoofTLS(
        packet: ByteArray,
        ipHeaderLen: Int,
        protocol: Int,
        destPort: Int,
        fakeSni: String
    ): ByteArray {
        if (protocol != 6 || destPort != 443) return packet
        
        val tcpHeaderLen = ((packet[ipHeaderLen + 12].toInt() shr 4) and 0x0F) * 4
        val dataStart = ipHeaderLen + tcpHeaderLen
        
        if (packet.size <= dataStart + 5) return packet
        
        // Простая модификация TLS ClientHello
        if (packet[dataStart] == 0x16 && packet[dataStart + 5] == 0x01) {
            val modified = packet.copyOf()
            // Меняем версию TLS на 1.2
            modified[dataStart + 1] = 0x03
            modified[dataStart + 2] = 0x03
            return modified
        }
        
        return packet
    }
    
    private fun recalculateIpChecksum(packet: ByteArray, headerLen: Int) {
        packet[10] = 0
        packet[11] = 0
        
        var sum = 0
        for (i in 0 until headerLen step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or
                    (packet[i + 1].toInt() and 0xFF)
            sum += word
        }
        
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        sum = sum.inv() and 0xFFFF
        
        packet[10] = (sum shr 8).toByte()
        packet[11] = sum.toByte()
    }
}
