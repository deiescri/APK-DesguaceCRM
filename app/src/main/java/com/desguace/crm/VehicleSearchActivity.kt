package com.desguace.crm

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class VehicleSearchActivity : AppCompatActivity() {

    private lateinit var db: VehicleCatalogDatabase
    private lateinit var etBuscar: EditText
    private lateinit var txtConteo: TextView
    private lateinit var llResultados: LinearLayout

    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var searchRequestId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_search)
        UiMotion.enterScreen(findViewById<View>(android.R.id.content))

        db = VehicleCatalogDatabase(this)

        etBuscar = findViewById(R.id.etBuscarVehiculo)
        txtConteo = findViewById(R.id.txtConteoVehiculos)
        llResultados = findViewById(R.id.llResultadosVehiculos)

        etBuscar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Invalida cuanto antes resultados de consultas anteriores.
                searchRequestId++
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    buscarVehiculos(s?.toString().orEmpty())
                }
                searchHandler.postDelayed(searchRunnable!!, 200)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        buscarVehiculos("")
    }

    private fun esModoOscuro(): Boolean {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private val searchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun buscarVehiculos(consulta: String) {
        val request = ++searchRequestId
        searchExecutor.execute {
            try {
                val vehiculos = db.buscarVariantesCombinadas(textoBusqueda = consulta, limite = 80)
                runOnUiThread {
                    if (isFinishing || isDestroyed || request != searchRequestId) return@runOnUiThread
                    llResultados.removeAllViews()

                    txtConteo.text = if (consulta.isBlank()) {
                        "Catálogo disponible (${vehiculos.size} recientes)"
                    } else {
                        "${vehiculos.size} resultados para \"$consulta\""
                    }

                    if (vehiculos.isEmpty()) {
                        val vacio = TextView(this@VehicleSearchActivity).apply {
                            text = "No se encontraron vehículos coincidentes."
                            setTextColor(if (esModoOscuro()) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139))
                            textSize = 14f
                            setPadding(dp(12), dp(24), dp(12), dp(24))
                            gravity = Gravity.CENTER
                        }
                        llResultados.addView(vacio)
                        return@runOnUiThread
                    }

                    vehiculos.forEach { vehiculo ->
                        llResultados.addView(crearTarjetaVehiculo(vehiculo))
                    }
                    UiMotion.animateItems(llResultados)
                }
            } catch (e: Exception) {
                android.util.Log.e("VehicleSearchActivity", "Error buscando vehículos", e)
            }
        }
    }

    private fun crearTarjetaVehiculo(vehiculo: VehicleVariant): LinearLayout {
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

        val resumenHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Tocar para ver todos los detalles del vehículo"
        }

        val iconoVehiculo = ImageView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (modoOscuro) Color.rgb(15, 118, 110) else Color.rgb(204, 251, 241))
            }
            setImageResource(R.drawable.ic_car)
            setColorFilter(if (modoOscuro) Color.rgb(204, 251, 241) else Color.rgb(15, 118, 110))
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginEnd = dp(12)
            }
        }
        resumenHeader.addView(iconoVehiculo)

        val infoBloque = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val txtTitulo = TextView(this).apply {
            text = "${vehiculo.marca} ${vehiculo.modelo}".trim()
            setTextColor(if (modoOscuro) Color.rgb(248, 250, 252) else Color.rgb(15, 23, 42))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        infoBloque.addView(txtTitulo)

        val etiquetaMotor = vehiculo.etiqueta()
        if (etiquetaMotor.isNotBlank()) {
            val txtEtiqueta = TextView(this).apply {
                text = etiquetaMotor
                setTextColor(if (modoOscuro) Color.rgb(203, 213, 225) else Color.rgb(71, 85, 105))
                textSize = 13f
            }
            infoBloque.addView(txtEtiqueta)
        }

        resumenHeader.addView(infoBloque)

        val bloqueBadges = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
        }

        if (vehiculo.fRecepcion.isNotBlank()) {
            val pillEntrada = TextView(this).apply {
                text = "Entrada: ${vehiculo.fRecepcion}"
                setTextColor(if (modoOscuro) Color.rgb(134, 239, 172) else Color.rgb(21, 128, 61))
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(if (modoOscuro) Color.rgb(20, 83, 45) else Color.rgb(240, 253, 244))
                    cornerRadius = dp(12).toFloat()
                }
            }
            bloqueBadges.addView(pillEntrada)
        }

        if (vehiculo.fBaja.isNotBlank()) {
            val pillBaja = TextView(this).apply {
                text = "Baja: ${vehiculo.fBaja}"
                setTextColor(if (modoOscuro) Color.rgb(252, 165, 165) else Color.rgb(185, 28, 28))
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(if (modoOscuro) Color.rgb(127, 29, 29) else Color.rgb(254, 226, 226))
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                }
            }
            bloqueBadges.addView(pillBaja)
        }

        resumenHeader.addView(bloqueBadges)

        tarjeta.addView(resumenHeader)

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

        if (vehiculo.fRecepcion.isNotBlank()) {
            agregarDetalle(detalles, "📅 F.Recepción/Compra (Entrada)", vehiculo.fRecepcion, destacado = true)
        }
        if (vehiculo.fBaja.isNotBlank()) {
            agregarDetalle(detalles, "📄 F.Baja (Baja oficial)", vehiculo.fBaja, destacado = true)
        }
        if (vehiculo.matricula.isNotBlank()) {
            agregarDetalle(detalles, "🚘 Matrícula", vehiculo.matricula)
        }
        if (vehiculo.bastidor.isNotBlank()) {
            agregarDetalle(detalles, "🔢 Bastidor (VIN)", vehiculo.bastidor)
        }
        if (vehiculo.proveedor.isNotBlank()) {
            agregarDetalle(detalles, "👤 Proveedor", vehiculo.proveedor)
        }

        agregarDetalle(detalles, "Marca", vehiculo.marca)
        agregarDetalle(detalles, "Modelo", vehiculo.modelo)
        agregarDetalle(detalles, "Motor / Versión", vehiculo.motor)
        agregarDetalle(detalles, "Códigos motor", vehiculo.codigosMotor)
        agregarDetalle(detalles, "Combustible", vehiculo.combustible)
        agregarDetalle(detalles, "Puertas", if (vehiculo.puertas.isNotBlank()) "${vehiculo.puertas} puertas" else "")
        agregarDetalle(detalles, "Potencia", if (vehiculo.potenciaCv.isNotBlank()) "${vehiculo.potenciaCv} CV" else "")
        agregarDetalle(detalles, "Período fabricación", if (vehiculo.anioDesde.isNotBlank()) "${vehiculo.anioDesde}–${vehiculo.anioHasta}" else "")

        val btnRegistrar = Button(this).apply {
            text = "Registrar llamada para este coche"
            isAllCaps = false
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(37, 99, 235))
            setTextColor(Color.WHITE)

            setOnClickListener {
                val intent = Intent(this@VehicleSearchActivity, CallFormActivity::class.java).apply {
                    putExtra("marca", vehiculo.marca)
                    putExtra("modelo", vehiculo.modelo)
                    putExtra("motor", vehiculo.motor)
                }
                startActivity(intent)
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                topMargin = dp(12)
            }
        }
        detalles.addView(btnRegistrar)

        tarjeta.addView(detalles)

        resumenHeader.setOnClickListener { UiMotion.toggleDetails(detalles) }
        UiMotion.bindPressFeedback(resumenHeader)

        return tarjeta
    }

    private fun agregarDetalle(
        contenedor: LinearLayout,
        etiqueta: String,
        valor: String,
        destacado: Boolean = false
    ) {
        if (valor.isBlank() || valor == "null") return

        val modoOscuro = esModoOscuro()
        val txt = TextView(this).apply {
            text = "$etiqueta: $valor"
            setTextColor(
                if (destacado) {
                    if (modoOscuro) Color.rgb(134, 239, 172) else Color.rgb(21, 128, 61)
                } else {
                    if (modoOscuro) Color.rgb(203, 213, 225) else Color.rgb(51, 65, 85)
                }
            )
            textSize = if (destacado) 14f else 13f
            setPadding(0, dp(3), 0, dp(3))

            if (destacado) {
                setTypeface(null, Typeface.BOLD)
            }
        }

        contenedor.addView(txt)
    }

    private fun dp(valor: Int): Int {
        return (valor * resources.displayMetrics.density).toInt()
    }
    override fun onDestroy() {
        searchRequestId++
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchExecutor.shutdownNow()
        super.onDestroy()
    }

}