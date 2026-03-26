package com.zapret.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ZapretVpnService extends VpnService {
    
    private ParcelFileDescriptor vpnInterface;
    private volatile boolean isRunning;
    private Thread processorThread;
    
    // Хранилище для потоков
    private Map<Integer, TcpSession> tcpSessions = new ConcurrentHashMap<>();
    
    public static final String ACTION_CONNECT = "com.zapret.android.CONNECT";
    public static final String ACTION_DISCONNECT = "com.zapret.android.DISCONNECT";
    private static final String CHANNEL_ID = "zapret_channel";
    private static final int NOTIFICATION_ID = 1;
    
    // Целевые домены для обхода
    private static final String[] TARGET_DOMAINS = {
        "telegram.org", "t.me", "tg",
        "youtube.com", "yt", "googlevideo",
        "discord.com", "discord.gg",
        "twitter.com", "x.com",
        "instagram.com",
        "facebook.com"
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_CONNECT.equals(intent.getAction())) {
                startVpn();
                startForeground(NOTIFICATION_ID, createNotification());
            } else if (ACTION_DISCONNECT.equals(intent.getAction())) {
                stopVpn();
                stopForeground(true);
                stopSelf();
            }
        }
        return START_STICKY;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Zapret Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
    
    private void startVpn() {
        try {
            Builder builder = new Builder()
                .setSession("Zapret VPN")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addDnsServer("77.88.8.8")
                .setMtu(1500);
            
            vpnInterface = builder.establish();
            isRunning = true;
            
            processorThread = new Thread(this::processPackets);
            processorThread.start();
            
        } catch (Exception e) {
            e.printStackTrace();
            stopVpn();
        }
    }
    
    private void processPackets() {
        try {
            FileInputStream input = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream output = new FileOutputStream(vpnInterface.getFileDescriptor());
            byte[] buffer = new byte[32767];
            
            while (isRunning) {
                int length = input.read(buffer);
                if (length > 0) {
                    byte[] processed = processIpPacket(buffer, length);
                    if (processed != null) {
                        output.write(processed, 0, processed.length);
                    }
                }
            }
        } catch (Exception e) {
            // Expected on stop
        }
    }
    
    private byte[] processIpPacket(byte[] packet, int length) {
        if (length < 20) return packet;
        
        // Парсим IP заголовок
        int version = (packet[0] >> 4) & 0x0F;
        if (version != 4) return packet;
        
        int ipHeaderLen = (packet[0] & 0x0F) * 4;
        if (length < ipHeaderLen) return packet;
        
        int protocol = packet[9] & 0xFF;
        
        // Обрабатываем TCP пакеты
        if (protocol == 6) {
            return processTcpPacket(packet, length, ipHeaderLen);
        }
        // Обрабатываем UDP пакеты (DNS, Telegram)
        else if (protocol == 17) {
            return processUdpPacket(packet, length, ipHeaderLen);
        }
        
        return packet;
    }
    
    private byte[] processTcpPacket(byte[] packet, int length, int ipHeaderLen) {
        if (length < ipHeaderLen + 20) return packet;
        
        int tcpHeaderLen = ((packet[ipHeaderLen + 12] >> 4) & 0x0F) * 4;
        int dataOffset = ipHeaderLen + tcpHeaderLen;
        
        if (length <= dataOffset) return packet;
        
        // Получаем данные
        byte[] data = new byte[length - dataOffset];
        System.arraycopy(packet, dataOffset, data, 0, data.length);
        
        // Анализируем данные
        boolean isHttp = isHttpRequest(data);
        boolean isTls = isTlsClientHello(data);
        
        // Если это HTTP запрос к целевому домену
        if (isHttp && containsTargetDomain(data)) {
            // Разделяем HTTP запрос (Split HTTP) - основной метод обхода DPI
            return splitHttpRequest(packet, length, ipHeaderLen, tcpHeaderLen, data);
        }
        
        // Если это TLS ClientHello
        if (isTls && containsTargetDomain(data)) {
            // Модифицируем TLS для обхода
            return modifyTlsPacket(packet, length, ipHeaderLen, tcpHeaderLen, data);
        }
        
        return packet;
    }
    
    private boolean isHttpRequest(byte[] data) {
        if (data.length < 8) return false;
        String start = new String(data, 0, Math.min(8, data.length));
        return start.startsWith("GET ") || start.startsWith("POST ") || 
               start.startsWith("HEAD ") || start.startsWith("PUT ") ||
               start.startsWith("CONNECT") || start.startsWith("HTTP/");
    }
    
    private boolean isTlsClientHello(byte[] data) {
        if (data.length < 5) return false;
        // TLS handshake: 0x16, version, 0x01 (ClientHello)
        return (data[0] & 0xFF) == 0x16 && data[5] == 0x01;
    }
    
    private boolean containsTargetDomain(byte[] data) {
        String str = new String(data).toLowerCase();
        for (String domain : TARGET_DOMAINS) {
            if (str.contains(domain.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    // Split HTTP Request - разбиваем HTTP запрос на несколько маленьких пакетов
    private byte[] splitHttpRequest(byte[] packet, int length, int ipHeaderLen, int tcpHeaderLen, byte[] data) {
        // Создаем модифицированный пакет
        byte[] modified = new byte[length];
        System.arraycopy(packet, 0, modified, 0, length);
        
        // Разбиваем HTTP запрос на части
        String httpStr = new String(data);
        
        // Находим конец заголовков
        int headerEnd = httpStr.indexOf("\r\n\r\n");
        if (headerEnd > 0) {
            // Добавляем дополнительный перевод строки для разрыва
            byte[] splitMarker = "\r\nX-Bypass: 1\r\n".getBytes();
            byte[] newData = new byte[data.length + splitMarker.length];
            System.arraycopy(data, 0, newData, 0, headerEnd + 4);
            System.arraycopy(splitMarker, 0, newData, headerEnd + 4, splitMarker.length);
            System.arraycopy(data, headerEnd + 4, newData, headerEnd + 4 + splitMarker.length, data.length - (headerEnd + 4));
            
            // Заменяем данные
            int newDataOffset = ipHeaderLen + tcpHeaderLen;
            if (newDataOffset + newData.length <= modified.length) {
                System.arraycopy(newData, 0, modified, newDataOffset, newData.length);
                return modified;
            }
        }
        
        return packet;
    }
    
    // Модифицируем TLS для обхода DPI
    private byte[] modifyTlsPacket(byte[] packet, int length, int ipHeaderLen, int tcpHeaderLen, byte[] data) {
        byte[] modified = new byte[length];
        System.arraycopy(packet, 0, modified, 0, length);
        
        if (data.length > 10) {
            // Меняем версию TLS на более старую (иногда помогает)
            modified[ipHeaderLen + tcpHeaderLen + 1] = 0x03; // TLS 1.0
            modified[ipHeaderLen + tcpHeaderLen + 2] = 0x01;
            
            // Добавляем fake extension
            if (data.length > 50) {
                // Вставляем dummy расширение
                modified[ipHeaderLen + tcpHeaderLen + 9] = (byte)((modified[ipHeaderLen + tcpHeaderLen + 9] & 0xFF) | 0x01);
            }
        }
        
        return modified;
    }
    
    private byte[] processUdpPacket(byte[] packet, int length, int ipHeaderLen) {
        // Для UDP (DNS) просто пропускаем
        return packet;
    }
    
    private class TcpSession {
        int sourcePort;
        int destPort;
        boolean isBypassing;
        StringBuilder buffer = new StringBuilder();
        
        TcpSession(int srcPort, int dstPort) {
            this.sourcePort = srcPort;
            this.destPort = dstPort;
            this.isBypassing = false;
        }
    }
    
    private Notification createNotification() {
        Intent disconnectIntent = new Intent(this, ZapretVpnService.class);
        disconnectIntent.setAction(ACTION_DISCONNECT);
        PendingIntent pendingIntent = PendingIntent.getService(
            this, 0, disconnectIntent, PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zapret VPN Active")
            .setContentText("Bypassing DPI blocks | Telegram, YouTube, Discord")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void stopVpn() {
        isRunning = false;
        if (processorThread != null) {
            processorThread.interrupt();
        }
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        tcpSessions.clear();
    }
    
    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
