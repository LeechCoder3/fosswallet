package nz.eloque.foss_wallet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
        private val STRING_KEYS = listOf("passId", "title", "issuer", "validUntil")
        private val COLOR_KEYS = listOf("background", "foreground", "label")

        private const val SOFT_HYPHEN = "­"

        /** Word parts at which a long German ticket name may break, e.g. "Deutschland-" / "semesterticket". */
        private val HYPHEN_STEMS = listOf("semester", "ticket", "karte", "ausweis", "tarif", "verbund", "bahn")

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
            expiresAt: ZonedDateTime? = null,
        ) {
            // Stored as text so the widget never has to open the pass database. null removes the key.
            val validUntil = expiresAt?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            preferences(context).edit()
                .putString(key(widgetId, "passId"), passId)
                .putString(key(widgetId, "title"), title)
                .putString(key(widgetId, "issuer"), issuer)
                .putString(key(widgetId, "validUntil"), validUntil)
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
         * True when most visible pixels of the logo are light. Many pkpass logos are white and made
         * for a coloured pass, so the white logo plate would swallow them.
         */
        private fun isMostlyLight(bitmap: Bitmap): Boolean {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return false
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            var sum = 0.0
            var count = 0
            for (pixel in pixels) {
                if (Color.alpha(pixel) < 128) continue
                sum += 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
                count++
            }
            return count > 0 && sum / count > 200.0
        }

        /**
         * Offers line breaks at common German compound seams, so a name like "Deutschlandsemesterticket"
         * wraps as "Deutschland-" / "semesterticket" instead of in the middle of a syllable.
         * Soft hyphens stay invisible when the whole word fits on one line.
         */
        private fun softHyphenate(text: String): String {
            var result = text
            HYPHEN_STEMS.forEach { stem ->
                result = result.replace(
                    Regex("(?<=\\p{L}{3})(?=$stem)", RegexOption.IGNORE_CASE),
                    SOFT_HYPHEN,
                )
            }
            return result
        }

        /**
         * The card fills the complete area the launcher hands to the widget; there is no outer margin
         * (the old 10dp/18dp insets of the 2x2 card are gone). The bitmap is created at the size the
         * launcher reports for the current orientation, so scaling stays close to 1:1 and the corners
         * stay round. All gloss/border layers use the same 24dp corner radius.
         */
        private fun glassPanelBitmap(context: Context, widgetId: Int, tint: Int, isCompact: Boolean): Bitmap {
            val density = context.resources.displayMetrics.density
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            // In portrait the widget is MIN_WIDTH wide and MAX_HEIGHT high, in landscape MAX_WIDTH x MIN_HEIGHT.
            val reportedWidth = options.getInt(
                if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                0,
            )
            val reportedHeight = options.getInt(
                if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                0,
            )
            val defaultWidth = if (isCompact) 160 else 320
            val defaultHeight = 160
            val dpWidth = (if (reportedWidth > 0) reportedWidth else defaultWidth).toFloat().coerceIn(68f, 600f)
            val dpHeight = (if (reportedHeight > 0) reportedHeight else defaultHeight).toFloat().coerceIn(68f, 500f)
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

            views.setImageViewBitmap(
                R.id.pass_widget_background_image,
                glassPanelBitmap(context, widgetId, background, isCompact),
            )
            views.setTextColor(R.id.pass_widget_title, Color.WHITE)
            views.setTextColor(R.id.pass_widget_issuer, Color.rgb(207, 227, 238))
            views.setTextColor(R.id.pass_widget_caption, Color.rgb(212, 230, 240))
            // 2x2: white arrow on a glass circle. 4x2: dark text on the light pill.
            views.setTextColor(R.id.pass_widget_action, if (isCompact) Color.WHITE else Color.rgb(27, 54, 76))

            val clickIntent: Intent
            val logo: Bitmap?
            if (passId.isNullOrBlank()) {
                views.setTextViewText(R.id.pass_widget_title, "Ticket auswählen")
                views.setTextViewText(R.id.pass_widget_issuer, "FossWallet")
                views.setTextViewText(R.id.pass_widget_caption, "DEIN TICKET")
                views.setTextViewText(R.id.pass_widget_action, if (isCompact) "+" else "Auswählen ↗")
                views.setContentDescription(R.id.pass_widget_action, "Ticket auswählen")
                logo = null
                clickIntent = Intent(context, PassWidgetConfigActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
            } else {
                val title = prefs.getString(key(widgetId, "title"), "Ticket") ?: "Ticket"
                views.setTextViewText(R.id.pass_widget_title, softHyphenate(title))
                views.setTextViewText(
                    R.id.pass_widget_issuer,
                    prefs.getString(key(widgetId, "issuer"), "FossWallet"),
                )
                val validUntil = prefs.getString(key(widgetId, "validUntil"), null)
                views.setTextViewText(
                    R.id.pass_widget_caption,
                    if (validUntil.isNullOrBlank()) "DEIN TICKET" else "GÜLTIG BIS $validUntil".uppercase(),
                )
                views.setTextViewText(R.id.pass_widget_action, if (isCompact) "↗" else "Ticket öffnen ↗")
                views.setContentDescription(R.id.pass_widget_action, "Ticket öffnen")
                logo = logoBitmap(context, passId)
                clickIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("${ShortcutService.BASE_URI}/${Uri.encode(passId)}"),
                    context,
                    MainActivity::class.java,
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            }

            if (logo != null) {
                views.setImageViewBitmap(R.id.pass_widget_logo, logo)
            } else {
                views.setImageViewResource(R.id.pass_widget_logo, R.mipmap.icon)
            }
            views.setInt(
                R.id.pass_widget_logo_plate,
                "setBackgroundResource",
                if (logo != null && isMostlyLight(logo)) {
                    R.drawable.pass_widget_logo_plate_dark
                } else {
                    R.drawable.pass_widget_logo_plate
                },
            )

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
