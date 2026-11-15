package com.tvbox.mobileemu

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
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
    private var isRootShell = false
    private var localVirtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var isProjectionRequested = false
    private var isPublicDisplay = false
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
    private var dragStartX = 270f
    private var dragStartY = 480f
    private var isDragging = false
    private var dragDownTime = 0L

    private lateinit var textureView: TextureView
    private lateinit var viewportContainer: FrameLayout
    private lateinit var cursorView: View
    private lateinit var hudBanner: TextView
    private lateinit var statusText: TextView
    private lateinit var appListLayout: LinearLayout
    private lateinit var modeBtn: Button
    private lateinit var scrollBtn: Button

    private val handler = Handler(Looper.getMainLooper())

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) installPickedApk(uri)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultData = result.data!!
            val resultCode = result.resultCode
            ProjectionService.onForegroundReady = {
                handler.post {
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjection?.stop()
                        mediaProjection = mpm.getMediaProjection(resultCode, resultData)
                        createOrResizeVirtualDisplay()
                    } catch (t: Throwable) {
                        showError("Projection err: ${t.message}")
                    }
                }
            }
            startForegroundService(Intent(this, ProjectionService::class.java))
        } else {
            showError("Allow screen prompt for Phone Box mode")
            createOrResizeVirtualDisplay()
        }
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                rootService = IRootDisplayService.Stub.asInterface(service)
                createOrResizeVirtualDisplay()
            } catch (t: Throwable) {
                showError("Service error: ${t.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
            handler.post { updateStatusReady() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            handler.post {
                showError("Error: ${throwable.javaClass.simpleName}: ${throwable.message}")
            }
        }

        buildUi()
        bindRootDaemon()
        refreshInstalledApps()
    }

    private fun showError(msg: String) {
        try {
            statusText.text = msg
            statusText.setTextColor(Color.parseColor("#F87171"))
            hudBanner.text = msg
        } catch (_: Throwable) {
        }
    }

    private fun updateStatusReady() {
        val mode = when {
            rootService != null -> "Root IPC"
            isRootShell -> "Root Shell"
            else -> "Waiting Root"
        }
        val dispType = if (isPublicDisplay) "Public #$virtualDisplayId" else "Private #$virtualDisplayId"
        statusText.text = if (virtualDisplayId >= 0) "$mode • $dispType" else mode
        statusText.setTextColor(
            if ((rootService != null || isRootShell) && isPublicDisplay) Color.parseColor("#6EE7B7")
            else Color.parseColor("#FBBF24")
        )
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
            try {
                apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
            } catch (t: Throwable) {
                showError("Picker error: ${t.message}")
            }
        }

        modeBtn = makeMenuButton("Display: Phone (9:16)") {
            isTabletMode = !isTabletMode
            if (isTabletMode) {
                virtWidth = 1024
                virtHeight = 640
                virtDpi = 160
                modeBtn.text = "Display: Tablet (16:10)"
            } else {
                virtWidth = 540
                virtHeight = 960
                virtDpi = 210
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
                sendBack(virtualDisplayId)
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
                    if (mediaProjection != null || rootService != null) {
                        createOrResizeVirtualDisplay()
                    } else if (isRootShell) {
                        requestPublicProjectionOnce()
                    }
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
        try {
            Shell.getShell { shell ->
                isRootShell = shell.isRoot
                if (!shell.isRoot) {
                    Thread {
                        val diag = Shell.cmd("id; which su; ls /system/xbin/su /system/bin/su 2>/dev/null").exec()
                        val msg = (diag.out + diag.err).joinToString(" | ")
                        handler.post { showError("No Root (su): $msg") }
                    }.start()
                    return@getShell
                }

                Thread {
                    Shell.cmd(
                        "cmd appops set $packageName PROJECT_MEDIA allow 2>/dev/null",
                        "settings put global force_resizable_activities 1 2>/dev/null",
                        "settings put global enable_freeform_support 1 2>/dev/null"
                    ).exec()
                    handler.post {
                        updateStatusReady()
                        if (currentSurface != null && mediaProjection == null && rootService == null) {
                            requestPublicProjectionOnce()
                        }
                    }
                }.start()

                try {
                    val intent = Intent(this, RootDisplayService::class.java)
                    RootService.bind(intent, serviceConn)
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            showError("Shell error: ${t.message}")
        }
    }

    private fun requestPublicProjectionOnce() {
        if (isProjectionRequested || mediaProjection != null) return
        isProjectionRequested = true
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (t: Throwable) {
            createOrResizeVirtualDisplay()
        }
    }

    private fun createOrResizeVirtualDisplay() {
        val surf = currentSurface ?: return
        val svc = rootService
        val proj = mediaProjection

        if (proj != null) {
            val existing = localVirtualDisplay
            if (existing != null) {
                try {
                    existing.resize(virtWidth, virtHeight, virtDpi)
                    existing.surface = surf
                    virtualDisplayId = existing.display.displayId
                    isPublicDisplay = (existing.display.flags and Display.FLAG_PRIVATE) == 0
                    updateStatusReady()
                    return
                } catch (_: Throwable) {
                    releaseLocalDisplay()
                }
            }

            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            try {
                val vd = proj.createVirtualDisplay(
                    "MobileBoxTV",
                    virtWidth,
                    virtHeight,
                    virtDpi,
                    flags,
                    surf,
                    null,
                    null
                )
                if (vd != null) {
                    localVirtualDisplay = vd
                    virtualDisplayId = vd.display.displayId
                    isPublicDisplay = (vd.display.flags and Display.FLAG_PRIVATE) == 0
                    updateStatusReady()
                    return
                }
            } catch (t: Throwable) {
                showError("Proj VD err: ${t.message}")
            }
        }

        Thread {
            var id = -1
            if (svc != null) {
                try {
                    id = svc.createVirtualDisplay(surf, virtWidth, virtHeight, virtDpi)
                } catch (_: Throwable) {
                    id = -1
                }
            }

            if (id >= 0) {
                handler.post {
                    releaseLocalDisplay()
                    virtualDisplayId = id
                    isPublicDisplay = true
                    updateStatusReady()
                }
            } else {
                handler.post {
                    val localId = createLocalVirtualDisplay(surf)
                    virtualDisplayId = localId
                    if (localId >= 0) {
                        updateStatusReady()
                    } else {
                        showError("Failed creating VirtualDisplay")
                    }
                }
            }
        }.start()
    }

    private fun releaseLocalDisplay() {
        try {
            localVirtualDisplay?.release()
        } catch (_: Throwable) {
        }
        localVirtualDisplay = null
    }

    private fun createLocalVirtualDisplay(surf: Surface): Int {
        releaseLocalDisplay()
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val flagSets = intArrayOf(
            (1 shl 0) or (1 shl 3) or (1 shl 6) or (1 shl 8),
            (1 shl 0) or (1 shl 3),
            (1 shl 3)
        )
        for (f in flagSets) {
            try {
                val vd = dm.createVirtualDisplay("MobileBoxTV", virtWidth, virtHeight, virtDpi, surf, f)
                if (vd != null) {
                    localVirtualDisplay = vd
                    isPublicDisplay = (vd.display.flags and Display.FLAG_PRIVATE) == 0
                    return vd.display.displayId
                }
            } catch (_: Throwable) {
            }
        }
        return -1
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
                val dId = virtualDisplayId
                Thread {
                    launchAppCompat(app.packageName, dId)
                }.start()
                enterScreenControl()
            })
        }
    }

    private fun launchAppCompat(pkg: String, dId: Int) {
        if (isRootShell) {
            Shell.cmd("am force-stop $pkg").exec()
            grantPermissionsViaShell(pkg)
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            val component = launchIntent?.component
            val res = if (component != null) {
                Shell.cmd(
                    "am start --display $dId --windowingMode 1 " +
                        "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                        "-f 0x10008000 -n ${component.flattenToString()} " +
                        "|| am start --display $dId -a android.intent.action.MAIN " +
                        "-c android.intent.category.LAUNCHER -f 0x10008000 -n ${component.flattenToString()}"
                ).exec()
            } else {
                Shell.cmd("monkey -p $pkg -c android.intent.category.LAUNCHER 1").exec()
            }
            val msg = (res.out + res.err).joinToString(" ").trim()
            if (msg.contains("Warning", ignoreCase = true) || msg.contains("Error", ignoreCase = true) || !res.isSuccess) {
                handler.post { hudBanner.text = "Launch: $msg" }
            }
            return
        }

        val svc = rootService
        if (svc != null) {
            try {
                svc.launchAppOnDisplay(pkg, dId)
                return
            } catch (_: Throwable) {
            }
        }
        handler.post { showError("Need Root to launch on Display #$dId") }
    }

    private fun grantPermissionsViaShell(pkg: String) {
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

    private fun installPickedApk(uri: Uri) {
        statusText.text = "Copying & installing APK..."
        Thread {
            try {
                val outFile = File(cacheDir, "sideload_temp.apk")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }

                val result = if (rootService != null) {
                    rootService!!.installApkAndGrant(outFile.absolutePath)
                } else if (isRootShell) {
                    val safePath = outFile.absolutePath.replace("\"", "\\\"")
                    val res = Shell.cmd("pm install -r -g \"$safePath\"").exec()
                    val out = (res.out + res.err).joinToString("\n").trim()
                    if (res.isSuccess) "OK: $out" else "ERR: $out"
                } else {
                    "ERR: Root shell not available"
                }

                outFile.delete()
                handler.post {
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                    if (result.startsWith("OK")) {
                        statusText.text = "APK Installed!"
                        statusText.setTextColor(Color.parseColor("#6EE7B7"))
                    } else {
                        showError(result)
                    }
                    refreshInstalledApps()
                }
            } catch (e: Throwable) {
                handler.post {
                    showError("Install error: ${e.message}")
                }
            }
        }.start()
    }

    private fun sendBack(dId: Int) {
        Thread {
            val svc = rootService
            if (svc != null) {
                try {
                    svc.injectBackKey(dId)
                    return@Thread
                } catch (_: Throwable) {
                }
            }
            if (isRootShell) {
                Shell.cmd("input -d $dId keyevent 4").exec()
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
            val step = if (event.repeatCount > 3) 32f else 16f

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
                        sendBack(virtualDisplayId)
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
                        dragStartX = cursorX
                        dragStartY = cursorY
                        val x = cursorX
                        val y = cursorY
                        val dId = virtualDisplayId
                        if (rootService != null) {
                            Thread {
                                try {
                                    rootService?.injectTouch(MotionEvent.ACTION_DOWN, x, y, dId, dragDownTime, dragDownTime)
                                } catch (_: Throwable) {
                                }
                            }.start()
                        }
                    }
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && isDragging) {
                isDragging = false
                val x = cursorX
                val y = cursorY
                val sx = dragStartX
                val sy = dragStartY
                val dId = virtualDisplayId
                val down = dragDownTime
                val up = SystemClock.uptimeMillis()
                Thread {
                    if (rootService != null) {
                        try {
                            rootService?.injectTouch(MotionEvent.ACTION_UP, x, y, dId, down, up)
                            return@Thread
                        } catch (_: Throwable) {
                        }
                    }
                    if (isRootShell) {
                        val moved = kotlin.math.hypot((x - sx).toDouble(), (y - sy).toDouble()) > 10.0
                        val dur = (up - down).coerceIn(150, 1500)
                        if (moved) {
                            Shell.cmd("input -d $dId swipe ${sx.toInt()} ${sy.toInt()} ${x.toInt()} ${y.toInt()} $dur").exec()
                        } else if (up - down >= 450) {
                            Shell.cmd("input -d $dId swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 700").exec()
                        } else {
                            Shell.cmd("input -d $dId tap ${x.toInt()} ${y.toInt()}").exec()
                        }
                    }
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
        if (isDragging && rootService != null) {
            val x = cursorX
            val y = cursorY
            val dId = virtualDisplayId
            val down = dragDownTime
            val now = SystemClock.uptimeMillis()
            Thread {
                try {
                    rootService?.injectTouch(MotionEvent.ACTION_MOVE, x, y, dId, down, now)
                } catch (_: Throwable) {
                }
            }.start()
        }
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val dId = virtualDisplayId
        Thread {
            if (rootService != null) {
                try {
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
                    return@Thread
                } catch (_: Throwable) {
                }
            }
            if (isRootShell) {
                Shell.cmd("input -d $dId swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} 220").exec()
            }
        }.start()
    }

    override fun onDestroy() {
        try {
            releaseLocalDisplay()
            mediaProjection?.stop()
            stopService(Intent(this, ProjectionService::class.java))
            rootService?.releaseDisplay()
            unbindService(serviceConn)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
