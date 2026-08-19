package com.leadrdrk.umapatcher.patcher

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ZipEntryMap
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ResValue
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.io.IOException

/**
 * Standalone split APK merger that works on ARSCLib 1.2.4.
 * This file contains a Kotlin port of ARSCLib's AndroidManifestBlockMerger
 * and AndroidManifestBlockSplitSanitizer (https://github.com/REAndroid/ARSCLib),
 * licensed under the Apache License 2.0.
 *
 * @link https://github.com/REAndroid/ARSCLib/blob/V1.4.0/src/main/java/com/reandroid/apk/AndroidManifestBlockMerger.java
 * @link https://github.com/REAndroid/ARSCLib/blob/V1.4.0/src/main/java/com/reandroid/apk/AndroidManifestBlockSplitSanitizer.java
 */
internal object SplitApkMerger {

    private const val ID_isFeatureSplit = 0x0101055b
    private const val ID_isolatedSplits = 0x0101054b
    private const val ID_splitTypes = 0x0101064f
    private const val ID_requiredSplitTypes = 0x0101064e

    private const val NAME_isFeatureSplit = "isFeatureSplit"
    private const val NAME_isolatedSplits = "isolatedSplits"
    private const val NAME_splitTypes = "splitTypes"
    private const val NAME_requiredSplitTypes = "requiredSplitTypes"

    private const val TAG_uses_split = "uses-split"
    private const val TAG_queries = "queries"
    private const val TAG_permission_group = "permission-group"
    private const val TAG_permission_tree = "permission-tree"
    private const val TAG_uses_library = "uses-library"
    private const val TAG_uses_feature = "uses-feature"

    private val NAMED_MANIFEST_TAGS = setOf(
        AndroidManifestBlock.TAG_activity,
        AndroidManifestBlock.TAG_activity_alias,
        AndroidManifestBlock.TAG_service,
        AndroidManifestBlock.TAG_receiver,
        AndroidManifestBlock.TAG_provider,
        AndroidManifestBlock.TAG_permission,
        TAG_permission_group,
        TAG_permission_tree,
        AndroidManifestBlock.TAG_uses_permission,
        AndroidManifestBlock.TAG_uses_feature,
        AndroidManifestBlock.TAG_uses_library,
    )

    private val NAMED_APPLICATION_TAGS = setOf(
        AndroidManifestBlock.TAG_activity,
        AndroidManifestBlock.TAG_activity_alias,
        AndroidManifestBlock.TAG_service,
        AndroidManifestBlock.TAG_receiver,
        AndroidManifestBlock.TAG_provider,
        AndroidManifestBlock.TAG_meta_data,
    )

    private val SPLIT_METADATA_NAMES = setOf(
        "com.android.vending.splits",
        "com.android.vending.splits.required",
        "com.android.vending.delivery",
        "com.android.vending.derived.apk.id",
        "com.android.stamp.source",
        "com.android.stamp.type",
    )

    data class Result(
        val merged: ApkModule,
        val inputModules: List<ApkModule>
    )

    fun merge(files: Array<File>, logger: APKLogger? = null): Result {
        val bundle = ApkBundle()
        logger?.let { bundle.setAPKLogger(it) }

        val inputModules = mutableListOf<ApkModule>()
        try {
            files.forEachIndexed { i, file ->
                val module = ApkModule.loadApkFile(file, "split_$i")
                module.setLoadDefaultFramework(false)
                bundle.addModule(module)
                inputModules.add(module)
            }

            if (bundle.baseModule == null) {
                throw MergeException("No base APK found among the selected splits")
            }

            val merged = bundle.mergeModules()

            try {
                mergeManifestElements(merged, bundle)
                propagateExtractNativeLibs(merged, bundle)
                sanitizeMergedManifest(merged)

                if (merged.hasAndroidManifestBlock()) {
                    merged.androidManifestBlock.refreshFull()
                }
            } catch (ex: Exception) {
                runCatching { merged.close() }
                throw ex
            }

            return Result(merged = merged, inputModules = inputModules)
        } catch (ex: Exception) {
            inputModules.forEach { runCatching { it.close() } }
            throw ex
        }
    }

    private fun mergeManifestElements(merged: ApkModule, bundle: ApkBundle) {
        if (!merged.hasAndroidManifestBlock()) return
        val baseManifest = merged.androidManifestBlock
        val baseManifestElement = baseManifest.manifestElement ?: return
        val baseApplication = baseManifest.applicationElement

        for (module in bundle.apkModuleList) {
            if (module === merged) continue
            val splitManifest = try {
                module.androidManifestBlock ?: continue
            } catch (_: Throwable) {
                continue
            }
            val splitManifestElement = splitManifest.manifestElement ?: continue

            for (splitChild in splitManifestElement.listElements()) {
                val tag = splitChild.name ?: continue
                if (tag !in NAMED_MANIFEST_TAGS) continue
                if (TAG_uses_split == tag) continue // dropped by sanitizer
                val name = androidNameValue(splitChild) ?: continue
                if (findNamedChild(baseManifestElement, tag, name) != null) continue
                val created = baseManifestElement.createChildElement(tag)
                deepCopy(splitChild, created)
            }

            // <application> children (activity, service, receiver, provider, meta-data).
            val splitApplication = splitManifest.applicationElement ?: continue
            if (baseApplication == null) continue
            for (splitChild in splitApplication.listElements()) {
                val tag = splitChild.name ?: continue
                if (tag !in NAMED_APPLICATION_TAGS) continue
                val name = androidNameValue(splitChild) ?: continue
                if (findNamedChild(baseApplication, tag, name) != null) continue
                val created = baseApplication.createChildElement(tag)
                deepCopy(splitChild, created)
            }

            // <queries> children (package / intent / provider)
            val splitQueriesList = splitManifestElement.listElements(TAG_queries)
            for (splitQueries in splitQueriesList) {
                val baseQueries = baseManifestElement.getElementByTagName(TAG_queries)
                if (baseQueries == null) {
                    val created = baseManifestElement.createChildElement(TAG_queries)
                    deepCopy(splitQueries, created)
                } else {
                    for (child in splitQueries.listElements()) {
                        val created = baseQueries.createChildElement(child.name ?: continue)
                        deepCopy(child, created)
                    }
                }
            }
        }
    }

    private fun findNamedChild(
        parent: ResXmlElement,
        tag: String,
        name: String
    ): ResXmlElement? {
        for (child in parent.listElements(tag)) {
            val childName = androidNameValue(child) ?: continue
            if (childName == name) return child
        }
        return null
    }

    private fun androidNameValue(element: ResXmlElement): String? {
        val attr = element.searchAttributeByResourceId(AndroidManifestBlock.ID_name)
            ?: element.searchAttributeByName(AndroidManifestBlock.NAME_name)
            ?: return null
        return try { attr.valueAsString } catch (_: Throwable) { null }
    }

    private fun deepCopy(source: ResXmlElement, dest: ResXmlElement) {
        for (srcAttr in source.listAttributes()) {
            val name = srcAttr.name ?: continue
            val resourceId = srcAttr.nameResourceID
            val dstAttr = if (resourceId != 0) {
                dest.getOrCreateAndroidAttribute(name, resourceId)
            } else {
                dest.getOrCreateAttribute(name, 0)
            }
            try {
                val type = srcAttr.valueType
                dstAttr.setValueType(type)
                when (type) {
                    ValueType.STRING -> {
                        val s = try { srcAttr.valueAsString } catch (_: Throwable) { null }
                        if (s != null) dstAttr.setValueAsString(s)
                        else dstAttr.setData(srcAttr.data)
                    }
                    ValueType.BOOLEAN -> dstAttr.setValueAsBoolean(srcAttr.valueAsBoolean)
                    else -> {
                        dstAttr.setData(srcAttr.data)
                    }
                }
            } catch (_: Throwable) {}
        }

        for (srcChild in source.listElements()) {
            val childTag = srcChild.name ?: continue
            val dstChild = dest.createChildElement(childTag)
            deepCopy(srcChild, dstChild)
        }
    }

    private fun propagateExtractNativeLibs(merged: ApkModule, bundle: ApkBundle) {
        if (!merged.hasAndroidManifestBlock()) return
        val manifest = merged.androidManifestBlock
        val mergedApp = manifest.applicationElement ?: return

        val existing = mergedApp.searchAttributeByResourceId(
            AndroidManifestBlock.ID_extractNativeLibs
        )
        if (existing != null) return

        for (module in bundle.apkModuleList) {
            if (module === merged) continue
            val splitManifest = try { module.androidManifestBlock } catch (_: Throwable) { null } ?: continue
            val splitApp = splitManifest.applicationElement ?: continue
            val attr = splitApp.searchAttributeByResourceId(
                AndroidManifestBlock.ID_extractNativeLibs
            ) ?: continue
            try {
                val value = attr.valueAsBoolean
                val dst = mergedApp.getOrCreateAndroidAttribute(
                    AndroidManifestBlock.NAME_extractNativeLibs,
                    AndroidManifestBlock.ID_extractNativeLibs
                )
                dst.setValueAsBoolean(value)
                return
            } catch (_: Throwable) {}
        }
    }

    private fun sanitizeMergedManifest(merged: ApkModule) {
        if (!merged.hasAndroidManifestBlock()) return
        val manifest = merged.androidManifestBlock
        val manifestElement = manifest.manifestElement ?: return

        manifestElement.removeAttributesWithName(AndroidManifestBlock.NAME_split)
        manifestElement.removeAttributesWithName(NAME_isFeatureSplit)
        manifestElement.removeAttributesWithName(NAME_isolatedSplits)
        manifestElement.removeAttributesWithName(NAME_splitTypes)
        manifestElement.removeAttributesWithName(NAME_requiredSplitTypes)
        manifestElement.removeAttributesWithName(AndroidManifestBlock.NAME_isSplitRequired)
        manifestElement.removeAttributesWithId(ID_isFeatureSplit)
        manifestElement.removeAttributesWithId(ID_isolatedSplits)
        manifestElement.removeAttributesWithId(ID_splitTypes)
        manifestElement.removeAttributesWithId(ID_requiredSplitTypes)
        manifestElement.removeAttributesWithId(AndroidManifestBlock.ID_isSplitRequired)

        val application = manifest.applicationElement
        application?.removeAttributesWithName(AndroidManifestBlock.NAME_isSplitRequired)
        application?.removeAttributesWithId(AndroidManifestBlock.ID_isSplitRequired)

        val toRemoveUsesSplit = manifestElement.listElements(TAG_uses_split).toList()
        toRemoveUsesSplit.forEach { manifestElement.removeElement(it) }

        if (application != null) {
            val toRemoveMeta = application.listElements(AndroidManifestBlock.TAG_meta_data)
                .filter { meta ->
                    val nameAttr = androidNameValue(meta) ?: return@filter false
                    nameAttr in SPLIT_METADATA_NAMES
                }
                .toList()

            for (meta in toRemoveMeta) {
                if (androidNameValue(meta) == "com.android.vending.splits") {
                    deleteSplitsXmlResource(merged, meta)
                }
            }
            toRemoveMeta.forEach { application.removeElement(it) }
        }
    }

    private fun deleteSplitsXmlResource(merged: ApkModule, metaData: ResXmlElement) {
        var refAttr = metaData.searchAttributeByResourceId(AndroidManifestBlock.ID_resource)
        if (refAttr == null) {
            refAttr = metaData.searchAttributeByResourceId(AndroidManifestBlock.ID_value)
        }
        if (refAttr == null || refAttr.valueType != ValueType.REFERENCE) return
        val resourceId = refAttr.data

        val tableBlock: TableBlock = try {
            merged.tableBlock ?: return
        } catch (_: Throwable) { return }

        val resourceEntry = tableBlock.getResource(resourceId) ?: return
        val zipEntryMap: ZipEntryMap = try { merged.zipEntryMap } catch (_: Throwable) { return }

        for (entry in resourceEntry) {
            if (entry == null) continue
            val resValue: ResValue = try { entry.resValue } catch (_: Throwable) { null } ?: continue
            val path = try { resValue.valueAsString } catch (_: Throwable) { null } ?: continue
            if (path.isNotEmpty()) {
                zipEntryMap.remove(path)
            }
            try { entry.setNull(true) } catch (_: Throwable) {}
        }
    }

    class MergeException(message: String) : RuntimeException(message)
}
