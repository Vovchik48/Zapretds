package com.zapret.android

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        connectButton = findViewById(R.id.btn_connect)
        disconnectButton = findViewById(R.id.btn_disconnect)
        statusText = findViewById(R.id.status_text)
        infoText = findViewById(R.id.info_text)
        
        connectButton.setOnClickListener { startVpn() }
        disconnectButton.setOnClickListener { stopVpn() }
        
        disconnectButton.isEnabled = false
        
        infoText.text = "✓ Bypasses DPI for Telegram & YouTube\n✓ Changes TTL and packet signatures\n✓ No root required"
    }
    
    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 100)
        } else {
            onActivityResult(100, RESULT_OK, null)
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val serviceIntent = Intent(this, ZapretVpnService::class.java)
            serviceIntent.action = ZapretVpnService.ACTION_CONNECT
            startService(serviceIntent)
            
            statusText.text = "🟢 Active"
            statusText.setTextColor(0xFF4CAF50.toInt())
            connectButton.isEnabled = false
            disconnectButton.isEnabled = true
            Toast.makeText(this, "Zapret VPN started", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopVpn() {
        val serviceIntent = Intent(this, ZapretVpnService::class.java)
        serviceIntent.action = ZapretVpnService.ACTION_DISCONNECT
        startService(serviceIntent)
        
        statusText.text = "⚫ Stopped"
        statusText.setTextColor(0xFFF44336.toInt())
        connectButton.isEnabled = true
        disconnectButton.isEnabled = false
        Toast.makeText(this, "Zapret VPN stopped", Toast.LENGTH_SHORT).show()
    }
}
