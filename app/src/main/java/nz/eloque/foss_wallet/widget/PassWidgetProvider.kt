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

        /**
         * V7: The same 24dp corner radius is used for both widget sizes.
         * Scaling is uniform to preserve circular corners when a bitmap is
         * resized by the launcher. All gloss/border layers use the same radius.
         */
        private fun glassPanelBitmap(context: Context, widgetId: Int, tint: Int, isCompact: Boolean): Bitmap {
            val density = context.resources.displayMetrics.density
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val defaultWidth = if (isCompact) 160 else 320
            // Match the V6.1 2x2 card's visible area, not its padded launcher cell.
            val hInset = if (isCompact) 10f else 0f
            val vInset = if (isCompact) 18f else 0f
            val dpWidth = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defaultWidth) - 2f * hInset)
                .coerceIn(68f, 600f)
            val dpHeight = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160) - 2f * vInset)
                .coerceIn(68f, 500f)
            val desiredWidth = dpWidth * density
            val desiredHeight = dpHeight * density
            // Uniform downscale to avoid excessive RemoteViews bitmap memory.
            val scale = minOf(1f, 540f / desiredWidth, 540f / desiredHeight)
            val width = (desiredWidth * scale).toInt().coerceAtLeast(128)
            val height = (desiredHeight * scale).toInt().coerceAtLeast(128)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val unit = density * scale
            val edge = 1.2f * unit
            val radius = (24f * unit).coerceAtMost(minOf(width, height) * 0.35f)
            val rect = RectF(edge, edge, width - edge, height - edge)

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
            paint.strokeWidth = 1.1f * unit
            paint.color = Color.argb(114, 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, paint)

            val innerInset = 2.0f * unit
            val inner = RectF(innerInset, innerInset, width - innerInset, height - innerInset)
            paint.strokeWidth = 0.65f * unit
            paint.color = Color.argb(40, 255, 255, 255)
            val innerRadius = (radius - (innerInset - edge)).coerceAtLeast(1f)
            canvas.drawRoundRect(inner, innerRadius, innerRadius, paint)
            return bitmap
        }

        /** V7: Fully rounded, symmetric pill; gloss and border share one outline. */
        private fun glassButtonBitmap(): Bitmap {
            val width = 280
            val height = 88
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val edge = 2f
            val rect = RectF(edge, edge, width - edge, height - edge)
            val radius = (height - 2f * edge) / 2f
            paint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.argb(242, 248, 252, 255), Color.argb(214, 225, 241, 250)),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.argb(65, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(191, 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, paint)
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
