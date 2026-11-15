package com.wangzan101.mika.fiona

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.remote.RelayGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Pair the phone with the owner's gateway without touching the UI.
 *
 * Drop `fiona-pair.json` ({"gateway":"https://host:8443","code":"..."}) into the app's external files
 * dir and launch the app: it pairs, turns remote access on, and replaces the file with the outcome.
 * Useful when a text field on a phone with an IME is the only thing standing between us and a working
 * link — and for provisioning a fresh phone later.
 */
object FionaProvision {

    private const val FILE = "fiona-pair.json"
    private const val RESULT = "fiona-pair.result.json"

    suspend fun run(app: MikaApp) = withContext(Dispatchers.IO) {
        val dir = app.getExternalFilesDir(null) ?: return@withContext
        val input = File(dir, FILE)
        if (!input.isFile) return@withContext
        val result = File(dir, RESULT)
        val body = runCatching { input.readText() }.getOrNull()
        val parsed = runCatching { Json.parseToJsonElement(body.orEmpty()) as JsonObject }.getOrNull()
        val action = (parsed?.get("action") as? JsonPrimitive)?.contentOrNull?.trim()
        if (action == "update") {
            val ok = runCatching { FionaUpdater.checkAndInstall(app, force = true) }.isSuccess
            result.writeText(if (ok) """{"ok":true,"checked":true}""" else """{"ok":false,"error":"update check failed"}""")
            input.delete()
            return@withContext
        }
        if (action == "allow-device") {
            val ok = runCatching { app.mcp.relaySettings.setDeviceAccess(true) }.isSuccess
            result.writeText(if (ok) """{"ok":true}""" else """{"ok":false,"error":"could not enable"}""")
            input.delete()
            return@withContext
        }
        if (action == "token") {
            // Hand the owner's tooling the access token this phone already accepts, without a UI round-trip.
            val token = runCatching { app.mcp.settings.token() }.getOrNull()
            result.writeText(
                if (token != null) """{"ok":true,"token":"$token"}"""
                else """{"ok":false,"error":"no token"}"""
            )
            input.delete()
            return@withContext
        }
        val gateway = (parsed?.get("gateway") as? JsonPrimitive)?.contentOrNull?.trim()
        val code = (parsed?.get("code") as? JsonPrimitive)?.contentOrNull?.trim()
        if (gateway.isNullOrEmpty() || code.isNullOrEmpty()) {
            result.writeText("""{"ok":false,"error":"gateway and code are required"}""")
            input.delete()
            return@withContext
        }
        val pairing = runCatching {
            RelayGateway(RelayGateway.parse(gateway), RelayGateway.http()).pair(code)
        }
        pairing.onSuccess { p ->
            runCatching {
                app.mcp.relaySettings.save(p)
                app.mcp.relaySettings.setEnabled(true)
            }
        }
        result.writeText(
            pairing.fold(
                onSuccess = { p -> """{"ok":true,"gateway":"${p.gateway}"}""" },
                onFailure = { e -> """{"ok":false,"error":"${(e.message ?: "pairing failed").replace("\"", "'")}"}""" },
            )
        )
        input.delete()
    }
}
