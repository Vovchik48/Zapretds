package com.zapret.android

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ZapretVpnService : VpnService() {
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private lateinit var serviceScope: CoroutineScope
    private val packetProcessor = PacketProcessor()
    
    companion object {
        const val ACTION_CONNECT = "com.zapret.android.CONNECT"
        const val ACTION_DISCONNECT = "com.zapret.android.DISCONNECT"
        const val ACTION_TOGGLE_MODE = "com.zapret.android.TOGGLE_MODE"
        
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "zapret_vpn_channel"
        
        @Volatile
        var currentMode: Config.OperationMode = Config.OperationMode.NORMAL
    }
    
    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE_MODE -> {
                toggleMode()
                updateNotification()
            }
        }
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zapret VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
    
    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("Zapret VPN")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(true)
            
            vpnInterface = builder.establish() ?: throw IllegalStateException("Failed to establish VPN")
            isRunning.set(true)
            
            serviceScope.launch {
                processPackets()
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }
    
    private suspend fun processPackets() {
        val input = FileInputStream(vpnInterface?.fileDescriptor ?: return)
        val output = FileOutputStream(vpnInterface?.fileDescriptor ?: return)
        val buffer = ByteArray(Config.PACKET_BUFFER_SIZE)
        
        while (isRunning.get()) {
            try {
                val length = input.read(buffer)
                if (length > 0) {
                    val packet = buffer.copyOf(length)
                    Config.bytesProcessed += length
                    
                    val processedPacket = packetProcessor.processPacket(packet, currentMode)
                    
                    output.write(processedPacket)
                    output.flush()
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    e.printStackTrace()
                }
                break
            }
        }
    }
    
    private fun toggleMode() {
        currentMode = when (currentMode) {
            Config.OperationMode.NORMAL -> Config.OperationMode.GAME_FILTER
            Config.OperationMode.GAME_FILTER -> Config.OperationMode.STEALTH
            Config.OperationMode.STEALTH -> Config.OperationMode.NORMAL
        }
    }
    
    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification())
    }
    
    private fun createNotification(): Notification {
        val modeText = when (currentMode) {
            Config.OperationMode.NORMAL -> "Normal"
            Config.OperationMode.GAME_FILTER -> "Game"
            Config.OperationMode.STEALTH -> "Stealth"
        }
        
        val toggleIntent = Intent(this, ZapretVpnService::class.java).apply {
            action = ACTION_TOGGLE_MODE
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val disconnectIntent = Intent(this, ZapretVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 2, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Zapret VPN")
            .setContentText("Mode: $modeText | ${Config.packetsBypassed} packets")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_menu_rotate, "Toggle Mode", togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", disconnectPendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun stopVpn() {
        isRunning.set(false)
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            vpnInterface = null
        }
        serviceScope.cancel()
    }
    
    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
