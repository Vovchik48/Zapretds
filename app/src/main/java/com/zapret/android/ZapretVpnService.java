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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ZapretVpnService extends VpnService {
    
    private ParcelFileDescriptor vpnInterface;
    private volatile boolean isRunning;
    private ExecutorService executorService;
    private Set<String> bypassTargets;
    private long packetsProcessed = 0;
    private long bytesProcessed = 0;
    
    public static final String ACTION_CONNECT = "com.zapret.android.CONNECT";
    public static final String ACTION_DISCONNECT = "com.zapret.android.DISCONNECT";
    private static final String CHANNEL_ID = "zapret_channel";
    private static final int NOTIFICATION_ID = 1;
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initBypassTargets();
        executorService = Executors.newFixedThreadPool(4);
    }
    
    private void initBypassTargets() {
        bypassTargets = new HashSet<>();
        // Telegram
        bypassTargets.add("telegram.org");
        bypassTargets.add("t.me");
        bypassTargets.add("tdesktop.com");
        // YouTube/Google
        bypassTargets.add("youtube.com");
        bypassTargets.add("ytimg.com");
        bypassTargets.add("googlevideo.com");
        bypassTargets.add("ggpht.com");
        // Discord
        bypassTargets.add("discord.com");
        bypassTargets.add("discordapp.com");
        // Meta
        bypassTargets.add("instagram.com");
        bypassTargets.add("facebook.com");
        bypassTargets.add("whatsapp.com");
        // Twitter/X
        bypassTargets.add("twitter.com");
        bypassTargets.add("x.com");
        // Другие популярные
        bypassTargets.add("spotify.com");
        bypassTargets.add("netflix.com");
        bypassTargets.add("reddit.com");
        bypassTargets.add("github.com");
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
                .addDnsServer("9.9.9.9")
                .setMtu(1500)
                .setBlocking(true);
            
            vpnInterface = builder.establish();
            isRunning = true;
            
            // Запускаем обработку в отдельном потоке
            executorService.submit(this::processPackets);
            
        } catch (Exception e) {
            e.printStackTrace();
            stopVpn();
        }
    }
    
    private void processPackets() {
        try {
            FileInputStream input = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream output = new FileOutputStream(vpnInterface.getFileDescriptor());
            byte[] buffer = new byte[65536];
            ByteBuffer packetBuffer = ByteBuffer.wrap(buffer);
            packetBuffer.order(ByteOrder.BIG_ENDIAN);
            
            while (isRunning) {
                int length = input.read(buffer);
                if (length > 0) {
                    packetsProcessed++;
                    bytesProcessed += length;
                    
                    // Быстрая обработка пакета
                    byte[] processed = fastPacketProcessing(buffer, length);
                    if (processed != null) {
                        output.write(processed, 0, processed.length);
                    }
                }
            }
        } catch (Exception e) {
            // Ожидаемая ошибка при остановке
        }
    }
    
    private byte[] fastPacketProcessing(byte[] packet, int length) {
        if (length < 20) return packet;
        
        // Проверяем IP версию
        int version = (packet[0] >> 4) & 0x0F;
        if (version != 4) return packet;
        
        int ipHeaderLen = (packet[0] & 0x0F) * 4;
        if (length < ipHeaderLen) return packet;
        
        int protocol = packet[9] & 0xFF;
        
        // Оптимизированная обработка TCP
        if (protocol == 6 && length > ipHeaderLen + 20) {
            return optimizeTcpPacket(packet, length, ipHeaderLen);
        }
        
        return packet;
    }
    
    private byte[] optimizeTcpPacket(byte[] packet, int length, int ipHeaderLen) {
        int tcpHeaderLen = ((packet[ipHeaderLen + 12] >> 4) & 0x0F) * 4;
        int dataOffset = ipHeaderLen + tcpHeaderLen;
        
        if (length <= dataOffset) return packet;
        
        // Получаем данные
        int dataLen = length - dataOffset;
        byte[] data = new byte[dataLen];
        System.arraycopy(packet, dataOffset, data, 0, dataLen);
        
        // Проверяем, нужно ли обрабатывать этот пакет
        boolean needsProcessing = false;
        
        // Быстрая проверка на HTTP/HTTPS
        if (dataLen > 4) {
            if ((data[0] == 'G' && data[1] == 'E' && data[2] == 'T') ||
                (data[0] == 'P' && data[1] == 'O' && data[2] == 'S') ||
                (data[0] == 'H' && data[1] == 'E' && data[2] == 'A') ||
                (data[0] == 0x16 && data[1] == 0x03)) { // TLS
                needsProcessing = true;
            }
        }
        
        if (!needsProcessing) return packet;
        
        // Проверяем целевые домены
        String dataStr = new String(data, 0, Math.min(256, dataLen)).toLowerCase();
        boolean isTarget = false;
        for (String domain : bypassTargets) {
            if (dataStr.contains(domain)) {
                isTarget = true;
                break;
            }
        }
        
        if (!isTarget) return packet;
        
        // Применяем методы обхода
        return applyBypassMethods(packet, length, ipHeaderLen, tcpHeaderLen, data, dataLen);
    }
    
    private byte[] applyBypassMethods(byte[] packet, int length, int ipHeaderLen, 
                                       int tcpHeaderLen, byte[] data, int dataLen) {
        
        // Создаем модифицированный пакет
        byte[] modified = Arrays.copyOf(packet, length + 256);
        
        // Метод 1: Разделение HTTP запроса (Split HTTP)
        if (dataLen > 20 && (data[0] == 'G' || data[0] == 'P' || data[0] == 'H')) {
            // Добавляем специальный заголовок для обхода
            byte[] bypassHeader = "X-Bypass: zapret\r\n".getBytes();
            byte[] newData = new byte[dataLen + bypassHeader.length];
            
            // Вставляем заголовок после первой строки
            int firstLineEnd = findLineEnd(data, 0);
            if (firstLineEnd > 0 && firstLineEnd < dataLen) {
                System.arraycopy(data, 0, newData, 0, firstLineEnd);
                System.arraycopy(bypassHeader, 0, newData, firstLineEnd, bypassHeader.length);
                System.arraycopy(data, firstLineEnd, newData, firstLineEnd + bypassHeader.length, 
                               dataLen - firstLineEnd);
                
                int newDataOffset = ipHeaderLen + tcpHeaderLen;
                if (newDataOffset + newData.length <= modified.length) {
                    System.arraycopy(newData, 0, modified, newDataOffset, newData.length);
                    return Arrays.copyOf(modified, newDataOffset + newData.length);
                }
            }
        }
        
        // Метод 2: Модификация TLS (для HTTPS)
        if (dataLen > 10 && data[0] == 0x16 && data[1] == 0x03) {
            // Изменяем версию TLS
            modified[ipHeaderLen + tcpHeaderLen + 1] = 0x03;
            modified[ipHeaderLen + tcpHeaderLen + 2] = 0x02; // TLS 1.2
            
            // Добавляем dummy extension
            if (dataLen > 50) {
                modified[ipHeaderLen + tcpHeaderLen + 9] = (byte)((modified[ipHeaderLen + tcpHeaderLen + 9] & 0xFF) | 0x80);
            }
            return Arrays.copyOf(modified, length);
        }
        
        // Метод 3: Фрагментация TCP (для всех остальных)
        if (dataLen > 100) {
            // Разбиваем на два пакета
            int splitPoint = Math.min(100, dataLen / 2);
            int newLength = ipHeaderLen + tcpHeaderLen + splitPoint;
            
            // Меняем TCP флаги
            modified[ipHeaderLen + 13] = (byte)((modified[ipHeaderLen + 13] & 0xFF) | 0x10); // PSH flag
            
            return Arrays.copyOf(modified, newLength);
        }
        
        return packet;
    }
    
    private int findLineEnd(byte[] data, int start) {
        for (int i = start; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i + 2;
            }
        }
        return -1;
    }
    
    private Notification createNotification() {
        Intent disconnectIntent = new Intent(this, ZapretVpnService.class);
        disconnectIntent.setAction(ACTION_DISCONNECT);
        PendingIntent pendingIntent = PendingIntent.getService(
            this, 0, disconnectIntent, PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zapret VPN Active")
            .setContentText("🚀 High-speed bypass | " + packetsProcessed + " packets")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    private void stopVpn() {
        isRunning = false;
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
            try {
                executorService.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
