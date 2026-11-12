
package com.desguace.crm

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILTER = "filtroLlamadas"
        const val EXTRA_TODAY_ONLY = "soloHoy"

        const val FILTER_ALL = "all"
        const val FILTER_ANSWERED = "answered"
        const val FILTER_MISSED = "missed"
        const val FILTER_PENDING = "pending"
        const val FILTER_RESERVED = "reserved"
        const val FILTER_NO_STOCK = "no_stock"
        const val FILTER_SOLD = "sold"
        const val FILTER_NO_CONCRETADA = "no_concretada"
    }

    private var filtroActual = FILTER_ALL
    private var fechaInicioFiltro: String? = null
    private var fechaFinFiltro: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call_list)
        UiMotion.enterScreen(findViewById<View>(android.R.id.content))

        filtroActual =
            intent.getStringExtra(EXTRA_FILTER) ?: FILTER_ALL

        fechaInicioFiltro =
            intent.getStringExtra("fechaInicioFiltro")

        fechaFinFiltro =
            intent.getStringExtra("fechaFinFiltro")

        // Compatibilidad con las aperturas antiguas que solo
        // enviaban el indicador de "hoy".
        if (
            fechaInicioFiltro.isNullOrBlank() &&
            fechaFinFiltro.isNullOrBlank() &&
            intent.getBooleanExtra(EXTRA_TODAY_ONLY, false)
        ) {
            val hoy = SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            ).format(Date())

            fechaInicioFiltro = hoy
            fechaFinFiltro = hoy
        }

        findViewById<Button>(R.id.btnFiltroTodas)
            .setOnClickListener {
                filtroActual = FILTER_ALL
                cargarLlamadas()
            }

        findViewById<Button>(R.id.btnFiltroContestadas)
            .setOnClickListener {
                filtroActual = FILTER_ANSWERED
                cargarLlamadas()
            }

        findViewById<Button>(R.id.btnFiltroPerdidas)
            .setOnClickListener {
                filtroActual = FILTER_MISSED
                cargarLlamadas()
            }
    }

    override fun onResume() {
        super.onResume()
        cargarLlamadas()
    }

    private fun leerLlamadas(): JSONArray {
        val prefs = getSharedPreferences(
            "desguace_crm",
            MODE_PRIVATE
        )

        return try {
            JSONArray(
                prefs.getString("llamadas", "[]") ?: "[]"
            )
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun tienePeriodo(): Boolean {
        return !fechaInicioFiltro.isNullOrBlank() &&
                !fechaFinFiltro.isNullOrBlank()
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
    private fun dentroDelPeriodo(fecha: String): Boolean {
        if (!tienePeriodo()) return true
        if (fecha.isBlank() || fecha == "null") return false

        val inicio = fechaInicioFiltro ?: return true
        val fin = fechaFinFiltro ?: return true
        val fechaISO = fechaISOParaFiltro(fecha)

        return fechaISO >= inicio && fechaISO <= fin
    }

    // Las estadísticas de llamadas siempre utilizan la fecha
    // en que entró la llamada.
    private fun llamadaDentroDelPeriodo(
        datos: JSONObject
    ): Boolean {
        return dentroDelPeriodo(
            datos.optString("fechaHora")
        )
    }

    // Al consultar ventas desde el dashboard, se utiliza
    // fechaVenta. El resto de filtros utiliza fechaHora.
    private fun registroDentroDelPeriodo(
        datos: JSONObject
    ): Boolean {
        val fecha = if (
            filtroActual == FILTER_SOLD && tienePeriodo()
        ) {
            datos.optString("fechaVenta")
        } else {
            datos.optString("fechaHora")
        }

        return dentroDelPeriodo(fecha)
    }

    private fun coincideFiltro(datos: JSONObject): Boolean {
        val tipo = datos.optString("tipoLlamada")
        val estado = datos.optString("estado")
            .lowercase(Locale.ROOT)

        return when (filtroActual) {
            FILTER_ANSWERED ->
                tipo.equals("Contestada", ignoreCase = true)

            FILTER_MISSED ->
                tipo.equals("Perdida", ignoreCase = true)

            FILTER_PENDING ->
                !tipo.equals("Perdida", ignoreCase = true) &&
                        (estado.contains("pendiente") || estado.isBlank())

            FILTER_RESERVED ->
                estado.contains("reserv")

            FILTER_NO_STOCK ->
                estado.contains("no tenemos") ||
                        estado.contains("sin existencias")

            FILTER_SOLD ->
                estado.contains("vendid")

            FILTER_NO_CONCRETADA ->
                estado.contains("no concretada")

            else -> true
        }
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

    private fun cargarLlamadas() {
        val lista = findViewById<LinearLayout>(
            R.id.llListaLlamadas
        )

        val mensaje = findViewById<TextView>(
            R.id.txtSinLlamadas
        )

        lista.removeAllViews()

        val llamadas = leerLlamadas()

        var totalPeriodo = 0
        var contestadasPeriodo = 0
        var perdidasPeriodo = 0

        // Contadores de llamadas según la fecha de entrada.
        for (i in 0 until llamadas.length()) {
            val llamada = llamadas.optJSONObject(i) ?: continue

            if (!llamadaDentroDelPeriodo(llamada)) continue

            totalPeriodo++

            when (llamada.optString("tipoLlamada")) {
                "Contestada" -> contestadasPeriodo++
                "Perdida" -> perdidasPeriodo++
            }
        }

        actualizarBotones(
            totalPeriodo,
            contestadasPeriodo,
            perdidasPeriodo
        )

        var visibles = 0

        // Mostrar primero las llamadas más recientes.
        for (i in llamadas.length() - 1 downTo 0) {
            val llamada = llamadas.optJSONObject(i) ?: continue

            if (!registroDentroDelPeriodo(llamada)) continue
            if (!coincideFiltro(llamada)) continue

            lista.addView(crearTarjeta(llamada, i))
            visibles++
        }
        UiMotion.animateItems(lista)

        val titulo = when (filtroActual) {
            FILTER_ANSWERED -> "Llamadas contestadas"
            FILTER_MISSED -> "Llamadas perdidas"
            FILTER_PENDING -> "Llamadas pendientes"
            FILTER_RESERVED -> "Piezas reservadas"
            FILTER_NO_STOCK -> "Piezas sin existencias"
            FILTER_SOLD -> "Piezas vendidas"
            FILTER_NO_CONCRETADA -> "Ventas no concretadas"

            else -> if (tienePeriodo()) {
                "Llamadas del periodo"
            } else {
                "Todas las llamadas"
            }
        }

        findViewById<TextView>(R.id.txtTituloFiltro).text = titulo

        val etiquetaRango = if (tienePeriodo()) {
            val inicio = fechaVisible(fechaInicioFiltro!!)
            val fin = fechaVisible(fechaFinFiltro!!)

            if (inicio == fin) inicio else "$inicio – $fin"
        } else {
            ""
        }

        findViewById<TextView>(R.id.txtConteoLlamadas).text =
            if (etiquetaRango.isNotBlank()) {
                "$visibles resultados · $etiquetaRango"
            } else {
                "$visibles resultados"
            }

        mensaje.text = when (filtroActual) {
            FILTER_ANSWERED -> "No hay llamadas contestadas."
            FILTER_MISSED -> "No hay llamadas perdidas."
            FILTER_PENDING -> "No hay llamadas pendientes."
            FILTER_RESERVED -> "No hay piezas reservadas."
            FILTER_NO_STOCK -> "No hay piezas sin existencias."
            FILTER_SOLD -> "No hay piezas vendidas."
            FILTER_NO_CONCRETADA -> "No hay ventas no concretadas."
            else -> "No hay llamadas registradas."
        }

        mensaje.visibility =
            if (visibles == 0) View.VISIBLE else View.GONE
    }

    private fun actualizarBotones(
        total: Int,
        contestadas: Int,
        perdidas: Int
    ) {
        val todas = findViewById<Button>(R.id.btnFiltroTodas)
        val atendidas = findViewById<Button>(
            R.id.btnFiltroContestadas
        )
        val noAtendidas = findViewById<Button>(
            R.id.btnFiltroPerdidas
        )

        todas.text = "Todas ($total)"
        atendidas.text = "Contest. ($contestadas)"
        noAtendidas.text = "Perdidas ($perdidas)"

        aplicarEstilo(
            todas,
            filtroActual == FILTER_ALL,
            "#334155"
        )

        aplicarEstilo(
            atendidas,
            filtroActual == FILTER_ANSWERED,
            "#16A34A"
        )

        aplicarEstilo(
            noAtendidas,
            filtroActual == FILTER_MISSED,
            "#DC2626"
        )
    }

    private fun aplicarEstilo(
        boton: Button,
        seleccionado: Boolean,
        colorActivo: String
    ) {
        boton.backgroundTintList = ColorStateList.valueOf(
            if (seleccionado) {
                Color.parseColor(colorActivo)
            } else {
                Color.rgb(226, 232, 240)
            }
        )

        boton.setTextColor(
            if (seleccionado) {
                Color.WHITE
            } else {
                Color.rgb(51, 65, 85)
            }
        )
    }

    private fun esModoOscuro(): Boolean {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun crearTarjeta(
        datos: JSONObject,
        indice: Int
    ): LinearLayout {
        val modoOscuro = esModoOscuro()

        val tarjeta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#A6121A2D"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#2B3B51"))
            }

            elevation = dp(2).toFloat()
        }

        tarjeta.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(12)
        }

        val tipo = datos.optString("tipoLlamada", "Contestada")
        val esPerdida = tipo.equals("Perdida", ignoreCase = true)

        val telefono = datos.optString("telefono")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: "Número desconocido"

        val nombre = datos.optString("nombre")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: "Nombre pendiente"

        val fecha = datos.optString("fechaHora")
        val estado = datos.optString("estado", "Pendiente")
        val estadoNorm = estado.lowercase(Locale.ROOT)

        // Configuración de Badge Pill por Estado
        val (bgPill, textPill, labelPill) = when {
            estadoNorm.contains("reserv") -> Triple(
                if (modoOscuro) Color.rgb(30, 58, 138) else Color.rgb(239, 246, 255),
                if (modoOscuro) Color.rgb(147, 197, 253) else Color.rgb(29, 78, 216),
                "RESERVADO"
            )
            estadoNorm.contains("no tenemos") || estadoNorm.contains("sin existencias") -> Triple(
                if (modoOscuro) Color.rgb(127, 29, 29) else Color.rgb(254, 226, 226),
                if (modoOscuro) Color.rgb(252, 165, 165) else Color.rgb(220, 38, 38),
                "SIN STOCK"
            )
            estadoNorm.contains("no concretada") -> Triple(
                if (modoOscuro) Color.rgb(124, 45, 18) else Color.rgb(255, 247, 237),
                if (modoOscuro) Color.rgb(253, 186, 116) else Color.rgb(194, 65, 12),
                "NO CONCRETADA"
            )
            estadoNorm.contains("vendid") -> Triple(
                if (modoOscuro) Color.rgb(20, 83, 45) else Color.rgb(240, 253, 244),
                if (modoOscuro) Color.rgb(134, 239, 172) else Color.rgb(21, 128, 61),
                "VENDIDO"
            )
            else -> Triple(
                if (modoOscuro) Color.rgb(51, 65, 85) else Color.rgb(241, 245, 249),
                if (modoOscuro) Color.rgb(226, 232, 240) else Color.rgb(71, 85, 105),
                "PENDIENTE"
            )
        }

        // Encabezado principal de la tarjeta
        val resumenHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Tocar para ver o editar la ficha"
        }

        // Ícono de estado circular
        val iconoAccion = ImageView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    if (esPerdida) {
                        if (modoOscuro) Color.rgb(127, 29, 29) else Color.rgb(254, 226, 226)
                    } else {
                        if (modoOscuro) Color.rgb(30, 58, 138) else Color.rgb(239, 246, 255)
                    }
                )
            }
            setImageResource(if (esPerdida) R.drawable.ic_phone_missed else R.drawable.ic_phone)
            setColorFilter(if (esPerdida) Color.rgb(220, 38, 38) else Color.rgb(37, 99, 235))
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginEnd = dp(12)
            }
        }
        resumenHeader.addView(iconoAccion)

        // Bloque central de información (Teléfono, Nombre, Vehículo/Pieza)
        val infoBloque = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val txtTelefono = TextView(this).apply {
            text = telefono
            setTextColor(if (modoOscuro) Color.rgb(248, 250, 252) else Color.rgb(15, 23, 42))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        }
        infoBloque.addView(txtTelefono)

        val txtNombre = TextView(this).apply {
            text = nombre
            setTextColor(if (modoOscuro) Color.rgb(203, 213, 225) else Color.rgb(71, 85, 105))
            textSize = 13f
        }
        infoBloque.addView(txtNombre)

        val piezaVehiculo = listOf(datos.optString("marca"), datos.optString("modelo"), datos.optString("pieza"))
            .filter { it.isNotBlank() && it != "null" }
            .joinToString(" · ")

        val txtMeta = TextView(this).apply {
            text = if (piezaVehiculo.isNotBlank()) "$fecha  •  $piezaVehiculo" else fecha
            setTextColor(if (modoOscuro) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        }
        infoBloque.addView(txtMeta)

        resumenHeader.addView(infoBloque)

        // Pill de Estado Comercial a la derecha
        val pillEstado = TextView(this).apply {
            text = labelPill
            setTextColor(textPill)
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(bgPill)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }
        resumenHeader.addView(pillEstado)

        tarjeta.addView(resumenHeader)

        // Bloque expandible de detalles
        val detalles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE

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

        agregarDetalle(detalles, "Tipo de llamada", tipo)
        agregarDetalle(detalles, "Pieza", datos.optString("pieza"))
        agregarDetalle(detalles, "Descripción", datos.optString("descripcion"))
        agregarDetalle(detalles, "Lado", datos.optString("lado"))
        agregarDetalle(detalles, "OEM", datos.optString("oem"))
        agregarDetalle(detalles, "Marca", datos.optString("marca"))
        agregarDetalle(detalles, "Modelo", datos.optString("modelo"))
        agregarDetalle(detalles, "Motor", datos.optString("motor"))
        agregarDetalle(detalles, "Año", datos.optString("anio"))

        agregarDetalle(detalles, "Motivo no concretada", datos.optString("motivoNoConcretada"))
        agregarDetalle(detalles, "Precio de venta", if (datos.has("precioVenta")) "${datos.optString("precioVenta")} €" else "")
        agregarDetalle(detalles, "Fecha de venta", datos.optString("fechaVenta"))
        agregarDetalle(detalles, "Apuntes", datos.optString("apuntes"))

        // Fila de Botones de Acción (Editar Ficha y Llamar)
        val filaAcciones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }

        val btnEditar = Button(this).apply {
            text = "Editar ficha completa"
            isAllCaps = false
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(37, 99, 235))
            setTextColor(Color.WHITE)

            setOnClickListener {
                val intent = Intent(this@CallListActivity, CallFormActivity::class.java).apply {
                    putExtra(CallFormActivity.EXTRA_CALL_INDEX, indice)
                }
                startActivity(intent)
            }

            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(8)
            }
        }
        filaAcciones.addView(btnEditar)

        val btnLlamar = ImageButton(this).apply {
            setImageResource(R.drawable.ic_phone)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(22, 163, 74))
            setColorFilter(Color.WHITE)
            contentDescription = "Llamar al cliente"
            setPadding(dp(10), dp(10), dp(10), dp(10))
            scaleType = ImageView.ScaleType.CENTER_INSIDE

            setOnClickListener {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$telefono"))
                startActivity(intent)
            }

            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        filaAcciones.addView(btnLlamar)

        detalles.addView(filaAcciones)
        tarjeta.addView(detalles)

        resumenHeader.setOnClickListener {
            detalles.visibility = if (detalles.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        return tarjeta
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
        val vista = TextView(this).apply {
            text = texto
            setTextColor(color)
            textSize = tamano
            setPadding(0, dp(3), 0, dp(3))

            if (negrita) {
                setTypeface(null, Typeface.BOLD)
            }
        }

        contenedor.addView(vista)
    }

    private fun dp(valor: Int): Int =
        (valor * resources.displayMetrics.density).toInt()
}
