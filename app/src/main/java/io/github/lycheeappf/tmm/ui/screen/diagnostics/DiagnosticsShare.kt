package io.github.lycheeappf.tmm.ui.screen.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Baut den Share-Intent für den Diagnose-Export. Reine Helfer-Funktionen (kein
 * Compose/ViewModel-State), damit Authority-/Intent-Bau testbar bleibt.
 */
object DiagnosticsShare {

    /** FileProvider-Authority = applicationId (inkl. .debug-Suffix) + ".fileprovider". */
    fun authority(packageName: String): String = "$packageName.fileprovider"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context.packageName), file)

    /** Fertiger Chooser-Intent: ACTION_SEND, JSON, lesbare content://-Uri. */
    fun chooser(context: Context, file: File, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_JSON
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    private const val MIME_JSON = "application/json"
}
