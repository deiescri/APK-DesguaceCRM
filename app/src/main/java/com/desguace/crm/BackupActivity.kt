
package com.desguace.crm

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class BackupActivity : AppCompatActivity() {

    private lateinit var backupManager: BackupManager
    private val executor = Executors.newSingleThreadExecutor()

    private val crearArchivo = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) crearCopia(uri)
    }

    private val seleccionarArchivo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) confirmarRestauracion(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        UiMotion.enterScreen(findViewById<android.view.View>(android.R.id.content))

        backupManager = BackupManager(this)

        findViewById<Button>(R.id.btnCrearBackup)
            .setOnClickListener {
                val fecha = SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.US
                ).format(Date())

                crearArchivo.launch("DesguaceCRM_$fecha.zip")
            }

        findViewById<Button>(R.id.btnRestaurarBackup)
            .setOnClickListener {
                seleccionarArchivo.launch(arrayOf("*/*"))
            }

        actualizarTextoServidor()

        findViewById<Button>(R.id.btnConfigurarServidor)?.setOnClickListener {
            mostrarDialogoConfigurarServidor()
        }
    }

    private fun actualizarTextoServidor() {
        val hostActual = ServerConfig.getServerHost(this)
        findViewById<TextView>(R.id.txtHostServidorActual)?.text = "Servidor PC actual: $hostActual"
    }

    private fun mostrarDialogoConfigurarServidor() {
        val hostActual = ServerConfig.getServerHost(this)

        val hostInput = android.widget.EditText(this).apply {
            setText(hostActual)
            hint = "Ej. 192.168.1.41:3000"
            setSingleLine(true)
            setSelection(text.length)
            setPadding(40, 24, 40, 24)
        }

        val tokenInput = android.widget.EditText(this).apply {
            hint = "Token privado de 64 caracteres"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod =
                android.text.method.PasswordTransformationMethod.getInstance()
            setSingleLine(true)
            setPadding(40, 24, 40, 24)
        }

        val contenido = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 8, 40, 0)

            addView(android.widget.TextView(this@BackupActivity).apply {
                text = "Dirección del servidor"
            })

            addView(
                hostInput,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(android.widget.TextView(this@BackupActivity).apply {
                text = "Token de emparejamiento"
            })

            addView(
                tokenInput,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Configurar Servidor PC")
            .setMessage(
                "Introduce la dirección del servidor y pega el token privado generado en el PC. " +
                    "Si ya guardaste el token anteriormente, puedes dejar ese campo vacío."
            )
            .setView(contenido)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar y conectar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nuevoHost = hostInput.text.toString().trim()
                val nuevoToken = tokenInput.text.toString().trim()

                if (nuevoHost.isBlank()) {
                    Toast.makeText(
                        this,
                        "Introduce la dirección del servidor.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                if (nuevoToken.isNotBlank() &&
                    !ServerConfig.setAndroidToken(this, nuevoToken)
                ) {
                    Toast.makeText(
                        this,
                        "Token no válido. Debe tener 64 caracteres hexadecimales.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                if (ServerConfig.getAndroidToken(this).isBlank()) {
                    Toast.makeText(
                        this,
                        "Debes introducir el token privado generado en el PC.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                ServerConfig.setServerHost(this, nuevoHost)
                actualizarTextoServidor()

                CallActionWebSocket.reconectar(this)

                Toast.makeText(
                    this,
                    "Servidor y emparejamiento guardados.",
                    Toast.LENGTH_SHORT
                ).show()

                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun actualizarEstado(texto: String) {
        if (!isFinishing && !isDestroyed) {
            findViewById<TextView>(R.id.txtBackupEstado).text = texto
        }
    }

    private fun bloquearBotones(bloquear: Boolean) {
        if (isFinishing || isDestroyed) return

        findViewById<Button>(R.id.btnCrearBackup).isEnabled = !bloquear
        findViewById<Button>(R.id.btnRestaurarBackup).isEnabled = !bloquear
    }

    private fun crearCopia(uri: Uri) {
        bloquearBotones(true)
        actualizarEstado("Creando copia de seguridad…")

        executor.execute {
            try {
                val salida = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException(
                        "No se pudo abrir el archivo de destino."
                    )

                salida.use {
                    backupManager.createBackup(it)
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    bloquearBotones(false)
                    actualizarEstado(
                        "Copia creada correctamente. " +
                                "Comprueba que el archivo ZIP se ha guardado."
                    )

                    Toast.makeText(
                        this,
                        "Copia de seguridad completada",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                android.util.Log.e(
                    "DesguaceCRM",
                    "Error creando la copia de seguridad",
                    e
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    bloquearBotones(false)
                    actualizarEstado("No se pudo crear la copia.")

                    AlertDialog.Builder(this)
                        .setTitle("Error al crear la copia")
                        .setMessage(
                            e.message ?: "No se pudo guardar el archivo ZIP."
                        )
                        .setPositiveButton("Aceptar", null)
                        .show()
                }
            }
        }
    }

    private fun confirmarRestauracion(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("¿Restaurar esta copia?")
            .setMessage(
                "La restauración sustituirá los datos locales incluidos " +
                        "en la copia. Antes de hacerlo, DesguaceCRM intentará " +
                        "crear una copia de recuperación de los datos actuales.\n\n" +
                        "Cierra las demás pantallas de la aplicación y no " +
                        "interrumpas el proceso."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Validar y restaurar") { _, _ ->
                restaurarCopia(uri)
            }
            .show()
    }

    private fun restaurarCopia(uri: Uri) {
        bloquearBotones(true)
        actualizarEstado("Validando archivo y restaurando datos…")

        executor.execute {
            try {
                val entrada = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(
                        "No se pudo abrir el archivo seleccionado."
                    )

                val resultado = entrada.use {
                    backupManager.restoreBackup(it)
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    bloquearBotones(false)
                    actualizarEstado(
                        "Restauración completada. " +
                                "Cierra y vuelve a abrir la aplicación."
                    )

                    AlertDialog.Builder(this)
                        .setTitle("Restauración completada")
                        .setMessage(
                            "Bases de datos restauradas: " +
                                    "${resultado.databasesRestored}\n" +
                                    "Llamadas recuperadas: " +
                                    "${resultado.callsRestored}\n\n" +
                                    "Se creó una copia de recuperación antes " +
                                    "de aplicar los cambios.\n\n" +
                                    "Cierra DesguaceCRM por completo y vuelve " +
                                    "a abrirla para recargar los datos."
                        )
                        .setPositiveButton("Aceptar", null)
                        .show()
                }

            } catch (e: Exception) {
                android.util.Log.e(
                    "DesguaceCRM",
                    "Error restaurando la copia",
                    e
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    bloquearBotones(false)
                    actualizarEstado("No se pudo restaurar la copia.")

                    AlertDialog.Builder(this)
                        .setTitle("Restauración fallida")
                        .setMessage(
                            (e.message
                                ?: "El archivo no es válido.") +
                                    "\n\nNo continúes trabajando hasta comprobar " +
                                    "que los datos actuales siguen disponibles."
                        )
                        .setPositiveButton("Aceptar", null)
                        .show()
                }
            }
        }
    }

    override fun onDestroy() {
        // No interrumpimos deliberadamente una operación de archivos
        // por un cambio de configuración o cierre de la pantalla.
        executor.shutdown()
        super.onDestroy()
    }
}