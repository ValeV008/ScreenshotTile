package com.github.cvzi.screenshottile.utils

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore.Images
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.github.cvzi.screenshottile.App
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object ScreenshotPostProcessingScanner {
    private const val TAG = "PostProcessScanner"
    private const val MAX_SCAN_ITEMS = 40
    private const val DEFAULT_JPEG_QUALITY = 95

    private val observerRegistered = AtomicBoolean(false)
    private val scanRunning = AtomicBoolean(false)
    private val pendingRescan = AtomicBoolean(false)
    @Volatile
    private var mediaObserver: ContentObserver? = null

    @Volatile
    private var scanJob: Job? = null

    // Keep a rolling in-memory set so non-deletable native PNGs are not reprocessed forever.
    private const val MAX_PROCESSED_URIS = 500
    private val processedSourceUris = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        registerObserver(context.applicationContext)
        Log.i(TAG, "Started MediaStore scanner observer")
        scheduleScan(context.applicationContext, "startup")
    }

    @JvmStatic
    fun convertNow(context: Context, uri: Uri?): Uri? {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return convertMediaStorePngToJpegIfScreenshot(context.applicationContext, uri)
    }

    @JvmStatic
    fun convertNow(context: Context, file: java.io.File?): java.io.File? {
        if (file == null) return null
        return convertFilePngToJpegIfScreenshot(file)
    }

    @JvmStatic
    fun onImageSaved(context: Context, uri: Uri?) {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val appContext = context.applicationContext
        App.getInstance().appScope.launch {
            withContext(Dispatchers.IO) {
                val converted = convertNow(appContext, uri)
                if (converted != null) {
                    Log.i(TAG, "Converted saved PNG uri=$uri to jpegUri=$converted")
                }
            }
        }
    }

    @JvmStatic
    fun onImageSaved(context: Context, file: java.io.File?) {
        if (file == null) return
        val appContext = context.applicationContext
        App.getInstance().appScope.launch {
            withContext(Dispatchers.IO) {
                val converted = convertNow(appContext, file)
                if (converted != null) {
                    Log.i(TAG, "Converted saved PNG file=${file.absolutePath} to ${converted.absolutePath}")
                }
            }
        }
    }

    private fun registerObserver(context: Context) {
        if (!observerRegistered.compareAndSet(false, true)) return
        mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scheduleScan(context, "mediastore_change")
            }
        }
        context.contentResolver.registerContentObserver(
            Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver!!
        )
    }

    private fun scheduleScan(context: Context, reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (scanRunning.get()) {
            pendingRescan.set(true)
            return
        }
        if (scanJob?.isActive == true) return
        scanJob = App.getInstance().appScope.launch {
            delay(1200)
            scanNow(context, reason)
        }
    }

    private suspend fun scanNow(context: Context, reason: String) {
        if (!scanRunning.compareAndSet(false, true)) return
        try {
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2 &&
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Scan[$reason] skipped: missing READ_EXTERNAL_STORAGE permission")
                return
            }
            withContext(Dispatchers.IO) {
                val projection = arrayOf(
                    Images.Media._ID,
                    Images.Media.DISPLAY_NAME,
                    Images.Media.RELATIVE_PATH,
                    Images.Media.DATA,
                    Images.Media.MIME_TYPE,
                    Images.Media.DATE_TAKEN,
                    Images.Media.DATE_ADDED,
                    Images.Media.DATE_MODIFIED
                )
                val selection = "${Images.Media.MIME_TYPE}=?"
                val selectionArgs = arrayOf("image/png")
                val order = "${Images.Media.DATE_ADDED} DESC"

                val candidates = mutableListOf<Uri>()
                context.contentResolver.query(
                    Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    order
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(Images.Media._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(Images.Media.DISPLAY_NAME)
                    val pathIndex = cursor.getColumnIndexOrThrow(Images.Media.RELATIVE_PATH)
                    val dataIndex = cursor.getColumnIndexOrThrow(Images.Media.DATA)
                    var skippedLogged = 0
                    while (cursor.moveToNext() && candidates.size < MAX_SCAN_ITEMS) {
                        val displayName = cursor.getString(nameIndex)
                        val relativePath = cursor.getString(pathIndex)
                        val dataPath = cursor.getString(dataIndex)
                        if (!isLikelyScreenshot(displayName, relativePath, dataPath)) {
                            if (skippedLogged < 10) {
                                Log.i(
                                    TAG,
                                    "Scan[$reason] skip png name=$displayName rel=$relativePath data=$dataPath"
                                )
                                skippedLogged++
                            }
                            continue
                        }
                        val id = cursor.getLong(idIndex)
                        candidates += Uri.withAppendedPath(Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    }
                }
                Log.i(TAG, "Scan[$reason] found ${candidates.size} PNG screenshot candidate(s)")

                var convertedCount = 0
                for (uri in candidates) {
                    val key = uri.toString()
                    if (processedSourceUris.contains(key)) continue
                    if (convertMediaStorePngToJpegIfScreenshot(context, uri) != null) {
                        rememberProcessedUri(key)
                        convertedCount++
                    }
                }
                Log.i(TAG, "Scan[$reason] converted $convertedCount PNG screenshot(s)")
            }
        } catch (_: CancellationException) {
            Log.i(TAG, "scanNow($reason) cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "scanNow($reason) failed", e)
        } finally {
            scanRunning.set(false)
            if (pendingRescan.compareAndSet(true, false)) {
                scheduleScan(context, "pending_rescan")
            }
        }
    }

    private fun convertMediaStorePngToJpegIfScreenshot(context: Context, sourceUri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!sourceUri.toString().startsWith("content://media/")) return null

        val projection = arrayOf(
            Images.Media.DISPLAY_NAME,
            Images.Media.RELATIVE_PATH,
            Images.Media.DATA,
            Images.Media.MIME_TYPE,
            Images.Media.DATE_TAKEN,
            Images.Media.DATE_ADDED,
            Images.Media.DATE_MODIFIED
        )

        var displayName: String? = null
        var relativePath: String? = null
        var dataPath: String? = null
        var mimeType: String? = null
        var dateTaken: Long = 0
        var dateAdded: Long = 0
        var dateModified: Long = 0

        context.contentResolver.query(sourceUri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            displayName = cursor.getString(0)
            relativePath = cursor.getString(1)
            dataPath = cursor.getString(2)
            mimeType = cursor.getString(3)
            dateTaken = cursor.getLong(4)
            dateAdded = cursor.getLong(5)
            dateModified = cursor.getLong(6)
        } ?: return null

        if (!mimeType.equals("image/png", ignoreCase = true)) return null
        if (!isLikelyScreenshot(displayName, relativePath, dataPath)) return null

        val bitmap = context.contentResolver.openInputStream(sourceUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val targetBitmap = jpegBitmap(bitmap)
        if (targetBitmap !== bitmap) {
            bitmap.recycle()
        }

        val dateMillis = when {
            dateTaken > 0 -> dateTaken
            dateModified > 0 -> dateModified * 1000L
            dateAdded > 0 -> dateAdded * 1000L
            else -> System.currentTimeMillis()
        }
        val date = Date(dateMillis)

        val baseName = (displayName ?: "Screenshot")
            .removeSuffix(".png")
            .removeSuffix(".PNG")
            .substringBeforeLast('.')

        var index = 0
        var destUri: Uri? = null
        while (destUri == null && index < 200) {
            val candidateName = if (index == 0) "$baseName.jpg" else "${baseName}_$index.jpg"
            val values = ContentValues().apply {
                put(Images.Media.DISPLAY_NAME, candidateName)
                put(Images.Media.TITLE, baseName)
                put(Images.Media.MIME_TYPE, "image/jpeg")
                put(Images.Media.DATE_ADDED, date.time / 1000)
                put(Images.Media.DATE_MODIFIED, date.time / 1000)
                put(Images.Media.DATE_TAKEN, date.time)
                put(
                    Images.Media.RELATIVE_PATH,
                    if (relativePath.isNullOrBlank()) {
                        "${Environment.DIRECTORY_PICTURES}/Screenshots/"
                    } else {
                        relativePath
                    }
                )
                put(Images.Media.IS_PENDING, 1)
            }
            destUri = context.contentResolver.insert(Images.Media.EXTERNAL_CONTENT_URI, values)
            index++
        }
        if (destUri == null) {
            targetBitmap.recycle()
            return null
        }

        try {
            context.contentResolver.openOutputStream(destUri)?.use { stream ->
                targetBitmap.compress(Bitmap.CompressFormat.JPEG, DEFAULT_JPEG_QUALITY, stream)
            } ?: return null

            context.contentResolver.openFileDescriptor(destUri, "rw")?.use { pfd ->
                setExifDate(pfd.fileDescriptor, date)
            }

            val values = ContentValues().apply {
                put(Images.Media.DATE_ADDED, date.time / 1000)
                put(Images.Media.DATE_MODIFIED, date.time / 1000)
                put(Images.Media.DATE_TAKEN, date.time)
                put(Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(destUri, values, null, null)
            context.contentResolver.notifyChange(destUri, null)
            try {
                val deleted = context.contentResolver.delete(sourceUri, null, null)
                Log.i(TAG, "Deleted original PNG uri=$sourceUri rows=$deleted")
            } catch (deleteError: SecurityException) {
                // On some OEM/system screenshot rows, app cannot delete without user action.
                val deletedViaSaf = deleteViaSafTree(
                    context = context,
                    sourceUri = sourceUri,
                    displayName = displayName,
                    relativePath = relativePath
                )
                if (deletedViaSaf) {
                    Log.i(TAG, "Deleted original PNG via SAF fallback uri=$sourceUri")
                } else {
                    Log.w(
                        TAG,
                        "Converted PNG but could not delete source uri=$sourceUri (${deleteError.message})"
                    )
                }
            }
            return destUri
        } finally {
            targetBitmap.recycle()
        }
    }

    private fun convertFilePngToJpegIfScreenshot(sourceFile: java.io.File): java.io.File? {
        if (!sourceFile.exists()) return null
        if (!sourceFile.name.lowercase(Locale.US).endsWith(".png")) return null
        if (!sourceFile.parentFile?.absolutePath.orEmpty().contains("Screenshots", ignoreCase = true)) return null
        return try {
            val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return null
            val targetBitmap = jpegBitmap(sourceBitmap)
            if (targetBitmap !== sourceBitmap) {
                sourceBitmap.recycle()
            }

            val baseName = sourceFile.nameWithoutExtension
            val parent = sourceFile.parentFile ?: return null
            var target = java.io.File(parent, "$baseName.jpg")
            var i = 1
            while (target.exists()) {
                target = java.io.File(parent, "${baseName}_$i.jpg")
                i++
            }

            target.outputStream().use { stream ->
                targetBitmap.compress(Bitmap.CompressFormat.JPEG, DEFAULT_JPEG_QUALITY, stream)
            }
            targetBitmap.recycle()
            sourceFile.delete()
            target
        } catch (e: Exception) {
            Log.e(TAG, "convertFilePngToJpegIfScreenshot(${sourceFile.absolutePath}) failed", e)
            null
        }
    }

    private fun jpegBitmap(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) {
            return bitmap
        }
        val merged = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return merged
    }

    private fun setExifDate(fileDescriptor: java.io.FileDescriptor, date: Date) {
        val exif = ExifInterface(fileDescriptor)
        val dateText = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date)
        val offsetText = java.text.SimpleDateFormat("XXX", Locale.US).format(date)
        exif.setAttribute(ExifInterface.TAG_DATETIME, dateText)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateText)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateText)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offsetText)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offsetText)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offsetText)
        exif.saveAttributes()
    }

    private fun isLikelyScreenshot(
        displayName: String?,
        relativePath: String?,
        dataPath: String?
    ): Boolean {
        return displayName?.contains("screenshot", ignoreCase = true) == true ||
            relativePath?.contains("screenshot", ignoreCase = true) == true ||
            dataPath?.contains("screenshot", ignoreCase = true) == true
    }

    private fun rememberProcessedUri(uri: String) {
        processedSourceUris += uri
        if (processedSourceUris.size <= MAX_PROCESSED_URIS) return
        // Best effort trim to cap memory and keep recent items.
        val toRemove = processedSourceUris.size - MAX_PROCESSED_URIS
        val iterator = processedSourceUris.iterator()
        repeat(toRemove) {
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun deleteViaSafTree(
        context: Context,
        sourceUri: Uri,
        displayName: String?,
        relativePath: String?
    ): Boolean {
        if (displayName.isNullOrBlank()) return false
        val treeUriString = App.getInstance().prefManager.screenshotDirectory
        if (treeUriString.isNullOrBlank() || !treeUriString.startsWith("content://")) {
            Log.i(TAG, "SAF delete skipped for $sourceUri: screenshotDirectory not configured")
            return false
        }

        return try {
            val treeUri = Uri.parse(treeUriString)
            val root = DocumentFile.fromTreeUri(context, treeUri)
            if (root == null || !root.isDirectory) {
                Log.w(TAG, "SAF delete skipped for $sourceUri: invalid tree uri=$treeUriString")
                return false
            }

            val exact = root.findFile(displayName)
            if (exact != null && exact.isFile) {
                return exact.delete()
            }

            val inSubDir = findFileInLikelySubDir(root, displayName, relativePath)
            if (inSubDir != null) {
                return inSubDir.delete()
            }

            val recursive = findFileRecursive(root, displayName)
            if (recursive != null) {
                return recursive.delete()
            }

            false
        } catch (e: Exception) {
            Log.w(TAG, "SAF delete failed for $sourceUri", e)
            false
        }
    }

    private fun findFileInLikelySubDir(
        root: DocumentFile,
        fileName: String,
        relativePath: String?
    ): DocumentFile? {
        if (relativePath.isNullOrBlank()) return null
        val segments = relativePath
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        // Try relative-path tail to match a picked subfolder URI (for example user picked Screenshots).
        for (startIndex in segments.indices.reversed()) {
            var dir: DocumentFile? = root
            for (i in startIndex until segments.size) {
                dir = dir?.findFile(segments[i])
                if (dir == null || !dir.isDirectory) break
            }
            val candidate = dir?.findFile(fileName)
            if (candidate != null && candidate.isFile) {
                return candidate
            }
        }
        return null
    }

    private fun findFileRecursive(root: DocumentFile, fileName: String): DocumentFile? {
        val queue: ArrayDeque<DocumentFile> = ArrayDeque()
        queue.add(root)
        var visited = 0
        val visitLimit = 1500

        while (queue.isNotEmpty() && visited < visitLimit) {
            val dir = queue.removeFirst()
            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                emptyArray()
            }
            for (child in children) {
                visited++
                if (child.isFile && child.name == fileName) {
                    return child
                }
                if (child.isDirectory) {
                    queue.addLast(child)
                }
                if (visited >= visitLimit) break
            }
        }
        return null
    }
}
