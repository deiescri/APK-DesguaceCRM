
package com.desguace.crm

import org.json.JSONObject

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

class StockSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PICK_MODE = "stock_pick_mode"
        const val EXTRA_CALL_ID = "stock_call_id"
        const val EXTRA_CALL_CLIENTE = "stock_call_cliente"

        const val EXTRA_INITIAL_QUERY = "stock_initial_query"

        const val EXTRA_FALLBACK_QUERY = "stock_fallback_query"

        const val RESULT_STOCK_ID = "stock_result_id"
        const val RESULT_CODIGO = "stock_result_codigo"
        const val RESULT_DESCRIPCION = "stock_result_descripcion"
        const val RESULT_OEM = "stock_result_oem"
        const val RESULT_VEHICULO = "stock_result_vehiculo"
        const val RESULT_UBICACION = "stock_result_ubicacion"
        const val RESULT_PRECIO = "stock_result_precio"
    }

    private lateinit var stockDb: StockOperationsDatabase
    private lateinit var pcInventoryDb: WebInventoryCacheDatabase

    private var modoImportacionSeleccionado =
        StockImportMode.COMPLETO

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var importManager: StockCsvImportManager

    private val csvPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            previsualizarImportacion(uri)
        }
    }

    private var mostrandoReservas = false
    private var solicitudActual = 0

    private var consultaInicial = ""

    private var consultaAlternativa = ""

    private val modoSeleccion: Boolean
        get() = intent.getBooleanExtra(EXTRA_PICK_MODE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_search)
        UiMotion.enterScreen(findViewById<android.view.View>(android.R.id.content))

        stockDb = StockOperationsDatabase(this)
        pcInventoryDb = WebInventoryCacheDatabase(this)

        importManager = StockCsvImportManager(this)

        findViewById<Button>(R.id.btnImportarInventario)
            .setOnClickListener {
                seleccionarModoImportacion()
            }

        val entrada = findViewById<EditText>(R.id.etBuscarStock)

        consultaInicial = intent
            .getStringExtra(EXTRA_INITIAL_QUERY)
            .orEmpty()
            .trim()

        consultaAlternativa = intent
            .getStringExtra(EXTRA_FALLBACK_QUERY)
            .orEmpty()
            .trim()

        if (consultaInicial.isNotBlank()) {
            entrada.setText(consultaInicial)
        }

        val botonBuscar = findViewById<Button>(R.id.btnBuscarStock)
        val botonVista = findViewById<Button>(
            R.id.btnCambiarVistaStock
        )

        if (modoSeleccion) {
            botonVista.visibility = android.view.View.GONE
        }

        botonBuscar.setOnClickListener {
            if (mostrandoReservas) {
                mostrarBuscador()
            } else {
                buscarPiezas()
            }
        }

        botonVista.setOnClickListener {
            if (mostrandoReservas) {
                mostrarBuscador()
            } else {
                cargarReservas()
            }
        }

        entrada.setOnEditorActionListener { _, accion, _ ->
            if (
                accion == EditorInfo.IME_ACTION_SEARCH ||
                accion == EditorInfo.IME_ACTION_DONE
            ) {
                if (mostrandoReservas) {
                    mostrarBuscador()
                } else {
                    buscarPiezas()
                }
                true
            } else {
                false
            }
        }

        if (modoSeleccion) {
            findViewById<TextView>(R.id.txtStockEstado).text =
                "Busca una pieza para incorporarla a esta llamada."
            if (consultaInicial.isNotBlank()) {
                entrada.post {
                    buscarPiezas()
                }
            }
        }
    }


    private fun previsualizarImportacion(uri: Uri) {
        findViewById<TextView>(R.id.txtStockEstado).text =
            "Analizando CSV y preparando la vista previa…"

        findViewById<Button>(R.id.btnImportarInventario).isEnabled = false

        executor.execute {
            try {
                val vista = importManager.prepararVistaPrevia(
                    uri,
                    modoImportacionSeleccionado
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    findViewById<Button>(R.id.btnImportarInventario)
                        .isEnabled = true

                    mostrarVistaPrevia(vista)
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "DesguaceCRM",
                    "Error preparando la importación",
                    e
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    findViewById<Button>(R.id.btnImportarInventario)
                        .isEnabled = true

                    findViewById<TextView>(R.id.txtStockEstado).text =
                        "No se pudo preparar la importación."

                    Toast.makeText(
                        this,
                        "Error al leer el CSV: ${e.message ?: "archivo no válido"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun mostrarVistaPrevia(
        vista: StockImportPreview
    ) {
        val nombreModo = when (vista.modo) {
            StockImportMode.COMPLETO ->
                "Inventario completo"

            StockImportMode.PARCIAL ->
                "Archivo parcial"
        }

        val mensaje = """
    MODO: $nombreModo

    Filas leídas: ${vista.filasLeidas}
    Códigos únicos: ${vista.codigosUnicos}

    PIEZAS
    Nuevas: ${vista.nuevas}
    Actualizadas: ${vista.actualizadas}
    Sin cambios: ${vista.sinCambios}

    EXCLUIDAS
    Reservadas en Seintosoft: ${vista.reservadasExcluidas}
    Vendidas o desechadas: ${vista.otrasExcluidas}

    Ausentes de la nueva exportación:
    ${vista.ausentesDeLaNuevaExportacion}

    Códigos duplicados: ${vista.codigosDuplicadosEnCsv}
    Filas inválidas: ${vista.filasInvalidas}
""".trimIndent()

        val dialogo = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Revisar actualización de stock")
            .setMessage(mensaje)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar actualización", null)
            .create()

        dialogo.setOnShowListener {
            dialogo.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE
            ).setOnClickListener {
                dialogo.dismiss()
                cancelarImportacion()
            }

            dialogo.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {
                dialogo.dismiss()
                confirmarImportacion()
            }
        }

        dialogo.setOnCancelListener {
            cancelarImportacion()
        }

        dialogo.show()
    }

    private fun cancelarImportacion() {
        executor.execute {
            try {
                importManager.cancelarVistaPrevia()
            } catch (e: Exception) {
                android.util.Log.e(
                    "DesguaceCRM",
                    "Error cancelando la vista previa",
                    e
                )
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                findViewById<TextView>(R.id.txtStockEstado).text =
                    "Importación cancelada. El inventario no se ha actualizado."
            }
        }
    }

    private fun confirmarImportacion() {
        findViewById<TextView>(R.id.txtStockEstado).text =
            "Actualizando inventario…"

        findViewById<Button>(R.id.btnImportarInventario).isEnabled = false

        executor.execute {
            try {
                val resultado = importManager.confirmarImportacion()

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    findViewById<Button>(R.id.btnImportarInventario)
                        .isEnabled = true

                    findViewById<TextView>(R.id.txtStockEstado).text =
                        "Inventario actualizado correctamente."

                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Importación completada")
                        .setMessage(
                            """
                        Piezas nuevas: ${resultado.agregadas}
                        Piezas actualizadas: ${resultado.actualizadas}
                        Registros excluidos: ${resultado.excluidas}
                        Marcadas como ausentes: ${resultado.marcadasAusentes}
                        """.trimIndent()
                        )
                        .setPositiveButton("Aceptar") { _, _ ->
                            val consulta = findViewById<EditText>(
                                R.id.etBuscarStock
                            ).text.toString().trim()

                            if (consulta.isNotBlank()) {
                                buscarPiezas()
                            } else {
                                findViewById<TextView>(
                                    R.id.txtStockResultados
                                ).text = "Inventario actualizado"
                            }
                        }
                        .show()
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "DesguaceCRM",
                    "Error confirmando la importación",
                    e
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    findViewById<Button>(R.id.btnImportarInventario)
                        .isEnabled = true

                    findViewById<TextView>(R.id.txtStockEstado).text =
                        "No se pudo actualizar el inventario."

                    Toast.makeText(
                        this,
                        "Error al importar: ${e.message ?: "operación fallida"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    private fun mostrarBuscador() {
        mostrandoReservas = false

        findViewById<Button>(R.id.btnCambiarVistaStock).text =
            "Ver reservas"

        buscarPiezas()
    }


    private fun buscarPiezas() {
        val consulta = findViewById<EditText>(
            R.id.etBuscarStock
        ).text.toString().trim()

        if (consulta.isBlank()) {
            mostrarResultados(
                emptyList(),
                "Introduce un ID, OEM o descripción."
            )
            return
        }

        val solicitud = ++solicitudActual
        findViewById<TextView>(R.id.txtStockEstado).text =
            "Buscando en el inventario sincronizado…"

        executor.execute {
            try {
                var consultaUsada = consulta
                var resultados = stockDb.buscarPiezas(
                    consulta = consulta,
                    limite = 50
                )

                // Si la búsqueda inicial por OEM no encuentra nada,
                // probamos con el nombre de la pieza y el vehículo.
                val permitirAlternativa =
                    consultaInicial.isNotBlank() &&
                        consulta.equals(consultaInicial, ignoreCase = true) &&
                        consultaAlternativa.isNotBlank() &&
                        !consultaAlternativa.equals(consulta, ignoreCase = true)

                if (resultados.isEmpty() && permitirAlternativa) {
                    val alternativas = stockDb.buscarPiezas(
                        consulta = consultaAlternativa,
                        limite = 50
                    )
                    if (alternativas.isNotEmpty()) {
                        resultados = alternativas
                        consultaUsada = consultaAlternativa
                    }
                }

                // El CSV importado desde la web tiene IDs de texto y no comparte
                // la tabla local de operaciones; se muestra como consulta, no como reservable.
                val codigosLocales = resultados.map { normalizarCodigoLocal(it.codigo) }
                    .filter { it.isNotBlank() }
                    .toSet()
                val importadasPc = if (modoSeleccion) emptyList() else {
                    pcInventoryDb.buscarPiezasImportadas(consultaUsada, 30)
                        .filter { it.codigo.isBlank() || normalizarCodigoLocal(it.codigo) !in codigosLocales }
                }

                val mensaje = if (consultaUsada != consulta) {
                    "Sin coincidencias para el OEM. Resultados alternativos: $consultaUsada"
                } else {
                    "Resultados para: $consulta"
                }

                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (solicitud != solicitudActual) return@post
                    mostrarResultados(
                        resultados.map { it to null },
                        mensaje,
                        importadasPc
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("DesguaceCRM", "Error buscando piezas", e)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    if (solicitud != solicitudActual) return@post
                    mostrarResultados(emptyList(), "No se pudo consultar el inventario.")
                    Toast.makeText(this, "Error al buscar piezas", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun normalizarCodigoLocal(value: String): String =
        value.trim().uppercase(Locale.ROOT)


    private fun cargarReservas() {
        mostrandoReservas = true
        solicitudActual++

        findViewById<Button>(R.id.btnCambiarVistaStock).text =
            "Buscar piezas"

        findViewById<TextView>(R.id.txtStockEstado).text =
            "Cargando reservas…"

        executor.execute {
            try {
                val reservas = stockDb.listarReservas()

                val resultados = reservas.mapNotNull { reserva ->
                    val pieza = stockDb.buscarPorId(reserva.stockId)
                    pieza?.let { it to reserva }
                }

                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post

                    mostrarResultados(
                        resultados,
                        "Piezas reservadas en DesguaceCRM"
                    )
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post

                    Toast.makeText(
                        this,
                        "No se pudieron cargar las reservas: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun mostrarResultados(
        resultados: List<Pair<StockItem, StockOperation?>>,
        mensaje: String,
        importadasPc: List<CachedPcStockItem> = emptyList()
    ) {
        findViewById<TextView>(R.id.txtStockEstado).text = mensaje
        val total = resultados.size + importadasPc.size
        findViewById<TextView>(R.id.txtStockResultados).text = if (importadasPc.isEmpty()) {
            "$total resultados" + if (resultados.size == 50) " · mostrando hasta 50" else ""
        } else {
            "$total resultados · ${resultados.size} del catálogo local y ${importadasPc.size} importadas del PC"
        }

        val contenedor = findViewById<LinearLayout>(R.id.llResultadosStock)
        contenedor.removeAllViews()

        if (resultados.isEmpty() && importadasPc.isEmpty()) {
            val vacio = TextView(this).apply {
                text = "No se encontraron piezas."
                textSize = 15f
                setTextColor(Color.rgb(100, 116, 139))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(28))
            }
            contenedor.addView(vacio)
            return
        }

        resultados.forEach { (pieza, reserva) ->
            contenedor.addView(crearTarjeta(pieza, reserva))
        }

        if (importadasPc.isNotEmpty()) {
            val cabeceraPc = TextView(this).apply {
                text = "INVENTARIO IMPORTADO DESDE EL PC · SOLO CONSULTA"
                setTextColor(if (esModoOscuro()) Color.rgb(147, 197, 253) else Color.rgb(29, 78, 216))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(4), dp(12), dp(4), dp(8))
            }
            contenedor.addView(cabeceraPc)
            importadasPc.forEach { item -> contenedor.addView(crearTarjetaImportadaPc(item)) }
        }
        UiMotion.animateItems(contenedor)
    }

    private fun crearTarjetaImportadaPc(item: CachedPcStockItem): LinearLayout {
        val oscuro = esModoOscuro()
        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#A6121A2D"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#2B3B51"))
            }
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Tocar para ver más detalles de la pieza importada del PC"
        }
        val title = TextView(this).apply {
            text = item.descripcion.ifBlank { item.nombre.ifBlank { "Pieza sin descripción" } }
            setTextColor(if (oscuro) Color.rgb(248, 250, 252) else Color.rgb(15, 23, 42))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        header.addView(title)

        val subtitle = TextView(this).apply {
            text = buildList {
                if (item.codigo.isNotBlank()) add("Cód. ${item.codigo}")
                if (item.vehiculo.isNotBlank()) add(item.vehiculo)
                if (item.oem.isNotBlank()) add("OEM ${item.oem}")
            }.joinToString(" · ").ifBlank { "Registro importado ${item.id}" }
            setTextColor(if (oscuro) Color.rgb(203, 213, 225) else Color.rgb(71, 85, 105))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        }
        header.addView(subtitle)

        val meta = TextView(this).apply {
            text = buildList {
                if (item.ubicacion.isNotBlank()) add("Ubicación: ${item.ubicacion}")
                if (item.cantidad.isNotBlank()) add("Cantidad: ${item.cantidad}")
                if (item.precio.isNotBlank()) add("Precio: ${item.precio}")
            }.joinToString("  •  ")
            setTextColor(if (oscuro) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139))
            textSize = 12f
            setPadding(0, dp(5), 0, 0)
        }
        if (meta.text.isNotBlank()) header.addView(meta)
        tarjeta.addView(header)

        val detalles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(if (oscuro) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        agregarDetalle(detalles, "Código", item.codigo)
        agregarDetalle(detalles, "Nombre", item.nombre)
        agregarDetalle(detalles, "Descripción", item.descripcion)
        agregarDetalle(detalles, "OEM", item.oem)
        agregarDetalle(detalles, "Vehículo", item.vehiculo)
        agregarDetalle(detalles, "Ubicación", item.ubicacion)
        agregarDetalle(detalles, "Cantidad importada", item.cantidad)
        agregarDetalle(detalles, "Precio importado", item.precio)
        try {
            val payload =   JSONObject(item.payload)
            val original = payload.optJSONObject("datosOriginales") ?: payload
            val keys = original.keys()
            var shown = 0
            while (keys.hasNext() && shown < 24) {
                val key = keys.next()
                val value = original.opt(key)
                if (value == null || value == JSONObject.NULL || value is JSONObject || value is org.json.JSONArray) continue
                val text = value.toString().trim()
                if (text.isBlank() || key.equals("id", true)) continue
                agregarDetalle(detalles, key, text)
                shown++
            }
        } catch (e: Exception) {
            android.util.Log.d("StockSearchActivity", "No se pudieron leer todos los detalles de la pieza importada", e)
        }
        agregarTexto(
            detalles,
            "Consulta importada desde el ordenador. Las reservas y ventas se siguen gestionando desde el catálogo local.",
            if (oscuro) Color.rgb(147, 197, 253) else Color.rgb(29, 78, 216),
            true,
            12f
        )
        tarjeta.addView(detalles)
        header.setOnClickListener { UiMotion.toggleDetails(detalles) }
        UiMotion.bindPressFeedback(header)
        return tarjeta
    }


    private fun esModoOscuro(): Boolean {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun crearTarjeta(
        pieza: StockItem,
        reserva: StockOperation?
    ): LinearLayout {
        val modoOscuro = esModoOscuro()

        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))

            background = GradientDrawable().apply {
                setColor(if (modoOscuro) Color.rgb(30, 41, 59) else Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (modoOscuro) Color.rgb(51, 65, 85) else Color.rgb(226, 232, 240))
            }

            elevation = dp(2).toFloat()
        }

        tarjeta.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        }

        // Encabezado principal de la tarjeta de pieza
        val resumenHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Tocar para ver o desplegar detalles de la pieza"
        }

        // Ícono de stock circular
        val iconoStock = ImageView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (modoOscuro) Color.rgb(15, 118, 110) else Color.rgb(204, 251, 241))
            }
            setImageResource(R.drawable.ic_search_stock)
            setColorFilter(if (modoOscuro) Color.rgb(204, 251, 241) else Color.rgb(15, 118, 110))
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginEnd = dp(12)
            }
        }
        resumenHeader.addView(iconoStock)

        // Bloque central de datos
        val infoBloque = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val txtDescripcion = TextView(this).apply {
            text = pieza.descripcion.ifBlank { "Pieza sin descripción" }
            setTextColor(if (modoOscuro) Color.rgb(248, 250, 252) else Color.rgb(15, 23, 42))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        infoBloque.addView(txtDescripcion)

        val metaVehiculo = listOf(pieza.marca, pieza.modelo).filter { it.isNotBlank() }.joinToString(" ")
        val txtSubtitulo = TextView(this).apply {
            text = if (metaVehiculo.isNotBlank()) "Cód. ${pieza.codigo}  •  $metaVehiculo" else "Cód. ${pieza.codigo}  •  ID ${pieza.id}"
            setTextColor(if (modoOscuro) Color.rgb(203, 213, 225) else Color.rgb(71, 85, 105))
            textSize = 13f
        }
        infoBloque.addView(txtSubtitulo)

        val ubicacionStr = ubicacionCompleta(pieza)
        if (ubicacionStr.isNotBlank()) {
            val txtUbicacion = TextView(this).apply {
                text = "Ubicación: $ubicacionStr"
                setTextColor(if (modoOscuro) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139))
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            }
            infoBloque.addView(txtUbicacion)
        }

        resumenHeader.addView(infoBloque)

        // Badge Pill para Estado / Precio
        val bloquePills = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }

        val precioVal = pieza.pvpIva ?: pieza.precio
        if (precioVal != null) {
            val txtPrecioPill = TextView(this).apply {
                text = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES")).format(precioVal)
                setTextColor(if (modoOscuro) Color.rgb(134, 239, 172) else Color.rgb(21, 128, 61))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
            }
            bloquePills.addView(txtPrecioPill)
        }

        val estadoStr = pieza.estadoGestion
        val pillEstado = TextView(this).apply {
            text = estadoStr
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))

            val (bg, txt) = when {
                estadoStr.contains("Reservada") -> Pair(
                    if (modoOscuro) Color.rgb(30, 58, 138) else Color.rgb(239, 246, 255),
                    if (modoOscuro) Color.rgb(147, 197, 253) else Color.rgb(29, 78, 216)
                )
                estadoStr.contains("Vendida") -> Pair(
                    if (modoOscuro) Color.rgb(124, 45, 18) else Color.rgb(255, 247, 237),
                    if (modoOscuro) Color.rgb(253, 186, 116) else Color.rgb(194, 65, 12)
                )
                else -> Pair(
                    if (modoOscuro) Color.rgb(20, 83, 45) else Color.rgb(240, 253, 244),
                    if (modoOscuro) Color.rgb(134, 239, 172) else Color.rgb(21, 128, 61)
                )
            }

            setTextColor(txt)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bg)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        bloquePills.addView(pillEstado)

        resumenHeader.addView(bloquePills)
        tarjeta.addView(resumenHeader)

        // Bloque expandible de detalles
        val detalles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = android.view.View.GONE

            background = GradientDrawable().apply {
                setColor(if (modoOscuro) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252))
                cornerRadius = dp(12).toFloat()
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }

        agregarDetalle(detalles, "OEM", pieza.oem)
        agregarDetalle(detalles, "OE", pieza.oe)
        agregarDetalle(detalles, "IAM", pieza.iam)
        agregarDetalle(detalles, "Marca", pieza.marca)
        agregarDetalle(detalles, "Modelo", pieza.modelo)
        agregarDetalle(detalles, "Compatibilidad", pieza.compatibilidadVehiculo)
        agregarDetalle(detalles, "Ubicación almacén", ubicacionCompleta(pieza))
        agregarDetalle(detalles, "Existencias originales", pieza.stock?.toString() ?: "No especificadas")
        agregarDetalle(detalles, "Observaciones", pieza.observacionesPublicas)

        if (reserva != null) {
            agregarDetalle(detalles, "Cliente reserva", reserva.cliente)
            agregarDetalle(detalles, "Notas reserva", reserva.notas)

            if (reserva.llamadaId.isNotBlank()) {
                detalles.addView(
                    crearBoton("Abrir llamada asociada", "#334155") {
                        abrirLlamadaPorId(reserva.llamadaId)
                    }
                )
            }
        }

        if (modoSeleccion) {
            val seleccionar = crearBoton("Usar esta pieza", "#2563EB") {
                seleccionarPieza(pieza)
            }
            detalles.addView(seleccionar)
        } else {
            val acciones = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(10)
                }
            }

            when {
                pieza.estadoGestion == "Reservada en DesguaceCRM" -> {
                    acciones.addView(
                        crearBoton("Cancelar reserva", "#475569") {
                            confirmarCancelarReserva(pieza)
                        },
                        pesoLayout()
                    )
                    acciones.addView(
                        crearBoton("Marcar vendida", "#16A34A") {
                            confirmarVentaExterna(pieza)
                        },
                        pesoLayout()
                    )
                }
                pieza.estadoGestion == "Vendida fuera de DesguaceCRM" -> {
                    agregarTexto(
                        detalles,
                        "No disponible: marcada como vendida fuera de la app.",
                        Color.rgb(194, 65, 12),
                        true,
                        13f
                    )
                }
                puedeReservarse(pieza) -> {
                    acciones.addView(
                        crearBoton("Reservar pieza", "#2563EB") {
                            dialogoReservar(pieza)
                        },
                        pesoLayout()
                    )
                    acciones.addView(
                        crearBoton("Vendida fuera", "#C2410C") {
                            confirmarVentaExterna(pieza)
                        },
                        pesoLayout()
                    )
                }
            }

            if (acciones.childCount > 0) {
                detalles.addView(acciones)
            }
        }

        tarjeta.addView(detalles)

        resumenHeader.setOnClickListener { UiMotion.toggleDetails(detalles) }
        UiMotion.bindPressFeedback(resumenHeader)

        return tarjeta
    }

    private fun puedeReservarse(pieza: StockItem): Boolean {
        val disponibilidad = pieza.disponibilidadOriginal

        val disponible =
            disponibilidad.equals("Disponible", true) ||
                    disponibilidad.equals("En mostrador", true)

        val cantidadSuficiente =
            pieza.stock == null || pieza.stock > 0

        return disponible &&
                cantidadSuficiente &&
                pieza.estadoGestion == pieza.disponibilidadOriginal
    }


    private fun abrirLlamadaPorId(idLlamada: String) {
        if (idLlamada.isBlank()) {
            Toast.makeText(
                this,
                "Esta reserva no tiene una llamada asociada.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val prefs = getSharedPreferences(
            "desguace_crm",
            MODE_PRIVATE
        )

        val llamadas = try {
            org.json.JSONArray(
                prefs.getString("llamadas", "[]") ?: "[]"
            )
        } catch (e: Exception) {
            android.util.Log.e(
                "DesguaceCRM",
                "No se pudieron leer las llamadas",
                e
            )

            Toast.makeText(
                this,
                "No se pudieron consultar las llamadas.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        var indiceEncontrado = -1

        for (i in 0 until llamadas.length()) {
            val llamada = llamadas.optJSONObject(i) ?: continue

            if (
                llamada.optString("idLlamada") == idLlamada
            ) {
                indiceEncontrado = i
                break
            }
        }

        if (indiceEncontrado < 0) {
            Toast.makeText(
                this,
                "No se encontró la llamada asociada. " +
                        "Comprueba que la ficha esté guardada.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = Intent(
            this,
            CallFormActivity::class.java
        ).apply {
            putExtra(
                CallFormActivity.EXTRA_CALL_INDEX,
                indiceEncontrado
            )
        }

        startActivity(intent)
    }


    private fun ubicacionCompleta(pieza: StockItem): String {
        return listOf(
            pieza.ubicacion,
            pieza.almacen,
            pieza.subalmacen,
            pieza.subsubalmacen
        )
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
            .ifBlank { "Sin ubicación registrada" }
    }


    private fun colorEstado(estado: String): Int {
        return when {
            estado.contains("Reservada", ignoreCase = true) ->
                Color.rgb(37, 99, 235) // Azul

            estado.contains("Vendida", ignoreCase = true) ->
                Color.rgb(194, 65, 12) // Naranja

            estado.contains("Desechada", ignoreCase = true) ||
                    estado.contains("Descatalogada", ignoreCase = true) ||
                    estado.contains("Agotada", ignoreCase = true) ||
                    estado.contains("Ausente", ignoreCase = true) ->
                Color.rgb(220, 38, 38) // Rojo

            estado.equals("Disponible", ignoreCase = true) ||
                    estado.equals("En mostrador", ignoreCase = true) ->
                Color.rgb(22, 163, 74) // Verde

            estado.contains("incidencia", ignoreCase = true) ||
                    estado.contains("revisar", ignoreCase = true) ||
                    estado.contains("calidad", ignoreCase = true) ||
                    estado.contains("devuelta", ignoreCase = true) ||
                    estado.contains("desmontada", ignoreCase = true) ||
                    estado.contains("montada", ignoreCase = true) ||
                    estado.contains("desconocida", ignoreCase = true) ->
                Color.rgb(180, 83, 9) // Ámbar

            else ->
                Color.rgb(100, 116, 139) // Gris
        }
    }


    private fun dialogoReservar(pieza: StockItem) {
        val formulario = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }

        val cliente = EditText(this).apply {
            hint = "Nombre del cliente (opcional)"
            setSingleLine(true)

            setText(
                intent.getStringExtra(EXTRA_CALL_CLIENTE).orEmpty()
            )
        }

        val llamada = EditText(this).apply {
            hint = "Teléfono o referencia de llamada (opcional)"
            setSingleLine(true)

            setText(
                intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
            )
        }

        val notas = EditText(this).apply {
            hint = "Notas de la reserva (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }

        formulario.addView(cliente)
        formulario.addView(llamada)
        formulario.addView(notas)

        AlertDialog.Builder(this)
            .setTitle("Reservar ${pieza.codigo}")
            .setMessage(
                "${pieza.descripcion}\n" +
                        "Ubicación: ${ubicacionCompleta(pieza)}"
            )
            .setView(formulario)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Confirmar reserva") { _, _ ->
                ejecutarOperacion {
                    stockDb.reservarPorCodigo(
                        codigo = pieza.codigo,
                        cliente = cliente.text.toString(),
                        llamadaId = llamada.text.toString(),
                        notas = notas.text.toString()
                    )
                }
            }
            .show()
    }

    private fun confirmarCancelarReserva(pieza: StockItem) {
        AlertDialog.Builder(this)
            .setTitle("Cancelar reserva")
            .setMessage(
                "¿Quieres liberar la pieza ${pieza.codigo}?\n" +
                        "Esta operación volverá a permitir reservarla, " +
                        "si sigue disponible en el inventario."
            )
            .setNegativeButton("Volver", null)
            .setPositiveButton("Cancelar reserva") { _, _ ->
                ejecutarOperacion {
                    stockDb.cancelarReservaPorCodigo(
                        pieza.codigo,
                        "Reserva cancelada desde DesguaceCRM"
                    )
                }
            }
            .show()
    }

    private fun confirmarVentaExterna(pieza: StockItem) {
        val nota = EditText(this).apply {
            hint = "Nota opcional"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }

        AlertDialog.Builder(this)
            .setTitle("Marcar como vendida fuera")
            .setMessage(
                "La pieza ${pieza.codigo} dejará de aparecer " +
                        "como disponible en DesguaceCRM.\n\n" +
                        "No se borrará de la base original de inventario."
            )
            .setView(nota)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Confirmar") { _, _ ->
                ejecutarOperacion {
                    stockDb.marcarVendidaFueraPorCodigo(
                        pieza.codigo,
                        nota.text.toString()
                    )
                }
            }
            .show()
    }

    private fun ejecutarOperacion(
        operacion: () -> StockOperationResult
    ) {
        findViewById<Button>(R.id.btnBuscarStock).isEnabled = false

        executor.execute {
            val resultado = try {
                operacion()
            } catch (e: Exception) {
                android.util.Log.e(
                    "DesguaceCRM",
                    "Error en operación de stock",
                    e
                )
                null
            }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post

                findViewById<Button>(R.id.btnBuscarStock).isEnabled = true

                val mensaje = when (resultado) {
                    StockOperationResult.RESERVADA ->
                        "Pieza reservada correctamente."

                    StockOperationResult.YA_RESERVADA ->
                        "Esta pieza ya está reservada."

                    StockOperationResult.YA_VENDIDA,
                    StockOperationResult.YA_MARCADA_VENDIDA ->
                        "Esta pieza ya está marcada como vendida fuera."

                    StockOperationResult.NO_DISPONIBLE ->
                        "La pieza no está disponible para reservar."

                    StockOperationResult.NO_ENCONTRADA ->
                        "No se encontró la pieza."

                    StockOperationResult.CANCELADA ->
                        "Reserva cancelada. Pieza liberada."

                    StockOperationResult.NO_ESTABA_RESERVADA ->
                        "La pieza no tenía una reserva activa."

                    StockOperationResult.MARCADA_VENDIDA ->
                        "Pieza marcada como vendida fuera de la app."

                    null ->
                        "No se pudo completar la operación."
                }

                Toast.makeText(
                    this,
                    mensaje,
                    Toast.LENGTH_LONG
                ).show()

                if (mostrandoReservas) {
                    cargarReservas()
                } else {
                    buscarPiezas()
                }
            }
        }
    }

    private fun seleccionarPieza(pieza: StockItem) {
        val resultado = Intent().apply {
            putExtra(RESULT_STOCK_ID, pieza.id)
            putExtra(RESULT_CODIGO, pieza.codigo)
            putExtra(RESULT_DESCRIPCION, pieza.descripcion)
            putExtra(RESULT_OEM, pieza.oem)
            putExtra(RESULT_VEHICULO, pieza.compatibilidadVehiculo)
            putExtra(RESULT_UBICACION, ubicacionCompleta(pieza))
            putExtra(
                RESULT_PRECIO,
                pieza.pvpIva ?: pieza.precio ?: -1.0
            )
        }

        setResult(RESULT_OK, resultado)
        finish()
    }

    private fun agregarDetalle(
        contenedor: LinearLayout,
        etiqueta: String,
        valor: String
    ) {
        if (valor.isBlank() || valor == "null") return

        val colorTexto = if (esModoOscuro()) {
            Color.rgb(226, 232, 240)
        } else {
            Color.rgb(51, 65, 85)
        }

        agregarTexto(
            contenedor,
            "$etiqueta: $valor",
            colorTexto,
            false,
            13f
        )
    }

    private fun agregarTexto(
        contenedor: LinearLayout,
        texto: String,
        color: Int,
        negrita: Boolean,
        tamano: Float
    ) {
        contenedor.addView(
            TextView(this).apply {
                text = texto
                setTextColor(color)
                textSize = tamano
                setPadding(0, dp(3), 0, dp(3))

                if (negrita) {
                    setTypeface(null, Typeface.BOLD)
                }
            }
        )
    }

    private fun crearBoton(
        texto: String,
        color: String,
        accion: () -> Unit
    ): Button {
        return Button(this).apply {
            text = texto
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(color)
            )
            setTextColor(Color.WHITE)
            setOnClickListener { accion() }

            setPadding(dp(4), 0, dp(4), 0)
        }
    }

    private fun pesoLayout(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginEnd = dp(4)
        }
    }

    private fun dp(valor: Int): Int =
        (valor * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        solicitudActual++
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        if (::pcInventoryDb.isInitialized) pcInventoryDb.close()
        super.onDestroy()
    }


    private fun seleccionarModoImportacion() {
        val opciones = arrayOf(
            "Inventario completo — actualizar todo el stock",
            "Archivo parcial — añadir o actualizar solo las piezas incluidas"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tipo de importación")
            .setItems(opciones) { _, seleccion ->

                modoImportacionSeleccionado =
                    if (seleccion == 0) {
                        StockImportMode.COMPLETO
                    } else {
                        StockImportMode.PARCIAL
                    }

                // Abrir el selector de archivos solamente
                // después de elegir el tipo de importación.
                csvPicker.launch(arrayOf("*/*"))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }


}



