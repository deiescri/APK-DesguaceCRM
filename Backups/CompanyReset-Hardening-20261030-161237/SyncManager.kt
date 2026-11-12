package com.desguace.crm

import java.util.UUID
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.net.URLEncoder

object SyncManager {
    private const val TAG = "SyncManager"

    private fun obtenerUrlsApi(context: Context): List<String> {
        return ServerConfig.getCandidateHosts(context)
            .map { "http://$it/api/llamadas" }
            .distinct()
    }

    private fun llamadaVaciaPendiente(llamada: JSONObject): Boolean {
        val estado = llamada.optString("estado", "").trim()
        val tipoLlamada = llamada.optString("tipoLlamada", "").trim()
        val telefono = llamada.optString("telefono", "").trim()

        // Conservar las llamadas perdidas que tengan número,
        // aunque todavía no tengan nombre ni pieza.
        if (
            telefono.isNotBlank() &&
            (
                    tipoLlamada.equals("Perdida", ignoreCase = true) ||
                            tipoLlamada.equals("Perdidas", ignoreCase = true)
                    )
        ) {
            return false
        }

        if (
            estado.isNotBlank() &&
            !estado.equals("Pendiente", ignoreCase = true)
        ) {
            return false
        }

        val camposInformativos = listOf(
            "nombre",
            "pieza",
            "descripcion",
            "lado",
            "oem",
            "OEM",
            "marca",
            "modelo",
            "motor",
            "anio",
            "año",
            "apuntes",
            "notas",
            "precio",
            "precioVenta",
            "fechaVenta",
            "stockId",
            "codigoStock",
            "cliente",
            "referencia",
            "referencias",
            "tipoMotor",
            "marcaCoche",
            "modeloCoche",
            "descripcionPieza"
        )

        return camposInformativos.none { campo ->
            val valor = llamada.optString(campo, "").trim()

            valor.isNotEmpty() &&
                    !valor.equals("null", ignoreCase = true) &&
                    !valor.equals("undefined", ignoreCase = true) &&
                    valor != "0"
        }
    }

    private fun limpiarLlamadasVaciasLocales(context: Context) {
        val prefs = context.getSharedPreferences(
            "desguace_crm",
            Context.MODE_PRIVATE
        )

        // No limpiar mientras alguien está rellenando una ficha.
        if (prefs.getBoolean("call_form_abierto", false)) {
            return
        }

        val llamadaEnCurso = prefs.getBoolean("call_pending", false)
        val idEnCurso = prefs.getString("call_record_id", "").orEmpty()

        val llamadas = JSONArray(
            prefs.getString("llamadas", "[]") ?: "[]"
        )

        val idsPendientes = JSONArray(
            prefs.getString("llamadas_eliminadas_pendientes", "[]")
                ?: "[]"
        )

        val ids = linkedSetOf<String>()

        for (i in 0 until idsPendientes.length()) {
            val id = idsPendientes.optString(i).trim()
            if (id.isNotEmpty()) ids.add(id)
        }

        var cambios = false

        for (i in llamadas.length() - 1 downTo 0) {
            val llamada = llamadas.optJSONObject(i) ?: continue

            val callId = llamada.optString("callId").trim()
            val idLlamada = llamada.optString("idLlamada").trim()

            // Nunca borrar la llamada telefónica que aún está en curso.
            if (
                llamadaEnCurso &&
                (callId == idEnCurso || idLlamada == idEnCurso)
            ) {
                continue
            }

            if (!llamadaVaciaPendiente(llamada)) {
                continue
            }

            val idServidor = llamada.optString("serverId").trim()
                .ifBlank { callId }
                .ifBlank { idLlamada }

            if (idServidor.isNotBlank()) {
                ids.add(idServidor)
            }

            llamadas.remove(i)
            cambios = true

            Log.i(
                TAG,
                "Eliminada llamada vacía en el móvil: $idServidor"
            )
        }

        if (cambios || ids.size != idsPendientes.length()) {
            val nuevaCola = JSONArray()
            ids.forEach { nuevaCola.put(it) }

            prefs.edit()
                .putString("llamadas", llamadas.toString())
                .putString(
                    "llamadas_eliminadas_pendientes",
                    nuevaCola.toString()
                )
                .apply()
        }
    }

    private suspend fun procesarEliminacionesPendientes(
        context: Context,
        apiUrls: List<String>
    ) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(
            "desguace_crm",
            Context.MODE_PRIVATE
        )

        val pendientes = JSONArray(
            prefs.getString("llamadas_eliminadas_pendientes", "[]")
                ?: "[]"
        )

        if (pendientes.length() == 0) return@withContext

        val conservar = JSONArray()

        for (i in 0 until pendientes.length()) {
            val id = pendientes.optString(i).trim()

            if (id.isBlank()) continue

            var eliminado = false
            var huboError = false

            for (apiUrl in apiUrls) {
                var conexion: HttpURLConnection? = null

                try {
                    val idCodificado = URLEncoder.encode(id, "UTF-8")
                    val url = URL(
                        "${apiUrl.trimEnd('/')}/$idCodificado"
                    )

                    conexion = url.openConnection() as HttpURLConnection
                    conexion.requestMethod = "DELETE"
                    conexion.connectTimeout = 5000
                    conexion.readTimeout = 5000

                    val codigo = conexion.responseCode

                    if (codigo in 200..299) {
                        eliminado = true
                        break
                    }

                    huboError = true

                    Log.w(
                        TAG,
                        "No se pudo confirmar el borrado de $id. HTTP $codigo"
                    )
                } catch (e: Exception) {
                    huboError = true
                    Log.w(TAG, "No se pudo eliminar $id del servidor", e)
                } finally {
                    conexion?.disconnect()
                }
            }

            // Si todos respondieron correctamente pero no encontraron el
            // registro, también podemos quitarlo de la cola.

            if (eliminado) {
                Log.i(TAG, "Eliminación sincronizada: $id")
            } else {
                conservar.put(id)
            }
        }

        prefs.edit()
            .putString(
                "llamadas_eliminadas_pendientes",
                conservar.toString()
            )
            .apply()
    }

    // CRM_COMPANY_GENERATION_ANDROID_V1
    // Se ejecuta antes de subir/borrar llamadas: evita mezclar datos de dos empresas.
    private suspend fun comprobarCambioEmpresa(context: Context, apiUrls: List<String>): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("desguace_crm", Context.MODE_PRIVATE)
        for (callsUrl in apiUrls) {
            var connection: HttpURLConnection? = null
            try {
                val baseUrl = callsUrl.removeSuffix("/api/llamadas")
                connection = URL("$baseUrl/api/company/identity").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                val code = connection.responseCode
                if (code !in 200..299) continue
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val identity = JSONObject(body)
                if (!identity.optBoolean("success", false)) continue
                val remoteGeneration = identity.optString("generationId").trim()
                if (remoteGeneration.isBlank()) continue

                val localGeneration = prefs.getString("crm_company_generation_id", null)
                if (localGeneration.isNullOrBlank()) {
                    // Primera adopciÃ³n: conservar los datos actuales de instalaciones existentes.
                    if (!prefs.edit().putString("crm_company_generation_id", remoteGeneration).commit()) {
                        throw IllegalStateException("No se pudo guardar la identidad inicial de empresa.")
                    }
                    Log.i(TAG, "Identidad de empresa inicial registrada; se conservan las llamadas existentes.")
                    return@withContext true
                }

                if (localGeneration != remoteGeneration) {
                    if (prefs.getBoolean("call_pending", false) || prefs.getBoolean("call_form_abierto", false)) {
                        Log.w(TAG, "Cambio de empresa detectado, pero hay una llamada o ficha activa. Se aplaza la limpieza para no interrumpirla.")
                        return@withContext false
                    }

                    // Solo se limpia la cachÃ© importada desde el PC, nunca los catÃ¡logos nativos del mÃ³vil.
                    WebInventoryCacheDatabase(context).use { cacheDb ->
                        cacheDb.limpiarInventarioImportado()
                    }

                    val editor = prefs.edit()
                        .putString("llamadas", "[]")
                        .putString("llamadas_eliminadas_pendientes", "[]")
                    for (prefix in listOf("web_inv_yard", "web_inv_stock")) {
                        for (suffix in listOf("pending", "mode", "since_version", "until_version", "after_id", "last_version")) {
                            editor.remove("${prefix}_${suffix}")
                        }
                    }
                    if (!editor.putString("crm_company_generation_id", remoteGeneration).commit()) {
                        throw IllegalStateException("No se pudo guardar el cambio de empresa en Android.")
                    }
                    Log.w(TAG, "Cambio de empresa confirmado: llamadas antiguas y cachÃ©s de importaciones del PC limpiadas.")
                }
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo verificar la identidad de empresa desde $callsUrl.", e)
            } finally {
                connection?.disconnect()
            }
        }
        Log.e(TAG, "No se pudo verificar la identidad de empresa por USB ni Wi-Fi. Se bloquea la sincronizaciÃ³n para evitar mezclar datos.")
        false
    }
    suspend fun subirLlamadasAlServidor(context: Context, syncId: String? = null) = withContext(Dispatchers.IO) {
        var syncSuccess = true
        reportarProgresoSincronizacion(context, syncId, 2, "iniciando", "Iniciando sincronización…")
        try {
            val apiUrls = obtenerUrlsApi(context)

            Log.d(TAG, "Direcciones API disponibles: $apiUrls")
            if (!comprobarCambioEmpresa(context, apiUrls)) {
                syncSuccess = false
                reportarProgresoSincronizacion(context, syncId, 0, "empresa", "No se pudo verificar el cambio de empresa o hay una ficha/llamada activa. No se han mezclado datos.", done = true, success = false)
                return@withContext
            }

            // Procesar primero los borrados que quedaron pendientes.
            procesarEliminacionesPendientes(context, apiUrls)

// Descargar los registros actuales.
            descargarLlamadasDelServidor(context, apiUrls)

// Eliminar llamadas vacías tanto del móvil como del servidor.
            limpiarLlamadasVaciasLocales(context)
            procesarEliminacionesPendientes(context, apiUrls)
            val prefs = context.getSharedPreferences("desguace_crm", Context.MODE_PRIVATE)
            val llamadasArray = JSONArray(prefs.getString("llamadas", "[]") ?: "[]")
            var enviadas = 0
            var huboCambios = false
            for (i in 0 until llamadasArray.length()) {
                val llamada = llamadasArray.optJSONObject(i) ?: continue

                if (llamada.optBoolean("sincronizado", false)) continue

                var stableId = textoSeguro(llamada, "serverId")
                    .ifBlank { textoSeguro(llamada, "callId") }
                    .ifBlank { textoSeguro(llamada, "idLlamada") }

                if (stableId.isBlank()) {
                    stableId = UUID.randomUUID().toString()
                    llamada.put("idLlamada", stableId)
                }

                if (textoSeguro(llamada, "serverId").isBlank()) {
                    llamada.put("serverId", stableId)
                    huboCambios = true
                }

                llamadasArray.put(i, llamada)

                if (enviarAlServidor(apiUrls, llamada)) {
                    llamada.put("serverId", stableId)
                    llamada.put("sincronizado", true)
                    enviadas++
                    huboCambios = true
                }
            }
            if (huboCambios) prefs.edit().putString("llamadas", llamadasArray.toString()).apply()
            Log.d(
                TAG,
                "Sincronización de llamadas finalizada. Nuevas enviadas: $enviadas"
            )

            descargarLlamadasDelServidor(context, apiUrls)

            // Eliminar posibles llamadas vacías que hayan llegado del servidor.
            limpiarLlamadasVaciasLocales(context)
            procesarEliminacionesPendientes(context, apiUrls)

        } catch (e: Exception) {
            syncSuccess = false
            Log.e(TAG, "Error sincronizando llamadas", e)
        }
        reportarProgresoSincronizacion(context, syncId, 25, "llamadas", "Sincronización de llamadas procesada.")

        reportarProgresoSincronizacion(context, syncId, 30, "catalogos", "Comprobando catálogos técnicos…")
        try {
            sincronizarCatalogos(context)
            reportarProgresoSincronizacion(context, syncId, 40, "catalogos", "Catálogos técnicos comprobados.")
        } catch (e: Exception) {
            syncSuccess = false
            Log.e(TAG, "Error sincronizando catálogos", e)
        }

        try {
            val inventarioOk = WebInventorySyncManager.sincronizar(context) { percent, stage, message ->
                val totalPercent = (40 + (percent.coerceIn(0, 100) * 0.58).toInt()).coerceIn(40, 98)
                reportarProgresoSincronizacion(context, syncId, totalPercent, stage, message)
            }
            if (!inventarioOk) syncSuccess = false
        } catch (e: Exception) {
            syncSuccess = false
            Log.e(TAG, "Error sincronizando inventario importado desde el PC", e)
        }

        if (syncSuccess) {
            reportarProgresoSincronizacion(context, syncId, 100, "completado", "Sincronización terminada correctamente.", done = true, success = true)
        } else {
            reportarProgresoSincronizacion(context, syncId, 0, "error", "La sincronización terminó con errores. Revisa los registros de Android.", done = true, success = false)
        }
    }

    private fun reportarProgresoSincronizacion(
        context: Context,
        syncId: String?,
        percent: Int,
        stage: String,
        message: String,
        done: Boolean = false,
        success: Boolean = true
    ) {
        if (syncId.isNullOrBlank()) return
        val payload = JSONObject().apply {
            put("syncId", syncId)
            put("percent", percent.coerceIn(0, 100))
            put("stage", stage)
            put("message", message)
            put("done", done)
            put("success", success)
            put("updatedAt", System.currentTimeMillis())
        }.toString().toByteArray(Charsets.UTF_8)

        for (host in ServerConfig.getCandidateHosts(context).distinct()) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("http://$host/api/mobile-sync/progress").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.connectTimeout = 1800
                connection.readTimeout = 2200
                connection.doOutput = true
                connection.outputStream.use { it.write(payload) }
                if (connection.responseCode in 200..299) return
            } catch (e: Exception) {
                Log.d(TAG, "No se pudo publicar progreso por $host: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }


    private fun descargarLlamadasDelServidor(
        context: Context,
        apiUrls: List<String>
    ) {
        for (apiUrl in apiUrls) {
            Log.d(TAG, "Intentando descargar llamadas desde: $apiUrl")

            if (descargarLlamadasDesdeUrl(context, apiUrl)) {
                Log.i(TAG, "Descarga realizada desde: $apiUrl")
                return
            }
        }

        Log.e(TAG, "No se pudieron descargar llamadas por USB ni Wi-Fi.")
    }

    private fun descargarLlamadasDesdeUrl(
        context: Context,
        apiUrl: String
    ): Boolean {
        var conn: HttpURLConnection? = null

        try {
            conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 15000

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "Error descargando llamadas. HTTP $code desde $apiUrl")
                return false
            }

            val respuesta = conn.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }

            val remotas = JSONArray(respuesta)
            val prefs = context.getSharedPreferences(
                "desguace_crm",
                Context.MODE_PRIVATE
            )
            val locales = JSONArray(prefs.getString("llamadas", "[]") ?: "[]")
            val pendientesBorrado = JSONArray(
                prefs.getString("llamadas_eliminadas_pendientes", "[]")
                    ?: "[]"
            )

            val idsPendientesBorrado = HashSet<String>()

            for (j in 0 until pendientesBorrado.length()) {
                val id = pendientesBorrado.optString(j).trim()
                if (id.isNotBlank()) {
                    idsPendientesBorrado.add(id)
                }
            }

            var nuevas = 0
            var actualizadas = 0
            var pendientesConCoincidencia = 0
            var huboCambios = false

            for (i in 0 until remotas.length()) {
                val remota = remotas.optJSONObject(i) ?: continue
                val idRemoto = textoSeguro(remota, "id")
                if (idsPendientesBorrado.contains(idRemoto)) {
                    Log.d(
                        TAG,
                        "Se omite llamada pendiente de eliminación: $idRemoto"
                    )
                    continue
                }

                if (idRemoto.isBlank()) {
                    Log.w(TAG, "Se omite una llamada del servidor sin ID.")
                    continue
                }

                val indice = buscarIndiceLocal(locales, idRemoto, remota)

                if (indice == -1) {
                    // Es una llamada que todavía no existe en el móvil.
                    val nueva = JSONObject()
                    copiarCamposServidor(nueva, remota, idRemoto)
                    nueva.put("sincronizado", true)
                    locales.put(nueva)
                    nuevas++
                    huboCambios = true
                } else {
                    val local = locales.optJSONObject(indice) ?: continue

                    if (!local.optBoolean("sincronizado", false)) {
                        // Hay cambios locales pendientes: no los pisamos.
                        local.put("serverId", idRemoto)

                        if (textoSeguro(local, "callId").isBlank()) {
                            local.put("callId", idRemoto)
                        }

                        if (textoSeguro(local, "idLlamada").isBlank()) {
                            local.put(
                                "idLlamada",
                                textoSeguro(remota, "idLlamada").ifBlank { idRemoto }
                            )
                        }

                        locales.put(indice, local)
                        pendientesConCoincidencia++
                        huboCambios = true
                    } else {
                        // La copia local ya estaba sincronizada:
                        // los datos centrales incorporan los cambios de la web.
                        copiarCamposServidor(local, remota, idRemoto)
                        local.put("sincronizado", true)
                        locales.put(indice, local)
                        actualizadas++
                        huboCambios = true
                    }
                }
            }

            if (huboCambios) {
                prefs.edit()
                    .putString("llamadas", locales.toString())
                    .apply()
            }

            Log.i(
                TAG,
                "Descarga finalizada. Nuevas: $nuevas, " +
                        "actualizadas: $actualizadas, " +
                        "pendientes locales protegidas: $pendientesConCoincidencia"
            )

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error descargando o fusionando llamadas desde $apiUrl", e)
            return false
        } finally {
            conn?.disconnect()
        }
    }

    private fun buscarIndiceLocal(
        locales: JSONArray,
        idRemoto: String,
        remota: JSONObject
    ): Int {
        // Primero buscamos una coincidencia exacta por cualquiera
        // de los identificadores que utiliza la aplicación.
        val identificadores = arrayOf(
            "serverId",
            "id",
            "callId",
            "idLlamada"
        )

        for (i in 0 until locales.length()) {
            val local = locales.optJSONObject(i) ?: continue

            for (campo in identificadores) {
                if (textoSeguro(local, campo) == idRemoto) {
                    return i
                }
            }
        }

        // Respaldo para registros antiguos que no compartan el ID:
        // teléfono y fecha/hora deben coincidir exactamente.
        val telefonoRemoto = textoSeguro(remota, "telefono")
        val fechaRemota = textoSeguro(remota, "fechaHora")

        if (telefonoRemoto.isBlank() || fechaRemota.isBlank()) {
            return -1
        }

        for (i in 0 until locales.length()) {
            val local = locales.optJSONObject(i) ?: continue

            if (
                textoSeguro(local, "telefono") == telefonoRemoto &&
                textoSeguro(local, "fechaHora") == fechaRemota
            ) {
                return i
            }
        }

        return -1
    }

    private fun copiarCamposServidor(
        destino: JSONObject,
        remoto: JSONObject,
        idRemoto: String
    ) {
        val camposTexto = arrayOf(
            "telefono",
            "nombre",
            "tipoLlamada",
            "pieza",
            "descripcion",
            "lado",
            "oem",
            "marca",
            "modelo",
            "motor",
            "anio",
            "estado",
            "fechaHora",
            "fechaFin",
            "apuntes",
            "stockId",
            "codigoStock"
        )

        for (campo in camposTexto) {
            if (remoto.has(campo)) {
                destino.put(
                    campo,
                    if (remoto.isNull(campo)) "" else remoto.optString(campo, "")
                )
            }
        }

        if (remoto.has("duracionSegundos")) {
            destino.put(
                "duracionSegundos",
                remoto.optLong("duracionSegundos", 0L)
            )
        }

        if (remoto.has("llamadaEnCurso")) {
            val enCurso = remoto.optBoolean("llamadaEnCurso", false) ||
                    remoto.optInt("llamadaEnCurso", 0) != 0
            destino.put("llamadaEnCurso", enCurso)
        }

        if (remoto.has("precio")) {
            if (remoto.isNull("precio")) {
                destino.remove("precioVenta")
            } else {
                val precio = remoto.optDouble("precio", Double.NaN)
                if (!precio.isNaN()) {
                    destino.put("precioVenta", precio)
                }
            }
        }

        if (remoto.has("fechaVenta")) {
            if (remoto.isNull("fechaVenta")) {
                destino.remove("fechaVenta")
            } else {
                destino.put("fechaVenta", remoto.optString("fechaVenta", ""))
            }
        }

        destino.put("serverId", idRemoto)

        if (textoSeguro(destino, "callId").isBlank()) {
            destino.put("callId", idRemoto)
        }

        if (textoSeguro(destino, "idLlamada").isBlank()) {
            destino.put(
                "idLlamada",
                textoSeguro(remoto, "idLlamada").ifBlank { idRemoto }
            )
        }
    }

    private fun textoSeguro(objeto: JSONObject, campo: String): String {
        return if (!objeto.has(campo) || objeto.isNull(campo)) {
            ""
        } else {
            objeto.optString(campo, "").trim()
        }
    }

    private fun enviarAlServidor(
        apiUrls: List<String>,
        llamada: JSONObject
    ): Boolean {
        for (apiUrl in apiUrls) {
            Log.d(TAG, "Intentando enviar llamada a: $apiUrl")

            if (enviarLlamadaAUrl(apiUrl, llamada)) {
                Log.i(TAG, "Llamada enviada correctamente a: $apiUrl")
                return true
            }
        }

        Log.e(
            TAG,
            "No se pudo enviar la llamada por ninguna conexión. " +
                    "ID: ${textoSeguro(llamada, "serverId")}"
        )
        return false
    }

    private fun enviarLlamadaAUrl(
        apiUrl: String,
        llamada: JSONObject
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            val stableId = textoSeguro(llamada, "serverId")
                .ifBlank { textoSeguro(llamada, "callId") }
                .ifBlank { textoSeguro(llamada, "idLlamada") }
            val payload = JSONObject().apply {
                if (stableId.isNotBlank()) put("id", stableId)
                put("source", "android")
                put("callId", stableId)
                put("telefono", llamada.optString("telefono", ""))
                put("nombre", llamada.optString("nombre", ""))
                put("tipoLlamada", llamada.optString("tipoLlamada", "Contestada"))
                put("pieza", llamada.optString("pieza", ""))
                put("descripcion", llamada.optString("descripcion", ""))
                put("lado", llamada.optString("lado", ""))
                put("oem", llamada.optString("oem", ""))
                put("marca", llamada.optString("marca", ""))
                put("modelo", llamada.optString("modelo", ""))
                put("motor", llamada.optString("motor", ""))
                put("anio", llamada.optString("anio", ""))
                put("estado", llamada.optString("estado", "Pendiente"))
                put("fechaHora", llamada.optString("fechaHora", "Sin fecha"))
                put("fechaFin", llamada.optString("fechaFin", ""))
                put("llamadaEnCurso", llamada.optBoolean("llamadaEnCurso", false))
                put("duracionSegundos", llamada.optLong("duracionSegundos", 0L))
                put("apuntes", llamada.optString("apuntes", ""))
                put("stockId", llamada.optString("stockId", ""))
                put("codigoStock", llamada.optString("codigoStock", ""))
                put("idLlamada", llamada.optString("idLlamada", stableId))
                if (llamada.has("precioVenta") && !llamada.isNull("precioVenta")) put("precioVenta", llamada.getDouble("precioVenta"))
                if (llamada.has("fechaVenta") && !llamada.isNull("fechaVenta")) put("fechaVenta", llamada.getString("fechaVenta"))
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) true else {
                Log.e(TAG, "Error HTTP $code al enviar llamada ${stableId.ifBlank { llamada.optString("telefono") }}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando llamada", e)
            false
        } finally { conn?.disconnect() }
    }


    private fun sincronizarCatalogos(context: Context) {
        val hosts = ServerConfig.getCandidateHosts(context)

        var base: String? = null
        var hashesServidor: JSONObject? = null

        for (host in hosts) {
            val baseActual = "http://$host"
            Log.d(TAG, "Consultando catálogo en: $baseActual")

            val hashes = consultarHuellas("$baseActual/api/catalog/status")

            if (hashes != null) {
                base = baseActual
                hashesServidor = hashes
                break
            }
        }

        val baseActiva = base ?: run {
            Log.w(TAG, "No se pudo acceder al catálogo por USB ni Wi-Fi.")
            return
        }

        val hashesActivos = hashesServidor ?: return

        val stockFiles =
            StockOperationsDatabase(context).archivosParaSincronizacion()
        val vehicleFiles =
            VehicleCatalogDatabase(context).archivosParaSincronizacion()

        val catalogFiles = linkedMapOf<String, File>()
        catalogFiles.putAll(stockFiles)
        catalogFiles.putAll(vehicleFiles)

        for ((kind, file) in catalogFiles) {
            if (!file.exists() || file.length() <= 0L) {
                Log.w(
                    TAG,
                    "Se omite catálogo $kind: archivo no disponible ${file.absolutePath}"
                )
                continue
            }

            val hash = sha256(file)

            if (hashesActivos.optString(kind) == hash) {
                Log.d(TAG, "Catálogo $kind ya actualizado (${file.length()} bytes).")
                continue
            }

            if (subirArchivoComprimido(
                    "$baseActiva/api/catalog/upload/$kind",
                    file,
                    hash
                )
            ) {
                Log.i(TAG, "Catálogo $kind sincronizado (${file.length()} bytes).")
            } else {
                Log.e(TAG, "No se pudo sincronizar el catálogo $kind.")
            }
        }
    }


    private fun consultarHuellas(urlText: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlText).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 7000
            if (conn.responseCode !in 200..299) return null
            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(response).optJSONObject("files") ?: JSONObject()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron consultar versiones del catálogo: ${e.message}")
            null
        } finally { conn?.disconnect() }
    }

    private fun subirArchivoComprimido(urlText: String, file: File, hash: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlText).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Content-Encoding", "gzip")
            conn.setRequestProperty("X-Content-SHA256", hash)
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setChunkedStreamingMode(64 * 1024)
            BufferedInputStream(FileInputStream(file)).use { input ->
                GZIPOutputStream(BufferedOutputStream(conn.outputStream)).use { gzip ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        gzip.write(buffer, 0, count)
                    }
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) Log.e(TAG, "Subida $urlText devolvió HTTP $code")
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "Error subiendo $urlText", e)
            false
        } finally { conn?.disconnect() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
