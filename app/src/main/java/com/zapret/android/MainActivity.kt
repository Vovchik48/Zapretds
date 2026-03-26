package com.zapret.android

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var modeButton: Button
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var profilesList: ListView
    
    private val vpnRequestCode = 1001
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupListeners()
        loadProfiles()
        startStatsUpdater()
    }
    
    private fun initViews() {
        connectButton = findViewById(R.id.btn_connect)
        disconnectButton = findViewById(R.id.btn_disconnect)
        modeButton = findViewById(R.id.btn_mode)
        statusText = findViewById(R.id.status_text)
        statsText = findViewById(R.id.stats_text)
        profilesList = findViewById(R.id.profiles_list)
    }
    
    private fun setupListeners() {
        connectButton.setOnClickListener { startVpn() }
        disconnectButton.setOnClickListener { stopVpn() }
        modeButton.setOnClickListener { toggleMode() }
    }
    
    private fun loadProfiles() {
        val profileNames = Config.profiles.values.map { it.name }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, profileNames)
        profilesList.adapter = adapter
        profilesList.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        
        Config.profiles.entries.forEachIndexed { index, (key, _) ->
            if (key in Config.activeProfiles) {
                profilesList.setItemChecked(index, true)
            }
        }
        
        profilesList.setOnItemClickListener { _, _, position, _ ->
            val profileName = Config.profiles.entries.elementAt(position).key
            if (profilesList.isItemChecked(position)) {
                Config.activeProfiles += profileName
            } else {
                Config.activeProfiles -= profileName
            }
        }
    }
    
    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            onActivityResult(vpnRequestCode, RESULT_OK, null)
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == RESULT_OK) {
            val serviceIntent = Intent(this, ZapretVpnService::class.java)
            serviceIntent.action = ZapretVpnService.ACTION_CONNECT
            startService(serviceIntent)
            updateUI(true)
            Toast.makeText(this, "VPN started", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopVpn() {
        val serviceIntent = Intent(this, ZapretVpnService::class.java)
        serviceIntent.action = ZapretVpnService.ACTION_DISCONNECT
        startService(serviceIntent)
        updateUI(false)
        Toast.makeText(this, "VPN stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun toggleMode() {
        val serviceIntent = Intent(this, ZapretVpnService::class.java)
        serviceIntent.action = ZapretVpnService.ACTION_TOGGLE_MODE
        startService(serviceIntent)
        updateModeButton()
    }
    
    private fun updateUI(isConnected: Boolean) {
        connectButton.isEnabled = !isConnected
        disconnectButton.isEnabled = isConnected
        profilesList.isEnabled = !isConnected
        
        statusText.text = if (isConnected) "● Connected" else "○ Disconnected"
        statusText.setTextColor(if (isConnected) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        
        updateModeButton()
    }
    
    private fun updateModeButton() {
        val modeText = when (ZapretVpnService.currentMode) {
            Config.OperationMode.NORMAL -> "Normal"
            Config.OperationMode.GAME_FILTER -> "Game Filter"
            Config.OperationMode.STEALTH -> "Stealth"
        }
        modeButton.text = "Mode: $modeText"
    }
    
    private fun startStatsUpdater() {
        lifecycleScope.launch {
            while (true) {
                statsText.text = "📊 Packets: ${Config.packetsBypassed}\n📦 Bytes: ${Config.bytesProcessed / 1024} KB"
                delay(2000)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        val isConnected = connectButton.isEnabled.not()
        updateUI(isConnected)
    }
}
