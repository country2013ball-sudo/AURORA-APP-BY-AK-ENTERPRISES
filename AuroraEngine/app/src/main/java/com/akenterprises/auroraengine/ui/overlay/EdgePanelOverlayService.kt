package com.akenterprises.auroraengine.ui.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.akenterprises.auroraengine.AuroraApplication
import com.akenterprises.auroraengine.MainActivity
import com.akenterprises.auroraengine.R

/**
 * Lightweight Collapsible Edge-Panel Handle Service.
 * Runs ONLY when the user explicitly grants SYSTEM_ALERT_WINDOW permission and toggles it on.
 * Tapping the edge handle opens the full Aurora Engine Edge Panel interface.
 */
class EdgePanelOverlayService : Service() {

    companion object {
        var isOverlayRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var handleView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1002, notification)
        }

        if (handleView == null) {
            attachFloatingEdgeHandle()
        }
        isOverlayRunning = true
        return START_STICKY
    }

    private fun attachFloatingEdgeHandle() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
            y = -120
        }

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 36, 14, 36)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(0f, 0f, 28f, 28f, 28f, 28f, 0f, 0f)
                setColor(Color.parseColor("#E612141C"))
                setStroke(2, Color.parseColor("#FFD4AF37"))
            }
            val label = TextView(context).apply {
                text = "AE"
                textSize = 11f
                setTextColor(Color.parseColor("#FFD4AF37"))
            }
            addView(label)
            setOnClickListener {
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(launch)
            }
        }

        handleView = pill
        windowManager?.addView(pill, params)
    }

    override fun onDestroy() {
        isOverlayRunning = false
        handleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Throwable) {}
        }
        handleView = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AuroraApplication.CHANNEL_OVERLAY_ID)
            .setSmallIcon(R.drawable.ic_stat_aurora)
            .setContentTitle("Aurora Engine Edge Handle Active")
            .setContentText("Tap the left-edge gold handle anytime to open the gaming panel.")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
