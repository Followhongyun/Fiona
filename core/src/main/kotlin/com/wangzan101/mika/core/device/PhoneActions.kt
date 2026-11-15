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

    val screenWidth: Int
    val screenHeight: Int
}
