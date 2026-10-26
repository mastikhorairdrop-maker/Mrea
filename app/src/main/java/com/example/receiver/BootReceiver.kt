package com.example.receiver
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ZoyaForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("BootReceiver", "Received boot broadcast: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = context.getSharedPreferences("ZoyaPrefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", true)
            if (!autoStart) {
                Log.d("BootReceiver", "Auto-start on boot is disabled by user.")
                return
            }

            // Check if microphone permission is granted before starting foreground service
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val serviceIntent = Intent(context, ZoyaForegroundService::class.java).apply {
                        putExtra("from_boot", true)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("BootReceiver", "Maya foreground service successfully started after boot.")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start Maya service on boot", e)
                }
            } else {
                Log.w("BootReceiver", "Cannot start Maya on boot: RECORD_AUDIO permission missing.")
            }
        }
    }
}
