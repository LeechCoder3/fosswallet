package nz.eloque.foss_wallet.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.eloque.foss_wallet.persistence.PassStore

/** Android opens this screen when a widget is first added or reconfigured. */
@AndroidEntryPoint
class PassWidgetConfigActivity : ComponentActivity() {
    @Inject lateinit var passStore: PassStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val padding = dp(18)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(18, 28, 32))
        }
        root.addView(label("Ticket für die Leiste auswählen", 22f))
        root.addView(label("Wähle einen gespeicherten Pass.", 14f))

        val scroll = ScrollView(this)
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        scroll.addView(choices)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)
        choices.addView(label("Tickets werden geladen …", 14f))

        scope.launch {
            try {
                val passes = withContext(Dispatchers.IO) {
                    passStore.allPasses().first()
                }.filter { !it.metadata.archived }
                    .sortedBy { it.pass.description.lowercase() }

                choices.removeAllViews()
                if (passes.isEmpty()) {
                    choices.addView(label("Keine aktiven Tickets gefunden. Importiere zuerst einen Pass in diese FossWallet-Version.", 15f))
                }
                passes.forEach { localized ->
                    val pass = localized.pass
                    val title = pass.description.ifBlank {
                        pass.logoText?.takeIf { it.isNotBlank() }
                            ?: pass.organization.ifBlank { "Ticket" }
                    }
                    val issuer = pass.organization.ifBlank { "FossWallet" }
                    val item = label("$title\n$issuer", 16f).apply {
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        background = GradientDrawable().apply {
                            setColor(Color.rgb(37, 65, 72))
                            cornerRadius = dp(12).toFloat()
                        }
                        isClickable = true
                        isFocusable = true
                        contentDescription = "$title, $issuer auswählen"
                        setOnClickListener {
                            PassWidgetProvider.saveSelection(
                                this@PassWidgetConfigActivity,
                                widgetId,
                                pass.id,
                                title,
                                issuer,
                            )
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                            )
                            finish()
                        }
                    }
                    choices.addView(item, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) })
                }
            } catch (exception: Exception) {
                choices.removeAllViews()
                choices.addView(label("Tickets konnten nicht geladen werden: ${exception.localizedMessage}", 14f))
            }
        }
    }

    private fun label(textValue: String, size: Float) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
