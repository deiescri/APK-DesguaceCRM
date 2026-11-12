package com.desguace.crm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View

object BackdropBlurEngine {

    private var cachedBlurredBitmap: Bitmap? = null

    /**
     * Prepara el bitmap desenfocado a partir del fondo de pantalla.
     */
    fun obtenerBitmapDesenfocado(context: Context): Bitmap? {
        if (cachedBlurredBitmap != null && !cachedBlurredBitmap!!.isRecycled) {
            return cachedBlurredBitmap
        }

        return try {
            val original = BitmapFactory.decodeResource(context.resources, R.drawable.img_splash_car)
                ?: return null

            val width = 360
            val height = (original.height * (360f / original.width)).toInt().coerceAtLeast(360)
            val scaled = Bitmap.createScaledBitmap(original, width, height, true)

            val blurred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val renderNode = android.graphics.RenderNode("BlurNode")
                renderNode.setPosition(0, 0, width, height)
                val canvas = renderNode.beginRecording()
                canvas.drawBitmap(scaled, 0f, 0f, null)
                renderNode.endRecording()
                renderNode.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(35f, 35f, Shader.TileMode.CLAMP))

                val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val c = Canvas(result)
                c.drawRenderNode(renderNode)
                result
            } else {
                fastBlur(scaled, 25)
            }

            cachedBlurredBitmap = blurred
            blurred
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Aplica el efecto de fondo nítido con desenfoque únicamente dentro del cuadro de la tarjeta.
     */
    fun aplicarBackdropBlurACard(view: View?, radiusDp: Float = 28f) {
        if (view == null) return
        val context = view.context
        val density = view.resources.displayMetrics.density
        val radiusPx = radiusDp * density

        val bitmapDesenfocado = obtenerBitmapDesenfocado(context)

        val capaBlur = if (bitmapDesenfocado != null) {
            BitmapDrawable(view.resources, bitmapDesenfocado).apply {
                tileModeX = Shader.TileMode.REPEAT
                tileModeY = Shader.TileMode.REPEAT
            }
        } else null

        val capaTinte = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.argb(205, 15, 23, 42))
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

        val layerDrawable = LayerDrawable(capas)
        view.background = layerDrawable
        view.clipToOutline = true
    }

    private fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return sentBitmap
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int; var gsum: Int; var bsum: Int
        var x: Int; var y: Int; var i: Int; var p: Int; var yp: Int; var yi: Int; var yw: Int
        val vmin = IntArray(Math.max(w, h))

        var divsum = div + 1 shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) {
            dv[i] = i / divsum
            i++
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var routsum: Int; var goutsum: Int; var bsumOut: Int
        var rinsum: Int; var ginsum: Int; var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0; gsum = 0; rsum = 0; bsumOut = 0; goutsum = 0; routsum = 0; binsum = 0; ginsum = 0; rinsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))]
                val sir = stack[i + radius]
                sir[0] = p and 0xff0000 shr 16
                sir[1] = p and 0x00ff00 shr 8
                sir[2] = p and 0x0000ff
                val rbs = divsum - Math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    bsumOut += sir[2]
                }
                i++
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= bsumOut

                stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                bsumOut -= sir[2]

                if (y == 0) {
                    vmin[x] = Math.min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = p and 0xff0000 shr 16
                sir[1] = p and 0x00ff00 shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val sirNext = stack[stackpointer % div]

                routsum += sirNext[0]
                goutsum += sirNext[1]
                bsumOut += sirNext[2]

                rinsum -= sirNext[0]
                ginsum -= sirNext[1]
                binsum -= sirNext[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            bsum = 0; gsum = 0; rsum = 0; bsumOut = 0; goutsum = 0; routsum = 0; binsum = 0; ginsum = 0; rinsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = Math.max(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                val rbs = divsum - Math.abs(i)
                rsum += r[yi] * rbs
                gsum += r[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    bsumOut += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = -0x1000000 and pix[yi] or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= bsumOut

                stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                bsumOut -= sir[2]

                if (x == 0) {
                    vmin[y] = Math.min(y + radius + 1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val sirNext = stack[stackpointer % div]

                routsum += sirNext[0]
                goutsum += sirNext[1]
                bsumOut += sirNext[2]

                rinsum -= sirNext[0]
                ginsum -= sirNext[1]
                binsum -= sirNext[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}