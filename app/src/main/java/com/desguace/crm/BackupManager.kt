package com.desguace.crm

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exporta y restaura los datos locales de DesguaceCRM en un ZIP validado.
 * La restauracion sustituye el conjunto local completo y conserva un ZIP
 * de recuperacion interna antes de aplicar cambios.
 */
data class BackupRestoreSummary(
    val databasesRestored: Int,
    val callsRestored: Int,
    val recoveryBackupPath: String
)

class BackupManager(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        private const val FORMAT_VERSION = 1
        private const val PREFERENCES_NAME = "desguace_crm"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val PREFERENCES_ENTRY = "preferences/desguace_crm.json"
        private const val MAX_TOTAL_BYTES = 600L * 1024L * 1024L
        private const val MAX_ENTRY_BYTES = 400L * 1024L * 1024L

        private val DATABASE_NAMES = listOf(
            "catalogo_vehiculos_desguace_v3.db",
            "vehiculos_aprendidos.db",
            "inventario_stock_desguace.db",
            "stock_operations.db",
            "web_inventory_cache.db"
        )
    }

    fun createBackup(destination: OutputStream) {
        DATABASE_NAMES.forEach { name ->
            val file = appContext.getDatabasePath(name)
            if (file.exists() && file.length() > 0L) {
                checkpointDatabase(file)
            }
        }

        val includedDatabases = DATABASE_NAMES.filter { name ->
            val file = appContext.getDatabasePath(name)
            file.isFile && file.length() > 0L
        }

        val manifest = JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("packageName", appContext.packageName)
            put("createdAtMillis", System.currentTimeMillis())
            put("databaseFiles", JSONArray(includedDatabases))
            put("preferencesEntry", PREFERENCES_ENTRY)
        }

        val preferencesJson = serializarPreferencias()

        ZipOutputStream(BufferedOutputStream(destination)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(PREFERENCES_ENTRY))
            zip.write(preferencesJson.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (name in includedDatabases) {
                val file = appContext.getDatabasePath(name)
                zip.putNextEntry(ZipEntry("databases/$name"))
                FileInputStream(file).use { input ->
                    input.copyTo(zip, 1024 * 1024)
                }
                zip.closeEntry()
            }
        }
    }

    /** Restaura únicamente ZIP de este formato y de este paquete. */
    fun restoreBackup(source: InputStream): BackupRestoreSummary {
        val stageRoot = File(
            appContext.cacheDir,
            "backup_stage_${UUID.randomUUID()}"
        )
        check(stageRoot.mkdirs()) { "No se pudo crear el espacio temporal." }

        var changesStarted = false
        val previousFiles = mutableMapOf<String, File?>()
        var previousPreferences: Map<String, *>? = null
        var recoveryFile: File? = null

        try {
            val extracted = extraerYValidar(source, stageRoot)
            val manifest = JSONObject(
                File(extracted.getValue(MANIFEST_ENTRY).absolutePath)
                    .readText(Charsets.UTF_8)
            )
            val preferencesJson = JSONObject(
                File(extracted.getValue(PREFERENCES_ENTRY).absolutePath)
                    .readText(Charsets.UTF_8)
            )

            val included = manifest.getJSONArray("databaseFiles")
                .let { array ->
                    (0 until array.length()).map { array.getString(it) }
                }

            // Preparar copia recuperable fuera del area temporal que limpiaremos.
            val recoveryDirectory = File(appContext.filesDir, "backup_recovery")
            check(recoveryDirectory.exists() || recoveryDirectory.mkdirs()) {
                "No se pudo crear la carpeta de recuperación."
            }
            val timestamp = SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date())
            recoveryFile = File(
                recoveryDirectory,
                "pre_restore_$timestamp.zip"
            )
            FileOutputStream(recoveryFile).use { output -> createBackup(output) }

            // Capturar estado anterior para revertir en caso de un fallo durante la restauración.
            previousPreferences = snapshotPreferences()
            val rollbackDirectory = File(stageRoot, "rollback")
            check(rollbackDirectory.mkdirs())

            for (name in DATABASE_NAMES) {
                val original = appContext.getDatabasePath(name)
                if (original.isFile) {
                    val copy = File(rollbackDirectory, name)
                    original.copyTo(copy, overwrite = true)
                    previousFiles[name] = copy
                } else {
                    previousFiles[name] = null
                }
            }

            changesStarted = true

            for (name in DATABASE_NAMES) {
                val staged = extracted["databases/$name"]
                val target = appContext.getDatabasePath(name)

                if (staged != null) {
                    replaceDatabase(staged, target)
                } else {
                    deleteDatabaseAndSidecars(target)
                }
            }

            restaurarPreferencias(preferencesJson)

            val callsCount = try {
                val callsEntry = preferencesJson.optJSONObject("llamadas")
                val callsString = if (callsEntry?.optString("type") == "String") {
                    callsEntry.optString("value", "[]")
                } else {
                    "[]"
                }
                JSONArray(callsString).length()
            } catch (_: Exception) {
                0
            }

            return BackupRestoreSummary(
                databasesRestored = included.size,
                callsRestored = callsCount,
                recoveryBackupPath = recoveryFile.absolutePath
            )
        } catch (e: Exception) {
            if (changesStarted) {
                try {
                    for (name in DATABASE_NAMES) {
                        val target = appContext.getDatabasePath(name)
                        val saved = previousFiles[name]
                        if (saved != null && saved.isFile) {
                            replaceDatabase(saved, target)
                        } else {
                            deleteDatabaseAndSidecars(target)
                        }
                    }
                    previousPreferences?.let { restaurarPreferenciasSnapshot(it) }
                } catch (rollbackError: Exception) {
                    e.addSuppressed(rollbackError)
                }
            }
            throw IllegalStateException(
                "No se pudo restaurar la copia. " +
                    "Se ha intentado conservar los datos anteriores.",
                e
            )
        } finally {
            runCatching { source.close() }
            stageRoot.deleteRecursively()
        }
    }

    private fun extraerYValidar(
        source: InputStream,
        stageRoot: File
    ): Map<String, File> {
        val allowedEntries = setOf(MANIFEST_ENTRY, PREFERENCES_ENTRY) +
            DATABASE_NAMES.map { "databases/$it" }
        val extracted = mutableMapOf<String, File>()
        var totalBytes = 0L

        ZipInputStream(BufferedInputStream(source)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                if (entry.name !in allowedEntries) {
                    throw IllegalArgumentException(
                        "El archivo de copia contiene una entrada no permitida: ${entry.name}"
                    )
                }
                if (extracted.containsKey(entry.name)) {
                    throw IllegalArgumentException(
                        "El archivo contiene una entrada duplicada: ${entry.name}"
                    )
                }

                val output = File(stageRoot, entry.name.replace('/', File.separatorChar))
                check(output.parentFile?.exists() == true || output.parentFile?.mkdirs() == true) {
                    "No se pudo preparar el archivo temporal."
                }

                var entryBytes = 0L
                FileOutputStream(output).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalBytes += count
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw IllegalArgumentException(
                                "La copia supera el tamaño máximo permitido."
                            )
                        }
                        out.write(buffer, 0, count)
                    }
                }

                extracted[entry.name] = output
                zip.closeEntry()
            }
        }

        val manifestFile = extracted[MANIFEST_ENTRY]
            ?: throw IllegalArgumentException("Falta el manifiesto de la copia.")
        if (extracted[PREFERENCES_ENTRY] == null) {
            throw IllegalArgumentException("Faltan las preferencias de la aplicación.")
        }

        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(manifest.optInt("formatVersion", -1) == FORMAT_VERSION) {
            "La versión del formato de copia no es compatible."
        }
        require(manifest.optString("packageName") == appContext.packageName) {
            "La copia no pertenece a esta aplicación."
        }
        require(manifest.optString("preferencesEntry") == PREFERENCES_ENTRY) {
            "El manifiesto de la copia no es válido."
        }

        val declared = manifest.optJSONArray("databaseFiles")
            ?: throw IllegalArgumentException("Falta la lista de bases de datos.")
        val declaredNames = (0 until declared.length()).map {
            declared.getString(it)
        }
        require(declaredNames.distinct().size == declaredNames.size) {
            "El manifiesto contiene bases de datos repetidas."
        }
        require(declaredNames.all { it in DATABASE_NAMES }) {
            "La copia incluye una base de datos desconocida."
        }

        val actualNames = extracted.keys
            .filter { it.startsWith("databases/") }
            .map { it.removePrefix("databases/") }
        require(actualNames.toSet() == declaredNames.toSet()) {
            "Las bases de datos del archivo no coinciden con el manifiesto."
        }

        for (name in declaredNames) {
            validarBaseSQLite(extracted.getValue("databases/$name"))
        }

        // Verifica que las preferencias se puedan interpretar antes de modificar nada.
        validarPreferencias(JSONObject(
            extracted.getValue(PREFERENCES_ENTRY).readText(Charsets.UTF_8)
        ))

        return extracted
    }

    private fun serializarPreferencias(): JSONObject {
        val all = appContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).all
        val result = JSONObject()

        all.forEach { (key, value) ->
            val encoded = JSONObject()
            when (value) {
                is String -> {
                    encoded.put("type", "String")
                    encoded.put("value", value)
                }
                is Boolean -> {
                    encoded.put("type", "Boolean")
                    encoded.put("value", value)
                }
                is Int -> {
                    encoded.put("type", "Int")
                    encoded.put("value", value)
                }
                is Long -> {
                    encoded.put("type", "Long")
                    encoded.put("value", value)
                }
                is Float -> {
                    encoded.put("type", "Float")
                    encoded.put("value", value.toDouble())
                }
                is Set<*> -> {
                    encoded.put("type", "StringSet")
                    val array = JSONArray()
                    value.filterIsInstance<String>().sorted().forEach { array.put(it) }
                    encoded.put("value", array)
                }
                null -> {
                    encoded.put("type", "String")
                    encoded.put("value", "")
                }
                else -> throw IllegalStateException(
                    "Tipo de preferencia no compatible para la clave $key"
                )
            }
            result.put(key, encoded)
        }
        return result
    }

    private fun validarPreferencias(json: JSONObject) {
        val iterator = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val entry = json.optJSONObject(key)
                ?: throw IllegalArgumentException("Preferencia inválida: $key")
            when (entry.optString("type")) {
                "String", "Boolean", "Int", "Long", "Float", "StringSet" -> Unit
                else -> throw IllegalArgumentException(
                    "Tipo de preferencia no compatible en $key"
                )
            }
        }
    }

    private fun restaurarPreferencias(json: JSONObject) {
        validarPreferencias(json)
        val editor = appContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear()

        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = json.getJSONObject(key)
            val value = entry.opt("value")
            when (entry.getString("type")) {
                "String" -> editor.putString(key, entry.optString("value", ""))
                "Boolean" -> editor.putBoolean(key, entry.optBoolean("value"))
                "Int" -> editor.putInt(key, entry.optInt("value"))
                "Long" -> editor.putLong(key, entry.optLong("value"))
                "Float" -> editor.putFloat(key, entry.optDouble("value").toFloat())
                "StringSet" -> {
                    val array = value as? JSONArray ?: JSONArray()
                    val set = HashSet<String>()
                    for (i in 0 until array.length()) set.add(array.getString(i))
                    editor.putStringSet(key, set)
                }
            }
        }

        check(editor.commit()) { "No se pudieron restaurar las preferencias." }
    }

    private fun snapshotPreferences(): Map<String, *> {
        val all = appContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).all
        return all.mapValues { (_, value) ->
            if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
        }
    }

    private fun restaurarPreferenciasSnapshot(snapshot: Map<String, *>) {
        val editor = appContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear()

        snapshot.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(
                    key,
                    value.filterIsInstance<String>().toSet()
                )
            }
        }
        check(editor.commit()) { "No se pudieron recuperar las preferencias anteriores." }
    }

    private fun checkpointDatabase(file: File) {
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(0) != 0) {
                    throw IllegalStateException(
                        "No se pudo consolidar la base ${file.name} antes de la copia."
                    )
                }
            }
        }
    }

    private fun validarBaseSQLite(file: File) {
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", true)) {
                    "La base ${file.name} no supera la comprobación de integridad."
                }
            }
        }
    }

    private fun replaceDatabase(source: File, target: File) {
        validarBaseSQLite(source)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.restore_tmp")
        temporary.delete()
        source.copyTo(temporary, overwrite = true)
        validarBaseSQLite(temporary)
        deleteDatabaseAndSidecars(target)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IllegalStateException("No se pudo sustituir ${target.name}.")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IllegalStateException("No se pudo instalar ${target.name}.")
        }
    }

    private fun deleteDatabaseAndSidecars(file: File) {
        listOf(
            file,
            File(file.absolutePath + "-wal"),
            File(file.absolutePath + "-shm"),
            File(file.absolutePath + "-journal")
        ).forEach { candidate ->
            if (candidate.exists() && !candidate.delete()) {
                throw IllegalStateException(
                    "No se pudo retirar el archivo ${candidate.name}."
                )
            }
        }
    }
}
