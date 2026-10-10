package com.noryan.romcore.data

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/** Best-effort recovery of a real filesystem path from a SAF document/tree Uri (same approach as RomRunner). */
object SafPathUtils {
    fun realPathFromDocumentUri(uri: Uri): String? = try {
        val docId = try { DocumentsContract.getDocumentId(uri) } catch (e: IllegalArgumentException) { DocumentsContract.getTreeDocumentId(uri) }
        val parts = docId.split(":", limit = 2)
        if (parts.size < 2) null
        else when (val volume = parts[0]) {
            "primary" -> "${Environment.getExternalStorageDirectory().path}/${parts[1]}"
            else -> "/storage/$volume/${parts[1]}"
        }
    } catch (e: Exception) { null }
}
