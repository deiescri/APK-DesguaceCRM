package com.desguace.crm

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.nio.charset.Charset
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tipo de exportación: completa (foto del inventario) o parcial (altas/cambios). */
enum class StockImportMode {
    COMPLETO,
    PARCIAL
}

/** Resumen calculado antes de modificar el inventario real. */
data class StockImportPreview(
    val filasLeidas: Int,
    val codigosUnicos: Int,
    val nuevas: Int,
    val actualizadas: Int,
    val sinCambios: Int,
    val reservadasExcluidas: Int,
    val otrasExcluidas: Int,
    val ausentesDeLaNuevaExportacion: Int,
    val codigosDuplicadosEnCsv: Int,
    val filasInvalidas: Int,
    val modo: StockImportMode
)

/** Resultado de confirmar una importación. */
data class StockImportResult(
    val agregadas: Int,
    val actualizadas: Int,
    val excluidas: Int,
    val marcadasAusentes: Int
)

/**
 * Importa CSV de Seintosoft en la base local de inventario.
 *
 * - Lee CSV delimitado por punto y coma y codificación Windows-1252.
 * - Prepara tablas temporales dentro de la base de stock para mostrar una vista previa.
 * - No modifica stock_items hasta confirmar la importación.
 * - Modo COMPLETO puede marcar como ausentes los códigos que no aparecen.
 * - Modo PARCIAL solo añade/actualiza las filas del archivo y no marca ausentes.
 * - Conserva los IDs locales existentes al actualizar por el código de Seintosoft.
 * - Mantiene las reservas y ventas hechas en DesguaceCRM en stock_operations.db.
 */
class StockCsvImportManager(context: Context) {

    private val appContext = context.applicationContext

    private val inventoryFileName = "inventario_stock_desguace.db"
    private val stageTable = "stock_import_stage"
    private val stageRefsTable = "stock_import_stage_refs"
    private val stageMetaTable = "stock_import_stage_meta"

    private data class ParseStats(
        var filasLeidas: Int = 0,
        var codigosDuplicados: Int = 0,
        var filasInvalidas: Int = 0
    )

    private val stockColumns = listOf(
        "codigo",
        "codigo_norm",
        "descripcion",
        "observaciones_publicas",
        "oem",
        "oe",
        "iam",
        "almacen",
        "subalmacen",
        "subsubalmacen",
        "stock",
        "precio",
        "pvp_iva",
        "precio_online",
        "unidades_vendidas",
        "ubicacion",
        "desmontada",
        "producto",
        "marca_pieza",
        "modelo",
        "vehiculo_compatibilidad",
        "estado",
        "motivo_estado",
        "calidad",
        "fecha_almacenaje",
        "disponibilidad",
        "descripcion_norm",
        "observaciones_norm",
        "ubicacion_norm",
        "vehiculo_norm",
        "marca_norm",
        "modelo_norm",
        "estado_norm",
        "busqueda_norm",
        "observaciones_internas"
    )

    private val columnasComparables = stockColumns.filter {
        it != "codigo"
    }

    /** Lee el CSV y prepara la vista previa sin tocar el inventario activo. */
    fun prepararVistaPrevia(
        uri: Uri,
        modo: StockImportMode = StockImportMode.COMPLETO
    ): StockImportPreview {
        val db = abrirInventarioEscritura()
        val stats = ParseStats()

        try {
            db.beginTransaction()
            try {
                crearTablasTemporales(db)
                db.execSQL(
                    "INSERT INTO $stageMetaTable(modo) VALUES (?)",
                    arrayOf(modo.name)
                )
                cargarCsvEnStage(db, uri, stats)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            return calcularVistaPrevia(db, stats, modo)
        } finally {
            db.close()
        }
    }

    /** Aplica el staging previamente generado, dentro de una sola transacción. */
    fun confirmarImportacion(): StockImportResult {
        val db = abrirInventarioEscritura()

        try {
            comprobarStageExiste(db)
            val modo = leerModoStage(db)

            var agregadas = 0
            var actualizadas = 0
            var excluidas = 0

            // Copia de seguridad única previa a cada importación confirmada.
            crearCopiaSeguridad(db)

            db.beginTransaction()
            try {
                val queryColumns = stockColumns.joinToString(",")
                db.rawQuery(
                    "SELECT $queryColumns, import_excluded FROM $stageTable ORDER BY codigo",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val codigo = cursor.getString(
                            cursor.getColumnIndexOrThrow("codigo")
                        ) ?: continue

                        val excluded = cursor.getInt(
                            cursor.getColumnIndexOrThrow("import_excluded")
                        ) != 0
                        val currentId = buscarIdPorCodigo(db, codigo)

                        // Las piezas reservadas/vendidas/descartadas en Seintosoft
                        // no se añaden si son nuevas. Si ya existían, se ocultan
                        // de la disponibilidad sin borrar su historial local.
                        if (excluded && currentId == null) {
                            excluidas++
                            continue
                        }

                        val values = valuesDesdeCursor(cursor, stockColumns)
                        // El staging ya contiene la disponibilidad con el motivo
                        // específico (vendida, reservada, desechada, etc.).
                        if (excluded) excluidas++

                        val stockId: Long
                        if (currentId != null) {
                            db.update(
                                "stock_items",
                                values,
                                "id = ?",
                                arrayOf(currentId.toString())
                            )
                            stockId = currentId
                            // También contamos como actualización los cambios
                            // de estado a reservada/vendida/desechada.
                            actualizadas++
                        } else {
                            // El ID local se genera en destino y no depende del
                            // orden de las filas de la nueva exportación.
                            stockId = db.insertOrThrow(
                                "stock_items",
                                null,
                                values
                            )
                            if (!excluded) agregadas++
                        }

                        actualizarReferencias(db, stockId, codigo)
                    }
                }

                val ausentesMarcadas = if (modo == StockImportMode.COMPLETO) {
                    val ausente = "Ausente de la última importación"
                    db.compileStatement(
                        """
                        UPDATE stock_items
                        SET disponibilidad = ?
                        WHERE codigo NOT IN (SELECT codigo FROM $stageTable)
                          AND disponibilidad NOT IN (
                              ?,
                              'Excluida en Seintosoft',
                              'Reservada en Seintosoft',
                              'Vendida en Seintosoft',
                              'Desechada en Seintosoft',
                              'Descatalogada en Seintosoft'
                          )
                        """.trimIndent()
                    ).use { statement ->
                        statement.bindString(1, ausente)
                        statement.bindString(2, ausente)
                        statement.executeUpdateDelete()
                    }
                } else {
                    // En importación parcial, lo que no viene en el CSV no se toca.
                    0
                }

                guardarMetadato(db, "last_import_mode", modo.name)
                guardarMetadato(db, "last_import_at", fechaHoraActual())
                guardarMetadato(db, "last_import_source", "CSV de Seintosoft")
                guardarMetadato(
                    db,
                    "last_import_rows",
                    contar(db, "SELECT COUNT(*) FROM $stageTable").toString()
                )

                db.execSQL("DROP TABLE IF EXISTS $stageRefsTable")
                db.execSQL("DROP TABLE IF EXISTS $stageTable")
                db.execSQL("DROP TABLE IF EXISTS $stageMetaTable")
                db.setTransactionSuccessful()

                return StockImportResult(
                    agregadas = agregadas,
                    actualizadas = actualizadas,
                    excluidas = excluidas,
                    marcadasAusentes = ausentesMarcadas
                )
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    /** Cancela una vista previa y elimina únicamente las tablas temporales. */
    fun cancelarVistaPrevia() {
        val db = abrirInventarioEscritura()
        try {
            db.beginTransaction()
            try {
                db.execSQL("DROP TABLE IF EXISTS $stageRefsTable")
                db.execSQL("DROP TABLE IF EXISTS $stageTable")
                db.execSQL("DROP TABLE IF EXISTS $stageMetaTable")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    private fun abrirInventarioEscritura(): SQLiteDatabase {
        // Inicializa la copia privada desde assets si todavía no existe.
        // El resultado puede ser null si el ID 1 no existe; lo importante es
        // que la consulta haya preparado la base de datos local.
        StockOperationsDatabase(appContext).buscarPorId(1L)

        val file = appContext.getDatabasePath(inventoryFileName)
        check(file.exists() && file.length() > 0L) {
            "No se encontró el inventario local. Comprueba assets/$inventoryFileName."
        }

        return SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
    }

    private fun crearCopiaSeguridad(db: SQLiteDatabase) {
        // La aplicación abre esta base en modo journal por defecto. El checkpoint
        // se ejecuta solo mediante la conexión escribible, nunca desde una lectura.
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }

        val origen = appContext.getDatabasePath(inventoryFileName)
        val carpeta = java.io.File(appContext.filesDir, "backups")
        if (!carpeta.exists() && !carpeta.mkdirs()) {
            throw IOException("No se pudo crear la carpeta de copias de seguridad.")
        }

        val destino = java.io.File(
            carpeta,
            "inventario_stock_desguace_preimport.db"
        )
        val temporal = java.io.File(carpeta, "inventario_stock_desguace_preimport.tmp")
        temporal.delete()

        origen.inputStream().use { input ->
            temporal.outputStream().use { output ->
                input.copyTo(output, 1024 * 1024)
                output.flush()
                output.fd.sync()
            }
        }

        if (destino.exists() && !destino.delete()) {
            temporal.delete()
            throw IOException("No se pudo reemplazar la copia de seguridad anterior.")
        }
        if (!temporal.renameTo(destino)) {
            temporal.delete()
            throw IOException("No se pudo guardar la copia de seguridad previa.")
        }
    }

    private fun crearTablasTemporales(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS $stageRefsTable")
        db.execSQL("DROP TABLE IF EXISTS $stageTable")
        db.execSQL("DROP TABLE IF EXISTS $stageMetaTable")
        db.execSQL(
            "CREATE TABLE $stageMetaTable (modo TEXT NOT NULL)"
        )

        val columnas = stockColumns.joinToString(",")
        db.execSQL(
            "CREATE TABLE $stageTable AS SELECT $columnas FROM stock_items WHERE 0"
        )
        db.execSQL(
            "ALTER TABLE $stageTable ADD COLUMN import_excluded INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE $stageTable ADD COLUMN exclusion_reason TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX idx_stock_import_stage_codigo ON $stageTable(codigo)"
        )

        db.execSQL(
            """
            CREATE TABLE $stageRefsTable (
                codigo TEXT NOT NULL,
                tipo TEXT NOT NULL,
                referencia_original TEXT NOT NULL,
                referencia_normalizada TEXT NOT NULL,
                prioridad INTEGER NOT NULL,
                es_segmento INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (codigo, tipo, referencia_normalizada, es_segmento)
            )
            """.trimIndent()
        )
    }

    private fun cargarCsvEnStage(
        db: SQLiteDatabase,
        uri: Uri,
        stats: ParseStats
    ) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Android no pudo abrir el archivo seleccionado.")

        input.use { stream ->
            val reader = PushbackReader(
                BufferedReader(
                    InputStreamReader(stream, Charset.forName("windows-1252")),
                    64 * 1024
                ),
                1
            )

            var headers: Map<String, Int>? = null

            leerRegistrosCsv(reader) { fila ->
                if (headers == null) {
                    val mapa = linkedMapOf<String, Int>()
                    fila.forEachIndexed { index, nombre ->
                        val clave = normalizarCabecera(nombre)
                        if (clave.isNotBlank() && !mapa.containsKey(clave)) {
                            mapa[clave] = index
                        }
                    }

                    if (!mapa.containsKey("codigo") ||
                        !mapa.containsKey("descripcion")
                    ) {
                        throw IllegalArgumentException(
                            "El CSV no parece una exportación de Seintosoft: " +
                                "faltan las columnas Código o Descripción. " +
                                "Selecciona un CSV separado por punto y coma."
                        )
                    }
                    headers = mapa
                    return@leerRegistrosCsv
                }

                if (fila.all { it.isBlank() }) return@leerRegistrosCsv
                stats.filasLeidas++

                val mapaCabeceras = headers ?: return@leerRegistrosCsv
                val codigo = campo(fila, mapaCabeceras, "codigo").trim()
                if (codigo.isBlank()) {
                    stats.filasInvalidas++
                    return@leerRegistrosCsv
                }

                val valuesAndInfo = crearValoresDesdeFila(fila, mapaCabeceras)
                val values = valuesAndInfo.first
                val referencias = valuesAndInfo.second
                val exclusion = valuesAndInfo.third

                if (existeCodigoEnStage(db, codigo)) {
                    stats.codigosDuplicados++
                    db.delete(stageRefsTable, "codigo = ?", arrayOf(codigo))
                }

                values.put("import_excluded", if (exclusion.isBlank()) 0 else 1)
                values.put("exclusion_reason", exclusion)

                db.insertWithOnConflict(
                    stageTable,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )

                referencias.forEach { ref ->
                    val refValues = ContentValues().apply {
                        put("codigo", codigo)
                        put("tipo", ref.tipo)
                        put("referencia_original", ref.original)
                        put("referencia_normalizada", ref.normalizada)
                        put("prioridad", ref.prioridad)
                        put("es_segmento", if (ref.segmento) 1 else 0)
                    }
                    db.insertWithOnConflict(
                        stageRefsTable,
                        null,
                        refValues,
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
            }

            check(headers != null) {
                "El archivo CSV está vacío o no contiene una cabecera válida."
            }
        }
    }

    private data class ReferenciaImportada(
        val tipo: String,
        val original: String,
        val normalizada: String,
        val prioridad: Int,
        val segmento: Boolean
    )

    private fun crearValoresDesdeFila(
        fila: List<String>,
        headers: Map<String, Int>
    ): Triple<ContentValues, List<ReferenciaImportada>, String> {
        val codigo = campo(fila, headers, "codigo").trim()
        val descripcion = campo(fila, headers, "descripcion")
        val publicas = campo(
            fila,
            headers,
            "obspublicas",
            "observacionespublicas"
        )
        val internas = campo(
            fila,
            headers,
            "obsinternas",
            "observacionesinternas"
        )
        val oem = campo(fila, headers, "oem")
        val oe = campo(fila, headers, "oe")
        val iam = campo(fila, headers, "iam")
        val almacen = campo(fila, headers, "almacen")
        val subalmacen = campo(fila, headers, "subalmacen")
        val subsubalmacen = campo(fila, headers, "subsubalmacen")
        val stockTexto = campo(fila, headers, "stock")
        val precioTexto = campo(fila, headers, "precio")
        val pvpTexto = campo(fila, headers, "pvpiva")
        val precioOnlineTexto = campo(fila, headers, "precioonline")
        val vendidasTexto = campo(fila, headers, "vendidas")
        val ubicacion = campo(fila, headers, "ubicacion")
        val desmontada = campo(fila, headers, "desmontada")
        val producto = campo(fila, headers, "producto")
        val marcaPieza = campo(fila, headers, "marca")
        val modelo = campo(fila, headers, "modelo")
        val metadatos = campo(fila, headers, "metadatos")
        val estado = campo(fila, headers, "estado")
        val motivoEstado = campo(fila, headers, "motivoestado")
        val calidad = campo(fila, headers, "calidad")
        val fechaAlmacenaje = campo(fila, headers, "fechaalmacenaje")

        val stock = parseInt(stockTexto)
        val precio = parseNumero(precioTexto)
        val pvp = parseNumero(pvpTexto)
        val precioOnline = parseNumero(precioOnlineTexto)
        val unidadesVendidas = parseNumero(vendidasTexto)

        val compatibilidad = extraerCompatibilidadVehiculo(metadatos)
        val exclusion = motivoExclusion(estado, motivoEstado)
        val disponibilidad = when (exclusion) {
            "RESERVADA" -> "Reservada en Seintosoft"
            "VENDIDA" -> "Vendida en Seintosoft"
            "DESECHADA" -> "Desechada en Seintosoft"
            "DESCATALOGADA" -> "Descatalogada en Seintosoft"
            else -> determinarDisponibilidad(estado, stock)
        }

        val descripcionNorm = normalizarTexto(descripcion)
        val observacionesNorm = normalizarTexto(publicas)
        val ubicacionNorm = normalizarTexto(
            listOf(ubicacion, almacen, subalmacen, subsubalmacen)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
        val vehiculoNorm = normalizarTexto("$modelo $compatibilidad")
        val marcaNorm = normalizarTexto(marcaPieza)
        val modeloNorm = normalizarTexto(modelo)
        val estadoNorm = normalizarTexto(estado)

        val partesBusqueda = listOf(
            codigo, descripcion, publicas, internas, oem, oe, iam,
            ubicacion, almacen, subalmacen, subsubalmacen, producto,
            marcaPieza, modelo, compatibilidad, estado, motivoEstado,
            calidad, desmontada
        ).filter { it.isNotBlank() }

        val busquedaBase = normalizarTexto(partesBusqueda.joinToString(" "))
        val sinonimosLado = mutableListOf<String>()
        if (Regex("""\b(derecho|derecha|dcho|dcha|right|rh)\b""")
                .containsMatchIn(busquedaBase)
        ) {
            sinonimosLado.add("derecho derecha dcho dcha right rh")
        }
        if (Regex("""\b(izquierdo|izquierda|izq|izda|left|lh)\b""")
                .containsMatchIn(busquedaBase)
        ) {
            sinonimosLado.add("izquierdo izquierda izq izda left lh")
        }
        val busquedaNorm = (listOf(busquedaBase) + sinonimosLado)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        val values = ContentValues().apply {
            put("codigo", codigo)
            put("codigo_norm", normalizarReferencia(codigo))
            put("descripcion", descripcion)
            put("observaciones_publicas", publicas)
            put("oem", oem)
            put("oe", oe)
            put("iam", iam)
            put("almacen", almacen)
            put("subalmacen", subalmacen)
            put("subsubalmacen", subsubalmacen)
            if (stock == null) putNull("stock") else put("stock", stock)
            if (precio == null) putNull("precio") else put("precio", precio)
            if (pvp == null) putNull("pvp_iva") else put("pvp_iva", pvp)
            if (precioOnline == null) putNull("precio_online") else put("precio_online", precioOnline)
            if (unidadesVendidas == null) putNull("unidades_vendidas") else put("unidades_vendidas", unidadesVendidas)
            put("ubicacion", ubicacion)
            put("desmontada", desmontada)
            put("producto", producto)
            put("marca_pieza", marcaPieza)
            put("modelo", modelo)
            put("vehiculo_compatibilidad", compatibilidad)
            put("estado", estado)
            put("motivo_estado", motivoEstado)
            put("calidad", calidad)
            put("fecha_almacenaje", fechaAlmacenaje)
            put("disponibilidad", disponibilidad)
            put("descripcion_norm", descripcionNorm)
            put("observaciones_norm", observacionesNorm)
            put("ubicacion_norm", ubicacionNorm)
            put("vehiculo_norm", vehiculoNorm)
            put("marca_norm", marcaNorm)
            put("modelo_norm", modeloNorm)
            put("estado_norm", estadoNorm)
            put("busqueda_norm", busquedaNorm)
            put("observaciones_internas", internas)
        }

        val refs = mutableListOf<ReferenciaImportada>()
        agregarReferencias(refs, "OEM", oem, 0)
        agregarReferencias(refs, "OE", oe, 2)
        agregarReferencias(refs, "IAM", iam, 3)

        return Triple(values, refs, exclusion)
    }

    private fun agregarReferencias(
        salida: MutableList<ReferenciaImportada>,
        tipo: String,
        valor: String,
        prioridad: Int
    ) {
        if (valor.isBlank()) return

        val completa = normalizarReferencia(valor)
        if (esReferenciaPlausible(completa)) {
            salida.add(
                ReferenciaImportada(
                    tipo, valor.trim(), completa, prioridad, false
                )
            )
        }

        // Además de la celda completa, indexa códigos separados por barra,
        // coma, punto y coma, pipe o salto de línea.
        val segmentos = valor.split(Regex("""[/|;,\r\n]+"""))
        for (segmento in segmentos) {
            val limpio = segmento.trim()
            val normalizado = normalizarReferencia(limpio)
            if (!esReferenciaPlausible(normalizado)) continue
            if (normalizado == completa) continue

            salida.add(
                ReferenciaImportada(
                    tipo, limpio, normalizado, prioridad, true
                )
            )
        }
    }

    private fun esReferenciaPlausible(valor: String): Boolean {
        return valor.length >= 4 && valor.any { it.isDigit() }
    }

    private fun determinarDisponibilidad(
        estadoOriginal: String,
        stock: Int?
    ): String {
        val estado = normalizarTexto(estadoOriginal)

        return when {
            estado.contains("reserv") ->
                "Reservada en Seintosoft"

            estado.contains("vendid") ->
                "Vendida en Seintosoft"

            estado.contains("desech") || estado.contains("descart") ->
                "Desechada en Seintosoft"

            estado.contains("descatalog") ->
                "Descatalogada en Seintosoft"

            estado.contains("en mostrador") && (stock == null || stock > 0) ->
                "En mostrador"

            estado == "almacenada" && stock != null && stock <= 0 ->
                "Agotada en Seintosoft"

            estado == "almacenada" && (stock == null || stock > 0) ->
                "Disponible"

            estado.contains("con incidencia") ->
                "Con incidencia en Seintosoft"

            estado.contains("devuelta") ->
                "Devuelta en Seintosoft"

            estado.contains("montada en vehiculo") ->
                "Montada en vehículo en Seintosoft"

            estado.contains("montada revisada") ->
                "Montada revisada en Seintosoft"

            estado.contains("desmontada") ->
                "Desmontada en Seintosoft"

            estado.contains("control de calidad") ->
                "En control de calidad en Seintosoft"

            estado.contains("situacion desconocida") ->
                "Situación desconocida en Seintosoft"

            estado.isBlank() ->
                "Revisar estado: sin estado en Seintosoft"

            else -> "Revisar estado: $estadoOriginal"
        }
    }

    private fun motivoExclusion(
        estadoOriginal: String,
        motivoOriginal: String
    ): String {
        val estado = normalizarTexto(estadoOriginal)
        val motivo = normalizarTexto(motivoOriginal)
        val combinado = "$estado $motivo"

        return when {
            combinado.contains("reserv") -> "RESERVADA"
            combinado.contains("vendid") -> "VENDIDA"
            combinado.contains("desech") || combinado.contains("descart") -> "DESECHADA"
            combinado.contains("descatalog") -> "DESCATALOGADA"
            else -> ""
        }
    }

    private fun extraerCompatibilidadVehiculo(metadatos: String): String {
        if (metadatos.isBlank()) return ""
        val partes = metadatos.split('|')
        return partes.getOrNull(2)?.trim().orEmpty()
    }

    private fun calcularVistaPrevia(
        db: SQLiteDatabase,
        stats: ParseStats,
        modo: StockImportMode
    ): StockImportPreview {
        val stageCount = contar(db, "SELECT COUNT(*) FROM $stageTable")
        val nuevas = contar(
            db,
            """
            SELECT COUNT(*)
            FROM $stageTable s
            LEFT JOIN stock_items i ON i.codigo = s.codigo
            WHERE s.import_excluded = 0 AND i.codigo IS NULL
            """.trimIndent()
        )

        val condicionesDiferencia = columnasComparables.joinToString(" OR ") { columna ->
            "COALESCE(i.$columna, '') <> COALESCE(s.$columna, '')"
        }

        val actualizadas = contar(
            db,
            """
            SELECT COUNT(*)
            FROM $stageTable s
            INNER JOIN stock_items i ON i.codigo = s.codigo
            WHERE ($condicionesDiferencia)
            """.trimIndent()
        )

        val existentesActivos = contar(
            db,
            """
            SELECT COUNT(*)
            FROM $stageTable s
            INNER JOIN stock_items i ON i.codigo = s.codigo
            WHERE s.import_excluded = 0
            """.trimIndent()
        )

        val actualizadasActivas = contar(
            db,
            """
            SELECT COUNT(*)
            FROM $stageTable s
            INNER JOIN stock_items i ON i.codigo = s.codigo
            WHERE s.import_excluded = 0
              AND ($condicionesDiferencia)
            """.trimIndent()
        )

        val reservadas = contar(
            db,
            "SELECT COUNT(*) FROM $stageTable WHERE import_excluded = 1 AND exclusion_reason = 'RESERVADA'"
        )
        val otrasExcluidas = contar(
            db,
            "SELECT COUNT(*) FROM $stageTable WHERE import_excluded = 1 AND exclusion_reason <> 'RESERVADA'"
        )

        val ausentes = if (modo == StockImportMode.COMPLETO) {
            contar(
                db,
                """
                SELECT COUNT(*)
                FROM stock_items i
                WHERE i.disponibilidad NOT IN (
                    'Ausente de la última importación',
                    'Excluida en Seintosoft',
                    'Reservada en Seintosoft',
                    'Vendida en Seintosoft',
                    'Desechada en Seintosoft',
                    'Descatalogada en Seintosoft'
                )
                AND NOT EXISTS (
                    SELECT 1 FROM $stageTable s WHERE s.codigo = i.codigo
                )
                """.trimIndent()
            )
        } else {
            0
        }

        return StockImportPreview(
            filasLeidas = stats.filasLeidas,
            codigosUnicos = stageCount,
            nuevas = nuevas,
            actualizadas = actualizadas,
            sinCambios = (existentesActivos - actualizadasActivas).coerceAtLeast(0),
            reservadasExcluidas = reservadas,
            otrasExcluidas = otrasExcluidas,
            ausentesDeLaNuevaExportacion = ausentes,
            codigosDuplicadosEnCsv = stats.codigosDuplicados,
            filasInvalidas = stats.filasInvalidas,
            modo = modo
        )
    }

    private fun aplicarReferencias(db: SQLiteDatabase, stockId: Long, codigo: String) {
        actualizarReferencias(db, stockId, codigo)
    }

    private fun actualizarReferencias(
        db: SQLiteDatabase,
        stockId: Long,
        codigo: String
    ) {
        db.delete(
            "stock_references",
            "stock_id = ?",
            arrayOf(stockId.toString())
        )

        db.rawQuery(
            """
            SELECT tipo, referencia_original, referencia_normalizada,
                   prioridad, es_segmento
            FROM $stageRefsTable
            WHERE codigo = ?
            """.trimIndent(),
            arrayOf(codigo)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues().apply {
                    put("stock_id", stockId)
                    put("tipo", cursor.getString(0) ?: "")
                    put("referencia_original", cursor.getString(1) ?: "")
                    put("referencia_normalizada", cursor.getString(2) ?: "")
                    put("prioridad", cursor.getInt(3))
                    put("es_segmento", cursor.getInt(4))
                }
                db.insertWithOnConflict(
                    "stock_references",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
        }
    }

    private fun valuesDesdeCursor(
        cursor: Cursor,
        columnas: List<String>
    ): ContentValues {
        val values = ContentValues()

        for (columna in columnas) {
            val indice = cursor.getColumnIndexOrThrow(columna)
            when (cursor.getType(indice)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(columna)
                Cursor.FIELD_TYPE_INTEGER -> values.put(columna, cursor.getLong(indice))
                Cursor.FIELD_TYPE_FLOAT -> values.put(columna, cursor.getDouble(indice))
                Cursor.FIELD_TYPE_BLOB -> values.put(columna, cursor.getBlob(indice))
                else -> values.put(columna, cursor.getString(indice) ?: "")
            }
        }

        return values
    }

    private fun buscarIdPorCodigo(db: SQLiteDatabase, codigo: String): Long? {
        return db.rawQuery(
            "SELECT id FROM stock_items WHERE codigo = ? LIMIT 1",
            arrayOf(codigo)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun existeCodigoEnStage(db: SQLiteDatabase, codigo: String): Boolean {
        return db.rawQuery(
            "SELECT 1 FROM $stageTable WHERE codigo = ? LIMIT 1",
            arrayOf(codigo)
        ).use { it.moveToFirst() }
    }

    private fun leerModoStage(db: SQLiteDatabase): StockImportMode {
        val nombre = db.rawQuery(
            "SELECT modo FROM $stageMetaTable LIMIT 1",
            null
        ).use { cursor ->
            check(cursor.moveToFirst()) {
                "No se encontró el modo de importación. Vuelve a analizar el CSV."
            }
            cursor.getString(0)
        }

        return runCatching {
            StockImportMode.valueOf(nombre)
        }.getOrElse {
            throw IllegalStateException(
                "El modo de importación no es válido. Vuelve a analizar el CSV."
            )
        }
    }

    private fun comprobarStageExiste(db: SQLiteDatabase) {
        val existe = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(stageTable)
        ).use { it.moveToFirst() }

        check(existe) {
            "No hay una vista previa preparada. Selecciona primero un CSV."
        }

        val existeModo = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(stageMetaTable)
        ).use { it.moveToFirst() }

        check(existeModo) {
            "La vista previa no tiene modo de importación. Vuelve a seleccionar el CSV."
        }
    }

    private fun guardarMetadato(
        db: SQLiteDatabase,
        clave: String,
        valor: String
    ) {
        val values = ContentValues().apply {
            put("clave", clave)
            put("valor", valor)
        }
        db.insertWithOnConflict(
            "stock_meta",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun contar(db: SQLiteDatabase, sql: String): Int {
        return db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun fechaHoraActual(): String = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        Locale.getDefault()
    ).format(Date())

    private fun parseInt(valor: String): Int? {
        val numero = parseNumero(valor) ?: return null
        if (!numero.isFinite()) return null
        return numero.toInt()
    }

    private fun parseNumero(valorOriginal: String): Double? {
        var valor = valorOriginal.trim()
            .replace("€", "")
            .replace(" ", "")

        if (valor.isBlank() || valor == "-") return null

        if (valor.contains(',') && valor.contains('.')) {
            // Decide por el último separador si la cifra es española o inglesa.
            valor = if (valor.lastIndexOf(',') > valor.lastIndexOf('.')) {
                valor.replace(".", "").replace(',', '.')
            } else {
                valor.replace(",", "")
            }
        } else if (valor.contains(',')) {
            valor = valor.replace(',', '.')
        }

        return valor.toDoubleOrNull()
    }

    private fun campo(
        fila: List<String>,
        headers: Map<String, Int>,
        vararg nombres: String
    ): String {
        for (nombre in nombres) {
            val indice = headers[nombre]
            if (indice != null && indice in fila.indices) {
                return fila[indice].trim()
            }
        }
        return ""
    }

    private fun normalizarCabecera(valor: String): String {
        val limpio = valor.trim()
            .removePrefix("\uFEFF")
            .removePrefix("ï»¿")
        return normalizarTexto(limpio).filter { it.isLetterOrDigit() }
    }

    private fun normalizarTexto(valor: String): String {
        return Normalizer.normalize(
            valor.trim().lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        )
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun normalizarReferencia(valor: String): String =
        valor.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Parser CSV delimitado por ; que soporta comillas y saltos de línea dentro de campos. */
    private fun leerRegistrosCsv(
        reader: PushbackReader,
        recibir: (List<String>) -> Unit
    ) {
        var campos = mutableListOf<String>()
        val campo = StringBuilder()
        var entreComillas = false

        while (true) {
            val entero = reader.read()
            if (entero == -1) break
            val caracter = entero.toChar()

            when {
                caracter == '"' && entreComillas -> {
                    val siguiente = reader.read()
                    if (siguiente == '"'.code) {
                        campo.append('"')
                    } else {
                        entreComillas = false
                        if (siguiente != -1) reader.unread(siguiente)
                    }
                }

                caracter == '"' && !entreComillas && campo.isEmpty() -> {
                    entreComillas = true
                }

                caracter == ';' && !entreComillas -> {
                    campos.add(campo.toString())
                    campo.setLength(0)
                }

                (caracter == '\n' || caracter == '\r') && !entreComillas -> {
                    if (caracter == '\r') {
                        val siguiente = reader.read()
                        if (siguiente != '\n'.code && siguiente != -1) {
                            reader.unread(siguiente)
                        }
                    }

                    campos.add(campo.toString())
                    campo.setLength(0)

                    if (campos.any { it.isNotBlank() }) {
                        recibir(campos.toList())
                    }
                    campos = mutableListOf()
                }

                else -> campo.append(caracter)
            }
        }

        if (entreComillas) {
            throw IOException("El CSV termina con un campo entrecomillado sin cerrar.")
        }

        if (campo.isNotEmpty() || campos.isNotEmpty()) {
            campos.add(campo.toString())
            if (campos.any { it.isNotBlank() }) recibir(campos.toList())
        }
    }

    private fun crearTablaStageSql(): String =
        stockColumns.joinToString(",")
}
