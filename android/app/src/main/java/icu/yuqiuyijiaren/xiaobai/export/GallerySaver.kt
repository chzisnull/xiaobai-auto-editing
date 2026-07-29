package icu.yuqiuyijiaren.xiaobai.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persist an exported MP4 into the public Movies/Xiaobai collection (or legacy Movies).
 */
object GallerySaver {

    data class Result(
        val displayName: String,
        val contentUri: Uri?,
        val absolutePath: String?,
    )

    suspend fun saveVideoToGallery(
        context: Context,
        sourceFile: File,
        displayName: String = "xiaobai_${System.currentTimeMillis()}.mp4",
    ): Result = withContext(Dispatchers.IO) {
        require(sourceFile.exists() && sourceFile.length() > 0L) { "导出文件无效" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Xiaobai",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("无法创建相册条目")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("无法写入相册")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Result(displayName = displayName, contentUri = uri, absolutePath = null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val dir = File(movies, "Xiaobai").apply { mkdirs() }
            val dest = File(dir, displayName)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            // Notify media scanner
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DATA, dest.absolutePath)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            )
            Result(displayName = displayName, contentUri = uri, absolutePath = dest.absolutePath)
        }
    }
}
