package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d("BootCompletedReceiver", "Received startup intent action: $action")
        
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" || 
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = context.getSharedPreferences("VideoWallpaperPrefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("video_auto_start", true) // Default to true as requested to auto-run on boot
            
            if (autoStart) {
                Log.d("BootCompletedReceiver", "Auto-start is enabled. Attempting to start MainActivity...")
                try {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e("BootCompletedReceiver", "Failed to launch main activity automatically on boot", e)
                }
            } else {
                Log.d("BootCompletedReceiver", "Auto-start feature is toggled off by user.")
            }
        }
    }
}
