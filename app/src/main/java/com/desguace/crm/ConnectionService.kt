package com.desguace.crm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ConnectionService : Service() {

    override fun onCreate() {
        super.onCreate()
        crearCanal(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val estado = prefs.getString(KEY_STATUS, "Iniciando conexion...") 
            ?: "Iniciando conexion..."

        val notification = crearNotificacion(this, estado)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        CallActionWebSocket.conectar(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "desguace_crm_connection"
        private const val NOTIFICATION_ID = 7314
        private const val PREFS = "desguace_crm_connection_state"
        private const val KEY_STATUS = "status"

        fun iniciar(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun actualizarEstado(context: Context, host: String?, conectado: Boolean) {
            val appContext = context.applicationContext

            val tipo = host?.let { tipoConexion(appContext, it) }
            val estado = when {
                conectado && tipo != null -> "Conectado por $tipo"
                host != null && tipo != null -> "Conectando por $tipo..."
                else -> "Sin conexion; reconectando..."
            }

            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATUS, estado)
                .apply()

            crearCanal(appContext)

            val manager = appContext.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

            try {
                manager.notify(
                    NOTIFICATION_ID,
                    crearNotificacion(appContext, estado)
                )
            } catch (_: SecurityException) {
                // Si las notificaciones estan bloqueadas, el servicio sigue funcionando.
            }
        }

        private fun tipoConexion(context: Context, host: String): String {
            return when {
                host.startsWith("127.0.0.1:") -> "USB"
                host.startsWith("100.116.67.134:") -> "VPN"
                host.startsWith("fdc2:36bc:33a5:6e87:") -> "VPN"
                else -> "Wi-Fi"
            }
        }

        private fun crearCanal(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Conexion de DesguaceCRM",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Estado de la conexion y sincronizacion en segundo plano"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }

                val manager = context.getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

                manager.createNotificationChannel(channel)
            }
        }

        private fun crearNotificacion(context: Context, estado: String): Notification {
            val openApp = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                7314,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DesguaceCRM - Conexion")
                .setContentText(estado)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("$estado. Servicio de conexion en segundo plano.")
                )
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build()
        }
    }
}