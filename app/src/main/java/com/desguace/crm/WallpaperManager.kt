package com.desguace.crm

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.widget.ImageView

object WallpaperManager {
    private const val PREFS = "desguace_crm_wallpaper"
    private const val KEY_URI = "wallpaper_uri"
    private const val KEY_PRESET = "wallpaper_preset"

    fun aplicarFondo(context: Context, imageView: ImageView?) {
        if (imageView == null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val customUriStr = prefs.getString(KEY_URI, "")

        if (!customUriStr.isNullOrBlank()) {
            try {
                imageView.setImageURI(Uri.parse(customUriStr))
                aplicarBlur(imageView)
                return
            } catch (_: Exception) {}
        }

        imageView.setImageResource(R.drawable.img_splash_car)
        aplicarBlur(imageView)
    }

    private fun aplicarBlur(imageView: ImageView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val blurEffect = RenderEffect.createBlurEffect(
                    55f,
                    55f,
                    Shader.TileMode.CLAMP
                )
                imageView.setRenderEffect(blurEffect)
            } catch (_: Exception) {}
        }
    }

    fun guardarImagenPersonalizada(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_PRESET, "custom")
            .apply()
    }

    fun guardarPreset(context: Context, preset: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, "")
            .putString(KEY_PRESET, preset)
            .apply()
    }
}