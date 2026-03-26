package com.zapret.android;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    
    private static final int VPN_REQUEST_CODE = 100;
    private Button connectButton, disconnectButton;
    private TextView statusText;
    private boolean isVpnActive = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        connectButton = findViewById(R.id.btn_connect);
        disconnectButton = findViewById(R.id.btn_disconnect);
        statusText = findViewById(R.id.status_text);
        
        connectButton.setOnClickListener(v -> startVpn());
        disconnectButton.setOnClickListener(v -> stopVpn());
        
        disconnectButton.setEnabled(false);
    }
    
    private void startVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            Intent serviceIntent = new Intent(this, ZapretVpnService.class);
            serviceIntent.setAction(ZapretVpnService.ACTION_CONNECT);
            startService(serviceIntent);
            
            isVpnActive = true;
            statusText.setText("Status: Active (Bypassing DPI)");
            statusText.setTextColor(0xFF4CAF50);
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
            Toast.makeText(this, "Zapret started - Bypassing Telegram, YouTube, Discord", Toast.LENGTH_LONG).show();
        }
    }
    
    private void stopVpn() {
        Intent serviceIntent = new Intent(this, ZapretVpnService.class);
        serviceIntent.setAction(ZapretVpnService.ACTION_DISCONNECT);
        startService(serviceIntent);
        
        isVpnActive = false;
        statusText.setText("Status: Stopped");
        statusText.setTextColor(0xFFF44336);
        connectButton.setEnabled(true);
        disconnectButton.setEnabled(false);
        Toast.makeText(this, "Zapret stopped", Toast.LENGTH_SHORT).show();
    }
}
