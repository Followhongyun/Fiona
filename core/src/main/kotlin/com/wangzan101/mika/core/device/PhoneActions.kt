package com.wangzan101.mika.core.device

import kotlinx.serialization.json.JsonObject

/**
 * Fiona: raw phone actions for an external brain.
 *
 * Everything in here is mechanical. Nothing decides *what* to do — the owner's agent does that from
 * the other side of the connection. Keeping the port decision-free is the whole point of Fiona: the
 * phone is a body, not a second assistant.
 */
interface PhoneActionsPort {
    /** Current screen as PNG bytes, downscaled so the long edge is at most [maxWidth]. */
    suspend fun screenshot(maxWidth: Int): ByteArray

    /** Flattened accessibility tree of the active window: one line per node, `type text bounds [flags]`. */
    suspend fun uiTree(): List<String>

    /** Coordinates are in pixels of the current screen at [screenWidth] x [screenHeight]. */
    suspend fun tap(x: Float, y: Float): Boolean
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean
    suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean

    /** Replace the whole text of the focused editable field. Returns false when no field has focus. */
    suspend fun setText(text: String): Boolean

    /** back | home | recents | notifications | enter | delete */
    suspend fun key(name: String): Boolean

    /** Launch an app by package name or by its visible label. Reports what actually came to the front. */
    suspend fun launch(target: String): JsonObject

    /** Foreground package/activity plus screen size and lock state. */
    suspend fun currentApp(): JsonObject

    /**
     * Run a command as the Android shell user (uid 2000) — the same identity adb gets.
     * Returns exit code and combined output. This is the admin door: pm, settings, dumpsys,
     * input, screencap all work here, exactly as they do over adb.
     */
    suspend fun shell(command: String): JsonObject

    /**
     * Background screen: open [package] on a virtual display so it does not disturb the main screen.
     * Returns the display size to use for taps.
     */
    suspend fun backgroundOpen(packageName: String): JsonObject

    /** PNG of the background display, long edge at most [maxWidth]. Null when nothing is open. */
    suspend fun backgroundScreen(maxWidth: Int): ByteArray?

    /** Tap on the background display, in its own coordinates. */
    suspend fun backgroundTap(x: Float, y: Float): Boolean

    /** Send a key (back/home/enter/delete or a raw Android key code as text) to the background display. */
    suspend fun backgroundKey(key: String): Boolean

    suspend fun backgroundClose(): Boolean

    /**
     * Bring a result to the main screen on purpose: a page/URL, a block of text, or an image.
     * Only for when the person asked to see something; background work belongs on the virtual screen.
     */
    suspend fun show(url: String?, text: String?, caption: String?, imagePng: ByteArray?): JsonObject

    /**
     * Move the app that is running on the background screen onto the MAIN screen, exactly where it was.
     * This is how she shows the thing she was working on instead of describing it.
     */
    suspend fun bringToMain(): JsonObject

    /** Move it back to the background screen afterwards (only if the foreground app is still hers). */
    suspend fun sendBack(): JsonObject

    /** Whether the person is holding the phone right now (screen on, unlocked, foreground app). */
    suspend fun presence(): JsonObject

    val screenWidth: Int
    val screenHeight: Int
}
