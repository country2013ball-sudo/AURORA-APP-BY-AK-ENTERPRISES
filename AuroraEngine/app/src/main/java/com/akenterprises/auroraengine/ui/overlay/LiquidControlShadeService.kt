package com.akenterprises.auroraengine.ui.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.akenterprises.auroraengine.AuroraApplication
import com.akenterprises.auroraengine.R
import com.akenterprises.auroraengine.core.prefs.AuroraPreferences

class LiquidControlShadeService : Service() {

    companion object {
        var isOverlayRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var shadeView: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, AuroraApplication.CHANNEL_OVERLAY_ID)
                .setSmallIcon(R.drawable.ic_stat_aurora)
                .setContentTitle("Aurora Liquid Control Centre")
                .setContentText("App-owned quick settings overlay is active.")
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1003, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1003, notification)
        }
        showShade()
        return START_NOT_STICKY
    }

    private fun showShade() {
        if (shadeView != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#EE171A22"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
        }
        val accentColor = AuroraPreferences(this).visualTheme.accentHex.toInt()

        val heading = row()
        heading.addView(label("LIQUID CONTROL CENTRE", 13f, Color.WHITE, true), weightParams())
        heading.addView(label("Close", 12f, accentColor, true).apply {
            setOnClickListener { stopSelf() }
        })
        panel.addView(heading)

        val tiles = row()
        tiles.addView(tile("Wi-Fi", accentColor) { openPanel(SettingsPanel.INTERNET) }, weightParams())
        tiles.addView(tile("Bluetooth", accentColor) { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }, weightParams())
        tiles.addView(tile("DND", accentColor) { openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }, weightParams())
        tiles.addView(tile("Refresh", accentColor) { openSettings(Settings.ACTION_DISPLAY_SETTINGS) }, weightParams())
        panel.addView(tiles)

        panel.addView(label("Brightness", 11f, Color.LTGRAY, false).apply {
            setPadding(0, dp(12), 0, 0)
        })
        val canWriteSettings = Settings.System.canWrite(this)
        if (!canWriteSettings) {
            panel.addView(label("Grant Modify System Settings", 10f, Color.parseColor("#FFD700"), true).apply {
                setOnClickListener {
                    stopSelf()
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:$packageName")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            })
        }
        panel.addView(slider(max = 255, initial = currentBrightness(), enabled = canWriteSettings) { value ->
            if (Settings.System.canWrite(this)) {
                val changed = Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
                if (!changed) {
                    Toast.makeText(this, "Android did not apply the brightness change.", Toast.LENGTH_SHORT).show()
                }
            }
        })

        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        panel.addView(label("Volume", 11f, Color.LTGRAY, false))
        panel.addView(slider(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        })

        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = glassBackground()
        }
        note.addView(label("Liquid Glass notifications", 11f, Color.WHITE, true))
        note.addView(label("System notifications remain in Android's protected notification shade.", 10f, Color.LTGRAY, false))
        panel.addView(note)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        shadeView = panel
        try {
            windowManager?.addView(panel, params)
            isOverlayRunning = true
        } catch (error: WindowManager.BadTokenException) {
            shadeView = null
            Toast.makeText(this, "Android rejected the Liquid Control Centre overlay.", Toast.LENGTH_SHORT).show()
            stopSelf()
        } catch (error: SecurityException) {
            shadeView = null
            Toast.makeText(this, "Overlay permission is required for Liquid Control Centre.", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun tile(text: String, accentColor: Int, onClick: () -> Unit) = label(text, 11f, accentColor, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(12), dp(4), dp(12))
        background = glassBackground(accentColor)
        setOnClickListener { onClick() }
    }

    private fun slider(max: Int, initial: Int, enabled: Boolean = true, onChanged: (Int) -> Unit): SeekBar =
        SeekBar(this).apply {
            this.max = max.coerceAtLeast(1)
            progress = initial.coerceIn(0, this.max)
            isEnabled = enabled
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) onChanged(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

    private fun currentBrightness(): Int = if (Settings.System.canWrite(this)) {
        Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128
        )
    } else {
        128
    }

    private fun openPanel(panel: SettingsPanel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && panel == SettingsPanel.INTERNET) {
            openSettings(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        } else {
            openSettings(Settings.ACTION_WIFI_SETTINGS)
        }
    }

    private fun openSettings(action: String) {
        stopSelf()
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(8))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun glassBackground(accentColor: Int = Color.WHITE) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.parseColor("#33FFFFFF"))
        setStroke(dp(1), accentColor and 0x66FFFFFF)
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(6)
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        shadeView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: IllegalArgumentException) {
            }
        }
        shadeView = null
        isOverlayRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private enum class SettingsPanel { INTERNET }
}
