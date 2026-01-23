package com.leadrdrk.umapatcher.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.leadrdrk.umapatcher.utils.pluginsDir
import java.io.File

data class PluginEntry(
    val name: String,
    val fileName: String,
    val enabled: Boolean = true
)

object PluginManager {
    private const val MANIFEST_FILE = "plugins.json"
    private val gson = Gson()
    private val listType = object : TypeToken<List<PluginEntry>>() {}.type

    private fun manifestFile(context: Context): File =
        context.pluginsDir.resolve(MANIFEST_FILE)

    fun listPlugins(context: Context): MutableList<PluginEntry> {
        val manifest = manifestFile(context)
        if (!manifest.isFile) return mutableListOf()
        return try {
            val text = manifest.readText()
            gson.fromJson<List<PluginEntry>>(text, listType)?.toMutableList() ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun savePlugins(context: Context, plugins: List<PluginEntry>) {
        if (!context.pluginsDir.exists()) {
            context.pluginsDir.mkdirs()
        }
        manifestFile(context).writeText(gson.toJson(plugins))
    }

    fun addPlugin(context: Context, uri: Uri): PluginEntry? {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
        val displayName = doc.name ?: "plugin.so"
        val safeName = ensureSoSuffix(displayName)
        if (!context.pluginsDir.exists()) {
            context.pluginsDir.mkdirs()
        }

        val targetName = uniqueName(context.pluginsDir, safeName)
        val targetFile = context.pluginsDir.resolve(targetName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        val entry = PluginEntry(
            name = targetName.removeSuffix(".so"),
            fileName = targetName,
            enabled = true
        )
        val plugins = listPlugins(context).apply { add(entry) }
        savePlugins(context, plugins)
        return entry
    }

    fun setEnabled(context: Context, fileName: String, enabled: Boolean) {
        val plugins = listPlugins(context)
        val updated = plugins.map { if (it.fileName == fileName) it.copy(enabled = enabled) else it }
        savePlugins(context, updated)
    }

    fun removePlugin(context: Context, fileName: String) {
        val plugins = listPlugins(context).filterNot { it.fileName == fileName }
        context.pluginsDir.resolve(fileName).delete()
        savePlugins(context, plugins)
    }

    fun enabledPluginFiles(context: Context): List<File> {
        return listPlugins(context)
            .filter { it.enabled }
            .map { context.pluginsDir.resolve(it.fileName) }
            .filter { it.isFile }
    }

    fun prefixedName(fileName: String): String {
        return if (fileName.startsWith("libhachimi_")) {
            fileName
        } else {
            val base = fileName.removePrefix("lib")
            "libhachimi_$base"
        }
    }

    private fun ensureSoSuffix(name: String): String =
        if (name.endsWith(".so")) name else "$name.so"

    private fun uniqueName(dir: File, name: String): String {
        var candidate = name
        var counter = 1
        while (dir.resolve(candidate).exists()) {
            val base = name.removeSuffix(".so")
            candidate = "${base}_$counter.so"
            counter += 1
        }
        return candidate
    }
}
