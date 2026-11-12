package com.desguace.crm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Caché local de los inventarios importados desde el CRM web. No sustituye los catálogos base ni sus tablas de reservas. */
data class CachedYardVehicle(
    val id: String,
    val matricula: String,
    val codigo: String,
    val bastidor: String,
    val marca: String,
    val modelo: String,
    val fechaBaja: String,
    val fechaRecepcion: String,
    val estados: String,
    val almacen: String,
    val busqueda: String,
    val payload: String,
    val updatedAt: String
)

data class CachedPcStockItem(
    val id: String,
    val codigo: String,
    val nombre: String,
    val oem: String,
    val vehiculo: String,
    val ubicacion: String,
    val precio: String,
    val cantidad: String,
    val descripcion: String,
    val searchText: String,
    val payload: String,
    val updatedAt: String
)

class WebInventoryCacheDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "web_inventory_cache.db"
        private const val DATABASE_VERSION = 1
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS yard_vehicles (
                id TEXT PRIMARY KEY NOT NULL,
                matricula TEXT NOT NULL DEFAULT '',
                codigo TEXT NOT NULL DEFAULT '',
                bastidor TEXT NOT NULL DEFAULT '',
                marca TEXT NOT NULL DEFAULT '',
                modelo TEXT NOT NULL DEFAULT '',
                fecha_baja TEXT NOT NULL DEFAULT '',
                fecha_recepcion TEXT NOT NULL DEFAULT '',
                estados TEXT NOT NULL DEFAULT '',
                almacen TEXT NOT NULL DEFAULT '',
                busqueda TEXT NOT NULL DEFAULT '',
                payload TEXT NOT NULL DEFAULT '{}',
                updated_at TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_web_yard_matricula ON yard_vehicles(matricula)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_web_yard_codigo ON yard_vehicles(codigo)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_web_yard_bastidor ON yard_vehicles(bastidor)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_web_yard_baja ON yard_vehicles(fecha_baja)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS pc_stock_imports (
                id TEXT PRIMARY KEY NOT NULL,
                codigo TEXT NOT NULL DEFAULT '',
                nombre TEXT NOT NULL DEFAULT '',
                oem TEXT NOT NULL DEFAULT '',
                vehiculo TEXT NOT NULL DEFAULT '',
                ubicacion TEXT NOT NULL DEFAULT '',
                precio TEXT NOT NULL DEFAULT '',
                cantidad TEXT NOT NULL DEFAULT '',
                descripcion TEXT NOT NULL DEFAULT '',
                search_text TEXT NOT NULL DEFAULT '',
                payload TEXT NOT NULL DEFAULT '{}',
                updated_at TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pc_stock_codigo ON pc_stock_imports(codigo)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pc_stock_oem ON pc_stock_imports(oem)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pc_stock_updated ON pc_stock_imports(updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }
    // CRM_COMPANY_CACHE_CLEAR_V1
    /** Borra Ãºnicamente inventarios importados desde el PC; conserva las bases/catÃ¡logos nativos de Android. */
    fun limpiarInventarioImportado() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("yard_vehicles", null, null)
            db.delete("pc_stock_imports", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun normalizar(texto: String): String = Normalizer
        .normalize(texto, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun text(item: JSONObject, key: String): String {
        if (!item.has(key) || item.isNull(key)) return ""
        return item.optString(key, "").trim()
    }

    fun upsertYardVehicles(items: JSONArray): Int {
        val db = writableDatabase
        var saved = 0
        db.beginTransaction()
        try {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = text(item, "id")
                if (id.isBlank()) continue
                val matricula = text(item, "matricula")
                val codigo = text(item, "codigo")
                val bastidor = text(item, "bastidor")
                val marca = text(item, "marca")
                val modelo = text(item, "modelo")
                val baja = text(item, "fecha_baja").ifBlank { text(item, "fechaBaja") }
                val recepcion = text(item, "fecha_recepcion").ifBlank { text(item, "fechaRecepcion") }
                val estados = text(item, "estados")
                val almacen = text(item, "almacen")
                val payload = text(item, "payload").ifBlank { "{}" }
                val busqueda = text(item, "busqueda").ifBlank {
                    normalizar(listOf(matricula, codigo, bastidor, marca, modelo, baja, recepcion, estados, almacen, payload).joinToString(" "))
                }
                val values = ContentValues().apply {
                    put("id", id)
                    put("matricula", matricula)
                    put("codigo", codigo)
                    put("bastidor", bastidor)
                    put("marca", marca)
                    put("modelo", modelo)
                    put("fecha_baja", baja)
                    put("fecha_recepcion", recepcion)
                    put("estados", estados)
                    put("almacen", almacen)
                    put("busqueda", busqueda)
                    put("payload", payload)
                    put("updated_at", text(item, "updated_at"))
                }
                db.insertWithOnConflict("yard_vehicles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                saved++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return saved
    }

    fun upsertPcStock(items: JSONArray): Int {
        val db = writableDatabase
        var saved = 0
        db.beginTransaction()
        try {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = text(item, "id")
                if (id.isBlank()) continue
                val codigo = text(item, "codigo")
                val nombre = text(item, "nombre")
                val oem = text(item, "oem")
                val vehiculo = text(item, "vehiculo")
                val ubicacion = text(item, "ubicacion")
                val precio = text(item, "precio")
                val cantidad = text(item, "cantidad")
                val descripcion = text(item, "descripcion").ifBlank { nombre }
                val payload = text(item, "payload").ifBlank { "{}" }
                val search = text(item, "search_text").ifBlank {
                    normalizar(listOf(id, codigo, nombre, oem, vehiculo, ubicacion, descripcion, precio, cantidad, payload).joinToString(" "))
                }
                val values = ContentValues().apply {
                    put("id", id)
                    put("codigo", codigo)
                    put("nombre", nombre)
                    put("oem", oem)
                    put("vehiculo", vehiculo)
                    put("ubicacion", ubicacion)
                    put("precio", precio)
                    put("cantidad", cantidad)
                    put("descripcion", descripcion)
                    put("search_text", search)
                    put("payload", payload)
                    put("updated_at", text(item, "updated_at"))
                }
                db.insertWithOnConflict("pc_stock_imports", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                saved++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return saved
    }

    fun contarVehiculos(): Int = contar("yard_vehicles")
    fun contarPiezasImportadas(): Int = contar("pc_stock_imports")

    private fun contar(tabla: String): Int {
        require(tabla == "yard_vehicles" || tabla == "pc_stock_imports")
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM $tabla", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun buscarVehiculos(consulta: String, limite: Int = 40): List<CachedYardVehicle> {
        val tokens = normalizar(consulta).split(" ").filter { it.isNotBlank() }.take(8)
        val where = if (tokens.isEmpty()) "" else "WHERE " + tokens.joinToString(" AND ") { "busqueda LIKE ?" }
        val args = tokens.map { "%$it%" }.toTypedArray()
        val max = limite.coerceIn(1, 100)
        val orden = if (tokens.isEmpty()) "ORDER BY updated_at DESC, id ASC" else
            "ORDER BY CASE WHEN lower(matricula) = ? THEN 0 WHEN lower(codigo) = ? THEN 1 ELSE 2 END, updated_at DESC, id ASC"
        val finalArgs = if (tokens.isEmpty()) args else args + arrayOf(tokens.joinToString(" "), tokens.joinToString(" "))
        return readableDatabase.rawQuery(
            "SELECT id, matricula, codigo, bastidor, marca, modelo, fecha_baja, fecha_recepcion, estados, almacen, busqueda, payload, updated_at FROM yard_vehicles $where $orden LIMIT $max",
            finalArgs
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CachedYardVehicle(
                            id = cursor.getString(0) ?: "",
                            matricula = cursor.getString(1) ?: "",
                            codigo = cursor.getString(2) ?: "",
                            bastidor = cursor.getString(3) ?: "",
                            marca = cursor.getString(4) ?: "",
                            modelo = cursor.getString(5) ?: "",
                            fechaBaja = cursor.getString(6) ?: "",
                            fechaRecepcion = cursor.getString(7) ?: "",
                            estados = cursor.getString(8) ?: "",
                            almacen = cursor.getString(9) ?: "",
                            busqueda = cursor.getString(10) ?: "",
                            payload = cursor.getString(11) ?: "{}",
                            updatedAt = cursor.getString(12) ?: ""
                        )
                    )
                }
            }
        }
    }

    fun buscarPiezasImportadas(consulta: String, limite: Int = 30): List<CachedPcStockItem> {
        val tokens = normalizar(consulta).split(" ").filter { it.isNotBlank() }.take(8)
        if (tokens.isEmpty()) return emptyList()
        val where = tokens.joinToString(" AND ") { "search_text LIKE ?" }
        val args = tokens.map { "%$it%" }.toTypedArray()
        return readableDatabase.rawQuery(
            "SELECT id, codigo, nombre, oem, vehiculo, ubicacion, precio, cantidad, descripcion, search_text, payload, updated_at FROM pc_stock_imports WHERE $where ORDER BY codigo, id LIMIT ${limite.coerceIn(1, 50)}",
            args
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CachedPcStockItem(
                            id = cursor.getString(0) ?: "",
                            codigo = cursor.getString(1) ?: "",
                            nombre = cursor.getString(2) ?: "",
                            oem = cursor.getString(3) ?: "",
                            vehiculo = cursor.getString(4) ?: "",
                            ubicacion = cursor.getString(5) ?: "",
                            precio = cursor.getString(6) ?: "",
                            cantidad = cursor.getString(7) ?: "",
                            descripcion = cursor.getString(8) ?: "",
                            searchText = cursor.getString(9) ?: "",
                            payload = cursor.getString(10) ?: "{}",
                            updatedAt = cursor.getString(11) ?: ""
                        )
                    )
                }
            }
        }
    }
}
