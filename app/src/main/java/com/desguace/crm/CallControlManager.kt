package com.desguace.crm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log

object CallControlManager {

    private var microfonoSilenciado = false

    @SuppressLint("MissingPermission")
    fun establecerMicrofonoSilenciado(context: Context, silenciado: Boolean): Boolean {
        Log.d("CallControlManager", "Solicitando mute=$silenciado")
        val servicio = CallAccessibilityService.instance
        if (servicio != null && servicio.silenciarLlamada(silenciado)) {
            microfonoSilenciado = silenciado
            Log.d("CallControlManager", "Mute aplicado pulsando el control del sistema.")
            return true
        }
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            audio.setMicrophoneMute(silenciado)
            val aplicado = audio.isMicrophoneMute == silenciado
            if (aplicado) microfonoSilenciado = silenciado
            Log.i("CallControlManager", "AudioManager isMicrophoneMute=${audio.isMicrophoneMute}")
            aplicado
        } catch (e: Exception) {
            Log.e("CallControlManager", "No se pudo cambiar el mute del micrófono", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun contestarLlamada(context: Context): Boolean {
        Log.d("CallControlManager", "Intentando contestar llamada...")

        try {
            if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager != null) {
                    @Suppress("DEPRECATION")
                    telecomManager.acceptRingingCall()
                    Log.d("CallControlManager", "Llamada contestada vía TelecomManager nativo.")
                    return true
                }
            } else {
                Log.w("CallControlManager", "Falta el permiso ANSWER_PHONE_CALLS")
            }
        } catch (e: Exception) {
            Log.e("CallControlManager", "Error con TelecomManager: ${e.message}")
        }

        val serv = CallAccessibilityService.instance
        if (serv != null) {
            val exito = serv.contestarLlamada()
            if (exito) {
                Log.d("CallControlManager", "Llamada contestada vía Servicio de Accesibilidad.")
                return true
            }
        } else {
            Log.w("CallControlManager", "CallAccessibilityService no está conectado o activado.")
        }

        return false
    }

    @SuppressLint("MissingPermission")
    fun colgarLlamada(context: Context): Boolean {
        Log.d("CallControlManager", "Intentando colgar llamada...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    if (telecomManager != null) {
                        @Suppress("DEPRECATION")
                        val exito = telecomManager.endCall()
                        if (exito) {
                            Log.d("CallControlManager", "Llamada colgada vía TelecomManager nativo.")
                            return true
                        } else {
                            Log.w("CallControlManager", "TelecomManager.endCall() devolvió false (no somos la app de teléfono por defecto).")
                        }
                    }
                } else {
                    Log.w("CallControlManager", "Falta el permiso ANSWER_PHONE_CALLS.")
                }
            } catch (e: Exception) {
                Log.e("CallControlManager", "Error colgando vía TelecomManager: ${e.message}")
            }
        }

        val serv = CallAccessibilityService.instance
        if (serv != null) {
            Log.d("CallControlManager", "Servicio de Accesibilidad encontrado. Intentando colgar...")
            val exito = serv.colgarLlamada()
            if (exito) {
                Log.d("CallControlManager", "Llamada colgada vía Servicio de Accesibilidad.")
                return true
            } else {
                Log.e("CallControlManager", "El Servicio de Accesibilidad no pudo encontrar el botón de colgar en pantalla.")
            }
        } else {
            Log.e("CallControlManager", "El Servicio de Accesibilidad NO está activo (instance es null). Revisa Ajustes > Accesibilidad.")
        }

        return false
    }

    @SuppressLint("MissingPermission")
    fun iniciarLlamadaSaliente(context: Context, numero: String): Boolean {
        val appContext = context.applicationContext
        val digits = numero.filter { it.isDigit() }

        if (digits.length !in 8..15) {
            Log.e("CallControlManager", "NÃºmero de telÃ©fono no vÃ¡lido para llamada saliente.")
            return false
        }

        if (appContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.e("CallControlManager", "Falta el permiso CALL_PHONE. ConcÃ©delo desde los ajustes de Android.")
            return false
        }

        val numeroNormalizado = if (numero.trim().startsWith("+")) "+$digits" else digits
        val telecomManager = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager == null) {
            Log.e("CallControlManager", "TelecomManager no estÃ¡ disponible.")
            return false
        }

        return try {
            val address = Uri.fromParts("tel", numeroNormalizado, null)
            telecomManager.placeCall(address, Bundle())
            Log.i("CallControlManager", "Orden de llamada entregada a TelecomManager: $numeroNormalizado")
            true
        } catch (e: SecurityException) {
            Log.e("CallControlManager", "Android rechazÃ³ la llamada por permisos o seguridad.", e)
            false
        } catch (e: Exception) {
            Log.e("CallControlManager", "No se pudo iniciar la llamada saliente.", e)
            false
        }
    }
}
