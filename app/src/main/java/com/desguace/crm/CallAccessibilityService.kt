package com.desguace.crm

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class CallAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CallAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Log.d("CallAccessibility", "Servicio de accesibilidad conectado y listo.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun contestarLlamada(): Boolean {
        val ids = listOf(
            "com.samsung.android.incallui:id/incoming_call_answer",
            "com.samsung.android.incallui:id/answer_button",
            "com.samsung.android.incallui:id/btn_answer",
            "com.samsung.android.incallui:id/accept",
            "com.android.incallui:id/answer_button",
            "com.android.incallui:id/incall_answer",
            "com.google.android.dialer:id/answer_button"
        )
        val palabras = listOf(
            "Contestar", "Responder", "Answer", "Desliza", "Aceptar",
            "Atender", "Swipe", "Slide", "Llamada", "Atender llamada", "Incoming"
        )
        return pulsarPorIdOPalabra(ids, palabras)
    }

    fun colgarLlamada(): Boolean {
        val ids = listOf(
            "com.samsung.android.incallui:id/incoming_call_reject",
            "com.samsung.android.incallui:id/reject_button",
            "com.samsung.android.incallui:id/end_call_button",
            "com.samsung.android.incallui:id/voice_hangup_button",
            "com.samsung.android.incallui:id/btn_end",
            "com.android.incallui:id/incall_end_call",
            "com.android.incallui:id/decline_button",
            "com.google.android.dialer:id/decline_button",
            "com.google.android.dialer:id/incall_end_call"
        )
        val palabras = listOf(
            "Colgar", "Finalizar", "End call", "Rechazar", "Reject",
            "Decline", "Desconectar", "Cancel", "Cancelar", "End"
        )
        return pulsarPorIdOPalabra(ids, palabras)
    }

    fun silenciarLlamada(silenciar: Boolean): Boolean {
        val ids = listOf(
            "com.samsung.android.incallui:id/mute_button",
            "com.android.incallui:id/mute_button",
            "com.google.android.dialer:id/mute_button"
        )
        val palabras = if (silenciar) {
            listOf("Silenciar", "Mute microphone", "Mute", "Micrófono apagado", "Desactivar micrófono")
        } else {
            listOf("Activar micrófono", "Activar sonido", "Unmute microphone", "Unmute", "Micrófono activado")
        }
        return pulsarPorIdOPalabra(ids, palabras)
    }

    private fun pulsarPorIdOPalabra(resourceIds: List<String>, textos: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false

        for (viewId in resourceIds) {
            val nodos = root.findAccessibilityNodeInfosByViewId(viewId)
            for (nodo in nodos) {
                if (hacerClicEnNodoOPadre(nodo)) {
                    Log.d("CallAccessibility", "Clic realizado por ID: $viewId")
                    return true
                }
            }
        }

        for (texto in textos) {
            val nodos = root.findAccessibilityNodeInfosByText(texto)
            for (nodo in nodos) {
                if (hacerClicEnNodoOPadre(nodo)) {
                    Log.d("CallAccessibility", "Clic realizado por texto: $texto")
                    return true
                }
            }
        }

        Log.w("CallAccessibility", "No se encontró ningún nodo coincidente con IDs o textos.")
        return false
    }

    private fun hacerClicEnNodoOPadre(nodo: AccessibilityNodeInfo): Boolean {
        if (nodo.isClickable) {
            nodo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        var padre = nodo.parent
        while (padre != null) {
            if (padre.isClickable) {
                padre.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            padre = padre.parent
        }
        return false
    }
}