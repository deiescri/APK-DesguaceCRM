
package com.desguace.crm

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import java.util.UUID
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.CheckBox
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.widget.AutoCompleteTextView
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Filter
import java.util.concurrent.Executors

class CallFormActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_INDEX = "indiceLlamada"
    }

    private var callIndex = -1

    private var idLlamadaActual =
        UUID.randomUUID().toString()

    private var stockIdSeleccionado: Long? = null

    private var actualizandoCamposStock = false

    private var compatibilidadPiezaSeleccionada = ""
    private lateinit var catalogoVehiculos: VehicleCatalogDatabase

    private lateinit var campoMarca: AutoCompleteTextView
    private lateinit var campoModelo: AutoCompleteTextView
    private lateinit var campoMotor: AutoCompleteTextView

    private var marcasCatalogo: List<String> = emptyList()
    private var actualizandoDesdeCatalogo = false

    private val vehicleSearchHandler = Handler(Looper.getMainLooper())
    private val vehicleSearchExecutor = Executors.newSingleThreadExecutor()
    private var vehicleSearchRunnable: Runnable? = null
    private var vehicleSearchRequestId = 0

    private val motivosNoConcretada = listOf(
        "Precio demasiado alto",
        "El cliente cambió de opinión",
        "Encontró la pieza en otro sitio",
        "La pieza no le sirve",
        "Otro motivo",
        "Sin motivo especificado"
    )

    private val buscarPiezaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->

        if (resultado.resultCode != RESULT_OK) return@registerForActivityResult

        val data = resultado.data ?: return@registerForActivityResult

        val id = data.getLongExtra(
            StockSearchActivity.RESULT_STOCK_ID,
            -1L
        )

        actualizandoCamposStock = true

        try {
            stockIdSeleccionado = id.takeIf { it > 0L }

            findViewById<android.widget.Button>(
                R.id.btnReservarPiezaSeleccionada
            ).isEnabled = stockIdSeleccionado != null

            findViewById<EditText>(R.id.etPieza).setText(
                data.getStringExtra(
                    StockSearchActivity.RESULT_DESCRIPCION
                ).orEmpty()
            )

            findViewById<EditText>(R.id.etCodigoStock).setText(
                data.getStringExtra(
                    StockSearchActivity.RESULT_CODIGO
                ).orEmpty()
            )

            findViewById<EditText>(R.id.etUbicacionStock).setText(
                data.getStringExtra(
                    StockSearchActivity.RESULT_UBICACION
                ).orEmpty()
            )

            val oem = data.getStringExtra(
                StockSearchActivity.RESULT_OEM
            ).orEmpty()

            if (oem.isNotBlank()) {
                findViewById<EditText>(R.id.etOEM).setText(oem)
            }

            compatibilidadPiezaSeleccionada =
                data.getStringExtra(
                    StockSearchActivity.RESULT_VEHICULO
                ).orEmpty()

            Toast.makeText(
                this,
                "Pieza seleccionada correctamente",
                Toast.LENGTH_SHORT
            ).show()

        } finally {
            actualizandoCamposStock = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        getSharedPreferences("desguace_crm", MODE_PRIVATE)
            .edit()
            .putBoolean("call_form_abierto", true)
            .apply()

        setContentView(R.layout.activity_call_form)
        WallpaperManager.aplicarFondo(this, findViewById(R.id.imgFondoLiquidGlass))
        UiMotion.enterScreen(findViewById<android.view.View>(android.R.id.content))

        findViewById<android.widget.ImageButton>(
            R.id.btnLlamarCliente
        ).setOnClickListener {
            llamarAlCliente()
        }

        findViewById<android.widget.Button>(
            R.id.btnReservarPiezaSeleccionada
        ).setOnClickListener {
            reservarPiezaSeleccionada()
        }

        findViewById<ImageButton>(R.id.btnBuscarPiezaStock)
            .setOnClickListener {
                abrirBuscadorStock()
            }

        configurarAutocompletadoVehiculos()

        callIndex = intent.getIntExtra(EXTRA_CALL_INDEX, -1)

        configurarDatosVenta()

        val btnEliminar = findViewById<ImageButton>(
            R.id.btnEliminarLlamada
        )

        if (callIndex >= 0) {
            cargarLlamada()

            btnEliminar.visibility = View.VISIBLE
            btnEliminar.setOnClickListener {
                confirmarEliminarLlamada()
            }
        } else {
            btnEliminar.visibility = View.GONE
        }

        findViewById<EditText>(R.id.etCodigoStock)
            .addTextChangedListener(object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    if (!actualizandoCamposStock) {
                        stockIdSeleccionado = null
                        compatibilidadPiezaSeleccionada = ""
                    }
                }

                override fun afterTextChanged(s: Editable?) {}
            })

        findViewById<Button>(R.id.btnGuardarLlamada)
            .setOnClickListener {
                guardarLlamada()
            }
    }

    override fun onResume() {
        super.onResume()
        WallpaperManager.aplicarFondo(this, findViewById(R.id.imgFondoLiquidGlass))
    }


    private fun llamarAlCliente() {
        val numero = findViewById<EditText>(
            R.id.etTelefono
        ).text.toString().trim()

        if (numero.isBlank() || !numero.any { it.isDigit() }) {
            Toast.makeText(
                this,
                "Introduce un número de teléfono válido.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (
            checkSelfPermission(Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permisoLlamarLauncher.launch(
                Manifest.permission.CALL_PHONE
            )
            return
        }

        iniciarLlamadaCliente()
    }

    private fun iniciarLlamadaCliente() {
        val numero = findViewById<EditText>(
            R.id.etTelefono
        ).text.toString().trim()

        if (numero.isBlank() || !numero.any { it.isDigit() }) {
            Toast.makeText(
                this,
                "No hay un número válido para llamar.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val uri = Uri.fromParts("tel", numero, null)
            val intent = Intent(Intent.ACTION_CALL, uri)

            startActivity(intent)
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "Android no ha concedido permiso para realizar la llamada.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            android.util.Log.e(
                "DesguaceCRM",
                "No se pudo iniciar la llamada",
                e
            )

            Toast.makeText(
                this,
                "No se pudo iniciar la llamada.",
                Toast.LENGTH_LONG
            ).show()
        }
    }



    private fun reservarPiezaSeleccionada() {
        val stockId = stockIdSeleccionado

        if (stockId == null) {
            Toast.makeText(
                this,
                "Primero selecciona una pieza con la lupa.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val grupoEstado =
            findViewById<android.widget.RadioGroup>(R.id.rgEstado)

        val radioReservado = (0 until grupoEstado.childCount)
            .mapNotNull {
                grupoEstado.getChildAt(it) as? android.widget.RadioButton
            }
            .firstOrNull {
                it.text.toString().contains("reserv", ignoreCase = true)
            }

        if (radioReservado == null) {
            Toast.makeText(
                this,
                "No se encuentra el estado comercial Reservado.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val boton = findViewById<android.widget.Button>(
            R.id.btnReservarPiezaSeleccionada
        )

        if (!boton.isEnabled) return

        boton.isEnabled = false

        val cliente = texto(R.id.etNombre)
        val llamadaId = idLlamadaActual
        val codigoStock = texto(R.id.etCodigoStock)

        Thread {
            val resultado = try {
                StockOperationsDatabase(applicationContext)
                    .reservarPorId(
                        id = stockId,
                        cliente = cliente,
                        llamadaId = llamadaId,
                        notas = "Reserva desde la ficha de llamada. " +
                                "Código: $codigoStock"
                    )
            } catch (e: Exception) {
                android.util.Log.e(
                    "DesguaceCRM",
                    "Error reservando la pieza",
                    e
                )
                null
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                when (resultado) {
                    StockOperationResult.RESERVADA -> {
                        grupoEstado.check(radioReservado.id)

                        Toast.makeText(
                            this,
                            "Pieza reservada. Guardando la llamada…",
                            Toast.LENGTH_SHORT
                        ).show()

                        guardarLlamada()
                    }

                    StockOperationResult.YA_RESERVADA -> {
                        boton.isEnabled = true
                        Toast.makeText(
                            this,
                            "Esta pieza ya está reservada.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    StockOperationResult.YA_VENDIDA,
                    StockOperationResult.YA_MARCADA_VENDIDA -> {
                        boton.isEnabled = true
                        Toast.makeText(
                            this,
                            "Esta pieza ya está marcada como vendida.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    StockOperationResult.NO_DISPONIBLE -> {
                        boton.isEnabled = true
                        Toast.makeText(
                            this,
                            "La pieza no está disponible para reservar.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    StockOperationResult.NO_ENCONTRADA -> {
                        boton.isEnabled = true
                        Toast.makeText(
                            this,
                            "No se encontró la pieza en el inventario.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    else -> {
                        boton.isEnabled = true
                        Toast.makeText(
                            this,
                            "No se pudo completar la reserva.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    private val permisoLlamarLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) {
            iniciarLlamadaCliente()
        } else {
            Toast.makeText(
                this,
                "Necesitas conceder permiso para llamar al cliente.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun abrirBuscadorStock() {
        val oem = texto(R.id.etOEM)
        val pieza = texto(R.id.etPieza)
        val marca = texto(R.id.etMarca)
        val modelo = texto(R.id.etModelo)
        val motor = texto(R.id.etMotor)

        val consultaVehiculo = listOf(
            marca,
            modelo,
            motor
        ).filter { it.isNotBlank() }
            .joinToString(" ")

        val consultaAlternativa = listOf(
            pieza,
            consultaVehiculo
        ).filter { it.isNotBlank() }
            .joinToString(" ")

        // Si hay OEM, se busca primero por esa referencia.
        // Si no hay OEM, se utiliza la pieza y el vehículo.
        val consultaInicial = if (oem.isNotBlank()) {
            oem
        } else {
            consultaAlternativa
        }

        val intent = Intent(
            this,
            StockSearchActivity::class.java
        ).apply {
            putExtra(
                StockSearchActivity.EXTRA_PICK_MODE,
                true
            )
            putExtra(
                StockSearchActivity.EXTRA_INITIAL_QUERY,
                consultaInicial
            )
            putExtra(
                StockSearchActivity.EXTRA_FALLBACK_QUERY,
                if (oem.isNotBlank()) consultaAlternativa else ""
            )
            putExtra(
                StockSearchActivity.EXTRA_CALL_ID,
                idLlamadaActual
            )
            putExtra(
                StockSearchActivity.EXTRA_CALL_CLIENTE,
                texto(R.id.etNombre)
            )
        }

        buscarPiezaLauncher.launch(intent)
    }


    private fun configurarDatosVenta() {
        val grupoEstado = findViewById<RadioGroup>(R.id.rgEstado)

        val spinnerMotivo = findViewById<Spinner>(
            R.id.spMotivoNoConcretada
        )

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            motivosNoConcretada
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinnerMotivo.adapter = adapter

        grupoEstado.setOnCheckedChangeListener { _, checkedId ->
            actualizarVisibilidadVenta(
                checkedId == R.id.rbVendido
            )

            findViewById<View>(R.id.layoutMotivoNoConcretada)
                .visibility = if (
                checkedId == R.id.rbNoConcretada
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        findViewById<EditText>(R.id.etFechaVenta)
            .setOnClickListener {
                mostrarCalendario()
            }

        val estadoInicial = grupoEstado.checkedRadioButtonId

        actualizarVisibilidadVenta(
            estadoInicial == R.id.rbVendido
        )

        findViewById<View>(R.id.layoutMotivoNoConcretada)
            .visibility = if (
            estadoInicial == R.id.rbNoConcretada
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun actualizarVisibilidadVenta(mostrar: Boolean) {
        findViewById<LinearLayout>(R.id.layoutDatosVenta)
            .visibility = if (mostrar) View.VISIBLE else View.GONE
    }

    private fun mostrarCalendario() {
        val campoFecha = findViewById<EditText>(
            R.id.etFechaVenta
        )

        val calendario = Calendar.getInstance()
        val fechaActual = campoFecha.text.toString().trim()

        // Si ya existe una fecha, utilizarla como fecha inicial.
        try {
            if (fechaActual.isNotBlank()) {
                val formato = SimpleDateFormat(
                    "dd/MM/yyyy",
                    Locale.getDefault()
                ).apply {
                    isLenient = false
                }

                val fecha = formato.parse(fechaActual)
                if (fecha != null) {
                    calendario.time = fecha
                }
            }
        } catch (_: Exception) {
            // Si la fecha no es válida, utilizamos la actual.
        }

        DatePickerDialog(
            this,
            { _, anio, mes, dia ->
                campoFecha.setText(
                    String.format(
                        Locale.US,
                        "%02d/%02d/%04d",
                        dia,
                        mes + 1,
                        anio
                    )
                )
            },
            calendario.get(Calendar.YEAR),
            calendario.get(Calendar.MONTH),
            calendario.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun fechaAFormatoISO(fechaVisible: String): String? {
        return try {
            val formatoEntrada = SimpleDateFormat(
                "dd/MM/yyyy",
                Locale.getDefault()
            ).apply {
                isLenient = false
            }

            val fecha = formatoEntrada.parse(fechaVisible) ?: return null

            // Evitar fechas ambiguas o inválidas.
            if (formatoEntrada.format(fecha) != fechaVisible) {
                return null
            }

            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            ).format(fecha)
        } catch (_: Exception) {
            null
        }
    }

    private fun fechaISOAFormatoVisible(fechaISO: String): String {
        return try {
            val formatoEntrada = SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            ).apply {
                isLenient = false
            }

            val fecha = formatoEntrada.parse(fechaISO) ?: return ""

            SimpleDateFormat(
                "dd/MM/yyyy",
                Locale.getDefault()
            ).format(fecha)
        } catch (_: Exception) {
            ""
        }
    }

    private fun leerLlamadas(): JSONArray {
        val preferencias = getSharedPreferences(
            "desguace_crm",
            MODE_PRIVATE
        )

        return try {
            JSONArray(
                preferencias.getString("llamadas", "[]") ?: "[]"
            )
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun cargarLlamada() {
        val llamadas = leerLlamadas()

        if (callIndex !in 0 until llamadas.length()) {
            Toast.makeText(
                this,
                "No se encontró la llamada",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val datos = llamadas.optJSONObject(callIndex)
        if (datos == null) {
            finish()
            return
        }

        idLlamadaActual = datos.optString("idLlamada")
            .ifBlank {
                UUID.randomUUID().toString()
            }

        ponerTexto(R.id.etTelefono, datos, "telefono")
        ponerTexto(R.id.etNombre, datos, "nombre")
        ponerTexto(R.id.etPieza, datos, "pieza")
        ponerTexto(R.id.etDescripcion, datos, "descripcion")
        ponerTexto(R.id.etLado, datos, "lado")
        ponerTexto(R.id.etOEM, datos, "oem")
        ponerTexto(R.id.etMarca, datos, "marca")
        ponerTexto(R.id.etModelo, datos, "modelo")
        ponerTexto(R.id.etMotor, datos, "motor")
        ponerTexto(R.id.etCodigosMotor, datos, "codigosMotor")
        ponerTexto(R.id.etCombustible, datos, "combustible")
        ponerTexto(R.id.etPuertas, datos, "puertas")
        ponerTexto(R.id.etPotenciaCv, datos, "potenciaCv")
        ponerTexto(R.id.etPotenciaKw, datos, "potenciaKw")
        ponerTexto(R.id.etRangoFabricacion, datos, "rangoFabricacion")
        ponerTexto(R.id.etAnio, datos, "anio")
        ponerTexto(R.id.etApuntes, datos, "apuntes")
        ponerTexto(R.id.etPrecioVenta, datos, "precioVenta")
        ponerTexto(R.id.etCodigoStock, datos, "codigoStock")
        ponerTexto(R.id.etUbicacionStock, datos, "ubicacionStock")

        stockIdSeleccionado = if (
            datos.has("stockId") && !datos.isNull("stockId")
        ) {
            datos.optLong("stockId", -1L).takeIf { it > 0L }
        } else {
            null
        }

        compatibilidadPiezaSeleccionada =
            datos.optString("compatibilidadPiezaStock", "")

        findViewById<EditText>(R.id.etFechaVenta).setText(
            fechaISOAFormatoVisible(
                datos.optString("fechaVenta", "")
            )
        )

        val tipo = datos.optString("tipoLlamada")

        findViewById<RadioGroup>(R.id.rgTipoLlamada).check(
            if (tipo.equals("Perdida", true)) {
                R.id.rbPerdida
            } else {
                R.id.rbContestada
            }
        )

        val estado = datos.optString("estado")

        val idEstado = when {
            estado.contains("reserv", ignoreCase = true) ->
                R.id.rbReservada

            estado.contains("no tenemos", ignoreCase = true) ||
                    estado.contains("sin existencias", ignoreCase = true) ->
                R.id.rbSinExistencias

            estado.contains("vendido", ignoreCase = true) ->
                R.id.rbVendido

            estado.contains("no concretada", ignoreCase = true) ->
                R.id.rbNoConcretada

            else -> R.id.rbPendiente
        }

        findViewById<RadioGroup>(R.id.rgEstado).check(idEstado)

        val motivoGuardado = datos.optString(
            "motivoNoConcretada",
            "Sin motivo especificado"
        )

        val indiceMotivo = motivosNoConcretada.indexOf(motivoGuardado)

        findViewById<Spinner>(R.id.spMotivoNoConcretada)
            .setSelection(
                if (indiceMotivo >= 0) {
                    indiceMotivo
                } else {
                    motivosNoConcretada.lastIndex
                }
            )

        findViewById<TextView>(R.id.txtTituloFormulario).text =
            "Editar llamada"

        findViewById<Button>(R.id.btnGuardarLlamada).text =
            "Guardar cambios"
    }

    private fun ponerTexto(
        id: Int,
        datos: JSONObject,
        clave: String
    ) {
        val valor = datos.optString(clave, "")
            .takeUnless { it == "null" } ?: ""

        findViewById<EditText>(id).setText(valor)
    }

    private fun texto(id: Int): String {
        return findViewById<EditText>(id)
            .text.toString().trim()
    }

    private fun opcionSeleccionada(id: Int): String {
        val grupo = findViewById<RadioGroup>(id)
        val seleccionado = grupo.checkedRadioButtonId

        if (seleccionado == -1) return ""

        return findViewById<RadioButton>(seleccionado)
            .text.toString()
    }


    private fun crearVarianteDesdeFormulario(): VehicleVariant? {
        val marca = texto(R.id.etMarca)
        val modelo = texto(R.id.etModelo)
        val motor = texto(R.id.etMotor)
        val rango = texto(R.id.etRangoFabricacion)

        // Marca y modelo son imprescindibles.
        if (marca.isBlank() || modelo.isBlank()) {
            return null
        }

        // Acepta rangos como 1996–2000, 1996 - 2000,
        // Desde 1996 o Hasta 2000.
        val anios = Regex("""\d{4}""")
            .findAll(rango)
            .map { it.value }
            .toList()

        val anioDesde: String
        val anioHasta: String

        when {
            anios.size >= 2 -> {
                anioDesde = anios[0]
                anioHasta = anios[1]
            }

            anios.size == 1 &&
                    rango.contains("desde", ignoreCase = true) -> {
                anioDesde = anios[0]
                anioHasta = ""
            }

            anios.size == 1 &&
                    rango.contains("hasta", ignoreCase = true) -> {
                anioDesde = ""
                anioHasta = anios[0]
            }

            anios.size == 1 -> {
                anioDesde = anios[0]
                anioHasta = anios[0]
            }

            else -> {
                anioDesde = ""
                anioHasta = ""
            }
        }

        return VehicleVariant(
            id = 0L,
            marca = marca,
            modelo = modelo,
            motor = motor,
            codigosMotor = texto(R.id.etCodigosMotor),
            combustible = texto(R.id.etCombustible),
            puertas = texto(R.id.etPuertas),
            potenciaCv = texto(R.id.etPotenciaCv),
            potenciaKw = texto(R.id.etPotenciaKw),
            anioDesde = anioDesde,
            anioHasta = anioHasta
        )
    }


    private fun guardarLlamada() {
        val llamadas = leerLlamadas()
        val editando = callIndex >= 0

        if (editando && callIndex !in 0 until llamadas.length()) {
            Toast.makeText(
                this,
                "La llamada ya no existe",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val estadoSeleccionado = opcionSeleccionada(R.id.rgEstado)

        var precioVenta: Double? = null
        var fechaVentaISO: String? = null

        if (estadoSeleccionado.equals("Vendido", ignoreCase = true)) {
            val textoPrecio = texto(R.id.etPrecioVenta)
                .replace(",", ".")

            precioVenta = textoPrecio.toDoubleOrNull()

            if (precioVenta == null ||
                precioVenta < 0 ||
                precioVenta.isNaN() ||
                precioVenta.isInfinite()
            ) {
                Toast.makeText(
                    this,
                    "Introduce un precio de venta válido",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            fechaVentaISO = fechaAFormatoISO(
                texto(R.id.etFechaVenta)
            )

            if (fechaVentaISO == null) {
                Toast.makeText(
                    this,
                    "Selecciona una fecha de venta válida",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val registroAnterior = if (editando) {
            llamadas.optJSONObject(callIndex)
        } else {
            null
        }

        val fechaHora = registroAnterior
            ?.optString("fechaHora")
            ?.takeIf { it.isNotBlank() }
            ?: SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

        // En edición conservamos el registro original.
        val registro = registroAnterior?.let {
            JSONObject(it.toString())
        } ?: JSONObject()

        registro.put("fechaHora", fechaHora)
        registro.put("telefono", texto(R.id.etTelefono))
        registro.put("nombre", texto(R.id.etNombre))
        registro.put(
            "tipoLlamada",
            opcionSeleccionada(R.id.rgTipoLlamada)
        )
        registro.put("pieza", texto(R.id.etPieza))
        registro.put("descripcion", texto(R.id.etDescripcion))
        registro.put(
            "codigoStock",
            texto(R.id.etCodigoStock)
        )

        registro.put(
            "ubicacionStock",
            texto(R.id.etUbicacionStock)
        )

        registro.put(
            "compatibilidadPiezaStock",
            compatibilidadPiezaSeleccionada
        )

        if (stockIdSeleccionado != null) {
            registro.put("stockId", stockIdSeleccionado!!)
        } else {
            registro.remove("stockId")
        }
        registro.put("lado", texto(R.id.etLado))
        registro.put("oem", texto(R.id.etOEM))
        registro.put("marca", texto(R.id.etMarca))
        registro.put("modelo", texto(R.id.etModelo))
        registro.put("motor", texto(R.id.etMotor))
        registro.put("codigosMotor", texto(R.id.etCodigosMotor))
        registro.put("combustible", texto(R.id.etCombustible))
        registro.put("puertas", texto(R.id.etPuertas))
        registro.put("potenciaCv", texto(R.id.etPotenciaCv))
        registro.put("potenciaKw", texto(R.id.etPotenciaKw))
        registro.put("rangoFabricacion", texto(R.id.etRangoFabricacion))
        registro.put("anio", texto(R.id.etAnio))
        registro.put("estado", estadoSeleccionado)
        val idLlamada = registro.optString("idLlamada")
            .ifBlank {
                UUID.randomUUID().toString()
            }

        registro.put("idLlamada", idLlamadaActual)

        if (estadoSeleccionado.equals(
                "No concretada",
                ignoreCase = true
            )
        ) {
            val motivo = findViewById<Spinner>(
                R.id.spMotivoNoConcretada
            ).selectedItem?.toString() ?: "Sin motivo especificado"

            registro.put("motivoNoConcretada", motivo)
        } else {
            registro.remove("motivoNoConcretada")
        }

        registro.put("apuntes", texto(R.id.etApuntes))
        // Si se edita una llamada ya sincronizada, debe volver a enviarse al servidor.
        registro.put("sincronizado", false)

        if (estadoSeleccionado.equals("Vendido", ignoreCase = true)) {
            registro.put("precioVenta", precioVenta!!)
            registro.put("fechaVenta", fechaVentaISO!!)
        } else {
            registro.remove("precioVenta")
            registro.remove("fechaVenta")
        }

        if (editando) {
            llamadas.put(callIndex, registro)
        } else {
            llamadas.put(registro)
        }

        getSharedPreferences("desguace_crm", MODE_PRIVATE)
            .edit()
            .putString("llamadas", llamadas.toString())
            .apply()

        Toast.makeText(
            this,
            if (editando) {
                "Cambios guardados correctamente"
            } else {
                "Llamada guardada correctamente"
            },
            Toast.LENGTH_SHORT
        ).show()

        finish()
    }

    private fun confirmarEliminarLlamada() {
        if (callIndex < 0) return

        AlertDialog.Builder(this)
            .setTitle("Eliminar llamada")
            .setMessage(
                "¿Seguro que quieres eliminar este registro? " +
                        "Esta acción no se puede deshacer."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                eliminarLlamada()
            }
            .show()
    }

    private fun eliminarLlamada() {
        val llamadas = leerLlamadas()

        if (callIndex !in 0 until llamadas.length()) {
            Toast.makeText(
                this,
                "No se encontró la llamada",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        llamadas.remove(callIndex)

        getSharedPreferences("desguace_crm", MODE_PRIVATE)
            .edit()
            .putString("llamadas", llamadas.toString())
            .apply()


        var mensaje = if (callIndex >= 0) {
            "Cambios guardados correctamente"
        } else {
            "Llamada guardada correctamente"
        }

        val guardarEnCatalogo = findViewById<CheckBox>(
            R.id.chkGuardarVehiculoCatalogo
        ).isChecked

        if (guardarEnCatalogo) {
            val variante = crearVarianteDesdeFormulario()

            if (variante == null) {
                mensaje += ". Para guardar el vehículo en el catálogo, " +
                        "completa al menos la marca y el modelo."
            } else {
                try {
                    when (
                        catalogoVehiculos.guardarVehiculoSiNoExiste(variante)
                    ) {
                        VehicleSaveResult.ADDED -> {
                            mensaje += ". Vehículo añadido al catálogo."
                        }

                        VehicleSaveResult.ALREADY_EXISTS -> {
                            mensaje += ". Esa variante ya existe; " +
                                    "no se ha creado un duplicado."
                        }

                        VehicleSaveResult.INVALID_DATA -> {
                            mensaje += ". Faltan datos para guardar el vehículo."
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                        "DesguaceCRM",
                        "Error guardando vehículo aprendido",
                        e
                    )

                    mensaje += ". No se pudo actualizar el catálogo."
                }
            }
        }

        Toast.makeText(
            this,
            mensaje,
            Toast.LENGTH_LONG
        ).show()

        finish()


        finish()
    }


    private fun configurarAutocompletadoVehiculos() {
        catalogoVehiculos = VehicleCatalogDatabase(this)

        campoMarca = findViewById(R.id.etMarca)
        campoModelo = findViewById(R.id.etModelo)
        campoMotor = findViewById(R.id.etMotor)

        marcasCatalogo = catalogoVehiculos.buscarMarcas()

        campoMarca.threshold = 1
        campoModelo.threshold = 1
        campoMotor.threshold = 1

        campoMarca.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                marcasCatalogo
            )
        )

        campoModelo.setAdapter(
            ArrayAdapter<VehicleVariant>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                emptyList()
            )
        )

        campoMotor.setAdapter(
            ArrayAdapter<String>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                emptyList()
            )
        )

        // La marca es un filtro opcional.
        campoMarca.setOnItemClickListener { parent, _, position, _ ->
            val marca = parent.getItemAtPosition(position).toString()

            actualizandoDesdeCatalogo = true
            campoMarca.setText(marca, false)
            campoModelo.setText("", false)
            campoMotor.setText("", false)
            actualizandoDesdeCatalogo = false

            limpiarDatosVersion()
            campoModelo.requestFocus()
        }

        // Buscar modelo y motor simultáneamente.
        campoModelo.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                if (actualizandoDesdeCatalogo) return

                vehicleSearchRunnable?.let {
                    vehicleSearchHandler.removeCallbacks(it)
                }

                val consulta = s?.toString()?.trim().orEmpty()
                val solicitud = ++vehicleSearchRequestId

                if (consulta.length < 2) {
                    campoModelo.setAdapter(
                        VehicleVariantAdapter(
                            this@CallFormActivity,
                            emptyList()
                        )
                    )
                    campoModelo.dismissDropDown()
                    return
                }

                val textoMarca = campoMarca.text.toString().trim()

                val marcaSeleccionada = marcasCatalogo.firstOrNull {
                    it.equals(textoMarca, ignoreCase = true)
                }.orEmpty()

                val tarea = Runnable {
                    vehicleSearchExecutor.execute {
                        val resultados = try {
                            catalogoVehiculos.buscarVariantesCombinadas(
                                marca = marcaSeleccionada,
                                textoBusqueda = consulta,
                                limite = 50
                            )
                        } catch (e: Exception) {
                            Log.e(
                                "DesguaceCRM",
                                "Error buscando vehículos",
                                e
                            )
                            emptyList()
                        }

                        runOnUiThread {
                            if (
                                isFinishing ||
                                isDestroyed ||
                                solicitud != vehicleSearchRequestId ||
                                actualizandoDesdeCatalogo
                            ) {
                                return@runOnUiThread
                            }

                            campoModelo.setAdapter(
                                VehicleVariantAdapter(
                                    this@CallFormActivity,
                                    resultados
                                )
                            )

                            if (
                                campoModelo.hasFocus() &&
                                resultados.isNotEmpty()
                            ) {
                                campoModelo.showDropDown()
                            }
                        }
                    }
                }

                vehicleSearchRunnable = tarea
                vehicleSearchHandler.postDelayed(tarea, 300)
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Al seleccionar una versión, completamos todos sus datos.
        campoModelo.setOnItemClickListener { parent, _, position, _ ->

            val variante = parent.getItemAtPosition(position)
                    as? VehicleVariant ?: return@setOnItemClickListener

            vehicleSearchRunnable?.let {
                vehicleSearchHandler.removeCallbacks(it)
            }

            vehicleSearchRunnable = null
            vehicleSearchRequestId++

            actualizandoDesdeCatalogo = true

            try {
                aplicarVarianteVehiculo(variante)
            } finally {
                actualizandoDesdeCatalogo = false
            }
        }
    }


    private fun cargarModelosVehiculo(marca: String) {
        val modelos = if (marca.isBlank()) {
            emptyList()
        } else {
            catalogoVehiculos.buscarModelos(marca)
        }

        campoModelo.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                modelos
            )
        )
    }

    private fun prepararVariantesDesdeModelo() {
        val marca = campoMarca.text.toString().trim()
        val textoModelo = campoModelo.text.toString().trim()

        if (marca.isBlank() || textoModelo.isBlank()) {
            return
        }

        val modeloCanonico = catalogoVehiculos
            .buscarModelos(marca)
            .firstOrNull {
                it.equals(textoModelo, ignoreCase = true)
            }

        if (modeloCanonico != null) {
            campoModelo.setText(modeloCanonico, false)

            cargarVariantesVehiculo(
                marca,
                modeloCanonico
            )
        }
    }

    private fun cargarVariantesVehiculo(
        marca: String,
        modelo: String
    ) {
        val variantes = catalogoVehiculos.buscarVariantes(
            marca = marca,
            modelo = modelo
        )

        campoMotor.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                variantes
            )
        )
    }

    private fun limpiarDatosVersion() {
        campoMotor.setText("", false)

        findViewById<EditText>(R.id.etCodigosMotor).setText("")
        findViewById<EditText>(R.id.etCombustible).setText("")
        findViewById<EditText>(R.id.etPuertas).setText("")
        findViewById<EditText>(R.id.etPotenciaCv).setText("")
        findViewById<EditText>(R.id.etPotenciaKw).setText("")
        findViewById<EditText>(R.id.etRangoFabricacion).setText("")
        findViewById<EditText>(R.id.etAnio).setText("")
    }

    private fun aplicarVarianteVehiculo(
        variante: VehicleVariant
    ) {
        campoMarca.setText(variante.marca, false)
        campoModelo.setText(variante.modelo, false)
        campoMotor.setText(variante.motor, false)

        findViewById<EditText>(R.id.etCodigosMotor)
            .setText(variante.codigosMotor)

        findViewById<EditText>(R.id.etCombustible)
            .setText(variante.combustible)

        findViewById<EditText>(R.id.etPuertas)
            .setText(variante.puertas)

        findViewById<EditText>(R.id.etPotenciaCv)
            .setText(variante.potenciaCv)

        findViewById<EditText>(R.id.etPotenciaKw)
            .setText(variante.potenciaKw)

        val rango = when {
            variante.anioDesde.isNotBlank() &&
                    variante.anioHasta.isNotBlank() ->
                "${variante.anioDesde}–${variante.anioHasta}"

            variante.anioDesde.isNotBlank() ->
                "Desde ${variante.anioDesde}"

            variante.anioHasta.isNotBlank() ->
                "Hasta ${variante.anioHasta}"

            else -> ""
        }

        findViewById<EditText>(R.id.etRangoFabricacion)
            .setText(rango)
    }


    private class VehicleVariantAdapter(
        context: Context,
        opciones: List<VehicleVariant>
    ) : ArrayAdapter<VehicleVariant>(
        context,
        android.R.layout.simple_dropdown_item_1line,
        ArrayList(opciones)
    ) {

        private val opcionesDisponibles: List<VehicleVariant> =
            opciones.toList()

        private val filtroCatalogo = object : Filter() {

            override fun performFiltering(
                constraint: CharSequence?
            ): FilterResults {
                // Devolvemos una lista nueva y modificable.
                val elementos = ArrayList(opcionesDisponibles)

                return FilterResults().apply {
                    values = elementos
                    count = elementos.size
                }
            }

            override fun publishResults(
                constraint: CharSequence?,
                results: FilterResults?
            ) {
                @Suppress("UNCHECKED_CAST")
                val elementos = results?.values as?
                        List<VehicleVariant> ?: emptyList()

                // La lista interna del adaptador ahora sí es modificable.
                clear()
                addAll(elementos)
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter {
            return filtroCatalogo
        }
    }


    override fun onDestroy() {
        val cambioConfiguracion = isChangingConfigurations

        if (!cambioConfiguracion) {
            getSharedPreferences("desguace_crm", MODE_PRIVATE)
                .edit()
                .putBoolean("call_form_abierto", false)
                .apply()
        }

        super.onDestroy()

        if (!cambioConfiguracion) {
            CallActionWebSocket.solicitarSincronizacion(
                applicationContext
            )
        }
    }

}


