package com.leadrdrk.umapatcher.patcher

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.File

/**
 * Injects a DocumentsProvider that exports the internal data directory of the
 * patched app to file managers. (for now)
 */
internal object ExtensionsPatcher {

    const val DEX_ASSET_NAME = "extensions.dex"

    private const val PROVIDER_CLASS_NAME = "com.leadrdrk.umapatcher.documentsprovider.InternalDataDocumentsProvider"

    private const val ATTR_NAME = 0x01010003
    private const val ATTR_PERMISSION = 0x01010006
    private const val ATTR_EXPORTED = 0x01010010
    private const val ATTR_AUTHORITIES = 0x01010018
    private const val ATTR_GRANT_URI_PERMISSIONS = 0x0101001b

    private const val PROVIDER_PERMISSION = "android.permission.MANAGE_DOCUMENTS"
    private const val ACTION_DOCUMENTS_PROVIDER = "android.content.action.DOCUMENTS_PROVIDER"

    private const val MANIFEST_FILE_NAME = "AndroidManifest.xml"
    private val DEX_FILE_PATTERN = Regex("^classes(?:([0-9]+))?\\.dex$")

    fun patchExtractedApk(extractDir: File, providerDex: ByteArray): Boolean {
        val manifestFile = extractDir.resolve(MANIFEST_FILE_NAME)
        val manifest = AndroidManifestBlock.load(manifestFile)

        if (hasProvider(manifest)) {
            return false
        }

        val authority = "${manifest.packageName}.$PROVIDER_CLASS_NAME"
        addProviderToManifest(manifest, authority)
        manifest.refreshFull()
        manifestFile.writeBytes(manifest.bytes)

        val dexName = nextDexEntryName(extractDir)
        extractDir.resolve(dexName).writeBytes(providerDex)

        return true
    }

    private fun hasProvider(manifest: AndroidManifestBlock): Boolean {
        val application = manifest.applicationElement ?: return false
        return application.listElements(AndroidManifestBlock.TAG_provider).any { provider ->
            val name = provider.searchAttributeByResourceId(ATTR_NAME)
                ?: provider.searchAttributeByName(AndroidManifestBlock.NAME_name)
            try {
                name != null && name.valueAsString == PROVIDER_CLASS_NAME
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun addProviderToManifest(manifest: AndroidManifestBlock, authority: String) {
        val application = manifest.getOrCreateApplicationElement()

        val provider = application.createChildElement(AndroidManifestBlock.TAG_provider)
        provider.getOrCreateAndroidAttribute(AndroidManifestBlock.NAME_name, ATTR_NAME)
            .setValueAsString(PROVIDER_CLASS_NAME)
        provider.getOrCreateAndroidAttribute("authorities", ATTR_AUTHORITIES)
            .setValueAsString(authority)
        provider.getOrCreateAndroidAttribute("exported", ATTR_EXPORTED)
            .setValueAsBoolean(true)
        provider.getOrCreateAndroidAttribute("grantUriPermissions", ATTR_GRANT_URI_PERMISSIONS)
            .setValueAsBoolean(true)
        provider.getOrCreateAndroidAttribute("permission", ATTR_PERMISSION)
            .setValueAsString(PROVIDER_PERMISSION)

        // Required for the system to register the provider with DocumentsUI.
        val intentFilter = provider.createChildElement(AndroidManifestBlock.TAG_intent_filter)
        val action = intentFilter.createChildElement(AndroidManifestBlock.TAG_action)
        action.getOrCreateAndroidAttribute(AndroidManifestBlock.NAME_name, ATTR_NAME)
            .setValueAsString(ACTION_DOCUMENTS_PROVIDER)
    }

    private fun nextDexEntryName(extractDir: File): String {
        var maxIndex = 0
        extractDir.listFiles { file -> DEX_FILE_PATTERN.matches(file.name) }?.forEach { file ->
            val index = DEX_FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(1)
            maxIndex = maxOf(maxIndex, index?.toIntOrNull() ?: 1)
        }
        val nextIndex = maxIndex + 1
        return if (nextIndex == 1) "classes.dex" else "classes$nextIndex.dex"
    }
}
