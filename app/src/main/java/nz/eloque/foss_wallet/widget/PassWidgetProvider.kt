package nz.eloque.foss_wallet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Color
import android.graphics.Canvas
import android.net.Uri
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import nz.eloque.foss_wallet.MainActivity
import nz.eloque.foss_wallet.R
import nz.eloque.foss_wallet.model.PassColors
import nz.eloque.foss_wallet.shortcut.ShortcutService
import java.io.File

/** Displays the chosen pass as a home-screen ticket card. */
open class PassWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { update(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        update(context, appWidgetManager, appWidgetId)
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

        /** Render a semi-transparent glass panel colored by the selected pass. */
        private fun glassPanelBitmap(context: Context, widgetId: Int, tint: Int, isCompact: Boolean): Bitmap {
            val density = context.resources.displayMetrics.density
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val fallbackWidth = if (isCompact) 160 else 320
            val dpWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, fallbackWidth)
                .coerceIn(100, 500)
            val dpHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
                .coerceIn(100, 300)
            // Limit RemoteViews bitmap memory, even on high-density phones.
            val width = (dpWidth * density).toInt().coerceIn(160, 440)
            val height = (dpHeight * density).toInt().coerceIn(120, 255)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = 0.115f * minOf(width, height)
            val rect = RectF(1.5f, 1.5f, width - 1.5f, height - 1.5f)
            fun blend(channel: Int, with: Int, fraction: Float): Int =
                (channel * fraction + with * (1f - fraction)).toInt().coerceIn(0, 255)
            fun panelColor(alpha: Int, level: Int): Int = Color.argb(
                alpha,
                blend(Color.red(tint), level, 0.50f),
                blend(Color.green(tint), level + 4, 0.50f),
                blend(Color.blue(tint), level + 9, 0.50f),
            )
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(panelColor(227, 53), panelColor(208, 26), panelColor(217, 14)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = LinearGradient(
                0f, 0f, width * 0.4f, height * 0.75f,
                intArrayOf(Color.argb(61, 255, 255, 255), Color.argb(12, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = Color.argb(105, 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.strokeWidth = 0.7f
            paint.color = Color.argb(44, 255, 255, 255)
            canvas.drawRoundRect(RectF(3f, 3f, width - 3f, height - 3f), radius - 1f, radius - 1f, paint)
            return bitmap
        }

        /** Frosted translucent pill with glass highlight and fine border. */
        private fun glassButtonBitmap(): Bitmap {
            val width = 280
            val height = 88
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val rect = RectF(1.6f, 1.6f, width - 1.6f, height - 1.6f)
            val radius = 31f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.argb(241, 248, 252, 255), Color.argb(216, 225, 241, 250)),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            paint.color = Color.argb(192, 255, 255, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.3f
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.color = Color.argb(89, 255, 255, 255)
            paint.strokeWidth = 1.5f
            canvas.drawLine(radius, 4f, width - radius, 4f, paint)
            return bitmap
        }

        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = preferences(context)
            val passId = prefs.getString(key(widgetId, "passId"), null)
            val isCompact = manager.getAppWidgetInfo(widgetId)?.provider?.className ==
                CompactPassWidgetProvider::class.java.name
            val views = RemoteViews(
                context.packageName,
                if (isCompact) R.layout.pass_widget_compact else R.layout.pass_widget,
            )

            val background = prefs.getInt(key(widgetId, "background"), BG_FALLBACK)
            val foreground = prefs.getInt(key(widgetId, "foreground"), FG_FALLBACK)
            val label = prefs.getInt(key(widgetId, "label"), LABEL_FALLBACK)

            views.setImageViewBitmap(
                R.id.pass_widget_background_image,
                glassPanelBitmap(context, widgetId, background, isCompact),
            )
            views.setImageViewBitmap(R.id.pass_widget_action_background_image, glassButtonBitmap())
            views.setTextColor(R.id.pass_widget_title, Color.WHITE)
            views.setTextColor(R.id.pass_widget_issuer, Color.rgb(220, 232, 244))
            views.setTextColor(R.id.pass_widget_caption, Color.rgb(220, 232, 244))
            views.setTextColor(R.id.pass_widget_action, Color.rgb(27, 54, 76))

            val clickIntent: Intent
            if (passId.isNullOrBlank()) {
                views.setTextViewText(R.id.pass_widget_title, "Ticket auswählen")
                views.setTextViewText(R.id.pass_widget_issuer, "FossWallet")
                views.setTextViewText(R.id.pass_widget_action, if (isCompact) "Wählen" else "Auswählen ↗")
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
                views.setTextViewText(R.id.pass_widget_action, if (isCompact) "Öffnen ↗" else "Ticket öffnen ↗")
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


/** Smaller 2x2 widget, reuses pass selection and navigation. */
class CompactPassWidgetProvider : PassWidgetProvider()
