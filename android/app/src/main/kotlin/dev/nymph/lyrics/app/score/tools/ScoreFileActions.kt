package dev.nymph.lyrics.app.score.tools

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

private const val TAG = "ScoreExport"

/** The FileProvider authority declared in the manifest for exported score files. */
fun scoreExportAuthority(context: Context): String = "${context.packageName}.scoreexports"

/** Why a share or save did not happen. */
sealed interface FileActionResult {
    data object Done : FileActionResult

    data class Failed(val message: String) : FileActionResult
}

/** Open the share sheet for [exported] (read access granted to the receiving app only). */
fun shareExport(context: Context, exported: ExportedFile): FileActionResult {
    val uri = try {
        FileProvider.getUriForFile(context, scoreExportAuthority(context), exported.file, exported.name)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "event=share_uri_failed file=${exported.file.name} error=${e.message}")
        return FileActionResult.Failed("Couldn't share the file")
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = exported.format.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, exported.name)
        clipData = ClipData.newRawUri(exported.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(Intent.createChooser(send, "Share ${exported.format.label}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(TAG, "event=shared format=${exported.format.wire}")
        FileActionResult.Done
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "event=share_no_app format=${exported.format.wire}")
        FileActionResult.Failed("No app can receive ${exported.format.label} files")
    }
}

/** Copy [exported] into the document the user picked with the system "save as" picker. */
suspend fun saveExport(context: Context, exported: ExportedFile, target: Uri): FileActionResult = withContext(Dispatchers.IO) {
    try {
        val out = context.contentResolver.openOutputStream(target, "wt") ?: return@withContext FileActionResult.Failed("Couldn't open the file to save")
        out.use { sink -> exported.file.inputStream().use { it.copyTo(sink) } }
        Log.i(TAG, "event=saved format=${exported.format.wire} bytes=${exported.file.length()}")
        FileActionResult.Done
    } catch (e: FileNotFoundException) {
        Log.w(TAG, "event=save_failed reason=not_found error=${e.message}")
        FileActionResult.Failed("Couldn't save: the file is gone")
    } catch (e: IOException) {
        Log.w(TAG, "event=save_failed reason=io error=${e.message}")
        FileActionResult.Failed("Couldn't save the file")
    } catch (e: SecurityException) {
        Log.w(TAG, "event=save_failed reason=denied error=${e.message}")
        FileActionResult.Failed("Couldn't save: access denied")
    }
}

/**
 * The system "save as" picker (ACTION_CREATE_DOCUMENT) for a file name and mime type chosen at
 * launch time (the stock CreateDocument contract fixes the mime when it is remembered).
 */
class CreateTypedDocument : ActivityResultContract<CreateTypedDocument.Input, Uri?>() {
    data class Input(val name: String, val mime: String)

    override fun createIntent(context: Context, input: Input): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(input.mime)
        .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}
