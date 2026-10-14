package nz.eloque.foss_wallet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import nz.eloque.foss_wallet.MainActivity
import nz.eloque.foss_wallet.R
import nz.eloque.foss_wallet.shortcut.ShortcutService

/** A compact home-screen strip opening exactly one saved FossWallet pass. */
class PassWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { update(context, appWidgetManager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = preferences(context).edit()
        appWidgetIds.forEach { id ->
            editor.remove(key(id, "passId"))
            editor.remove(key(id, "title"))
            editor.remove(key(id, "issuer"))
        }
        editor.apply()
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val prefs = preferences(context)
        val editor = prefs.edit()
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) ->
            listOf("passId", "title", "issuer").forEach { field ->
                prefs.getString(key(old, field), null)?.let { editor.putString(key(new, field), it) }
                editor.remove(key(old, field))
            }
        }
        editor.apply()
        onUpdate(context, AppWidgetManager.getInstance(context), newWidgetIds)
    }

    companion object {
        private const val PREFS_NAME = "fosswallet_pass_widget"

        private fun preferences(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun key(widgetId: Int, field: String) = "$widgetId.$field"

        fun saveSelection(
            context: Context,
            widgetId: Int,
            passId: String,
            title: String,
            issuer: String,
        ) {
            preferences(context).edit()
                .putString(key(widgetId, "passId"), passId)
                .putString(key(widgetId, "title"), title)
                .putString(key(widgetId, "issuer"), issuer)
                .apply()
            update(context, AppWidgetManager.getInstance(context), widgetId)
        }

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = preferences(context)
            val passId = prefs.getString(key(widgetId, "passId"), null)
            val views = RemoteViews(context.packageName, R.layout.pass_widget)

            val clickIntent: Intent
            if (passId.isNullOrBlank()) {
                views.setTextViewText(R.id.pass_widget_title, "Ticket auswählen")
                views.setTextViewText(R.id.pass_widget_issuer, "Tippen zum Einrichten")
                views.setTextViewText(R.id.pass_widget_action, "Wählen")
                clickIntent = Intent(context, PassWidgetConfigActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
            } else {
                views.setTextViewText(
                    R.id.pass_widget_title,
                    prefs.getString(key(widgetId, "title"), "Ticket"),
                )
                views.setTextViewText(
                    R.id.pass_widget_issuer,
                    prefs.getString(key(widgetId, "issuer"), "FossWallet"),
                )
                views.setTextViewText(R.id.pass_widget_action, "Öffnen")
                clickIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("${ShortcutService.BASE_URI}/${Uri.encode(passId)}"),
                    context,
                    MainActivity::class.java,
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            }

            val click = PendingIntent.getActivity(
                context,
                widgetId,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.pass_widget_root, click)
            manager.updateAppWidget(widgetId, views)
        }
    }
}
