package com.wangzan101.mika.fiona

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.app.KeyguardManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.device.PhoneActionsPort
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * Fiona's hands and eyes on the device.
 *
 * Every call here is mechanical: it does what it is told and reports what happened. No planning, no
 * model, no heuristics beyond the minimum needed to turn a request into a gesture. The owner's agent
 * is the only thing that decides.
 */
class FionaActions(private val context: Context) : PhoneActionsPort {

    override val screenWidth: Int get() = context.resources.displayMetrics.widthPixels
    override val screenHeight: Int get() = context.resources.displayMetrics.heightPixels

    private fun service(): ScreenAccessibility? = ScreenAccessibility.instance.value

    override suspend fun screenshot(maxWidth: Int): ByteArray {
        val svc = service() ?: error("accessibility service is not connected")
        val shot = svc.takeMainScreenshot()
        val bitmap = when (shot) {
            is ScreenAccessibility.MainShot.Ok -> shot.bitmap
            ScreenAccessibility.MainShot.Secure -> error("the current window blocks screenshots")
            is ScreenAccessibility.MainShot.Failed -> error("screenshot failed (code ${shot.code})")
        }
        val scaled = scale(bitmap, maxWidth)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun scale(src: Bitmap, maxWidth: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxWidth) return src
        val ratio = maxWidth.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt().coerceAtLeast(1), (src.height * ratio).toInt().coerceAtLeast(1), true)
    }

    override suspend fun uiTree(): List<String> {
        val svc = service() ?: error("accessibility service is not connected")
        val root = svc.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>(128)
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || out.size >= 800) return
            val b = android.graphics.Rect()
            node.getBoundsInScreen(b)
            val text = node.text?.toString()?.replace('\n', ' ')?.take(120).orEmpty()
            val desc = node.contentDescription?.toString()?.replace('\n', ' ')?.take(80).orEmpty()
            val flags = buildString {
                if (node.isClickable) append("clickable ")
                if (node.isEditable) append("editable ")
                if (node.isScrollable) append("scrollable ")
                if (node.isCheckable) append(if (node.isChecked) "checked " else "unchecked ")
                if (node.isEnabled.not()) append("disabled ")
            }.trim()
            if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable) {
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
                out += "${"  ".repeat(depth.coerceAtMost(12))}$cls \"${text}\"${if (desc.isNotEmpty()) " [desc=$desc]" else ""} " +
                    "[${b.left},${b.top},${b.right},${b.bottom}] ${if (flags.isEmpty()) "" else flags}"
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    override suspend fun tap(x: Float, y: Float): Boolean = gesture(x, y, x, y, 40L)

    override suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean = gesture(x, y, x, y, durationMs)

    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(x1, y1, x2, y2, durationMs)

    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val svc = service() ?: return false
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        return svc.gesture(Display.DEFAULT_DISPLAY, path, durationMs)
    }

    override suspend fun setText(text: String): Boolean {
        val svc = service() ?: return false
        val node = focusedEditable(svc) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun focusedEditable(svc: ScreenAccessibility): AccessibilityNodeInfo? {
        val root = svc.rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
    }

    override suspend fun key(name: String): Boolean {
        val svc = service() ?: return false
        return when (name) {
            "back" -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "enter" -> {
                val node = focusedEditable(svc)
                val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                node?.performAction(action.id) ?: false
            }
            "delete" -> {
                // No key-injection path without root, so emulate a backspace on the focused field.
                val node = focusedEditable(svc) ?: return false
                val current = node.text?.toString().orEmpty()
                if (current.isEmpty()) return false
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current.dropLast(1))
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            else -> false
        }
    }

    override suspend fun launch(target: String): JsonObject {
        val pm = context.packageManager
        val pkg = if (target.contains('.')) {
            target
        } else {
            findPackageByLabel(target)
        }
        if (pkg == null) return buildJsonObject { put("ok", false); put("reason", "no installed app matches \"$target\"") }
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: return buildJsonObject { put("ok", false); put("package", pkg); put("reason", "app has no launcher activity") }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            kotlinx.coroutines.delay(600)
            buildJsonObject {
                put("ok", true); put("package", pkg)
                currentApp().forEach { (k, v) -> if (k != "ok") put(k, v) }
            }
        } catch (e: Exception) {
            buildJsonObject { put("ok", false); put("package", pkg); put("reason", e.message ?: "launch failed") }
        }
    }

    private fun findPackageByLabel(label: String): String? {
        val pm = context.packageManager
        val wanted = label.trim().lowercase()
        val launchables = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        }
        var best: Pair<String, Int>? = null
        for (info in launchables) {
            val name = info.loadLabel(pm).toString().lowercase()
            if (name.isEmpty()) continue
            val score = when {
                name == wanted -> 100
                name.startsWith(wanted) || wanted.startsWith(name) -> 80
                name.contains(wanted) || wanted.contains(name) -> 60
                else -> 0
            }
            if (score > 0 && (best == null || score > best!!.second)) best = info.activityInfo.packageName to score
        }
        return best?.first
    }

    override suspend fun shell(command: String): JsonObject {
        val app = context.applicationContext as? com.wangzan101.mika.MikaApp
            ?: return buildJsonObject { put("ok", false); put("error", "no application context") }
        val helper = app.phone.hub.shell.value
            ?: return buildJsonObject {
                put("ok", false)
                put("error", "shell helper is not running (start it once from a computer over USB)")
            }
        val bundle = runCatching { helper.runCommand(command) }.getOrNull()
            ?: return buildJsonObject { put("ok", false); put("error", "the helper refused the command") }
        val code = bundle.getInt("exitCode")
        return buildJsonObject {
            put("ok", code == 0)
            put("exit_code", code)
            put("output", bundle.getString("output").orEmpty())
        }
    }

    // ---- background screen (virtual display) ----

    private var bg: com.wangzan101.mika.cap.phone.display.AgentDisplay? = null
    private var bgPackage: String? = null

    private fun helper(): com.wangzan101.mika.shell.IMikaShell? =
        (context.applicationContext as? com.wangzan101.mika.MikaApp)?.phone?.hub?.shell?.value

    override suspend fun backgroundOpen(packageName: String): JsonObject {
        backgroundClose()
        val sh = helper() ?: return buildJsonObject { put("ok", false); put("error", "shell helper is not running") }
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val w = 720
        val h = 1280
        val display = runCatching {
            com.wangzan101.mika.cap.phone.display.AgentDisplay(sh, w, h, 320, dm)
        }.getOrNull() ?: return buildJsonObject { put("ok", false); put("error", "could not create the virtual screen") }
        val code = runCatching { sh.startApp(display.displayId, packageName) }.getOrElse { -1 }
        if (code != 0) {
            display.release()
            return buildJsonObject {
                put("ok", false); put("code", code)
                put("error", when (code) {
                    1 -> "the person is using this app on the main screen right now; not starting a second copy"
                    2 -> "no launchable activity"
                    else -> "could not start the app on the virtual screen"
                })
            }
        }
        bg = display
        bgPackage = packageName
        kotlinx.coroutines.delay(1200)
        return buildJsonObject {
            put("ok", true); put("package", packageName)
            put("width", display.width); put("height", display.height)
            put("display_id", display.displayId)
        }
    }

    override suspend fun backgroundScreen(maxWidth: Int): ByteArray? {
        val display = bg ?: return null
        val bmp = display.latestFrameBitmap() ?: return null
        val scaled = scale(bmp, maxWidth)
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            out.toByteArray()
        }
    }

    override suspend fun backgroundTap(x: Float, y: Float): Boolean {
        val display = bg ?: return false
        val sh = helper() ?: return false
        return runCatching { sh.injectTap(display.displayId, x.toInt(), y.toInt()) }.getOrDefault(false)
    }

    override suspend fun backgroundKey(key: String): Boolean {
        val display = bg ?: return false
        val sh = helper() ?: return false
        val code = when (key) {
            "back" -> android.view.KeyEvent.KEYCODE_BACK
            "home" -> android.view.KeyEvent.KEYCODE_HOME
            "enter" -> android.view.KeyEvent.KEYCODE_ENTER
            "delete" -> android.view.KeyEvent.KEYCODE_DEL
            else -> key.toIntOrNull() ?: return false
        }
        return runCatching { sh.injectKey(display.displayId, code) }.getOrDefault(false)
    }

    override suspend fun backgroundClose(): Boolean {
        val display = bg ?: return true
        bg = null
        bgPackage = null
        return runCatching { display.release(); true }.getOrDefault(false)
    }

    // ---- showing a result on the main screen ----

    override suspend fun show(url: String?, text: String?, caption: String?, imagePng: ByteArray?): JsonObject {
        if (!url.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(intent); buildJsonObject { put("ok", true); put("shown", "url") } }
                .getOrElse { buildJsonObject { put("ok", false); put("error", it.message ?: "could not open the link") } }
        }
        var imagePath: String? = null
        if (imagePng != null) {
            imagePath = File(context.cacheDir, "fiona-show.png").apply { writeBytes(imagePng) }.absolutePath
        }
        if (text.isNullOrBlank() && imagePath == null) {
            return buildJsonObject { put("ok", false); put("error", "give url, text or image_png_base64") }
        }
        val intent = FionaShowActivity.intent(context, caption, text, imagePath)
        return runCatching { context.startActivity(intent); buildJsonObject { put("ok", true); put("shown", if (imagePath != null) "image" else "text") } }
            .getOrElse { buildJsonObject { put("ok", false); put("error", it.message ?: "could not show the result") } }
    }

    override suspend fun bringToMain(): JsonObject {
        val display = bg ?: return buildJsonObject { put("ok", false); put("error", "nothing is open on the background screen") }
        val sh = helper() ?: return buildJsonObject { put("ok", false); put("error", "shell helper is not running") }
        val task = runCatching { sh.topTaskId(display.displayId) }.getOrDefault(-1)
        if (task < 0) return buildJsonObject { put("ok", false); put("error", "the background screen has no app on it") }
        val pkg = runCatching { sh.packageOfTask(task) }.getOrNull()
        val moved = runCatching { sh.moveTask(task, android.view.Display.DEFAULT_DISPLAY) }.getOrDefault(false)
        return buildJsonObject {
            put("ok", moved)
            put("task_id", task)
            pkg?.let { put("package", it) }
            if (!moved) put("error", "the system refused to move the task")
        }
    }

    override suspend fun sendBack(): JsonObject {
        val display = bg ?: return buildJsonObject { put("ok", false); put("error", "the background screen is not open any more") }
        val sh = helper() ?: return buildJsonObject { put("ok", false); put("error", "shell helper is not running") }
        val task = runCatching { sh.topTaskId(android.view.Display.DEFAULT_DISPLAY) }.getOrDefault(-1)
        if (task < 0) return buildJsonObject { put("ok", false); put("error", "nothing is in the foreground") }
        val pkg = runCatching { sh.packageOfTask(task) }.getOrNull()
        val expected = bgPackage
        if (expected != null && pkg != expected) {
            return buildJsonObject {
                put("ok", false); put("package", pkg ?: ""); put("expected", expected)
                put("error", "the person is looking at something else; leave it alone")
            }
        }
        val moved = runCatching { sh.moveTask(task, display.displayId) }.getOrDefault(false)
        return buildJsonObject {
            put("ok", moved); put("task_id", task); pkg?.let { put("package", it) }
            if (!moved) put("error", "the system refused to move the task")
        }
    }

    override suspend fun presence(): JsonObject {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val current = currentApp()
        return buildJsonObject {
            val interactive = power.isInteractive
            val locked = keyguard.isKeyguardLocked
            put("screen_on", interactive)
            put("locked", locked)
            put("ready", interactive && !locked)
            current["package"]?.let { put("foreground", it) }
            current["label"]?.let { put("label", it) }
        }
    }

    override suspend fun currentApp(): JsonObject {
        val svc = service()
        val pm = context.packageManager
        var pkg: String? = null
        var activity: String? = null
        if (svc != null) {
            runCatching {
                val active = svc.windowsOn(Display.DEFAULT_DISPLAY).firstOrNull { it.isActive }
                    ?: svc.windowsOn(Display.DEFAULT_DISPLAY).firstOrNull()
                val root = active?.root
                pkg = root?.packageName?.toString()
                activity = root?.className?.toString()
            }
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return buildJsonObject {
            put("ok", pkg != null)
            pkg?.let { put("package", it) }
            activity?.let { put("activity", it) }
            pkg?.let { p ->
                runCatching { put("label", pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()) }
            }
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("screen_on", power.isInteractive)
            put("locked", keyguard.isKeyguardLocked)
        }
    }
}
