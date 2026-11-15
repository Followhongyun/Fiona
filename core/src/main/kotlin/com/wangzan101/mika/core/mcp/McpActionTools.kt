package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.device.PhoneActionsPort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Fiona's raw action surface: eyes and hands for an external brain.
 *
 * These tools do exactly what they say and nothing else — no planning, no model, no follow-up. The
 * caller decides. That is why they are separate from the mika_* task tools, which hand a goal to an
 * agent running on the phone.
 */
internal class McpActionTools(private val actions: PhoneActionsPort) {

    private fun noArgs() = buildJsonObject {
        put("type", "object"); putJsonObject("properties") {}; put("additionalProperties", false)
    }

    private fun fx() = buildJsonObject { put("type", "number"); put("description", "X in pixels (screen width ${actions.screenWidth}).") }
    private fun fy() = buildJsonObject { put("type", "number"); put("description", "Y in pixels (screen height ${actions.screenHeight}).") }

    val tools = listOf(
        McpTaskTools.Tool(SCREEN,
            "Screenshot of the phone's current screen as an image, downscaled. Use it to see what is on screen before acting.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("max_width") { put("type", "integer"); put("minimum", 240); put("maximum", 1440); put("description", "Long edge limit in pixels (default 720).") }
                }
                put("additionalProperties", false)
            }, true),
        McpTaskTools.Tool(UI,
            "Accessibility tree of the current screen as text lines: class, visible text, bounds and flags. Cheaper and more exact than reading a screenshot when the app exposes its views.",
            noArgs(), true),
        McpTaskTools.Tool(TAP,
            "Tap once at pixel coordinates. Use bounds from fiona_ui to place it.",
            buildJsonObject {
                put("type", "object"); putJsonObject("properties") { put("x", fx()); put("y", fy()) }
                putJsonArray("required") { add("x"); add("y") }; put("additionalProperties", false)
            }, false),
        McpTaskTools.Tool(SWIPE,
            "Drag one finger from (x1,y1) to (x2,y2) over duration_ms. Use it to scroll a list or drag an element.",
            buildJsonObject {
                put("type", "object"); putJsonObject("properties") {
                    put("x1", fx()); put("y1", fy()); put("x2", fx()); put("y2", fy())
                    putJsonObject("duration_ms") { put("type", "integer"); put("minimum", 50); put("maximum", 5000); put("description", "Default 300.") }
                }
                putJsonArray("required") { add("x1"); add("y1"); add("x2"); add("y2") }; put("additionalProperties", false)
            }, false),
        McpTaskTools.Tool(PRESS,
            "Press and hold at (x,y) for duration_ms. Use it for long-press menus.",
            buildJsonObject {
                put("type", "object"); putJsonObject("properties") {
                    put("x", fx()); put("y", fy())
                    putJsonObject("duration_ms") { put("type", "integer"); put("minimum", 200); put("maximum", 10000); put("description", "Default 700.") }
                }
                putJsonArray("required") { add("x"); add("y") }; put("additionalProperties", false)
            }, false),
        McpTaskTools.Tool(TYPE,
            "Replace the text of the focused input field with the given string. Focus the field first with a tap. Does not press Enter.",
            buildJsonObject {
                put("type", "object"); putJsonObject("properties") {
                    putJsonObject("text") { put("type", "string"); put("maxLength", 4000) }
                }
                putJsonArray("required") { add("text") }; put("additionalProperties", false)
            }, false),
        McpTaskTools.Tool(KEY,
            "Send a system key: back, home, recents, notifications, enter, delete.",
            buildJsonObject {
                put("type", "object"); putJsonObject("properties") {
                    putJsonObject("key") {
                        put("type", "string")
                        put("enum", buildJsonArray { listOf("back", "home", "recents", "notifications", "enter", "delete").forEach { add(it) } })
                    }
                }
                putJsonArray("required") { add("key") }; put("additionalProperties", false)
            }, false),
        McpTaskTools.Tool(LAUNCH,
            "Open an app by package name (com.tencent.mm) or by the label shown in the launcher (微信). Reports the foreground app after the attempt.",
            buildJsonObject {
                put("type", "object"); putJsonObject("properties") {
                    putJsonObject("target") { put("type", "string"); put("minLength", 1); put("maxLength", 200) }
                }
                putJsonArray("required") { add("target") }; put("additionalProperties", false)
            }, false),
        McpTaskTools.Tool(CURRENT,
            "Foreground package and activity, screen size in pixels, and whether the screen is on and locked.",
            noArgs(), true),
    )

    suspend fun call(name: String, args: JsonObject): JsonObject = when (name) {
        SCREEN -> {
            val maxWidth = (args["max_width"] as? JsonPrimitive)?.intOrNull?.coerceIn(240, 1440) ?: 720
            val png = actions.screenshot(maxWidth)
            buildJsonObject {
                put("ok", true)
                put("width", actions.screenWidth)
                put("height", actions.screenHeight)
                put("max_width", maxWidth)
                put("image_png_base64", Base64.getEncoder().encodeToString(png))
            }
        }
        UI -> buildJsonObject {
            val lines = actions.uiTree()
            put("ok", true); putJsonArray("lines") { lines.forEach { add(JsonPrimitive(it)) } }
        }
        TAP -> {
            val x = num(args, "x"); val y = num(args, "y")
            buildJsonObject { put("ok", actions.tap(x, y)) }
        }
        SWIPE -> {
            val d = (args["duration_ms"] as? JsonPrimitive)?.intOrNull?.coerceIn(50, 5000)?.toLong() ?: 300L
            buildJsonObject { put("ok", actions.swipe(num(args, "x1"), num(args, "y1"), num(args, "x2"), num(args, "y2"), d)) }
        }
        PRESS -> {
            val d = (args["duration_ms"] as? JsonPrimitive)?.intOrNull?.coerceIn(200, 10000)?.toLong() ?: 700L
            buildJsonObject { put("ok", actions.longPress(num(args, "x"), num(args, "y"), d)) }
        }
        TYPE -> {
            val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw McpTaskTools.ToolError("text must be a string")
            if (text.length > 4000) throw McpTaskTools.ToolError("text is too long")
            buildJsonObject { put("ok", actions.setText(text)) }
        }
        KEY -> {
            val key = (args["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw McpTaskTools.ToolError("key must be a string")
            if (key !in setOf("back", "home", "recents", "notifications", "enter", "delete")) throw McpTaskTools.ToolError("Unknown key: $key")
            buildJsonObject { put("ok", actions.key(key)) }
        }
        LAUNCH -> {
            val target = (args["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            if (target.isNullOrEmpty() || target.length > 200) throw McpTaskTools.ToolError("target must be a package name or app label")
            actions.launch(target)
        }
        CURRENT -> actions.currentApp()
        else -> throw McpTaskTools.ToolError("Unknown tool: $name")
    }

    private fun num(args: JsonObject, key: String): Float {
        val p = args[key] as? JsonPrimitive ?: throw McpTaskTools.ToolError("$key is required")
        return p.content.toFloatOrNull() ?: throw McpTaskTools.ToolError("$key must be a number")
    }

    companion object {
        const val SCREEN = "fiona_screen"
        const val UI = "fiona_ui"
        const val TAP = "fiona_tap"
        const val SWIPE = "fiona_swipe"
        const val PRESS = "fiona_press"
        const val TYPE = "fiona_type"
        const val KEY = "fiona_key"
        const val LAUNCH = "fiona_launch"
        const val CURRENT = "fiona_current"
    }
}
