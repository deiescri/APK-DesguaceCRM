package com.desguace.crm

import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

/**
 * Contenedor / Auxiliar maestro para aplicar el efecto Liquid Glass (Cristal Líquido tipo iOS Control Center)
 * en todas las tarjetas, formularios y buscadores de DesguaceCRM.
 */
object LiquidGlassHelper {

    /**
     * Aplica el efecto Liquid Glass con fondo desenfocado en la tarjeta,
     * tinte oscuro de alta legibilidad (#E610192C) y borde especular tipo gota de agua.
     */
    fun aplicarLiquidGlass(
        view: View?,
        radiusDp: Float = 28f,
        blurRadiusPx: Float = 30f,
        opacityAlpha: Float = 0.88f
    ) {
        if (view == null) return
        val context = view.context
        val density = view.resources.displayMetrics.density
        val radiusPx = radiusDp * density

        val bitmapBlur = BackdropBlurEngine.obtenerBitmapDesenfocado(context)

        val capaBlur = if (bitmapBlur != null) {
            BitmapDrawable(view.resources, bitmapBlur).apply {
                tileModeX = android.graphics.Shader.TileMode.REPEAT
                tileModeY = android.graphics.Shader.TileMode.REPEAT
            }
        } else null

        val alphaInt = (opacityAlpha.coerceIn(0.50f, 0.98f) * 255).toInt()
        val fillColor = Color.argb(alphaInt, 16, 25, 44)

        val capaTinte = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radiusPx
            setStroke(
                (1.2f * density).toInt(),
                Color.parseColor("#59FFFFFF")
            )
        }

        val capas = if (capaBlur != null) {
            arrayOf(capaBlur, capaTinte)
        } else {
            arrayOf(capaTinte)
        }

        view.background = LayerDrawable(capas)
        view.clipToOutline = true
    }

    /**
     * Aplica el estilo de cápsula / redondeado de alta legibilidad para campos de entrada TextField.
     */
    fun aplicarGlassInput(view: View?, radiusDp: Float = 14f) {
        if (view == null) return
        val density = view.resources.displayMetrics.density
        val radiusPx = radiusDp * density

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(242, 13, 21, 37))
            cornerRadius = radiusPx
            setStroke(
                (1f * density).toInt(),
                Color.parseColor("#382B3B51")
            )
        }
        view.background = drawable
    }
}