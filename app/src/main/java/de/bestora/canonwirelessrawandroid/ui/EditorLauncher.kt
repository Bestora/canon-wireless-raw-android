package de.bestora.canonwirelessrawandroid.ui

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.bestora.canonwirelessrawandroid.data.Prefs

/**
 * "Open in Luminar": hands downloaded RAWs to the editor picked last time, so it is one tap. The
 * first time (and via "other app") Android's app picker shows, and [EditorChosenReceiver] notes the
 * pick. Any app that takes shared images works: Luminar, Lightroom, Snapseed, …
 */
class EditorLauncher(private val context: Context, private val prefs: Prefs) {

    /** Name of the remembered app, or null before the first pick. */
    fun label(): String? {
        val component = remembered() ?: return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(component.packageName, 0)).toString()
        }.getOrNull()
    }

    fun open(uris: List<Uri>, mime: String, pickApp: Boolean = false) {
        if (uris.isEmpty()) return
        val send = sendIntent(uris, mime)
        val component = remembered()
        if (!pickApp && component != null) {
            try {
                context.startActivity(Intent(send).setComponent(component))
                return
            } catch (e: ActivityNotFoundException) {
                prefs.editorComponent = null // uninstalled: ask again
            }
        }
        val onPicked = PendingIntent.getBroadcast(
            context, 0, Intent(context, EditorChosenReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        context.startActivity(
            Intent.createChooser(send, "Open RAW in…", onPicked.intentSender).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun remembered(): ComponentName? = prefs.editorComponent?.let(ComponentName::unflattenFromString)

    private fun sendIntent(uris: List<Uri>, mime: String): Intent {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Name of the remembered editor, re-read whenever the app comes back to the front: the pick
 *  happens in the chooser, outside the app. */
@Composable
fun rememberEditorName(editor: EditorLauncher): String? {
    var name by remember { mutableStateOf(editor.label()) }
    LifecycleResumeEffect(editor) {
        name = editor.label()
        onPauseOrDispose {}
    }
    return name
}

/** Receives the app picked in the chooser (EXTRA_CHOSEN_COMPONENT) and remembers it. */
class EditorChosenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chosen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
        if (chosen != null) Prefs(context).editorComponent = chosen.flattenToString()
    }
}
