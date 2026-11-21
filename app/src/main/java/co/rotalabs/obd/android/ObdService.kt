package co.rotalabs.obd.android

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
import android.util.Log
import androidx.core.content.ContextCompat
import co.rotalabs.obd.ui.MainActivity

/**
 * Mantém o Rota.OBD lendo e gravando com outro app na tela (Maps, música).
 *
 * Sem um serviço em primeiro plano, o Android pode pausar ou encerrar o app assim que ele sai da tela,
 * e a conexão com o adaptador cai. O serviço não faz leitura nenhuma: só mantém o processo com
 * prioridade de primeiro plano e mostra uma notificação fixa enquanto há adaptador conectado ou o app
 * está aguardando o carro ligar. A leitura continua na ViewModel, como antes.
 */
class ObdService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, intent?.getStringExtra(EXTRA_TEXT) ?: "Conectado ao carro")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível entrar em primeiro plano: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "ObdService"
        private const val CHANNEL_ID = "leitura"
        private const val NOTIFICATION_ID = 21
        private const val EXTRA_TEXT = "texto"

        @Volatile
        var running = false
            private set

        /** Liga o serviço (se ainda não estiver ligado) ou só atualiza o texto da notificação. */
        fun show(context: Context, text: String) {
            if (running) {
                context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(context, text))
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java).putExtra(EXTRA_TEXT, text))
                running = true
            } catch (e: Exception) {
                // Android 12+ não deixa ligar serviço em primeiro plano com o app fora da tela.
                Log.w(TAG, "Serviço não iniciado: ${e.message}")
            }
        }

        fun hide(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, ObdService::class.java))
        }

        private fun build(context: Context, text: String): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL_ID) == null) {
                nm?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Leitura do carro", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Aparece enquanto o Rota.OBD está conectado ao adaptador"
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Rota.OBD")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}
