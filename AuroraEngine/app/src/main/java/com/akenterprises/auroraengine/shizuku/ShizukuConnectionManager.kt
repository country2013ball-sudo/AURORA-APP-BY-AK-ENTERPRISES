package com.akenterprises.auroraengine.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.akenterprises.auroraengine.core.model.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Mandatory Shizuku Authorization & Connection Lifecycle Manager.
 *
 * Strictly enforces:
 * - No silent permission grants
 * - No privileged execution before user approval
 * - Immediate revocation on binder death or permission denial
 */
class ShizukuConnectionManager(private val context: Context) {

    companion object {
        const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
        const val REQUEST_CODE_SHIZUKU_PERMISSION = 4070
    }

    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private var onServiceDisconnectedCallback: (() -> Unit)? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _status.value = ShizukuStatus(
            isInstalled = isShizukuInstalled(),
            isRunning = false,
            isAuthorized = false,
            uid = -1,
            version = -1,
            statusMessage = "Shizuku service disconnected. Privileged operations stopped immediately."
        )
        onServiceDisconnectedCallback?.invoke()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE_SHIZUKU_PERMISSION) {
                refreshStatus()
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    onServiceDisconnectedCallback?.invoke()
                }
            }
        }

    fun registerListeners(onDisconnected: () -> Unit) {
        this.onServiceDisconnectedCallback = onDisconnected
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {
            // Safe guard if Shizuku provider has not initialized yet
        }
        refreshStatus()
    }

    fun unregisterListeners() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {
        }
    }

    fun isShizukuInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE_NAME, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun refreshStatus(): ShizukuStatus {
        val installed = isShizukuInstalled()
        if (!installed) {
            val s = ShizukuStatus(
                isInstalled = false,
                isRunning = false,
                isAuthorized = false,
                statusMessage = "Shizuku is not installed. Install Shizuku from Play Store or GitHub to enable ADB-level gaming optimizations."
            )
            _status.value = s
            return s
        }

        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

        if (!binderAlive) {
            val s = ShizukuStatus(
                isInstalled = true,
                isRunning = false,
                isAuthorized = false,
                statusMessage = "Shizuku is installed but not running. Start Shizuku via Wireless Debugging (Android 11+) or PC ADB."
            )
            _status.value = s
            return s
        }

        val granted = try {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Throwable) {
            false
        }

        val uid = try {
            if (granted) Shizuku.getUid() else -1
        } catch (_: Throwable) {
            -1
        }

        val ver = try {
            Shizuku.getVersion()
        } catch (_: Throwable) {
            -1
        }

        val privilegeLabel = when (uid) {
            0 -> "ROOT (UID 0)"
            2000 -> "ADB SHELL (UID 2000)"
            else -> "UID $uid"
        }

        val s = ShizukuStatus(
            isInstalled = true,
            isRunning = true,
            isAuthorized = granted,
            uid = uid,
            version = ver,
            statusMessage = if (granted) {
                "Authorized & Connected via Shizuku v$ver ($privilegeLabel)"
            } else {
                "Shizuku is running. Tap 'Request Shizuku Authorization' to approve access."
            }
        )
        _status.value = s
        return s
    }

    fun requestAuthorization(): Boolean {
        val current = refreshStatus()
        if (!current.isInstalled || !current.isRunning) {
            return false
        }
        if (current.isAuthorized) {
            return true
        }
        return try {
            if (!Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU_PERMISSION)
                true
            } else {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU_PERMISSION)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
