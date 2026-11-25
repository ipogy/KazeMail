package com.kazemail.app.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.kazemail.app.data.AttachmentMetadataHelper
import java.io.File

internal object AttachmentOpener {
    fun openFileWithSystemViewer(
        context: Context,
        file: File,
        rawMimeType: String,
        onError: (String) -> Unit,
    ) {
        if (!file.exists() || file.length() <= 0L) {
            onError("添付ファイルが見つからないか空です（0バイト）")
            return
        }

        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrElse { err ->
            onError("添付ファイルの共有URI生成に失敗しました: ${err.message ?: err.javaClass.simpleName}")
            return
        }

        val sample = AttachmentMetadataHelper.readHeaderSample(file, maxBytes = 32)
        val resolvedMime = AttachmentMetadataHelper.normalizeAndResolveMimeType(
            fileName = file.name,
            rawMimeType = rawMimeType,
            headerSample = sample,
        ) { ext ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
        val finalMime = Intent.normalizeMimeType(resolvedMime) ?: resolvedMime

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, finalMime)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        try {
            context.startActivity(viewIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to wildcard viewer chooser
        } catch (err: Throwable) {
            onError("添付ファイルを開けませんでした: ${err.message ?: err.javaClass.simpleName}")
            return
        }

        val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val chooser = Intent.createChooser(fallbackIntent, file.name).apply {
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            onError("このファイル形式（$finalMime）を開けるアプリが端末に見つかりません")
        } catch (err: Throwable) {
            onError("添付ファイルを開けませんでした: ${err.message ?: err.javaClass.simpleName}")
        }
    }
}
