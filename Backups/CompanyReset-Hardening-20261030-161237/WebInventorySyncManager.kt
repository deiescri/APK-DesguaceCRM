package com.desguace.crm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** Descarga en lotes las importaciones del ordenador, sin sobrescribir los catálogos originales de Android. */
object WebInventorySyncManager {
    private const val TAG = "WebInventorySync"
    private const val PAGE_SIZE = 500
    private const val PREFS = "desguace_crm"
    private val syncRunning = AtomicBoolean(false)

    fun sincronizar(
        context: Context,
        onProgress: ((Int, String, String) -> Unit)? = null
    ): Boolean {
        if (!syncRunning.compareAndSet(false, true)) {
            Log.i(TAG, "Ya hay una sincronización de inventario en curso; se evita duplicarla.")
            onProgress?.invoke(0, "inventario", "Ya hay otra sincronización de inventario en curso.")
            return false
        }
        return try {
            sincronizarInterno(context.applicationContext, onProgress)
        } finally {
            syncRunning.set(false)
        }
    }

    private fun sincronizarInterno(
        appContext: Context,
        onProgress: ((Int, String, String) -> Unit)?
    ): Boolean {
        val statusPair = consultarEstado(appContext) ?: run {
            Log.w(TAG, "No se pudo consultar el estado de inventario del servidor por USB ni Wi-Fi.")
            onProgress?.invoke(0, "inventario", "No se pudo contactar con el inventario del servidor.")
            return false
        }
        val (baseUrl, status) = statusPair
        val db = WebInventoryCacheDatabase(appContext)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        try {
        val vehicleMeta = status.optJSONObject("vehicles") ?: JSONObject()
        val stockMeta = status.optJSONObject("stock") ?: JSONObject()
        val version = status.optLong("version", 0L).coerceAtLeast(0L)
        val vehicleCount = vehicleMeta.optInt("count", 0).coerceAtLeast(0)
        val stockCount = stockMeta.optInt("count", 0).coerceAtLeast(0)
        val totalCount = vehicleCount + stockCount
        val vehicleEnd = when {
            vehicleCount == 0 -> 0
            stockCount == 0 -> 100
            totalCount == 0 -> 50
            else -> (vehicleCount * 100.0 / totalCount).toInt().coerceIn(1, 99)
        }
        val stockStart = vehicleEnd
        val stockEnd = if (stockCount > 0) 100 else stockStart
        var allSuccessful = true

        Log.i(TAG, "Inventario remoto: $vehicleCount coches; $stockCount piezas importadas desde el PC; versión $version.")
        onProgress?.invoke(0, "inventario", "Estado consultado: $vehicleCount coches y $stockCount piezas.")

        try {
            sincronizarEntidad(
                baseUrl = baseUrl,
                prefs = prefs,
                prefKey = "web_inv_yard",
                endpoint = "/api/mobile-sync/yard-vehicles",
                remoteCount = vehicleCount,
                untilVersionDefault = version,
                rangeStart = 0,
                rangeEnd = vehicleEnd,
                onProgress = onProgress,
                guardarPagina = { items -> db.upsertYardVehicles(items) }
            )
        } catch (e: Exception) {
            allSuccessful = false
            onProgress?.invoke(0, "coches", "Error descargando coches; se conserva el punto de reanudación.")
            Log.e(TAG, "No se pudo completar la sincronización de coches; se conservará el punto de reanudación.", e)
        }

        try {
            sincronizarEntidad(
                baseUrl = baseUrl,
                prefs = prefs,
                prefKey = "web_inv_stock",
                endpoint = "/api/mobile-sync/stock-imports",
                remoteCount = stockCount,
                untilVersionDefault = version,
                rangeStart = stockStart,
                rangeEnd = stockEnd,
                onProgress = onProgress,
                guardarPagina = { items -> db.upsertPcStock(items) }
            )
        } catch (e: Exception) {
            allSuccessful = false
            onProgress?.invoke(0, "piezas", "Error descargando piezas; se conserva el punto de reanudación.")
            Log.e(TAG, "No se pudo completar la sincronización de piezas importadas; se conservará el punto de reanudación.", e)
        }

        Log.i(TAG, "Caché local al terminar: ${db.contarVehiculos()} coches y ${db.contarPiezasImportadas()} piezas importadas del PC.")
        onProgress?.invoke(
            if (allSuccessful) 100 else 0,
            "inventario",
            if (allSuccessful) "Inventario procesado: ${db.contarVehiculos()} coches y ${db.contarPiezasImportadas()} piezas importadas."
            else "El inventario terminó con errores; se conservarán los cursores para reanudar."
        )
        return allSuccessful
        } finally {
            db.close()
        }
    }

    private fun consultarEstado(context: Context): Pair<String, JSONObject>? {
        for (host in ServerConfig.getCandidateHosts(context)) {
            val base = "http://$host"
            val result = getJson("$base/api/mobile-sync/status", 2500, 5000)
            if (result != null && result.optBoolean("success", true)) return base to result
        }
        return null
    }

    private fun sincronizarEntidad(
        baseUrl: String,
        prefs: android.content.SharedPreferences,
        prefKey: String,
        endpoint: String,
        remoteCount: Int,
        untilVersionDefault: Long,
        rangeStart: Int,
        rangeEnd: Int,
        onProgress: ((Int, String, String) -> Unit)?,
        guardarPagina: (JSONArray) -> Int
    ) {
        val isPending = prefs.getBoolean("${prefKey}_pending", false)
        val storedLastVersion = prefs.getLong("${prefKey}_last_version", -1L)
        val mode: String
        val sinceVersion: Long
        val untilVersion: Long
        var afterId: String

        if (isPending) {
            mode = prefs.getString("${prefKey}_mode", "full") ?: "full"
            sinceVersion = prefs.getLong("${prefKey}_since_version", -1L)
            untilVersion = prefs.getLong("${prefKey}_until_version", untilVersionDefault)
            afterId = prefs.getString("${prefKey}_after_id", "") ?: ""
            Log.i(TAG, "Reanudando $prefKey desde id '$afterId' hasta versión $untilVersion.")
        } else {
            mode = if (storedLastVersion < 0L) "full" else "delta"
            sinceVersion = storedLastVersion
            untilVersion = untilVersionDefault
            afterId = ""
            prefs.edit()
                .putBoolean("${prefKey}_pending", true)
                .putString("${prefKey}_mode", mode)
                .putLong("${prefKey}_since_version", sinceVersion)
                .putLong("${prefKey}_until_version", untilVersion)
                .putString("${prefKey}_after_id", "")
                .apply()
        }

        if (mode == "delta" && untilVersion <= sinceVersion) {
            prefs.edit()
                .putLong("${prefKey}_last_version", maxOf(storedLastVersion, untilVersion))
                .putBoolean("${prefKey}_pending", false)
                .remove("${prefKey}_after_id")
                .apply()
            Log.i(TAG, "$prefKey ya está actualizado en versión $untilVersion.")
            onProgress?.invoke(rangeEnd, if (prefKey == "web_inv_yard") "coches" else "piezas", "${if (prefKey == "web_inv_yard") "Coches" else "Piezas"} ya están actualizados.")
            return
        }

        if (mode == "full" && remoteCount == 0) {
            prefs.edit()
                .putLong("${prefKey}_last_version", untilVersion)
                .putBoolean("${prefKey}_pending", false)
                .remove("${prefKey}_after_id")
                .apply()
            Log.i(TAG, "$prefKey no tiene registros en el servidor.")
            onProgress?.invoke(rangeEnd, if (prefKey == "web_inv_yard") "coches" else "piezas", "${if (prefKey == "web_inv_yard") "Coches" else "Piezas"}: no hay registros que descargar.")
            return
        }

        var descargadosEstaEjecucion = 0
        var totalPorDescargar: Int? = null
        val etapa = if (prefKey == "web_inv_yard") "coches" else "piezas"
        val nombreEtapa = if (prefKey == "web_inv_yard") "coches" else "piezas importadas"
        while (true) {
            val query = buildString {
                append("?limit=").append(PAGE_SIZE)
                append("&untilVersion=").append(untilVersion)
                append("&afterId=").append(encode(afterId))
                if (mode == "delta") append("&sinceVersion=").append(sinceVersion)
            }
            val response = getJson("$baseUrl$endpoint$query", 5000, 60000)
                ?: throw IllegalStateException("No se recibió una respuesta válida de $endpoint")
            if (!response.optBoolean("success", true)) {
                throw IllegalStateException(response.optString("message", "La API rechazó la sincronización de $prefKey"))
            }
            val items = response.optJSONArray("items")
                ?: throw IllegalStateException("La API de $endpoint no devolvió items.")
            if (totalPorDescargar == null) {
                totalPorDescargar = response.optInt("total", remoteCount).coerceAtLeast(0)
            }
            val nextId = response.optString("nextCursor", afterId)
            val hasMore = response.optBoolean("hasMore", false)

            if (hasMore && (items.length() == 0 || nextId.isBlank() || nextId == afterId)) {
                throw IllegalStateException("La paginación de $endpoint no avanzó; se conserva el cursor para reintentar.")
            }

            val guardados = guardarPagina(items)
            descargadosEstaEjecucion += guardados
            val total = totalPorDescargar ?: remoteCount
            val porcentajeLocal = if (total <= 0) 100 else
                (descargadosEstaEjecucion * 100.0 / total).toInt().coerceIn(0, 100)
            val progreso = if (hasMore) {
                rangeStart + ((rangeEnd - rangeStart) * porcentajeLocal / 100)
            } else rangeEnd
            onProgress?.invoke(
                progreso,
                etapa,
                if (hasMore) "Sincronizando $nombreEtapa: $descargadosEstaEjecucion de $total registros del lote/versionado actual…"
                else "$nombreEtapa sincronizados en esta ejecución: $descargadosEstaEjecucion registros."
            )
            if (hasMore) {
                afterId = nextId
                prefs.edit().putString("${prefKey}_after_id", afterId).apply()
                Log.i(TAG, "$prefKey: lote guardado ($guardados registros); cursor='$afterId'.")
            } else {
                prefs.edit()
                    .putLong("${prefKey}_last_version", untilVersion)
                    .putBoolean("${prefKey}_pending", false)
                    .remove("${prefKey}_mode")
                    .remove("${prefKey}_since_version")
                    .remove("${prefKey}_until_version")
                    .remove("${prefKey}_after_id")
                    .apply()
                Log.i(TAG, "$prefKey sincronizado. Registros aplicados en esta ejecución: $descargadosEstaEjecucion; versión $untilVersion.")
                break
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun getJson(urlText: String, connectTimeoutMs: Int, readTimeoutMs: Int): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(urlText).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "GET $urlText devolvió HTTP ${connection.responseCode}.")
                null
            } else {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar $urlText: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
