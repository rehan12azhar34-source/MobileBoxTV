package com.tvbox.mobileemu

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import org.lsposed.hiddenapibypass.HiddenApiBypass

class RootDisplayService : RootService() {

    private var virtualDisplay: VirtualDisplay? = null

    private fun ensureHiddenApiBypass() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        } catch (_: Throwable) {
        }
    }

    private class SystemPackageContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = "android"
        override fun getOpPackageName(): String = "android"
    }

    override fun onBind(intent: Intent): IBinder {
        ensureHiddenApiBypass()
        return object : IRootDisplayService.Stub() {

            override fun createVirtualDisplay(
                surface: Surface?,
                width: Int,
                height: Int,
                dpi: Int
            ): Int {
                releaseDisplay()
                if (surface == null) return -1

                var flags = (1 shl 0) or   // VIRTUAL_DISPLAY_FLAG_PUBLIC
                    (1 shl 3) or           // VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    (1 shl 6) or           // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                    (1 shl 8)              // VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    flags = flags or (1 shl 10) // TRUSTED (Android 11+)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or (1 shl 11) // OWN_DISPLAY_GROUP (Android 12+)
                }

                virtualDisplay = try {
                    val sysCtx = SystemPackageContext(this@RootDisplayService)
                    val dm = sysCtx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    dm.createVirtualDisplay("MobileBoxTV", width, height, dpi, surface, flags)
                } catch (_: Throwable) {
                    try {
                        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                        dm.createVirtualDisplay("MobileBoxTV", width, height, dpi, surface, flags)
                    } catch (_: Throwable) {
                        null
                    }
                }

                return try {
                    virtualDisplay?.display?.displayId ?: -1
                } catch (_: Throwable) {
                    -1
                }
            }

            override fun releaseDisplay() {
                try {
                    virtualDisplay?.release()
                } catch (_: Throwable) {
                }
                virtualDisplay = null
            }

            override fun launchAppOnDisplay(packageName: String, displayId: Int) {
                try {
                    grantCommonPermissions(packageName)
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    val component = launchIntent?.component
                    if (component != null) {
                        Shell.cmd(
                            "am start --display $displayId -a android.intent.action.MAIN " +
                                "-c android.intent.category.LAUNCHER " +
                                "-f 0x10200000 -n ${component.flattenToString()}"
                        ).exec()
                    } else {
                        Shell.cmd("monkey -p $packageName -c android.intent.category.LAUNCHER 1").exec()
                    }
                } catch (_: Throwable) {
                }
            }

            override fun injectTouch(
                action: Int,
                x: Float,
                y: Float,
                displayId: Int,
                downTime: Long,
                eventTime: Long
            ) {
                try {
                    val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    injectEventToDisplay(event, displayId)
                    event.recycle()
                } catch (_: Throwable) {
                }
            }

            override fun injectBackKey(displayId: Int) {
                try {
                    val now = android.os.SystemClock.uptimeMillis()
                    val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
                    val up = KeyEvent(now, now + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
                    injectEventToDisplay(down, displayId)
                    injectEventToDisplay(up, displayId)
                } catch (_: Throwable) {
                }
            }

            override fun installApkAndGrant(apkPath: String): String {
                return try {
                    val safePath = apkPath.replace("\"", "\\\"")
                    val res = Shell.cmd(
                        "pm install -r -g --bypass-low-target-sdk-block \"$safePath\" " +
                            "|| pm install -r -g \"$safePath\""
                    ).exec()
                    val out = (res.out + res.err).joinToString("\n").trim()
                    if (res.isSuccess) "OK: $out" else "ERR: $out"
                } catch (t: Throwable) {
                    "ERR: ${t.message}"
                }
            }
        }
    }

    private fun grantCommonPermissions(pkg: String) {
        val perms = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS"
        )
        val cmds = perms.map { "pm grant $pkg $it 2>/dev/null" }.toMutableList()
        cmds.add("appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null")
        Shell.cmd(*cmds.toTypedArray()).exec()
    }

    private fun injectEventToDisplay(event: InputEvent, displayId: Int) {
        try {
            val setDisplayId = InputEvent::class.java.getMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
            setDisplayId.invoke(event, displayId)

            val im = getSystemService(Context.INPUT_SERVICE) as InputManager
            val injectMethod = InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            injectMethod.invoke(im, event, 0)
        } catch (_: Throwable) {
            try {
                val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
                val img = imgClass.getMethod("getInstance").invoke(null)
                val injectMethod = imgClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                injectMethod.invoke(img, event, 0)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
