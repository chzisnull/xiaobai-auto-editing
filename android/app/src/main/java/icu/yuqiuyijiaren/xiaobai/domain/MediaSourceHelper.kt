package icu.yuqiuyijiaren.xiaobai.domain

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream

object MediaSourceHelper {

    fun openFileDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
        if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    try {
                        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        }
    }

    fun setExtractorDataSource(extractor: MediaExtractor, context: Context, uri: Uri) {
        if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    try {
                        FileInputStream(file).use { fis ->
                            extractor.setDataSource(fis.fd)
                        }
                        return
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val pfd = openFileDescriptor(context, uri)
        if (pfd != null) {
            pfd.use {
                extractor.setDataSource(it.fileDescriptor)
            }
            return
        }
        extractor.setDataSource(context, uri, null)
    }

    fun setRetrieverDataSource(retriever: MediaMetadataRetriever, context: Context, uri: Uri) {
        if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    try {
                        retriever.setDataSource(file.absolutePath)
                        return
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val pfd = openFileDescriptor(context, uri)
        if (pfd != null) {
            pfd.use {
                retriever.setDataSource(it.fileDescriptor)
            }
            return
        }
        retriever.setDataSource(context, uri)
    }
}
