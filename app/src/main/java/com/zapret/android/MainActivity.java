package com.zapret.android;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.cardview.widget.CardView;

public class MainActivity extends Activity {
    
    private static final int VPN_REQUEST_CODE = 100;
    private Button connectButton, disconnectButton;
    private TextView statusText, statusInfo;
    private ImageView statusIcon;
    private CardView statusCard;
    private Animation pulseAnimation;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        connectButton = findViewById(R.id.btn_connect);
        disconnectButton = findViewById(R.id.btn_disconnect);
        statusText = findViewById(R.id.status_text);
        statusInfo = findViewById(R.id.status_info);
        statusIcon = findViewById(R.id.status_icon);
        statusCard = findViewById(R.id.status_card);
        
        pulseAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
        
        connectButton.setOnClickListener(v -> startVpn());
        disconnectButton.setOnClickListener(v -> stopVpn());
        
        disconnectButton.setEnabled(false);
        updateUI(false);
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
            
            updateUI(true);
            animateStatusChange(true);
            
            Toast.makeText(this, "Zapret VPN Activated\nBypassing DPI blocks", Toast.LENGTH_LONG).show();
        }
    }
    
    private void stopVpn() {
        Intent serviceIntent = new Intent(this, ZapretVpnService.class);
        serviceIntent.setAction(ZapretVpnService.ACTION_DISCONNECT);
        startService(serviceIntent);
        
        updateUI(false);
        animateStatusChange(false);
        
        Toast.makeText(this, "Zapret VPN Stopped", Toast.LENGTH_SHORT).show();
    }
    
    private void updateUI(boolean isActive) {
        if (isActive) {
            statusText.setText("Status: Active");
            statusText.setTextColor(getColor(0xFF4CAF50));
            statusInfo.setText("Bypassing DPI blocks");
            statusIcon.setColorFilter(getColor(0xFF4CAF50));
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
        } else {
            statusText.setText("Status: Stopped");
            statusText.setTextColor(getColor(0xFFF44336));
            statusInfo.setText("Tap START to activate");
            statusIcon.setColorFilter(getColor(0xFF666666));
            connectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
        }
    }
    
    private void animateStatusChange(boolean isActive) {
        // Анимация цвета карточки
        int colorFrom = getColor(0xFFFFFFFF);
        int colorTo = isActive ? getColor(0xFFE8F5E9) : getColor(0xFFFFFFFF);
        
        ValueAnimator colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), colorFrom, colorTo);
        colorAnim.setDuration(300);
        colorAnim.addUpdateListener(animator -> {
            statusCard.setCardBackgroundColor((int) animator.getAnimatedValue());
        });
        colorAnim.start();
        
        // Анимация иконки
        statusIcon.startAnimation(pulseAnimation);
    }
    
    private int getColor(int color) {
        return color;
    }
}
