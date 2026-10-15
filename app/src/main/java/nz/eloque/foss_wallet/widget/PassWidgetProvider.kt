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
import android.graphics.RadialGradient
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
import kotlin.math.roundToInt

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

        /** Size of the whole widget area and of the card drawn inside it, in dp. */
        private class WidgetSize(
            val areaWidth: Float,
            val areaHeight: Float,
            val cardWidth: Float,
            val cardHeight: Float,
        )

        /**
         * The 4x2 card fills the complete area the launcher hands to the widget. The 2x2 card is the
         * largest square that fits into that area and sits centred in it (the provider centres it with
         * view padding, see [update]). Sizes come from the launcher for the current orientation.
         */
        private fun widgetSize(context: Context, widgetId: Int, isCompact: Boolean): WidgetSize {
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
            val width = (if (reportedWidth > 0) reportedWidth else if (isCompact) 160 else 320)
                .toFloat().coerceIn(68f, 600f)
            val height = (if (reportedHeight > 0) reportedHeight else 160)
                .toFloat().coerceIn(68f, 500f)
            return if (isCompact) {
                val side = minOf(width, height)
                WidgetSize(width, height, side, side)
            } else {
                WidgetSize(width, height, width, height)
            }
        }

        /** Colours derived from the pass colour: a deep gradient plus matching light and dark accents. */
        private class Palette(
            val top: Int,
            val middle: Int,
            val bottom: Int,
            /** Light, softly tinted colour for secondary text. */
            val tint: Int,
            /** Dark ink for text on the light pill. */
            val ink: Int,
        )

        private const val FALLBACK_HUE = 208f

        /**
         * Keeps the hue of the pass but moves it into a range that always works with white text:
         * saturation 0.45-0.80, value 0.42 at the top left down to 0.20 at the bottom right, and the
         * hue drifts a little along the diagonal, so the card reads as a lit surface instead of a flat tint.
         * Grey and white passes fall back to a calm blue.
         */
        private fun palette(base: Int): Palette {
            val hsv = FloatArray(3)
            Color.colorToHSV(base, hsv)
            val grey = hsv[1] < 0.12f
            val hue = if (grey) FALLBACK_HUE else hsv[0]
            val sat = if (grey) 0.55f else hsv[1].coerceIn(0.45f, 0.80f)
            fun shade(hueShift: Float, s: Float, v: Float, alpha: Int = 255): Int = Color.HSVToColor(
                alpha,
                floatArrayOf((hue + hueShift + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)),
            )
            return Palette(
                top = shade(-10f, sat * 0.85f, 0.42f, 240),
                middle = shade(0f, sat, 0.32f, 234),
                bottom = shade(14f, sat * 1.05f, 0.20f, 240),
                tint = shade(0f, 0.16f, 0.97f),
                ink = shade(0f, 0.75f, 0.30f),
            )
        }

        /**
         * Draws the glass card. A diagonal gradient from the palette, a soft light glow in the upper
         * left, a faint rim light that fades from top left to bottom right and one very fine inner line. All
         * layers share the same 24dp corner radius, and the bitmap is scaled uniformly so the corners
         * stay circular when the launcher resizes it.
         */
        private fun glassPanelBitmap(context: Context, size: WidgetSize, palette: Palette): Bitmap {
            val density = context.resources.displayMetrics.density
            val desiredWidth = size.cardWidth * density
            val desiredHeight = size.cardHeight * density
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

            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(palette.top, palette.middle, palette.bottom),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = RadialGradient(
                width * 0.18f, height * 0.06f, maxOf(width, height) * 0.85f,
                intArrayOf(Color.argb(64, 255, 255, 255), Color.argb(14, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.argb(72, 255, 255, 255), Color.argb(12, 255, 255, 255)),
                null, Shader.TileMode.CLAMP,
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.8f * unit
            canvas.drawRoundRect(rect, radius, radius, paint)

            paint.shader = null
            val innerInset = 2.0f * unit
            val inner = RectF(innerInset, innerInset, width - innerInset, height - innerInset)
            paint.strokeWidth = 0.65f * unit
            paint.color = Color.argb(14, 255, 255, 255)
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

            val look = palette(prefs.getInt(key(widgetId, "background"), BG_FALLBACK))
            val size = widgetSize(context, widgetId, isCompact)

            views.setImageViewBitmap(
                R.id.pass_widget_background_image,
                glassPanelBitmap(context, size, look),
            )
            if (isCompact) {
                // Centre the square card: the root keeps the leftover space as padding, so the
                // background and all content live inside the largest square that fits.
                val density = context.resources.displayMetrics.density
                val padH = ((size.areaWidth - size.cardWidth) / 2f * density).roundToInt().coerceAtLeast(0)
                val padV = ((size.areaHeight - size.cardHeight) / 2f * density).roundToInt().coerceAtLeast(0)
                views.setViewPadding(R.id.pass_widget_root, padH, padV, padH, padV)
            }
            views.setTextColor(R.id.pass_widget_title, Color.WHITE)
            views.setTextColor(R.id.pass_widget_issuer, look.tint)
            views.setTextColor(R.id.pass_widget_caption, look.tint)
            // 2x2: white arrow on a glass circle. 4x2: tinted dark text on the light pill.
            views.setTextColor(R.id.pass_widget_action, if (isCompact) Color.WHITE else look.ink)

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
