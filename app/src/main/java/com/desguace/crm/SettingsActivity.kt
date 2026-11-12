package com.desguace.crm

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val selectorGaleriaFondo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            WallpaperManager.guardarImagenPersonalizada(this, uri)
            WallpaperManager.aplicarFondo(this, findViewById(R.id.imgFondoLiquidGlass))
            Toast.makeText(this, "Fondo de pantalla personalizado guardado", Toast.LENGTH_SHORT).show()
        }
    }

    private val selectorCsvVehiculos = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importarCatalogoVehiculos(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val imgFondo = findViewById<ImageView>(R.id.imgFondoLiquidGlass)
        WallpaperManager.aplicarFondo(this, imgFondo)

        findViewById<Button>(R.id.btnElegirFondoGaleria)?.setOnClickListener {
            selectorGaleriaFondo.launch("image/*")
        }

        findViewById<Button>(R.id.btnFondoPredeterminado)?.setOnClickListener {
            WallpaperManager.guardarPreset(this, "bmw")
            WallpaperManager.aplicarFondo(this, imgFondo)
            Toast.makeText(this, "Fondo predeterminado restablecido", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnImportarVehiculosCsv)?.setOnClickListener {
            selectorCsvVehiculos.launch(arrayOf("*/*"))
        }

        findViewById<Button>(R.id.btnImportarStockCsv)?.setOnClickListener {
            startActivity(Intent(this, StockSearchActivity::class.java))
        }

        findViewById<Button>(R.id.btnExportarBackup)?.setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        findViewById<Button>(R.id.btnImportarBackup)?.setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        findViewById<Button>(R.id.btnConfigurarIpServidor)?.setOnClickListener {
            mostrarDialogoConfiguracionServer()
        }
    }

    override fun onResume() {
        super.onResume()
        WallpaperManager.aplicarFondo(this, findViewById(R.id.imgFondoLiquidGlass))
    }

    private fun mostrarDialogoConfiguracionServer() {
        val hostActual = ServerConfig.getServerHost(this)
        val input = android.widget.EditText(this).apply {
            setText(hostActual)
            setSelection(hostActual.length)
            hint = "Ej. 192.168.1.50:3000"
            setPadding(32, 32, 32, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Configurar Servidor PC")
            .setMessage("Introduce la dirección IP o Host del servidor PC:")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val nuevoHost = input.text.toString().trim()
                if (nuevoHost.isNotBlank()) {
                    ServerConfig.setServerHost(this, nuevoHost)
                    Toast.makeText(this, "Servidor actualizado: $nuevoHost", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun importarCatalogoVehiculos(uri: Uri) {
        Toast.makeText(this, "Procesando catálogo de vehículos...", Toast.LENGTH_SHORT).show()
        bgExecutor.execute {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    runOnUiThread {
                        Toast.makeText(this, "No se pudo abrir el archivo seleccionado", Toast.LENGTH_LONG).show()
                    }
                    return@execute
                }

                val catalogo = VehicleCatalogDatabase(this)
                val resultado = inputStream.use { stream ->
                    catalogo.importarCsvVehiculos(stream)
                }

                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Importación completada")
                        .setMessage(
                            "Filas leídas: ${resultado.filasLeidas}\n" +
                                    "Variantes añadidas: ${resultado.anadidas}\n" +
                                    "Ya existían: ${resultado.yaExistian}\n" +
                                    "Inválidas: ${resultado.invalidas}"
                        )
                        .setPositiveButton("Aceptar", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Error en la importación")
                        .setMessage("Ocurrió un error al importar el catálogo:\n${e.message ?: "Error desconocido"}")
                        .setPositiveButton("Aceptar", null)
                        .show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bgExecutor.shutdown()
    }
}