package nz.eloque.foss_wallet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import nz.eloque.foss_wallet.MainActivity
import nz.eloque.foss_wallet.R
import nz.eloque.foss_wallet.model.PassColors
import nz.eloque.foss_wallet.shortcut.ShortcutService
import java.io.File

/** Displays the chosen pass as a home-screen ticket card. */
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
            for (field in STRING_KEYS + COLOR_KEYS) editor.remove(key(id, field))
        }
        editor.apply()
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val prefs = preferences(context)
        val editor = prefs.edit()
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) ->
            STRING_KEYS.forEach { field ->
                prefs.getString(key(old, field), null)?.let { editor.putString(key(new, field), it) }
                editor.remove(key(old, field))
            }
            COLOR_KEYS.forEach { field ->
                if (prefs.contains(key(old, field))) {
                    editor.putInt(key(new, field), prefs.getInt(key(old, field), 0))
                }
                editor.remove(key(old, field))
            }
        }
        editor.apply()
        onUpdate(context, AppWidgetManager.getInstance(context), newWidgetIds)
    }

    companion object {
        private const val PREFS_NAME = "fosswallet_pass_widget"
        private const val BG_FALLBACK = 0xFF152F43.toInt()
        private const val FG_FALLBACK = 0xFFFFFFFF.toInt()
        private const val LABEL_FALLBACK = 0xFFBBD6E5.toInt()
        private val STRING_KEYS = listOf("passId", "title", "issuer")
        private val COLOR_KEYS = listOf("background", "foreground", "label")

        private fun preferences(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun key(widgetId: Int, field: String) = "$widgetId.$field"

        /** Save the already parsed pass colors, which FossWallet originally read from the .pkpass. */
        fun saveSelection(
            context: Context,
            widgetId: Int,
            passId: String,
            title: String,
            issuer: String,
            colors: PassColors?,
        ) {
            preferences(context).edit()
                .putString(key(widgetId, "passId"), passId)
                .putString(key(widgetId, "title"), title)
                .putString(key(widgetId, "issuer"), issuer)
                .putInt(key(widgetId, "background"), colors?.background?.toArgb() ?: BG_FALLBACK)
                .putInt(key(widgetId, "foreground"), colors?.foreground?.toArgb() ?: FG_FALLBACK)
                .putInt(key(widgetId, "label"), colors?.label?.toArgb() ?: LABEL_FALLBACK)
                .apply()
            update(context, AppWidgetManager.getInstance(context), widgetId)
        }

        /** Decode a bounded-size bitmap to avoid passing oversized images through RemoteViews. */
        private fun logoBitmap(context: Context, passId: String): Bitmap? {
            val dir = File(context.filesDir, passId)
            val image = listOf("logo.png", "icon.png")
                .map { File(dir, it) }
                .firstOrNull { it.isFile } ?: return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 192 || bounds.outHeight / sampleSize > 192) {
                sampleSize *= 2
            }
            return BitmapFactory.decodeFile(
                image.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        }

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = preferences(context)
            val passId = prefs.getString(key(widgetId, "passId"), null)
            val views = RemoteViews(context.packageName, R.layout.pass_widget)

            val background = prefs.getInt(key(widgetId, "background"), BG_FALLBACK)
            val foreground = prefs.getInt(key(widgetId, "foreground"), FG_FALLBACK)
            val label = prefs.getInt(key(widgetId, "label"), LABEL_FALLBACK)

            // Tint XML shape images rather than replacing the rounded drawable with a flat color.
            views.setInt(R.id.pass_widget_background_image, "setColorFilter", background)
            views.setInt(R.id.pass_widget_action_background_image, "setColorFilter", foreground)
            views.setTextColor(R.id.pass_widget_title, foreground)
            views.setTextColor(R.id.pass_widget_issuer, label)
            views.setTextColor(R.id.pass_widget_caption, label)
            views.setTextColor(R.id.pass_widget_action, background)

            val clickIntent: Intent
            if (passId.isNullOrBlank()) {
                views.setTextViewText(R.id.pass_widget_title, "Ticket auswählen")
                views.setTextViewText(R.id.pass_widget_issuer, "FossWallet")
                views.setTextViewText(R.id.pass_widget_action, "Auswählen ↗")
                views.setImageViewResource(R.id.pass_widget_logo, R.mipmap.icon)
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
                views.setTextViewText(R.id.pass_widget_action, "Ticket öffnen ↗")
                val bitmap = logoBitmap(context, passId)
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.pass_widget_logo, bitmap)
                } else {
                    views.setImageViewResource(R.id.pass_widget_logo, R.mipmap.icon)
                }
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
