package com.zapret.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import kotlin.math.min

class ZapretVpnService : VpnService() {
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    
    // Целевые сервисы для обхода
    private val targetIps = setOf(
        "149.154.167.0/24",   // Telegram
        "91.108.4.0/22",      // Telegram
        "142.250.0.0/15",     // Google/YouTube
        "162.159.128.0/17"    // Discord
    )
    
    companion object {
        const val ACTION_CONNECT = "com.zapret.android.CONNECT"
        const val ACTION_DISCONNECT = "com.zapret.android.DISCONNECT"
        private const val CHANNEL_ID = "zapret_channel"
        private const val NOTIFICATION_ID = 1
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startVpn()
                startForeground(NOTIFICATION_ID, createNotification())
            }
            ACTION_DISCONNECT -> {
                stopVpn()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Zapret Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    
    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("Zapret VPN")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addDnsServer("77.88.8.8")
                .setMtu(1500)
            
            vpnInterface = builder.establish()
            isRunning = true
            
            Thread {
                processPackets()
            }.start()
            
        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }
    
    private fun processPackets() {
        try {
            val input = FileInputStream(vpnInterface?.fileDescriptor)
            val output = FileOutputStream(vpnInterface?.fileDescriptor)
            val buffer = ByteArray(32767)
            
            while (isRunning) {
                val length = input.read(buffer)
                if (length > 0) {
                    val packet = buffer.copyOf(length)
                    val modifiedPacket = processPacket(packet)
                    output.write(modifiedPacket)
                }
            }
        } catch (e: Exception) {
            // Ожидаемо при остановке
        }
    }
    
    private fun processPacket(packet: ByteArray): ByteArray {
        if (packet.size < 20) return packet
        
        // Получаем IP заголовок
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return packet
        
        val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ipHeaderLen) return packet
        
        val protocol = packet[9].toInt() and 0xFF
        
        // Проверяем, нужно ли обходить этот пакет
        val destIp = getDestinationIp(packet, ipHeaderLen)
        if (destIp != null && isTargetIp(destIp)) {
            return applyBypass(packet, ipHeaderLen, protocol)
        }
        
        return packet
    }
    
    private fun getDestinationIp(packet: ByteArray, ipHeaderLen: Int): String? {
        return try {
            val ipBytes = packet.copyOfRange(16, 20)
            InetAddress.getByAddress(ipBytes).hostAddress
        } catch (e: Exception) {
            null
        }
    }
    
    private fun isTargetIp(ip: String): Boolean {
        for (cidr in targetIps) {
            if (isIpInCidr(ip, cidr)) {
                return true
            }
        }
        return false
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
                if ((ipBytes[i].toInt() and mask.toInt()) != (netBytes[i].toInt() and mask.toInt())) {
                    return false
                }
                bitsChecked += bitsToCheck
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun applyBypass(packet: ByteArray, ipHeaderLen: Int, protocol: Int): ByteArray {
        val modified = packet.copyOf()
        
        // Метод 1: Изменение TTL (Time To Live)
        modified[8] = 65 // Меняем TTL на 65 (обычно 64)
        
        // Метод 2: Для TCP пакетов - фрагментация
        if (protocol == 6) { // TCP
            val tcpHeaderLen = ((modified[ipHeaderLen + 12].toInt() shr 4) and 0x0F) * 4
            val dataStart = ipHeaderLen + tcpHeaderLen
            
            if (modified.size > dataStart + 10) {
                // Добавляем случайный байт в середину данных
                val pos = dataStart + 5
                if (pos < modified.size) {
                    modified[pos] = (modified[pos].toInt() xor 0x01).toByte()
                }
            }
        }
        
        // Пересчитываем контрольную сумму
        recalculateChecksum(modified, ipHeaderLen)
        
        return modified
    }
    
    private fun recalculateChecksum(packet: ByteArray, headerLen: Int) {
        packet[10] = 0
        packet[11] = 0
        
        var sum = 0
        for (i in 0 until headerLen step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
        }
        
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        sum = sum.inv() and 0xFFFF
        
        packet[10] = (sum shr 8).toByte()
        packet[11] = sum.toByte()
    }
    
    private fun createNotification(): Notification {
        val disconnectIntent = Intent(this, ZapretVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val pendingIntent = PendingIntent.getService(
            this, 0, disconnectIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Zapret VPN")
            .setContentText("Bypassing DPI blocks | Telegram, YouTube")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun stopVpn() {
        isRunning = false
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
