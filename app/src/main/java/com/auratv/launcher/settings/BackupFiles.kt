package com.auratv.launcher.settings

import android.content.Context
import android.os.Environment
import android.util.AtomicFile
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

internal object BackupFiles {
    const val NAME = "aura-backup.json"

    fun usbFiles(ctx: Context, export: Boolean): List<File> = ctx.getExternalFilesDirs(null).filterNotNull()
        .filter {
            val state = Environment.getExternalStorageState(it)
            Environment.isExternalStorageRemovable(it) &&
                (state == Environment.MEDIA_MOUNTED || (!export && state == Environment.MEDIA_MOUNTED_READ_ONLY))
        }
        .map { File(it, NAME) }

    fun write(file: File, json: String) {
        file.parentFile?.mkdirs()
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }

    fun read(input: InputStream): JSONObject {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= 4 * 1024 * 1024) { "Backup exceeds 4 MB" }
            bytes.write(buffer, 0, count)
        }
        return JSONObject(bytes.toString("UTF-8"))
    }
}
