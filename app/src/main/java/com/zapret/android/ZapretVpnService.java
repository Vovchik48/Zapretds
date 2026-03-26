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

public class ZapretVpnService extends VpnService {
    
    private ParcelFileDescriptor vpnInterface;
    private volatile boolean isRunning;
    
    public static final String ACTION_CONNECT = "com.zapret.android.CONNECT";
    public static final String ACTION_DISCONNECT = "com.zapret.android.DISCONNECT";
    private static final String CHANNEL_ID = "zapret_channel";
    private static final int NOTIFICATION_ID = 1;
    
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
                .setMtu(1500);
            
            vpnInterface = builder.establish();
            isRunning = true;
            
            new Thread(this::processPackets).start();
            
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
                    output.write(buffer, 0, length);
                }
            }
        } catch (Exception e) {
            // Expected on stop
        }
    }
    
    private Notification createNotification() {
        Intent disconnectIntent = new Intent(this, ZapretVpnService.class);
        disconnectIntent.setAction(ACTION_DISCONNECT);
        PendingIntent pendingIntent = PendingIntent.getService(
            this, 0, disconnectIntent, PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Zapret VPN Active")
            .setContentText("VPN running")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingIntent)
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
    }
    
    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
