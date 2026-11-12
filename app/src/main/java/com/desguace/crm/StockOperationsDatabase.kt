
package com.desguace.crm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StockItem(
    val id: Long,
    val codigo: String,
    val descripcion: String,
    val observacionesPublicas: String,
    val oem: String,
    val oe: String,
    val iam: String,
    val ubicacion: String,
    val almacen: String,
    val subalmacen: String,
    val subsubalmacen: String,
    val stock: Int?,
    val precio: Double?,
    val pvpIva: Double?,
    val marca: String,
    val modelo: String,
    val compatibilidadVehiculo: String,
    val disponibilidadOriginal: String,
    val estadoGestion: String = disponibilidadOriginal
)

data class StockOperation(
    val stockId: Long,
    val codigo: String,
    val estado: String,
    val cliente: String,
    val llamadaId: String,
    val notas: String,
    val actualizadoEn: Long
)

enum class StockOperationResult {
    RESERVADA,
    YA_RESERVADA,
    YA_VENDIDA,
    NO_DISPONIBLE,
    NO_ENCONTRADA,
    CANCELADA,
    NO_ESTABA_RESERVADA,
    MARCADA_VENDIDA,
    YA_MARCADA_VENDIDA
}

class StockOperationsDatabase(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val ASSET_NAME =
            "inventario_stock_desguace.db"

        private const val DATABASE_NAME =
            "inventario_stock_desguace.db"

        private const val ASSET_VERSION = 2

        private const val PREFS_NAME = "desguace_crm"
        private const val PREF_ASSET_VERSION =
            "version_inventario_stock"

        private const val RESERVED = "RESERVED"
        private const val SOLD_OUTSIDE = "SOLD_OUTSIDE"
    }

    private fun normalizarReferencia(valor: String): String =
        valor.uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    private fun normalizarTexto(valor: String): String =
        Normalizer.normalize(
            valor.trim().lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        ).replace(Regex("\\p{Mn}+"), "")

    /*
     * Copia el inventario a almacenamiento privado.
     * Si cambia la versión del asset, vuelve a copiarlo.
     */
    private fun prepararInventario() {
        val destino = appContext.getDatabasePath(DATABASE_NAME)

        val prefs = appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        if (
            destino.exists() &&
            destino.length() > 0L &&
            prefs.getInt(PREF_ASSET_VERSION, 0) == ASSET_VERSION
        ) {
            return
        }

        destino.parentFile?.mkdirs()

        val temporal = File(
            destino.parentFile,
            "$DATABASE_NAME.tmp"
        )

        temporal.delete()

        try {
            appContext.assets.open(ASSET_NAME).use { entrada ->
                FileOutputStream(temporal).use { salida ->
                    entrada.copyTo(salida, 1024 * 1024)
                    salida.fd.sync()
                }
            }

            // Verificar que la copia contiene la tabla esperada.
            SQLiteDatabase.openDatabase(
                temporal.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery(
                    "SELECT COUNT(*) FROM stock_items",
                    null
                ).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getLong(0) > 0) {
                        "El inventario está vacío o no tiene piezas."
                    }
                }
            }

            if (destino.exists() && !destino.delete()) {
                error("No se pudo reemplazar la copia anterior.")
            }

            if (!temporal.renameTo(destino)) {
                error("No se pudo instalar la copia del inventario.")
            }

            check(
                prefs.edit()
                    .putInt(PREF_ASSET_VERSION, ASSET_VERSION)
                    .commit()
            ) {
                "No se pudo guardar la versión del inventario."
            }

        } catch (e: Exception) {
            temporal.delete()
            throw IllegalStateException(
                "No se pudo preparar el inventario. " +
                        "Comprueba que $ASSET_NAME está en assets.",
                e
            )
        }
    }

    private fun abrirInventario(): SQLiteDatabase {
        prepararInventario()

        return SQLiteDatabase.openDatabase(
            appContext.getDatabasePath(DATABASE_NAME).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    /*
     * Base mutable separada del inventario.
     * Solo guarda las operaciones hechas desde la aplicación.
     */
    private fun abrirOperaciones(): SQLiteDatabase {
        val archivo = appContext.getDatabasePath(
            "stock_operations.db"
        )

        archivo.parentFile?.mkdirs()

        return SQLiteDatabase.openOrCreateDatabase(
            archivo,
            null
        ).also { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS stock_item_state (
                    stock_id INTEGER PRIMARY KEY,
                    codigo TEXT NOT NULL UNIQUE,
                    estado TEXT NOT NULL,
                    cliente TEXT NOT NULL DEFAULT '',
                    llamada_id TEXT NOT NULL DEFAULT '',
                    notas TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS stock_operation_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stock_id INTEGER NOT NULL,
                    codigo TEXT NOT NULL,
                    operacion TEXT NOT NULL,
                    cliente TEXT NOT NULL DEFAULT '',
                    llamada_id TEXT NOT NULL DEFAULT '',
                    notas TEXT NOT NULL DEFAULT '',
                    fecha_millis INTEGER NOT NULL,
                    fecha_hora TEXT NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_stock_state_status
                ON stock_item_state(estado)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_stock_history_code
                ON stock_operation_history(codigo, fecha_millis)
                """.trimIndent()
            )
        }
    }

    private fun leerStockItem(
        cursor: android.database.Cursor
    ): StockItem {
        return StockItem(
            id = cursor.getLong(0),
            codigo = cursor.getString(1) ?: "",
            descripcion = cursor.getString(2) ?: "",
            observacionesPublicas = cursor.getString(3) ?: "",
            oem = cursor.getString(4) ?: "",
            oe = cursor.getString(5) ?: "",
            iam = cursor.getString(6) ?: "",
            ubicacion = cursor.getString(7) ?: "",
            almacen = cursor.getString(8) ?: "",
            subalmacen = cursor.getString(9) ?: "",
            subsubalmacen = cursor.getString(10) ?: "",
            stock = if (cursor.isNull(11)) null else cursor.getInt(11),
            precio = if (cursor.isNull(12)) null else cursor.getDouble(12),
            pvpIva = if (cursor.isNull(13)) null else cursor.getDouble(13),
            marca = cursor.getString(14) ?: "",
            modelo = cursor.getString(15) ?: "",
            compatibilidadVehiculo = cursor.getString(16) ?: "",
            disponibilidadOriginal = cursor.getString(17) ?: ""
        )
    }

    private val columnasStock = """
        id, codigo, descripcion, observaciones_publicas,
        oem, oe, iam, ubicacion, almacen, subalmacen,
        subsubalmacen, stock, precio, pvp_iva,
        marca_pieza, modelo, vehiculo_compatibilidad, disponibilidad
    """.trimIndent()

    fun buscarPorCodigo(codigoIntroducido: String): StockItem? {
        val codigo = codigoIntroducido.trim()
        if (codigo.isBlank()) return null

        val codigoNormalizado = normalizarReferencia(codigo)

        val item = abrirInventario().use { db ->
            db.rawQuery(
                """
                SELECT $columnasStock
                FROM stock_items
                WHERE codigo = ? OR codigo_norm = ?
                ORDER BY CASE WHEN codigo = ? THEN 0 ELSE 1 END
                LIMIT 1
                """.trimIndent(),
                arrayOf(codigo, codigoNormalizado, codigo)
            ).use { cursor ->
                if (cursor.moveToFirst()) leerStockItem(cursor) else null
            }
        }

        return item?.let { aplicarEstadoGestion(it) }
    }

    // El ID es el identificador interno de la tabla stock_items.
    fun buscarPorId(id: Long): StockItem? {
        if (id <= 0L) return null

        val item = abrirInventario().use { db ->
            db.rawQuery(
                """
                SELECT $columnasStock
                FROM stock_items
                WHERE id = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(id.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) leerStockItem(cursor) else null
            }
        }

        return item?.let { aplicarEstadoGestion(it) }
    }

    /*
     * Busca coincidencias exactas de OEM, OE o IAM.
     * Se ignoran espacios, guiones y puntos.
     */
    fun buscarPorReferencia(
        referenciaIntroducida: String,
        limite: Int = 50
    ): List<StockItem> {
        val referencia = normalizarReferencia(referenciaIntroducida)

        if (referencia.isBlank()) return emptyList()

        val resultados = abrirInventario().use { db ->
            db.rawQuery(
                """
                SELECT DISTINCT
                    s.id, s.codigo, s.descripcion,
                    s.observaciones_publicas, s.oem, s.oe, s.iam,
                    s.ubicacion, s.almacen, s.subalmacen,
                    s.subsubalmacen, s.stock, s.precio, s.pvp_iva,
                    s.marca_pieza, s.modelo,
                    s.vehiculo_compatibilidad, s.disponibilidad
                FROM stock_references r
                INNER JOIN stock_items s ON s.id = r.stock_id
                WHERE r.referencia_normalizada = ?
                  AND r.es_segmento = 0
                ORDER BY r.prioridad ASC, s.id
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    referencia,
                    limite.coerceIn(1, 200).toString()
                )
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(leerStockItem(cursor))
                    }
                }
            }
        }

        return resultados.map { aplicarEstadoGestion(it) }
    }


    fun buscarPiezas(
        consulta: String,
        limite: Int = 50
    ): List<StockItem> {

        val texto = consulta.trim()
        if (texto.isBlank()) return emptyList()

        val maxResultados = limite.coerceIn(1, 100)
        val encontrados = linkedMapOf<Long, StockItem>()

        fun agregar(items: List<StockItem>) {
            for (item in items) {
                if (encontrados.size >= maxResultados) break
                encontrados.putIfAbsent(item.id, item)
            }
        }

        // 1. Si se introduce un número, probar primero el ID interno.
        texto.toLongOrNull()?.let { id ->
            buscarPorId(id)?.let { encontrados[it.id] = it }
        }

        // 2. Código interno exacto de Seintosoft.
        buscarPorCodigo(texto)?.let {
            encontrados.putIfAbsent(it.id, it)
        }

        // 3. Referencias OEM, OE e IAM exactas, ignorando separadores.
        agregar(buscarPorReferencia(texto, maxResultados))

        // 4. Referencias parciales normalizadas.
        val referenciaNormalizada = normalizarReferencia(texto)

        if (referenciaNormalizada.length >= 4) {
            val coincidenciasReferencia = abrirInventario().use { db ->
                db.rawQuery(
                    """
                SELECT DISTINCT s.id
                FROM stock_references r
                INNER JOIN stock_items s ON s.id = r.stock_id
                WHERE r.referencia_normalizada LIKE ?
                  AND r.es_segmento = 0
                ORDER BY r.prioridad ASC, s.id
                LIMIT ?
                """.trimIndent(),
                    arrayOf(
                        "%$referenciaNormalizada%",
                        maxResultados.toString()
                    )
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.getLong(0))
                        }
                    }
                }
            }

            for (id in coincidenciasReferencia) {
                buscarPorId(id)?.let {
                    encontrados.putIfAbsent(it.id, it)
                }
                if (encontrados.size >= maxResultados) break
            }
        }

        // 5. Buscar por descripción, vehículo, lado y ubicación.
        // Cada término debe aparecer en el texto de búsqueda normalizado.
        val terminos = normalizarTexto(texto)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }

        if (terminos.isNotEmpty() && encontrados.size < maxResultados) {
            val condiciones = terminos.joinToString(" AND ") {
                "busqueda_norm LIKE ?"
            }

            val argumentos = terminos
                .map { "%$it%" }
                .toMutableList()

            argumentos.add(maxResultados.toString())

            val coincidenciasTexto = abrirInventario().use { db ->
                db.rawQuery(
                    """
                SELECT $columnasStock
                FROM stock_items
                WHERE $condiciones
                ORDER BY
                    CASE
                        WHEN disponibilidad = 'Disponible' THEN 0
                        ELSE 1
                    END,
                    id
                LIMIT ?
                """.trimIndent(),
                    argumentos.toTypedArray()
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(leerStockItem(cursor))
                        }
                    }
                }
            }

            agregar(coincidenciasTexto)
        }

        // Las referencias exactas aparecen antes que las coincidencias generales.
        return encontrados.values
            .take(maxResultados)
            .map { aplicarEstadoGestion(it) }
    }



    fun estaDisponible(item: StockItem): Boolean {
        val disponibilidad = item.disponibilidadOriginal

        val estadoPermitido =
            disponibilidad.equals("Disponible", ignoreCase = true) ||
                    disponibilidad.equals("En mostrador", ignoreCase = true)

        if (!estadoPermitido) return false

        if (item.stock != null && item.stock <= 0) return false

        return obtenerEstadoOperacion(item.id) == null
    }


    fun reservarPorCodigo(
        codigo: String,
        cliente: String = "",
        llamadaId: String = "",
        notas: String = ""
    ): StockOperationResult {
        val item = buscarPorCodigo(codigo)
            ?: return StockOperationResult.NO_ENCONTRADA

        return reservar(item, cliente, llamadaId, notas)
    }

    fun reservarPorId(
        id: Long,
        cliente: String = "",
        llamadaId: String = "",
        notas: String = ""
    ): StockOperationResult {
        val item = buscarPorId(id)
            ?: return StockOperationResult.NO_ENCONTRADA

        return reservar(item, cliente, llamadaId, notas)
    }

    private fun reservar(
        item: StockItem,
        cliente: String,
        llamadaId: String,
        notas: String
    ): StockOperationResult {
        val estadoPermitido =
            item.disponibilidadOriginal.equals(
                "Disponible",
                ignoreCase = true
            ) ||
                    item.disponibilidadOriginal.equals(
                        "En mostrador",
                        ignoreCase = true
                    )

        if (
            !estadoPermitido ||
            (item.stock != null && item.stock <= 0)
        ) {
            return StockOperationResult.NO_DISPONIBLE
        }

        val ahora = System.currentTimeMillis()
        val db = abrirOperaciones()

        try {
            db.beginTransaction()

            val estadoActual = consultarEstado(db, item.id)

            if (estadoActual == RESERVED) {
                return StockOperationResult.YA_RESERVADA
            }

            if (estadoActual == SOLD_OUTSIDE) {
                return StockOperationResult.YA_VENDIDA
            }

            val valores = ContentValues().apply {
                put("stock_id", item.id)
                put("codigo", item.codigo)
                put("estado", RESERVED)
                put("cliente", cliente.trim())
                put("llamada_id", llamadaId.trim())
                put("notas", notas.trim())
                put("updated_at", ahora)
            }

            db.insertOrThrow(
                "stock_item_state",
                null,
                valores
            )

            registrarHistorial(
                db,
                item,
                "RESERVADA",
                cliente,
                llamadaId,
                notas,
                ahora
            )

            db.setTransactionSuccessful()
            return StockOperationResult.RESERVADA

        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun cancelarReservaPorCodigo(
        codigo: String,
        notas: String = ""
    ): StockOperationResult {
        val item = buscarPorCodigo(codigo)
            ?: return StockOperationResult.NO_ENCONTRADA

        val ahora = System.currentTimeMillis()
        val db = abrirOperaciones()

        try {
            db.beginTransaction()

            val estadoActual = consultarEstado(db, item.id)

            if (estadoActual != RESERVED) {
                return StockOperationResult.NO_ESTABA_RESERVADA
            }

            db.delete(
                "stock_item_state",
                "stock_id = ? AND estado = ?",
                arrayOf(item.id.toString(), RESERVED)
            )

            registrarHistorial(
                db,
                item,
                "RESERVA_CANCELADA",
                "",
                "",
                notas,
                ahora
            )

            db.setTransactionSuccessful()
            return StockOperationResult.CANCELADA

        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun marcarVendidaFueraPorCodigo(
        codigo: String,
        notas: String = ""
    ): StockOperationResult {
        val item = buscarPorCodigo(codigo)
            ?: return StockOperationResult.NO_ENCONTRADA

        val ahora = System.currentTimeMillis()
        val db = abrirOperaciones()

        try {
            db.beginTransaction()

            if (consultarEstado(db, item.id) == SOLD_OUTSIDE) {
                return StockOperationResult.YA_MARCADA_VENDIDA
            }

            val valores = ContentValues().apply {
                put("stock_id", item.id)
                put("codigo", item.codigo)
                put("estado", SOLD_OUTSIDE)
                put("cliente", "")
                put("llamada_id", "")
                put("notas", notas.trim())
                put("updated_at", ahora)
            }

            db.insertWithOnConflict(
                "stock_item_state",
                null,
                valores,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            registrarHistorial(
                db,
                item,
                "VENDIDA_FUERA_DE_APP",
                "",
                "",
                notas,
                ahora
            )

            db.setTransactionSuccessful()
            return StockOperationResult.MARCADA_VENDIDA

        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun listarReservas(): List<StockOperation> {
        return abrirOperaciones().use { db ->
            db.rawQuery(
                """
                SELECT stock_id, codigo, estado, cliente,
                       llamada_id, notas, updated_at
                FROM stock_item_state
                WHERE estado = ?
                ORDER BY updated_at DESC
                """.trimIndent(),
                arrayOf(RESERVED)
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            StockOperation(
                                stockId = cursor.getLong(0),
                                codigo = cursor.getString(1) ?: "",
                                estado = cursor.getString(2) ?: "",
                                cliente = cursor.getString(3) ?: "",
                                llamadaId = cursor.getString(4) ?: "",
                                notas = cursor.getString(5) ?: "",
                                actualizadoEn = cursor.getLong(6)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun obtenerEstadoOperacion(stockId: Long): String? {
        return abrirOperaciones().use { db ->
            consultarEstado(db, stockId)
        }
    }

    private fun consultarEstado(
        db: SQLiteDatabase,
        stockId: Long
    ): String? {
        return db.rawQuery(
            """
            SELECT estado
            FROM stock_item_state
            WHERE stock_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(stockId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }


    private fun aplicarEstadoGestion(item: StockItem): StockItem {
        val estadoOrigen = item.disponibilidadOriginal.trim()

        val disponibleEnOrigen =
            estadoOrigen.equals("Disponible", ignoreCase = true) ||
                    estadoOrigen.equals("En mostrador", ignoreCase = true)

        // Si Seintosoft indica que la pieza no está disponible,
        // su estado debe prevalecer sobre la reserva local.
        if (!disponibleEnOrigen) {
            return item.copy(estadoGestion = estadoOrigen)
        }

        return when (obtenerEstadoOperacion(item.id)) {
            RESERVED -> item.copy(
                estadoGestion = "Reservada en DesguaceCRM"
            )

            SOLD_OUTSIDE -> item.copy(
                estadoGestion = "Vendida fuera de DesguaceCRM"
            )

            else -> item.copy(
                estadoGestion = estadoOrigen
            )
        }
    }


    /** Prepara y devuelve las bases locales que usa el buscador de piezas y su estado comercial. */
    fun archivosParaSincronizacion(): Map<String, File> {
        abrirInventario().use { /* asegura que el catálogo exista y se pueda abrir */ }
        abrirOperaciones().use { /* asegura que existan las tablas de reservas/ventas */ }
        return mapOf(
            "stock" to appContext.getDatabasePath(DATABASE_NAME),
            "operations" to appContext.getDatabasePath("stock_operations.db")
        )
    }

    private fun registrarHistorial(
        db: SQLiteDatabase,
        item: StockItem,
        operacion: String,
        cliente: String,
        llamadaId: String,
        notas: String,
        cuando: Long
    ) {
        val fecha = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(cuando))

        val valores = ContentValues().apply {
            put("stock_id", item.id)
            put("codigo", item.codigo)
            put("operacion", operacion)
            put("cliente", cliente)
            put("llamada_id", llamadaId)
            put("notas", notas)
            put("fecha_millis", cuando)
            put("fecha_hora", fecha)
        }

        db.insertOrThrow(
            "stock_operation_history",
            null,
            valores
        )
    }
}
