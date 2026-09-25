package com.osone.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Widget da tela inicial: "Falar" abre o Live; o resto do cartão mostra a próxima rotina e abre a aba Rotinas. */
class OstieWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        /** Chamado quando as rotinas mudam ou disparam. Nunca derruba quem chamou. */
        fun refresh(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, OstieWidget::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
            } catch (_: Exception) { }
        }

        private fun views(context: Context): RemoteViews {
            val now = System.currentTimeMillis()
            val next = RoutineStore.get(context).routines.filter { it.enabled && !it.byEvent }
                .map { it to RoutineSchedule.next(now, it.hour, it.minute, it.days) }
                .minByOrNull { it.second }
            return RemoteViews(context.packageName, R.layout.widget_ostie).apply {
                setTextViewText(R.id.widget_next, next?.let { (routine, at) ->
                    "Próxima: ${routine.title} · ${RoutineSchedule.whenLabel(now, at)}"
                } ?: context.getString(R.string.widget_no_routines))
                setOnClickPendingIntent(R.id.widget_talk, open(context, 31, MainActivity.ACTION_LIVE))
                setOnClickPendingIntent(R.id.widget_root, open(context, 32, MainActivity.ACTION_ROUTINES))
            }
        }

        private fun open(context: Context, code: Int, action: String): PendingIntent = PendingIntent.getActivity(context, code,
            Intent(context, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
