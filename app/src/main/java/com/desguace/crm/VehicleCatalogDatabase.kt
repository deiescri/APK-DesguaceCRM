
package com.desguace.crm

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.InputStream
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale


data class VehicleVariant(
    val id: Long,
    val marca: String,
    val modelo: String,
    val motor: String,
    val codigosMotor: String,
    val combustible: String,
    val puertas: String,
    val potenciaCv: String,
    val potenciaKw: String,
    val anioDesde: String,
    val anioHasta: String,
    val fRecepcion: String = "",
    val fBaja: String = "",
    val matricula: String = "",
    val bastidor: String = "",
    val proveedor: String = ""
) {
    fun etiqueta(): String {
        val detalles = mutableListOf<String>()

        if (motor.isNotBlank()) detalles.add(motor)
        if (combustible.isNotBlank()) detalles.add(combustible)
        if (potenciaCv.isNotBlank()) detalles.add("$potenciaCv CV")
        if (puertas.isNotBlank()) detalles.add("${puertas}p")

        val periodo = when {
            anioDesde.isNotBlank() && anioHasta.isNotBlank() ->
                "$anioDesde–$anioHasta"

            anioDesde.isNotBlank() ->
                "Desde $anioDesde"

            anioHasta.isNotBlank() ->
                "Hasta $anioHasta"

            else -> ""
        }

        if (periodo.isNotBlank()) detalles.add(periodo)

        return detalles.joinToString(" · ")
    }

    override fun toString(): String {
        val detalles = etiqueta()

        return listOf(marca, modelo, detalles)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }
}

enum class VehicleSaveResult {
    ADDED,
    ALREADY_EXISTS,
    INVALID_DATA
}

data class VehicleCsvImportResult(
    val filasLeidas: Int,
    val anadidas: Int,
    val yaExistian: Int,
    val invalidas: Int
)
class VehicleCatalogDatabase(context: Context) {

    private val appContext = context.applicationContext

    private val databaseFile: File
        get() = appContext.getDatabasePath(
            "catalogo_vehiculos_desguace_v3.db"
        )

    private fun normalizar(texto: String): String {
        return Normalizer.normalize(
            texto.trim().lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        ).replace(Regex("\\p{Mn}+"), "")
    }

    private fun prepararBaseDeDatos() {
        val destino = databaseFile

        if (destino.exists() && destino.length() > 0L) {
            return
        }

        destino.parentFile?.mkdirs()

        val temporal = File(
            destino.parentFile,
            "${destino.name}.tmp"
        )

        try {
            appContext.assets.open(
                "catalogo_vehiculos_desguace_v3.db"
            ).use { entrada ->
                FileOutputStream(temporal).use { salida ->
                    entrada.copyTo(salida)
                    salida.fd.sync()
                }
            }

            if (destino.exists()) {
                destino.delete()
            }

            if (!temporal.renameTo(destino)) {
                throw IllegalStateException(
                    "No se pudo preparar el catálogo de vehículos"
                )
            }
        } catch (e: Exception) {
            temporal.delete()
            throw IllegalStateException(
                "No se pudo copiar el catálogo desde assets",
                e
            )
        }
    }

    private fun abrirBaseDeDatos(): SQLiteDatabase {
        prepararBaseDeDatos()

        return SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }


    fun buscarMarcas(filtro: String = ""): List<String> {
        val consulta = "%${normalizar(filtro)}%"

        fun consultar(tabla: String): List<String> {
            return abrirTablaVehiculos(tabla).use { db ->
                db.rawQuery(
                    """
                SELECT DISTINCT marca
                FROM $tabla
                WHERE marca_busqueda LIKE ?
                """.trimIndent(),
                    arrayOf(consulta)
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0) ?: "")
                        }
                    }
                }
            }
        }

        return (
                consultar("vehiculos") +
                        consultar("vehiculos_aprendidos")
                )
            .filter { it.isNotBlank() }
            .distinctBy { normalizar(it) }
            .sortedBy { normalizar(it) }
    }


    fun buscarModelos(
        marca: String,
        filtro: String = ""
    ): List<String> {
        val marcaNormalizada = normalizar(marca)
        val consulta = normalizar(filtro)

        return abrirBaseDeDatos().use { db ->
            db.rawQuery(
                """
                SELECT DISTINCT modelo
                FROM vehiculos
                WHERE marca_busqueda = ?
                  AND modelo_busqueda LIKE ?
                ORDER BY modelo_busqueda
                """.trimIndent(),
                arrayOf(
                    marcaNormalizada,
                    "%$consulta%"
                )
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }
        }
    }



    fun buscarVariantesCombinadas(
        marca: String = "",
        textoBusqueda: String,
        limite: Int = 50
    ): List<VehicleVariant> {
        val marcaNormalizada = normalizar(marca)
        val maxResultados = limite.coerceIn(1, 100)

        val tokens = normalizar(textoBusqueda)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) {
            val recientementes = try {
                consultarVariantes(
                    "vehiculos_aprendidos",
                    if (marcaNormalizada.isNotBlank()) "marca_busqueda = '$marcaNormalizada' AND f_baja != ''" else "f_baja != ''",
                    emptyList(),
                    maxResultados
                )
            } catch (e: Exception) {
                emptyList()
            }
            if (recientementes.isNotEmpty()) {
                return recientementes.sortedByDescending { claveOrdenFechaBaja(it.fBaja) }.take(maxResultados)
            }
        }

        val condAprendidos = mutableListOf<String>()
        val argAprendidos = mutableListOf<String>()

        if (marcaNormalizada.isNotBlank()) {
            condAprendidos.add("marca_busqueda = ?")
            argAprendidos.add(marcaNormalizada)
        }

        for (token in tokens) {
            condAprendidos.add(
                """
            (
                marca_busqueda LIKE ?
                OR modelo_busqueda LIKE ?
                OR motor_busqueda LIKE ?
                OR lower(codigos_motor) LIKE ?
                OR lower(combustible) LIKE ?
                OR lower(f_recepcion) LIKE ?
                OR lower(matricula) LIKE ?
                OR lower(bastidor) LIKE ?
            )
            """.trimIndent()
            )
            repeat(8) { argAprendidos.add("%$token%") }
        }

        val condOriginales = mutableListOf<String>()
        val argOriginales = mutableListOf<String>()

        if (marcaNormalizada.isNotBlank()) {
            condOriginales.add("marca_busqueda = ?")
            argOriginales.add(marcaNormalizada)
        }

        for (token in tokens) {
            condOriginales.add(
                """
            (
                marca_busqueda LIKE ?
                OR modelo_busqueda LIKE ?
                OR motor_busqueda LIKE ?
                OR lower(codigos_motor) LIKE ?
                OR lower(combustible) LIKE ?
            )
            """.trimIndent()
            )
            repeat(5) { argOriginales.add("%$token%") }
        }

        val aprendidas = try {
            consultarVariantes("vehiculos_aprendidos", condAprendidos.joinToString(" AND "), argAprendidos, maxResultados)
        } catch (e: Exception) {
            emptyList()
        }

        val originales = try {
            consultarVariantes("vehiculos", condOriginales.joinToString(" AND "), argOriginales, maxResultados)
        } catch (e: Exception) {
            emptyList()
        }

        return combinarVariantes(aprendidas, originales, maxResultados)
    }

    fun buscarVariantes(
        marca: String,
        modelo: String,
        filtroMotor: String = ""
    ): List<VehicleVariant> {

        val marcaNormalizada = normalizar(marca)
        val motorNormalizado = normalizar(filtroMotor)

        return abrirBaseDeDatos().use { db ->
            db.rawQuery(
                """
            SELECT
                id,
                marca,
                modelo,
                motor,
                codigos_motor,
                combustible,
                puertas,
                potencia_cv,
                potencia_kw,
                anio_desde,
                anio_hasta
            FROM vehiculos
            WHERE marca_busqueda = ?
              AND modelo = ?
              AND motor_busqueda LIKE ?
            ORDER BY motor_busqueda, potencia_cv, anio_desde
            """.trimIndent(),
                arrayOf(
                    marcaNormalizada,
                    modelo,
                    "%$motorNormalizado%"
                )
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            VehicleVariant(
                                id = cursor.getLong(0),
                                marca = cursor.getString(1),
                                modelo = cursor.getString(2),
                                motor = cursor.getString(3),
                                codigosMotor = cursor.getString(4),
                                combustible = cursor.getString(5),
                                puertas = cursor.getString(6),
                                potenciaCv = cursor.getString(7),
                                potenciaKw = cursor.getString(8),
                                anioDesde = cursor.getString(9),
                                anioHasta = cursor.getString(10)
                            )
                        )
                    }
                }
            }
        }
    }


    private fun abrirCatalogoAprendido(): SQLiteDatabase {
        val archivo = appContext.getDatabasePath(
            "vehiculos_aprendidos.db"
        )

        archivo.parentFile?.mkdirs()

        val db = SQLiteDatabase.openOrCreateDatabase(
            archivo,
            null
        )

        db.execSQL(
            """
        CREATE TABLE IF NOT EXISTS vehiculos_aprendidos (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            marca TEXT NOT NULL,
            modelo TEXT NOT NULL,
            motor TEXT NOT NULL DEFAULT '',
            codigos_motor TEXT NOT NULL DEFAULT '',
            combustible TEXT NOT NULL DEFAULT '',
            puertas TEXT NOT NULL DEFAULT '',
            potencia_cv TEXT NOT NULL DEFAULT '',
            potencia_kw TEXT NOT NULL DEFAULT '',
            anio_desde TEXT NOT NULL DEFAULT '',
            anio_hasta TEXT NOT NULL DEFAULT '',
            f_recepcion TEXT NOT NULL DEFAULT '',
            f_baja TEXT NOT NULL DEFAULT '',
            matricula TEXT NOT NULL DEFAULT '',
            bastidor TEXT NOT NULL DEFAULT '',
            proveedor TEXT NOT NULL DEFAULT '',
            marca_busqueda TEXT NOT NULL,
            modelo_busqueda TEXT NOT NULL,
            motor_busqueda TEXT NOT NULL
        )
        """.trimIndent()
        )

        try { db.execSQL("ALTER TABLE vehiculos_aprendidos ADD COLUMN f_recepcion TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE vehiculos_aprendidos ADD COLUMN f_baja TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE vehiculos_aprendidos ADD COLUMN matricula TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE vehiculos_aprendidos ADD COLUMN bastidor TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        try { db.execSQL("ALTER TABLE vehiculos_aprendidos ADD COLUMN proveedor TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}

        try { db.execSQL("DROP INDEX IF EXISTS ux_variante_aprendida") } catch (_: Exception) {}

        db.execSQL(
            """
        CREATE INDEX IF NOT EXISTS idx_variante_aprendida
        ON vehiculos_aprendidos (
            marca_busqueda,
            modelo_busqueda,
            motor_busqueda
        )
        """.trimIndent()
        )

        db.execSQL(
            """
        CREATE INDEX IF NOT EXISTS idx_aprendidos_marca
        ON vehiculos_aprendidos(marca_busqueda)
        """.trimIndent()
        )

        db.execSQL(
            """
        CREATE INDEX IF NOT EXISTS idx_aprendidos_modelo
        ON vehiculos_aprendidos(marca_busqueda, modelo_busqueda)
        """.trimIndent()
        )

        return db
    }

    private fun existeVariante(
        db: SQLiteDatabase,
        tabla: String,
        vehiculo: VehicleVariant
    ): Boolean {
        // El nombre de tabla solo procede de nuestras consultas internas.
        require(
            tabla == "vehiculos" ||
                    tabla == "vehiculos_aprendidos"
        )

        return db.rawQuery(
            """
        SELECT 1
        FROM $tabla
        WHERE marca_busqueda = ?
          AND modelo_busqueda = ?
          AND motor_busqueda = ?
          AND lower(trim(combustible)) = lower(trim(?))
          AND trim(puertas) = trim(?)
          AND trim(potencia_cv) = trim(?)
          AND trim(potencia_kw) = trim(?)
          AND trim(anio_desde) = trim(?)
          AND trim(anio_hasta) = trim(?)
        LIMIT 1
        """.trimIndent(),
            arrayOf(
                normalizar(vehiculo.marca),
                normalizar(vehiculo.modelo),
                normalizar(vehiculo.motor),
                vehiculo.combustible.trim(),
                vehiculo.puertas.trim(),
                vehiculo.potenciaCv.trim(),
                vehiculo.potenciaKw.trim(),
                vehiculo.anioDesde.trim(),
                vehiculo.anioHasta.trim()
            )
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    /**
     * Guarda una variante nueva en la base local editable.
     * El catálogo original se mantiene en solo lectura.
     */
    fun guardarVehiculoSiNoExiste(
        vehiculo: VehicleVariant
    ): VehicleSaveResult {

        if (
            vehiculo.marca.isBlank() ||
            vehiculo.modelo.isBlank()
        ) {
            return VehicleSaveResult.INVALID_DATA
        }

        // Primero comprobamos el catálogo original.
        abrirBaseDeDatos().use { db ->
            if (existeVariante(db, "vehiculos", vehiculo)) {
                return VehicleSaveResult.ALREADY_EXISTS
            }
        }

        abrirCatalogoAprendido().use { db ->
            db.beginTransaction()

            try {
                // Segunda comprobación, en la base modificable.
                if (
                    existeVariante(
                        db,
                        "vehiculos_aprendidos",
                        vehiculo
                    )
                ) {
                    return VehicleSaveResult.ALREADY_EXISTS
                }

                val valores = ContentValues().apply {
                    put("marca", vehiculo.marca.trim())
                    put("modelo", vehiculo.modelo.trim())
                    put("motor", vehiculo.motor.trim())
                    put("codigos_motor", vehiculo.codigosMotor.trim())
                    put("combustible", vehiculo.combustible.trim())
                    put("puertas", vehiculo.puertas.trim())
                    put("potencia_cv", vehiculo.potenciaCv.trim())
                    put("potencia_kw", vehiculo.potenciaKw.trim())
                    put("anio_desde", vehiculo.anioDesde.trim())
                    put("anio_hasta", vehiculo.anioHasta.trim())
                    put("marca_busqueda", normalizar(vehiculo.marca))
                    put("modelo_busqueda", normalizar(vehiculo.modelo))
                    put("motor_busqueda", normalizar(vehiculo.motor))
                }

                val id = db.insertWithOnConflict(
                    "vehiculos_aprendidos",
                    null,
                    valores,
                    SQLiteDatabase.CONFLICT_IGNORE
                )

                if (id == -1L) {
                    return VehicleSaveResult.ALREADY_EXISTS
                }

                db.setTransactionSuccessful()
                return VehicleSaveResult.ADDED

            } finally {
                db.endTransaction()
            }
        }
    }


    private fun abrirTablaVehiculos(tabla: String): SQLiteDatabase {
        return when (tabla) {
            "vehiculos" -> abrirBaseDeDatos()
            "vehiculos_aprendidos" -> abrirCatalogoAprendido()
            else -> error("Tabla de vehículos no permitida")
        }
    }

    private fun consultarVariantes(
        tabla: String,
        condiciones: String,
        argumentos: List<String>,
        limite: Int = 100
    ): List<VehicleVariant> {
        require(
            tabla == "vehiculos" ||
                    tabla == "vehiculos_aprendidos"
        )

        val argumentosConsulta =
            argumentos + limite.coerceIn(1, 200).toString()

        val tieneColsExtra = tabla == "vehiculos_aprendidos"
        val sqlQuery = if (tieneColsExtra) {
            """
            SELECT id, marca, modelo, motor, codigos_motor,
                   combustible, puertas, potencia_cv, potencia_kw,
                   anio_desde, anio_hasta, f_recepcion, f_baja, matricula, bastidor, proveedor
            FROM $tabla
            WHERE $condiciones
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent()
        } else {
            """
            SELECT id, marca, modelo, motor, codigos_motor,
                   combustible, puertas, potencia_cv, potencia_kw,
                   anio_desde, anio_hasta
            FROM $tabla
            WHERE $condiciones
            ORDER BY modelo_busqueda, motor_busqueda,
                     potencia_cv, anio_desde
            LIMIT ?
            """.trimIndent()
        }

        return abrirTablaVehiculos(tabla).use { db ->
            db.rawQuery(
                sqlQuery,
                argumentosConsulta.toTypedArray()
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            VehicleVariant(
                                id = cursor.getLong(0),
                                marca = cursor.getString(1) ?: "",
                                modelo = cursor.getString(2) ?: "",
                                motor = cursor.getString(3) ?: "",
                                codigosMotor = cursor.getString(4) ?: "",
                                combustible = cursor.getString(5) ?: "",
                                puertas = cursor.getString(6) ?: "",
                                potenciaCv = cursor.getString(7) ?: "",
                                potenciaKw = cursor.getString(8) ?: "",
                                anioDesde = cursor.getString(9) ?: "",
                                anioHasta = cursor.getString(10) ?: "",
                                fRecepcion = if (tieneColsExtra && cursor.columnCount > 11) cursor.getString(11) ?: "" else "",
                                fBaja = if (tieneColsExtra && cursor.columnCount > 12) cursor.getString(12) ?: "" else "",
                                matricula = if (tieneColsExtra && cursor.columnCount > 13) cursor.getString(13) ?: "" else "",
                                bastidor = if (tieneColsExtra && cursor.columnCount > 14) cursor.getString(14) ?: "" else "",
                                proveedor = if (tieneColsExtra && cursor.columnCount > 15) cursor.getString(15) ?: "" else ""
                            )
                        )
                    }
                }
            }
        }
    }

    private fun claveOrdenFechaBaja(fBaja: String): String {
        val limpia = fBaja.trim()
        if (limpia.isBlank()) return "00000000"

        if (limpia.contains("/")) {
            val partes = limpia.split("/")
            if (partes.size >= 3) {
                val dia = partes[0].padStart(2, '0')
                val mes = partes[1].padStart(2, '0')
                val anio = partes[2].take(4).padStart(4, '0')
                return "$anio$mes$dia"
            }
        }

        if (limpia.contains("-")) {
            val partes = limpia.split("-")
            if (partes.size >= 3) {
                val anio = partes[0].take(4).padStart(4, '0')
                val mes = partes[1].padStart(2, '0')
                val dia = partes[2].take(2).padStart(2, '0')
                return "$anio$mes$dia"
            }
        }

        return limpia
    }

    private fun combinarVariantes(
        originales: List<VehicleVariant>,
        aprendidas: List<VehicleVariant>,
        limite: Int = 100
    ): List<VehicleVariant> {
        val unicas = LinkedHashMap<String, VehicleVariant>()

        for (vehiculo in aprendidas + originales) {
            val clave = listOf(
                normalizar(vehiculo.marca),
                normalizar(vehiculo.modelo),
                normalizar(vehiculo.motor),
                normalizar(vehiculo.combustible),
                vehiculo.puertas.trim(),
                vehiculo.potenciaCv.trim(),
                vehiculo.potenciaKw.trim(),
                vehiculo.anioDesde.trim(),
                vehiculo.anioHasta.trim()
            ).joinToString("|")

            val existente = unicas[clave]
            if (existente == null) {
                unicas[clave] = vehiculo
            } else if (existente.fBaja.isBlank() && vehiculo.fBaja.isNotBlank()) {
                unicas[clave] = vehiculo
            } else if (existente.fRecepcion.isBlank() && vehiculo.fRecepcion.isNotBlank()) {
                unicas[clave] = vehiculo
            }
        }

        val filtradasConBaja = unicas.values.filter { it.fBaja.isNotBlank() }

        val resultadoFinal = if (filtradasConBaja.isNotEmpty()) {
            filtradasConBaja.sortedByDescending { claveOrdenFechaBaja(it.fBaja) }
        } else {
            unicas.values.toList()
        }

        return resultadoFinal.take(limite)
    }


    private fun separarLineaCsvVehiculos(linea: String): List<String> {
        val campos = mutableListOf<String>()
        val campo = StringBuilder()
        var entreComillas = false
        var i = 0

        while (i < linea.length) {
            val caracter = linea[i]

            when {
                caracter == '"' &&
                        entreComillas &&
                        i + 1 < linea.length &&
                        linea[i + 1] == '"' -> {
                    campo.append('"')
                    i++
                }

                caracter == '"' -> {
                    entreComillas = !entreComillas
                }

                caracter == ';' && !entreComillas -> {
                    campos.add(campo.toString())
                    campo.setLength(0)
                }

                else -> campo.append(caracter)
            }

            i++
        }

        campos.add(campo.toString())
        return campos
    }

    private fun huellaVarianteVehiculo(
        marca: String,
        modelo: String,
        motor: String,
        combustible: String,
        puertas: String,
        potenciaCv: String,
        potenciaKw: String,
        anioDesde: String,
        anioHasta: String
    ): String {
        return listOf(
            normalizar(marca),
            normalizar(modelo),
            normalizar(motor),
            normalizar(combustible),
            puertas.trim(),
            potenciaCv.trim(),
            potenciaKw.trim(),
            anioDesde.trim(),
            anioHasta.trim()
        ).joinToString("\u001F")
    }

    fun importarCsvVehiculos(
        inputStream: InputStream
    ): VehicleCsvImportResult {

        val lineas = inputStream.bufferedReader(Charsets.UTF_8).use {
            it.readLines()
        }

        require(lineas.isNotEmpty()) {
            "El archivo CSV está vacío."
        }

        val primeraLinea = lineas.first().removePrefix("\uFEFF")
        val separador = when {
            primeraLinea.contains(";") -> ';'
            primeraLinea.contains("\t") -> '\t'
            else -> ','
        }

        fun separarLinea(linea: String): List<String> {
            val campos = mutableListOf<String>()
            val campo = StringBuilder()
            var entreComillas = false
            var i = 0

            while (i < linea.length) {
                val caracter = linea[i]
                when {
                    caracter == '"' && entreComillas && i + 1 < linea.length && linea[i + 1] == '"' -> {
                        campo.append('"')
                        i++
                    }
                    caracter == '"' -> {
                        entreComillas = !entreComillas
                    }
                    caracter == separador && !entreComillas -> {
                        campos.add(campo.toString())
                        campo.setLength(0)
                    }
                    else -> campo.append(caracter)
                }
                i++
            }
            campos.add(campo.toString())
            return campos
        }

        val encabezados = separarLinea(primeraLinea).map {
            normalizar(it)
        }

        val indices = encabezados.withIndex().associate { it.value to it.index }

        fun buscarIndice(vararg posiblesNombres: String): Int? {
            for (nombre in posiblesNombres) {
                val nomNorm = normalizar(nombre)
                indices[nomNorm]?.let { return it }
                indices.keys.firstOrNull { it.contains(nomNorm) || nomNorm.contains(it) }?.let { return indices[it] }
            }
            return null
        }

        val idxMarca = buscarIndice("marca")
        val idxModelo = buscarIndice("modelo", "descripción", "descripcion")
        val idxMotor = buscarIndice("tipo motor", "motor", "tipo_motor", "codigos_motor", "cód. motor")
        val idxCombustible = buscarIndice("combustible")
        val idxPuertas = buscarIndice("puertas", "puerta")
        val idxPotenciaCv = buscarIndice("potencia_cv", "potencia", "cv")
        val idxPotenciaKw = buscarIndice("potencia_kw", "kw")
        val idxAnioDesde = buscarIndice("anio_desde", "año_desde", "f.creación", "f.creacion")
        val idxAnioHasta = buscarIndice("anio_hasta", "año_hasta", "hasta")
        val idxFRecepcion = buscarIndice("f.recepción/compra", "f.recepcion/compra", "f.recepción", "f.recepcion", "recepción/compra", "recepcion/compra", "recepcion", "compra")
        val idxFBaja = buscarIndice("f.baja", "f. baja", "fbaja", "baja")
        val idxMatricula = buscarIndice("matrícula", "matricula")
        val idxBastidor = buscarIndice("bastidor", "vin")
        val idxProveedor = buscarIndice("proveedor")

        fun valorIndice(campos: List<String>, idx: Int?): String {
            if (idx == null) return ""
            return campos.getOrNull(idx)?.trim().orEmpty()
        }

        val regexAnios = Regex("""\((\d{4})\s*[-–—]\s*(\d{4})\)""")
        val regexCv = Regex("""(\d+)\s*CV\b""", RegexOption.IGNORE_CASE)
        val regexKw = Regex("""(\d+)\s*KW\b""", RegexOption.IGNORE_CASE)
        val regexPuertas = Regex("""(\d+)\s*P\b""", RegexOption.IGNORE_CASE)

        val huellasOriginales = abrirBaseDeDatos().use { db ->
            db.rawQuery(
                """
            SELECT marca, modelo, motor, combustible, puertas,
                   potencia_cv, potencia_kw, anio_desde, anio_hasta
            FROM vehiculos
            """.trimIndent(),
                null
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(
                            huellaVarianteVehiculo(
                                cursor.getString(0) ?: "",
                                cursor.getString(1) ?: "",
                                cursor.getString(2) ?: "",
                                cursor.getString(3) ?: "",
                                cursor.getString(4) ?: "",
                                cursor.getString(5) ?: "",
                                cursor.getString(6) ?: "",
                                cursor.getString(7) ?: "",
                                cursor.getString(8) ?: ""
                            )
                        )
                    }
                }
            }
        }

        val db = abrirCatalogoAprendido()

        var filasLeidas = 0
        var anadidas = 0
        var yaExistian = 0
        var invalidas = 0

        try {
            db.beginTransaction()

            val huellasAprendidas = db.rawQuery(
                """
            SELECT marca, modelo, motor, combustible, puertas,
                   potencia_cv, potencia_kw, anio_desde, anio_hasta
            FROM vehiculos_aprendidos
            """.trimIndent(),
                null
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(
                            huellaVarianteVehiculo(
                                cursor.getString(0) ?: "",
                                cursor.getString(1) ?: "",
                                cursor.getString(2) ?: "",
                                cursor.getString(3) ?: "",
                                cursor.getString(4) ?: "",
                                cursor.getString(5) ?: "",
                                cursor.getString(6) ?: "",
                                cursor.getString(7) ?: "",
                                cursor.getString(8) ?: ""
                            )
                        )
                    }
                }.toMutableSet()
            }

            for (linea in lineas.drop(1)) {
                if (linea.isBlank()) continue

                val campos = separarLinea(linea)

                var marca = valorIndice(campos, idxMarca)
                var modelo = valorIndice(campos, idxModelo)

                if (marca.isBlank() && modelo.isBlank()) {
                    continue
                }

                filasLeidas++

                var motor = valorIndice(campos, idxMotor)
                var codigos = motor
                var combustible = valorIndice(campos, idxCombustible)
                var puertas = valorIndice(campos, idxPuertas)
                var cv = valorIndice(campos, idxPotenciaCv)
                var kw = valorIndice(campos, idxPotenciaKw)
                var desde = valorIndice(campos, idxAnioDesde)
                var hasta = valorIndice(campos, idxAnioHasta)
                var fRecepcion = valorIndice(campos, idxFRecepcion)
                var fBaja = valorIndice(campos, idxFBaja)
                var matricula = valorIndice(campos, idxMatricula)
                var bastidor = valorIndice(campos, idxBastidor)
                var proveedor = valorIndice(campos, idxProveedor)

                if (desde.length >= 4 && desde.contains("/")) {
                    desde = desde.split("/").lastOrNull()?.take(4).orEmpty()
                }

                if (modelo.isNotBlank()) {
                    val matchAnios = regexAnios.find(modelo)
                    if (matchAnios != null) {
                        if (desde.isBlank()) desde = matchAnios.groupValues[1]
                        if (hasta.isBlank()) hasta = matchAnios.groupValues[2]
                    }

                    if (cv.isBlank()) {
                        val matchCv = regexCv.find(modelo)
                        if (matchCv != null) cv = matchCv.groupValues[1]
                    }

                    if (kw.isBlank()) {
                        val matchKw = regexKw.find(modelo)
                        if (matchKw != null) kw = matchKw.groupValues[1]
                    }

                    if (puertas.isBlank()) {
                        val matchPuertas = regexPuertas.find(modelo)
                        if (matchPuertas != null) puertas = matchPuertas.groupValues[1]
                    }
                }

                if (marca.isBlank() || modelo.isBlank()) {
                    invalidas++
                    continue
                }

                val huella = huellaVarianteVehiculo(
                    marca, modelo, motor, combustible,
                    puertas, cv, kw, desde, hasta
                )

                val valores = ContentValues().apply {
                    put("marca", marca)
                    put("modelo", modelo)
                    put("motor", motor)
                    put("codigos_motor", codigos)
                    put("combustible", combustible)
                    put("puertas", puertas)
                    put("potencia_cv", cv)
                    put("potencia_kw", kw)
                    put("anio_desde", desde)
                    put("anio_hasta", hasta)
                    put("f_recepcion", fRecepcion)
                    put("f_baja", fBaja)
                    put("matricula", matricula)
                    put("bastidor", bastidor)
                    put("proveedor", proveedor)
                    put("marca_busqueda", normalizar(marca))
                    put("modelo_busqueda", normalizar(modelo))
                    put("motor_busqueda", normalizar(motor))
                }

                try {
                    if (huella in huellasAprendidas) {
                        db.update(
                            "vehiculos_aprendidos",
                            valores,
                            "marca_busqueda = ? AND modelo_busqueda = ?",
                            arrayOf(normalizar(marca), normalizar(modelo))
                        )
                        yaExistian++
                        continue
                    }

                    val id = db.insertWithOnConflict(
                        "vehiculos_aprendidos",
                        null,
                        valores,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )

                    if (id == -1L) {
                        yaExistian++
                    } else {
                        anadidas++
                        huellasAprendidas.add(huella)
                    }
                } catch (e: Exception) {
                    try {
                        db.update(
                            "vehiculos_aprendidos",
                            valores,
                            "marca_busqueda = ? AND modelo_busqueda = ?",
                            arrayOf(normalizar(marca), normalizar(modelo))
                        )
                        yaExistian++
                    } catch (_: Exception) {
                        invalidas++
                    }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }

        return VehicleCsvImportResult(
            filasLeidas = filasLeidas,
            anadidas = anadidas,
            yaExistian = yaExistian,
            invalidas = invalidas
        )
    }


    /** Prepara el catálogo base y el catálogo de vehículos aprendidos para sincronizarlos al servidor. */
    fun archivosParaSincronizacion(): Map<String, File> {
        abrirBaseDeDatos().use { /* garantiza que el catálogo empaquetado exista */ }
        abrirCatalogoAprendido().use { /* garantiza que la base de vehículos aprendidos exista */ }
        return mapOf(
            "vehicles" to databaseFile,
            "learnedVehicles" to appContext.getDatabasePath("vehiculos_aprendidos.db")
        )
    }
}
