package com.tvbox.mobileemu

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(15)
            )
        }
    }

    private var rootService: IRootDisplayService? = null
    private var virtualDisplayId: Int = -1
    private var currentSurface: Surface? = null

    private var isTabletMode = false
    private var isScrollMode = false
    private var isScreenFocused = false
    private var lastBackPressMs = 0L

    private var virtWidth = 540
    private var virtHeight = 960
    private var virtDpi = 210

    private var cursorX = 270f
    private var cursorY = 480f
    private var isDragging = false
    private var dragDownTime = 0L

    private lateinit var textureView: TextureView
    private lateinit var viewportContainer: FrameLayout
    private lateinit var cursorView: View
    private lateinit var hudBanner: TextView
    private lateinit var statusText: TextView
    private lateinit var appListLayout: LinearLayout
    private lateinit var modeBtn: Button
    private lateinit var scrollBtn: But

    private val handler = Handler(Looper.getMainLooper())

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) installPickedApk(uri)
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootService = IRootDisplayService.Stub.asInterface(service)
            statusText.text = "Root Ready"
            createOrResizeVirtualDisplay()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
            virtualDisplayId = -1
            statusText.text = "Root Disconnected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        bindRootDaemon()
        refreshInstalledApps()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#10141C"))
        }

        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 16, 24)
            setBackgroundColor(Color.parseColor("#171C28"))
        }
        val sidebarParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.30f)

        val title = TextView(this).apply {
            text = "MobileBox TV"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        statusText = TextView(this).apply {
            text = "Requesting Root..."
            textSize = 13f
            setTextColor(Color.parseColor("#6EE7B7"))
            setPadding(0, 4, 0, 14)
        }

        val installBtn = makeMenuButton("+ Install APK") {
            apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
        }

        modeBtn = makeMenuButton("Display: Phone (9:16)") {
            isTabletMode = !isTabletMode
            if (isTabletMode) {
                virtWidth = 1600
                virtHeight = 1000
                virtDpi = 240
                modeBtn.text = "Display: Tablet (16:10)"
            } else {
                virtWidth = 1080
                virtHeight = 1920
                virtDpi = 420
                modeBtn.text = "Display: Phone (9:16)"
            }
            cursorX = virtWidth / 2f
            cursorY = virtHeight / 2f
            updateViewportAspect()
            createOrResizeVirtualDisplay()
        }

        scrollBtn = makeMenuButton("Input: Cursor Mode") {
            toggleScrollMode()
        }

        val vBackBtn = makeMenuButton("Send Mobile Back") {
            if (virtualDisplayId >= 0) {
                Thread { rootService?.injectBackKey(virtualDisplayId) }.start()
            }
        }

        val focusScreenBtn = makeMenuButton("Control Virtual Screen >>") {
            enterScreenControl()
        }

        val appsHeader = TextView(this).apply {
            text = "Installed Apps"
            textSize = 14f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 16, 0, 8)
        }

        val scrollView = ScrollView(this).apply {
            isFocusable = false
        }
        appListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(appListLayout)

        sidebar.addView(title)
        sidebar.addView(statusText)
        sidebar.addView(installBtn)
        sidebar.addView(modeBtn)
        sidebar.addView(scrollBtn)
        sidebar.addView(vBackBtn)
        sidebar.addView(focusScreenBtn)
        sidebar.addView(appsHeader)
        sidebar.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val rightStage = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E14"))
            setPadding(24, 20, 24, 20)
        }
        val stageParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.70f)

        viewportContainer = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(4, Color.parseColor("#2E3748"))
                cornerRadius = 16f
            }
            setPadding(4, 4, 4, 4)
        }

        textureView = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    st.setDefaultBufferSize(virtWidth, virtHeight)
                    currentSurface?.release()
                    currentSurface = Surface(st)
                    createOrResizeVirtualDisplay()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    currentSurface?.release()
                    currentSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }

        cursorView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC38BDF8"))
                setStroke(3, Color.WHITE)
            }
            visibility = View.GONE
        }

        viewportContainer.addView(
            textureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        viewportContainer.addView(cursorView, FrameLayout.LayoutParams(28, 28))

        hudBanner = TextView(this).apply {
            text = "Select an app on the left to launch"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA111827"))
            setPadding(16, 8, 16, 8)
        }

        rightStage.addView(
            viewportContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        rightStage.addView(
            hudBanner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )

        root.addView(sidebar, sidebarParams)
        root.addView(rightStage, stageParams)
        setContentView(root)

        rightStage.post { updateViewportAspect() }
    }

    private fun updateViewportAspect() {
        val parent = viewportContainer.parent as? View ?: return
        val availW = (parent.width - parent.paddingLeft - parent.paddingRight).coerceAtLeast(300)
        val availH = (parent.height - parent.paddingTop - parent.paddingBottom).coerceAtLeast(300)

        val targetRatio = virtWidth.toFloat() / virtHeight.toFloat()
        var w = (availH * targetRatio).toInt()
        var h = availH
        if (w > availW) {
            w = availW
            h = (availW / targetRatio).toInt()
        }

        viewportContainer.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        textureView.surfaceTexture?.setDefaultBufferSize(virtWidth, virtHeight)
        updateCursorVisual()
    }

    private fun makeMenuButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#252D3D"))
                cornerRadius = 10f
            }
            background = bg
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
            setOnFocusChangeListener { _, hasFocus ->
                bg.setColor(if (hasFocus) Color.parseColor("#2563EB") else Color.parseColor("#252D3D"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun bindRootDaemon() {
        Shell.getShell { shell ->
            if (!shell.isRoot) {
                statusText.text = "Root Denied! Grant su permission."
                statusText.setTextColor(Color.parseColor("#F87171"))
                return@getShell
            }
            val intent = Intent(this, RootDisplayService::class.java)
            RootService.bind(intent, serviceConn)
        }
    }

    private fun createOrResizeVirtualDisplay() {
        val svc = rootService ?: return
        val surf = currentSurface ?: return
        Thread {
            val id = svc.createVirtualDisplay(surf, virtWidth, virtHeight, virtDpi)
            handler.post {
                virtualDisplayId = id
                if (id >= 0) {
                    statusText.text = "Root Ready • Display #$id"
                } else {
                    statusText.text = "Failed creating VirtualDisplay"
                }
            }
        }.start()
    }

    private fun refreshInstalledApps() {
        appListLayout.removeAllViews()
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launchIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter {
                it.packageName != packageName &&
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        if (apps.isEmpty()) {
            appListLayout.addView(TextView(this).apply {
                text = "No sideloaded apps found yet.\nUse '+ Install APK' above."
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 13f
            })
            return
        }

        for (app in apps) {
            val label = app.loadLabel(pm).toString()
            appListLayout.addView(makeMenuButton(label) {
                if (virtualDisplayId < 0) {
                    Toast.makeText(this, "Display not ready yet", Toast.LENGTH_SHORT).show()
                    return@makeMenuButton
                }
                Thread {
                    rootService?.launchAppOnDisplay(app.packageName, virtualDisplayId)
                }.start()
                enterScreenControl()
            })
        }
    }

    private fun installPickedApk(uri: Uri) {
        statusText.text = "Copying & installing APK..."
        Thread {
            try {
                val outFile = File(cacheDir, "sideload_temp.apk")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                val result = rootService?.installApkAndGrant(outFile.absolutePath)
                    ?: "ERR: Root service not connected"
                outFile.delete()
                handler.post {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                    statusText.text = if (result.startsWith("OK")) "APK Installed!" else result
                    refreshInstalledApps()
                }
            } catch (e: Exception) {
                handler.post {
                    statusText.text = "Install error: ${e.message}"
                }
            }
        }.start()
    }

    private fun enterScreenControl() {
        isScreenFocused = true
        viewportContainer.requestFocus()
        cursorView.visibility = View.VISIBLE
        viewportContainer.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(5, Color.parseColor("#38BDF8"))
            cornerRadius = 16f
        }
        updateHud()
        updateCursorVisual()
    }

    private fun exitScreenControl() {
        isScreenFocused = false
        cursorView.visibility = View.GONE
        viewportContainer.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke(4, Color.parseColor("#2E3748"))
            cornerRadius = 16f
        }
        modeBtn.requestFocus()
        hudBanner.text = "Menu Mode • Select an app or Control Virtual Screen"
    }

    private fun toggleScrollMode() {
        isScrollMode = !isScrollMode
        scrollBtn.text = if (isScrollMode) "Input: Scroll/Swipe Mode" else "Input: Cursor Mode"
        updateHud()
    }

    private fun updateHud() {
        hudBanner.text = if (isScrollMode) {
            "SCROLL MODE • UP/DOWN=Swipe • OK=Tap • MENU=Cursor • Double-BACK=Menu"
        } else {
            "CURSOR MODE • OK=Tap • Long-OK=Drag • MENU=Scroll • Left-Edge/2xBACK=Menu"
        }
    }

    private fun updateCursorVisual() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val px = (cursorX / virtWidth) * vw - (cursorView.width / 2f)
        val py = (cursorY / virtHeight) * vh - (cursorView.height / 2f)
        cursorView.translationX = px.coerceIn(0f, (vw - cursorView.width).coerceAtLeast(0f))
        cursorView.translationY = py.coerceIn(0f, (vh - cursorView.height).coerceAtLeast(0f))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isScreenFocused || virtualDisplayId < 0) {
            return super.dispatchKeyEvent(event)
        }

        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = if (event.repeatCount > 3) 48f else 24f

            when (code) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    toggleScrollMode()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastBackPressMs < 450) {
                        exitScreenControl()
                    } else {
                        lastBackPressMs = now
                        Thread { rootService?.injectBackKey(virtualDisplayId) }.start()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!isScrollMode && cursorX <= 4f) {
                        exitScreenControl()
                        return true
                    }
                    if (isScrollMode) {
                        performSwipe(virtWidth * 0.8f, virtHeight * 0.5f, virtWidth * 0.2f, virtHeight * 0.5f)
                    } else {
                        moveCursor(-step, 0f)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isScrollMode) {
                        performSwipe(virtWidth * 0.2f, virtHeight * 0.5f, virtWidth * 0.8f, virtHeight * 0.5f)
                    } else {
                        moveCursor(step, 0f)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isScrollMode) {
                        performSwipe(virtWidth * 0.5f, virtHeight * 0.35f, virtWidth * 0.5f, virtHeight * 0.75f)
                    } else {
                        moveCursor(0f, -step)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isScrollMode) {
                        performSwipe(virtWidth * 0.5f, virtHeight * 0.75f, virtWidth * 0.5f, virtHeight * 0.35f)
                    } else {
                        moveCursor(0f, step)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0 && !isDragging) {
                        isDragging = true
                        dragDownTime = SystemClock.uptimeMillis()
                        val x = cursorX
                        val y = cursorY
                        val dId = virtualDisplayId
                        Thread {
                            rootService?.injectTouch(MotionEvent.ACTION_DOWN, x, y, dId, dragDownTime, dragDownTime)
                        }.start()
                    }
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && isDragging) {
                isDragging = false
                val x = cursorX
                val y = cursorY
                val dId = virtualDisplayId
                val down = dragDownTime
                val up = SystemClock.uptimeMillis()
                Thread {
                    rootService?.injectTouch(MotionEvent.ACTION_UP, x, y, dId, down, up)
                }.start()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

   private fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, virtWidth.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, virtHeight.toFloat())
        updateCursorVisual()
        if (isDragging) {
            val x = cursorX
            val y = cursorY
            val dId = virtualDisplayId
            val down = dragDownTime
            val now = SystemClock.uptimeMillis()
            Thread {
                rootService?.injectTouch(MotionEvent.ACTION_MOVE, x, y, dId, down, now)
            }.start()
        }
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val dId = virtualDisplayId
        Thread {
            val down = SystemClock.uptimeMillis()
            rootService?.injectTouch(MotionEvent.ACTION_DOWN, x1, y1, dId, down, down)
            val steps = 8
            for (i in 1..steps) {
                Thread.sleep(16)
                val t = i.toFloat() / steps
                val x = x1 + (x2 - x1) * t
                val y = y1 + (y2 - y1) * t
                rootService?.injectTouch(
                    MotionEvent.ACTION_MOVE,
                    x,
                    y,
                    dId,
                    down,
                    SystemClock.uptimeMillis()
                )
            }
            rootService?.injectTouch(
                MotionEvent.ACTION_UP,
                x2,
                y2,
                dId,
                down,
                SystemClock.uptimeMillis()
            )
        }.start()
    }

    override fun onDestroy() {
        try {
            rootService?.releaseDisplay()
            unbindService(serviceConn)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
