package com.desguace.crm

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/** Consulta offline los vehículos físicos importados en la web del desguace. */
class YardInventoryActivity : AppCompatActivity() {
    private lateinit var cache: WebInventoryCacheDatabase
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var requestId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yard_inventory)
        UiMotion.enterScreen(findViewById<View>(android.R.id.content))
        cache = WebInventoryCacheDatabase(this)
        input = findViewById(R.id.etBuscarYardInventory)
        status = findViewById(R.id.txtYardInventoryStatus)
        results = findViewById(R.id.llYardInventoryResults)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingSearch?.let { handler.removeCallbacks(it) }
                pendingSearch = Runnable { buscar(s?.toString().orEmpty()) }
                handler.postDelayed(pendingSearch!!, 220)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<View>(R.id.btnRefrescarYardInventory).setOnClickListener {
            status.text = "Sincronizando inventario con el servidor…"
            executor.execute {
                WebInventorySyncManager.sincronizar(applicationContext)
                val query = input.text?.toString().orEmpty()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) buscar(query)
                }
            }
        }
        buscar("")
    }

    private fun oscuro(): Boolean {
        val mask = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun buscar(query: String) {
        val request = ++requestId
        status.text = "Consultando inventario local…"
        executor.execute {
            try {
                val totalCache = cache.contarVehiculos()
                val matches = cache.buscarVehiculos(query, if (query.isBlank()) 20 else 80)
                runOnUiThread {
                    if (isFinishing || isDestroyed || request != requestId) return@runOnUiThread
                    status.text = if (totalCache == 0) {
                        "No hay coches sincronizados. Deja DesguaceCRM abierto en el móvil y pulsa «Sincronizar con móvil» en el PC."
                    } else if (query.isBlank()) {
                        "Inventario local: $totalCache coches · mostrando ${matches.size} recientes"
                    } else {
                        "Inventario local: $totalCache coches · ${matches.size} coincidencias para «$query»"
                    }
                    render(matches)
                }
            } catch (e: Exception) {
                android.util.Log.e("YardInventoryActivity", "Error buscando coches sincronizados", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed || request != requestId) return@runOnUiThread
                    status.text = "No se pudo consultar el inventario local."
                    results.removeAllViews()
                }
            }
        }
    }

    private fun render(items: List<CachedYardVehicle>) {
        results.removeAllViews()
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No se encontraron vehículos. Comprueba la matrícula, el código, el bastidor o el modelo."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (oscuro()) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139))
                setPadding(dp(14), dp(28), dp(14), dp(28))
            }
            results.addView(empty)
            return
        }
        items.forEach { results.addView(crearTarjeta(it)) }
        UiMotion.animateItems(results)
    }

    private fun crearTarjeta(item: CachedYardVehicle): LinearLayout {
        val dark = oscuro()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = GradientDrawable().apply {
                setColor(if (dark) Color.rgb(30, 41, 59) else Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (dark) Color.rgb(51, 65, 85) else Color.rgb(226, 232, 240))
            }
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(11) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Tocar para mostrar todos los datos del coche"
        }
        val title = TextView(this).apply {
            val vehicle = listOf(item.marca, item.modelo).filter { it.isNotBlank() }.joinToString(" ")
            text = listOf(item.matricula.ifBlank { "Sin matrícula" }, vehicle).filter { it.isNotBlank() }.joinToString(" · ")
            setTextColor(if (dark) Color.rgb(248, 250, 252) else Color.rgb(15, 23, 42))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }
        head.addView(title)

        val subtitleText = buildList {
            if (item.codigo.isNotBlank()) add("Código: ${item.codigo}")
            if (item.bastidor.isNotBlank()) add("Bastidor: ${item.bastidor}")
        }.joinToString(" · ")
        if (subtitleText.isNotBlank()) {
            head.addView(TextView(this).apply {
                text = subtitleText
                setTextColor(if (dark) Color.rgb(203, 213, 225) else Color.rgb(71, 85, 105))
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
        }

        val metaText = buildList {
            if (item.fechaBaja.isNotBlank()) add("F. Baja: ${item.fechaBaja}")
            if (item.fechaRecepcion.isNotBlank()) add("Recepción: ${item.fechaRecepcion}")
            if (item.estados.isNotBlank()) add(item.estados)
            if (item.almacen.isNotBlank()) add("Almacén: ${item.almacen}")
        }.joinToString("  •  ")
        if (metaText.isNotBlank()) {
            head.addView(TextView(this).apply {
                text = metaText
                setTextColor(if (dark) Color.rgb(134, 239, 172) else Color.rgb(21, 128, 61))
                textSize = 12f
                setPadding(0, dp(5), 0, 0)
            })
        }
        card.addView(head)

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(if (dark) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        addDetail(details, "Matrícula", item.matricula)
        addDetail(details, "Código", item.codigo)
        addDetail(details, "Bastidor", item.bastidor)
        addDetail(details, "Marca", item.marca)
        addDetail(details, "Modelo", item.modelo)
        addDetail(details, "F. Baja", item.fechaBaja)
        addDetail(details, "F. Recepción/compra", item.fechaRecepcion)
        addDetail(details, "Estado", item.estados)
        addDetail(details, "Almacén", item.almacen)

        try {
            val payload = JSONObject(item.payload)
            val original = payload.optJSONObject("datosOriginales") ?: payload
            val keys = original.keys()
            val normalizedSeen = mutableSetOf<String>()
            var shown = 0
            while (keys.hasNext() && shown < 45) {
                val key = keys.next()
                val value = original.opt(key)
                if (value == null || value == JSONObject.NULL || value is JSONObject || value is org.json.JSONArray) continue
                val text = value.toString().trim()
                if (text.isBlank()) continue
                val normKey = key.lowercase(Locale.ROOT)
                if (!normalizedSeen.add(normKey)) continue
                addDetail(details, key, text)
                shown++
            }
        } catch (e: Exception) {
            android.util.Log.d("YardInventoryActivity", "No se pudieron interpretar todos los detalles del coche", e)
        }
        card.addView(details)
        head.setOnClickListener { UiMotion.toggleDetails(details) }
        UiMotion.bindPressFeedback(head)
        return card
    }

    private fun addDetail(container: LinearLayout, label: String, value: String) {
        if (value.isBlank()) return
        container.addView(TextView(this).apply {
            text = "$label: $value"
            textSize = 12f
            setTextColor(if (oscuro()) Color.rgb(226, 232, 240) else Color.rgb(51, 65, 85))
            setPadding(0, dp(3), 0, dp(3))
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        requestId++
        pendingSearch?.let { handler.removeCallbacks(it) }
        executor.shutdownNow()
        if (::cache.isInitialized) cache.close()
        super.onDestroy()
    }
}
