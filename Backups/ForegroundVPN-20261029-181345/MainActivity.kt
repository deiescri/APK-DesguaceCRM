
package com.desguace.crm

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors
import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CALL_PERMISSIONS = 200
    }

    private data class RangoFechas(
        val inicio: String,
        val fin: String,
        val etiqueta: String
    )

    private var rangoPersonalizado: RangoFechas? = null

    private val opcionesPeriodo = listOf(
        "Hoy",
        "Ayer",
        "Esta semana",
        "Semana pasada",
        "Este mes",
        "Mes pasado",
        "Fechas personalizadas"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        getSharedPreferences("desguace_crm", MODE_PRIVATE)
            .edit()
            .putBoolean("call_form_abierto", false)
            .apply()

        CallActionWebSocket.solicitarSincronizacion(this)
        aplicarTemaGuardado()
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        UiMotion.enterScreen(findViewById<View>(android.R.id.content))

        actualizarIconoTemaPrincipal()

        findViewById<View>(R.id.btnModoOscuro)?.setOnClickListener {
            alternarModoOscuro()
        }

        val botonMenu = findViewById<android.widget.ImageButton>(
            R.id.btnToggleMenuAccesos
        )

        botonMenu.setOnClickListener {
            mostrarMenuAccesosRapidos()
        }

        findViewById<Button>(R.id.btnImportarCatalogoVehiculos)?.setOnClickListener {
            selectorCsvVehiculos.launch(arrayOf("*/*"))
        }


        findViewById<Button>(R.id.btnCopiasSeguridad)?.setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        findViewById<Button>(R.id.btnNuevaLlamada)?.setOnClickListener {
            startActivity(Intent(this, CallFormActivity::class.java))
        }

        findViewById<Button>(R.id.btnBuscarPieza)?.setOnClickListener {
            startActivity(Intent(this, StockSearchActivity::class.java))
        }

        findViewById<Button>(R.id.btnVerLlamadas)?.setOnClickListener {
            abrirLista(CallListActivity.FILTER_ALL, false)
        }

        findViewById<View>(R.id.cardTotalLlamadas)
            .setOnClickListener {
                abrirLista(CallListActivity.FILTER_ALL, true)
            }

        findViewById<View>(R.id.cardContestadas)
            .setOnClickListener {
                abrirLista(CallListActivity.FILTER_ANSWERED, true)
            }

        findViewById<View>(R.id.cardPerdidas)
            .setOnClickListener {
                abrirLista(CallListActivity.FILTER_MISSED, true)
            }

        findViewById<View>(R.id.cardVendidas)
            .setOnClickListener {
                abrirLista(CallListActivity.FILTER_SOLD, true)
            }

        findViewById<TextView>(R.id.estadoPendiente)
            .setOnClickListener {
                abrirLista(CallListActivity.FILTER_PENDING, false)
            }

        findViewById<TextView>(R.id.estadoReservado)
            .setOnClickListener {
                abrirLista(CallListActivity.FILTER_RESERVED, false)
            }

        findViewById<TextView>(R.id.estadoNoConcretada)
            .setOnClickListener {
                abrirLista(
                    CallListActivity.FILTER_NO_CONCRETADA,
                    false
                )
            }

        findViewById<TextView>(R.id.estadoSinExistencias)
            .setOnClickListener {
                abrirLista(CallListActivity.FILTER_NO_STOCK, false)
            }

        findViewById<TextView>(R.id.estadoVendido)
            .setOnClickListener {
                abrirLista(CallListActivity.FILTER_SOLD, false)
            }

        listOf(
            R.id.cardTotalLlamadas,
            R.id.cardContestadas,
            R.id.cardPerdidas,
            R.id.cardVendidas,
            R.id.estadoPendiente,
            R.id.estadoReservado,
            R.id.estadoSinExistencias,
            R.id.estadoNoConcretada,
            R.id.estadoVendido
        ).forEach { id ->
            findViewById<View>(id).apply {
                isClickable = true
                isFocusable = true
                UiMotion.bindPressFeedback(this)
            }
        }

        configurarSelectorPeriodo()

        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.main)
        ) { v, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        solicitarPermisosTelefonicos()

        // Al conectar el WebSocket con el PC, se sincronizan llamadas, piezas y coches.
        // También se repite la sincronización si el WebSocket vuelve a conectarse.
        CallActionWebSocket.conectar(this@MainActivity)
    }


    private fun actualizarIconoTemaPrincipal() {
        val esOscuro = getSharedPreferences(
            "desguace_crm_prefs",
            MODE_PRIVATE
        ).getBoolean("modo_oscuro", false)

        val icono = if (esOscuro) {
            R.drawable.ic_theme_sun
        } else {
            R.drawable.ic_theme_toggle
        }

        findViewById<android.widget.ImageView>(
            R.id.btnModoOscuro
        )?.setImageResource(icono)
    }


    private fun mostrarMenuAccesosRapidos() {
        val bottomSheet = BottomSheetDialog(this)
        val vista = layoutInflater.inflate(R.layout.dialog_menu_accesos, null)


        val botonModoTema = vista.findViewById<View>(
            R.id.btnMenuModoOscuro
        )

        val esOscuroActivo = getSharedPreferences(
            "desguace_crm_prefs",
            MODE_PRIVATE
        ).getBoolean("modo_oscuro", false)


        val iconoTema = botonModoTema?.findViewById<android.widget.ImageView>(
            R.id.ivIconoModoTema
        )

        iconoTema?.setImageResource(
            if (esOscuroActivo) {
                R.drawable.ic_theme_sun
            } else {
                R.drawable.ic_theme_toggle
            }
        )

        iconoTema?.contentDescription = if (esOscuroActivo) {
            "Cambiar al tema claro"
        } else {
            "Cambiar al tema oscuro"
        }


        botonModoTema?.contentDescription = if (esOscuroActivo) {
            "Cambiar al tema claro"
        } else {
            "Cambiar al tema oscuro"
        }


        vista.findViewById<View>(R.id.btnCerrarMenu)?.setOnClickListener {
            bottomSheet.dismiss()
        }

        vista.findViewById<View>(R.id.btnMenuNuevaLlamada)?.setOnClickListener {
            bottomSheet.dismiss()
            startActivity(Intent(this, CallFormActivity::class.java))
        }

        vista.findViewById<View>(R.id.btnMenuBuscarPieza)?.setOnClickListener {
            bottomSheet.dismiss()
            startActivity(Intent(this, StockSearchActivity::class.java))
        }

        vista.findViewById<View>(R.id.btnMenuBuscarVehiculo)?.setOnClickListener {
            bottomSheet.dismiss()
            startActivity(Intent(this, VehicleSearchActivity::class.java))
        }

        vista.findViewById<View>(R.id.btnMenuInventarioCoches)?.setOnClickListener {
            bottomSheet.dismiss()
            startActivity(Intent(this, YardInventoryActivity::class.java))
        }

        vista.findViewById<View>(R.id.btnMenuCopiasSeguridad)?.setOnClickListener {
            bottomSheet.dismiss()
            startActivity(Intent(this, BackupActivity::class.java))
        }

        vista.findViewById<View>(R.id.btnMenuImportarCatalogoVehiculos)?.setOnClickListener {
            bottomSheet.dismiss()
            selectorCsvVehiculos.launch(arrayOf("*/*"))
        }

        vista.findViewById<View>(R.id.btnMenuVerLlamadas)?.setOnClickListener {
            bottomSheet.dismiss()
            abrirLista(CallListActivity.FILTER_ALL, false)
        }

        botonModoTema?.setOnClickListener {
            bottomSheet.dismiss()
            alternarModoOscuro()
        }

        bottomSheet.setContentView(vista)
        bottomSheet.show()
    }

    private fun alternarModoOscuro() {
        val prefs = getSharedPreferences("desguace_crm_prefs", MODE_PRIVATE)
        val esOscuroActivo = prefs.getBoolean("modo_oscuro", false)
        val nuevoEstado = !esOscuroActivo

        prefs.edit().putBoolean("modo_oscuro", nuevoEstado).apply()

        AppCompatDelegate.setDefaultNightMode(
            if (nuevoEstado) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
    }

    private fun aplicarTemaGuardado() {
        val prefs = getSharedPreferences("desguace_crm_prefs", MODE_PRIVATE)
        if (prefs.contains("modo_oscuro")) {
            val esOscuro = prefs.getBoolean("modo_oscuro", false)
            AppCompatDelegate.setDefaultNightMode(
                if (esOscuro) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private val importarVehiculosExecutor =
        Executors.newSingleThreadExecutor()

    private val selectorCsvVehiculos =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                importarCatalogoVehiculos(uri)
            }
        }

    private fun importarCatalogoVehiculos(uri: Uri) {
        Toast.makeText(
            this,
            "Importando catálogo de vehículos...",
            Toast.LENGTH_SHORT
        ).show()

        importarVehiculosExecutor.execute {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "No se pudo abrir el archivo seleccionado",
                            Toast.LENGTH_LONG
                        ).show()
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
                        .setMessage(
                            "Ocurrió un error al importar el catálogo:\n${e.message ?: "Error desconocido"}"
                        )
                        .setPositiveButton("Aceptar", null)
                        .show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        importarVehiculosExecutor.shutdown()
    }

    private fun configurarSelectorPeriodo() {
        val spinner = findViewById<Spinner>(R.id.spPeriodo)

        val adaptador = ArrayAdapter(
            this,
            R.layout.item_spinner_periodo,
            opcionesPeriodo
        )

        adaptador.setDropDownViewResource(
            R.layout.item_spinner_periodo_dropdown
        )
        spinner.adapter = adaptador

        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position == 6) {
                        seleccionarFechasPersonalizadas()
                    } else {
                        actualizarEstadisticas()
                    }
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {}
            }
    }

    private fun fechaISO(calendario: Calendar): String {
        return SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.US
        ).format(calendario.time)
    }

    private fun fechaVisible(fecha: String): String {
        return try {
            val entrada = SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            )
            val fechaParseada = entrada.parse(fecha)
                ?: return fecha

            SimpleDateFormat(
                "dd/MM/yyyy",
                Locale.getDefault()
            ).format(fechaParseada)
        } catch (_: Exception) {
            fecha
        }
    }

    private fun inicioDia(calendario: Calendar): Calendar {
        return (calendario.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun calcularRango(): RangoFechas {
        val hoy = inicioDia(Calendar.getInstance())
        val inicio = hoy.clone() as Calendar
        val fin = hoy.clone() as Calendar

        when (
            findViewById<Spinner>(R.id.spPeriodo)
                .selectedItemPosition
        ) {
            1 -> {
                inicio.add(Calendar.DAY_OF_MONTH, -1)
                fin.add(Calendar.DAY_OF_MONTH, -1)
            }

            2 -> {
                inicio.firstDayOfWeek = Calendar.MONDAY
                inicio.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }

            3 -> {
                inicio.firstDayOfWeek = Calendar.MONDAY
                inicio.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                inicio.add(Calendar.DAY_OF_MONTH, -7)

                fin.time = inicio.time
                fin.add(Calendar.DAY_OF_MONTH, 6)
            }

            4 -> {
                inicio.set(Calendar.DAY_OF_MONTH, 1)
            }

            5 -> {
                inicio.set(Calendar.DAY_OF_MONTH, 1)
                inicio.add(Calendar.DAY_OF_MONTH, -1)

                fin.time = inicio.time

                inicio.set(Calendar.DAY_OF_MONTH, 1)
            }

            6 -> {
                rangoPersonalizado?.let { return it }
            }
        }

        val posicion = findViewById<Spinner>(R.id.spPeriodo)
            .selectedItemPosition

        val etiqueta = if (posicion in opcionesPeriodo.indices) {
            opcionesPeriodo[posicion]
        } else {
            "Hoy"
        }

        return RangoFechas(
            fechaISO(inicio),
            fechaISO(fin),
            etiqueta
        )
    }

    private fun seleccionarFechasPersonalizadas() {
        val hoy = inicioDia(Calendar.getInstance())
        val primerDiaMes = hoy.clone() as Calendar
        primerDiaMes.set(Calendar.DAY_OF_MONTH, 1)

        val rangoAnterior = rangoPersonalizado

        val inicioInicial = rangoAnterior?.inicio
            ?: fechaISO(primerDiaMes)

        val finInicial = rangoAnterior?.fin
            ?: fechaISO(hoy)

        seleccionarFecha(inicioInicial) { inicio ->
            seleccionarFecha(finInicial) { finSeleccionado ->

                val fin = if (finSeleccionado < inicio) {
                    Toast.makeText(
                        this,
                        "La fecha final no puede ser anterior a la inicial",
                        Toast.LENGTH_LONG
                    ).show()
                    inicio
                } else {
                    finSeleccionado
                }

                rangoPersonalizado = RangoFechas(
                    inicio,
                    fin,
                    "Fechas personalizadas"
                )

                actualizarEstadisticas()
            }
        }
    }

    private fun seleccionarFecha(
        fechaInicial: String,
        alElegir: (String) -> Unit
    ) {
        val calendario = Calendar.getInstance()

        try {
            val formato = SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            )
            val fecha = formato.parse(fechaInicial)

            if (fecha != null) {
                calendario.time = fecha
            }
        } catch (_: Exception) {}

        val dialogo = DatePickerDialog(
            this,
            { _, anio, mes, dia ->
                val elegido = Calendar.getInstance()
                elegido.set(anio, mes, dia)
                alElegir(fechaISO(elegido))
            },
            calendario.get(Calendar.YEAR),
            calendario.get(Calendar.MONTH),
            calendario.get(Calendar.DAY_OF_MONTH)
        )

        dialogo.setOnCancelListener {
            findViewById<Spinner>(R.id.spPeriodo).setSelection(0)
        }

        dialogo.show()
    }

    private fun abrirLista(
        filtro: String,
        aplicarPeriodo: Boolean
    ) {
        val intent = Intent(this, CallListActivity::class.java).apply {
            putExtra(CallListActivity.EXTRA_FILTER, filtro)

            if (aplicarPeriodo) {
                val rango = calcularRango()
                putExtra("fechaInicioFiltro", rango.inicio)
                putExtra("fechaFinFiltro", rango.fin)
                putExtra(
                    CallListActivity.EXTRA_TODAY_ONLY,
                    rango.inicio == rango.fin &&
                            rango.inicio == fechaISO(inicioDia(Calendar.getInstance()))
                )
            } else {
                putExtra(CallListActivity.EXTRA_TODAY_ONLY, false)
            }
        }

        startActivity(intent)
    }

    private fun solicitarPermisosTelefonicos() {
        val permisos = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )

        val pendientes = permisos.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (pendientes.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Acceso al registro telefónico")
            .setMessage(
                "DesguaceCRM necesita consultar el registro " +
                        "telefónico para identificar llamadas."
            )
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Continuar") { _, _ ->
                requestPermissions(
                    pendientes.toTypedArray(),
                    REQUEST_CALL_PERMISSIONS
                )
            }
            .show()
    }

    @Deprecated("Callback para compatibilidad con Android 9")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_CALL_PERMISSIONS) {
            val concedidos = grantResults.isNotEmpty() &&
                    grantResults.all {
                        it == PackageManager.PERMISSION_GRANTED
                    }

            Toast.makeText(
                this,
                if (concedidos) {
                    "Permisos telefónicos concedidos"
                } else {
                    "Faltan permisos telefónicos"
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        actualizarEstadisticas()
    }

    /** Convierte fechas del servidor (dd/MM/yyyy) y fechas ISO a yyyy-MM-dd para comparar periodos. */
    private fun fechaISOParaFiltro(valor: String): String {
        val texto = valor.trim()
        if (texto.isBlank() || texto.equals("null", ignoreCase = true)) return ""

        val iso = Regex("""^\d{4}-\d{2}-\d{2}""").find(texto)
        if (iso != null) return iso.value

        val formatos = arrayOf(
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy",
            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm",
            "dd-MM-yyyy"
        )

        for (formato in formatos) {
            try {
                val parser = SimpleDateFormat(formato, Locale.US)
                parser.isLenient = false
                val fecha = parser.parse(texto) ?: continue
                return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(fecha)
            } catch (_: Exception) {
                // Probar el siguiente formato admitido.
            }
        }
        return ""
    }
    private fun actualizarEstadisticas() {
        val prefs = getSharedPreferences(
            "desguace_crm",
            MODE_PRIVATE
        )

        val llamadas = try {
            JSONArray(prefs.getString("llamadas", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }

        val rango = calcularRango()

        findViewById<TextView>(R.id.txtTituloPeriodo).text =
            "Resumen: ${rango.etiqueta}"

        findViewById<TextView>(R.id.txtRangoPeriodo).text =
            if (rango.inicio == rango.fin) {
                fechaVisible(rango.inicio)
            } else {
                "${fechaVisible(rango.inicio)} – ${fechaVisible(rango.fin)}"
            }

        var total = 0
        var contestadas = 0
        var perdidas = 0
        var vendidas = 0
        var importeVendido = 0.0
        var noConcretadasTotal = 0

        var reservadasTotal = 0
        var sinStockTotal = 0
        var vendidasTotal = 0
        var pendientesTotal = 0

        for (i in 0 until llamadas.length()) {
            val llamada = llamadas.optJSONObject(i) ?: continue

            val fechaLlamada = fechaISOParaFiltro(llamada.optString("fechaHora"))

            val estado = llamada.optString("estado")
                .lowercase(Locale.ROOT)

            val tipo = llamada.optString("tipoLlamada")

            val esVendida = estado.contains("vendid")

            // Estados comerciales: historial completo.
            when {
                !tipo.equals("Perdida", ignoreCase = true) &&
                        (estado.contains("pendiente") || estado.isBlank()) -> {
                    pendientesTotal++
                }

                estado.contains("reserv") -> {
                    reservadasTotal++
                }

                estado.contains("no tenemos") ||
                        estado.contains("sin existencias") -> {
                    sinStockTotal++
                }

                estado.contains("no concretada") -> {
                    noConcretadasTotal++
                }

                estado.contains("vendid") -> {
                    vendidasTotal++
                }
            }

            // Llamadas: se filtran por la fecha de entrada.
            if (
                fechaLlamada >= rango.inicio &&
                fechaLlamada <= rango.fin
            ) {
                total++

                when {
                    llamada.optString("tipoLlamada").equals("Contestada", ignoreCase = true) -> contestadas++
                    llamada.optString("tipoLlamada").equals("Perdida", ignoreCase = true) -> perdidas++
                }
            }

            // Ventas: se filtran por la fecha real de venta.
            val fechaVenta = fechaISOParaFiltro(llamada.optString("fechaVenta"))

            if (
                esVendida &&
                fechaVenta >= rango.inicio &&
                fechaVenta <= rango.fin
            ) {
                vendidas++

                val precio = llamada.optDouble(
                    "precioVenta",
                    0.0
                )

                if (precio.isFinite() && precio >= 0) {
                    importeVendido += precio
                }
            }
        }

        findViewById<TextView>(R.id.txtTotalLlamadas).text =
            total.toString()

        findViewById<TextView>(R.id.txtContestadas).text =
            contestadas.toString()

        findViewById<TextView>(R.id.txtPerdidas).text =
            perdidas.toString()

        findViewById<TextView>(R.id.txtVendidas).text =
            vendidas.toString()

        findViewById<TextView>(R.id.estadoPendiente).text =
            "PENDIENTE ($pendientesTotal)"

        findViewById<TextView>(R.id.estadoReservado).text =
            "RESERVADO ($reservadasTotal)"

        findViewById<TextView>(R.id.estadoSinExistencias).text =
            "SIN EXISTENCIAS ($sinStockTotal)"

        findViewById<TextView>(R.id.estadoNoConcretada).text =
            "NO CONCRETADA ($noConcretadasTotal)"

        findViewById<TextView>(R.id.estadoVendido).text =
            "VENDIDO ($vendidasTotal)"

        val formatoMoneda = NumberFormat.getCurrencyInstance(
            Locale("es", "ES")
        )

        findViewById<TextView>(R.id.txtImporteVendido).text =
            formatoMoneda.format(importeVendido)
    }
}
