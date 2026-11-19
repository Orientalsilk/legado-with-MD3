package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.HttpTtsRepository
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfigStore
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 备份
 */
object Backup {

    private val readStyleGateway: ReadStyleGateway
        get() = GlobalContext.get().get()

    private val backupSettingsGateway: BackupSettingsGateway
        get() = GlobalContext.get().get()

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

    /** 段评气泡包在备份里的目录名：整棵 `bubblePackages/` 树跟着备份走（与 Legado-Arc 对齐）。 */
    const val bubblePackagesDirName = "bubblePackages"

    /** 旧版备份把全部气泡包打成一个 zip；仅用于恢复旧备份。 */
    const val legacyBubblePackagesFileName = "bubblePackages.zip"

    private val backupFileNames by lazy {
        arrayOf(
            "bookshelf.json",
            "bookmark.json",
            "bookMarking.json",
            "bookGroup.json",
            "bookSource.json",
            "rssSources.json",
            "rssStar.json",
            "replaceRule.json",
            "readRecord.json",
            "readRecordDetail.json",
            "readRecordSession.json",
            "searchHistory.json",
            "sourceSub.json",
            "txtTocRule.json",
            "httpTTS.json",
            "httpTtsVoices.json",
            "keyboardAssists.json",
            "dictRule.json",
            "homepageModules.json",
            "homepageCustomSets.json",
            "highlightRule.json",
            "highlightTagRule.json",
            "tagGroupRule.json",
            "servers.json",
            DirectLinkUpload.ruleFileName,
            ReadBookConfig.configFileName,
            ReadBookConfig.shareConfigFileName,
            ThemeConfigStore.configFileName,
            BookCover.configFileName,
            "config.xml"
        )
    }

    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val deviceName = backupSettingsGateway.currentSettings.webDavDeviceName
        return if (deviceName.isNotBlank()) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    private fun shouldBackup(): Boolean {
        val lastBackup = LocalConfig.lastBackup
        return lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()
    }

    fun autoBack(context: Context) {
        if (shouldBackup()) {
            Coroutine.async {
                BackupRestoreLock.withLock {
                    if (shouldBackup()) {
                        val backupZipFileName = getNowZipFileName()
                        if (!AppWebDav.hasBackUp(backupZipFileName)) {
                            backup(context, backupSettingsGateway.currentSettings.backupPath)
                        } else {
                            LocalConfig.lastBackup = System.currentTimeMillis()
                        }
                    }
                }
            }.onError {
                AppLog.put("自动备份失败\n${it.localizedMessage}")
            }
        }
    }

    suspend fun backupLocked(context: Context, path: String?, mode: String = "both") {
        BackupRestoreLock.withLock {
            withContext(IO) {
                backup(context, path, mode)
            }
        }
    }

    private suspend fun backup(context: Context, path: String?, mode: String = "both") {
        LogUtils.d(TAG, "开始备份 path:$path")
        val backupTimestamp = System.currentTimeMillis()
        val aes = BackupAES()
        FileUtils.delete(backupPath)
        writeListToJson(
            appDb.bookDao.all.filterNot { BackupConfig.backupIgnoreLocalBook && it.isLocal },
            "bookshelf.json",
            backupPath,
        )
        // 书签与划线/想法笔记（book_marks）视为一体，统一受既有 bookmark 忽略项控制
        if (BackupConfig.dbIsNotIgnored("bookmark", true)) {
            writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
            writeListToJson(appDb.bookMarkingDao.all, "bookMarking.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookGroup", true)) {
            writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookSource", true)) {
            writeListToJson(appDb.bookSourceDao.all, "bookSource.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("rssSource", true)) {
            writeListToJson(appDb.rssSourceDao.all, "rssSources.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("rssStar", true)) {
            writeListToJson(appDb.rssStarDao.all, "rssStar.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("replaceRule", true)) {
            writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("readRecord", true)) {
            writeListToJson(appDb.readRecordDao.all, "readRecord.json", backupPath)
            writeListToJson(appDb.readRecordDao.allDetail, "readRecordDetail.json", backupPath)
            writeListToJson(appDb.readRecordDao.allSession, "readRecordSession.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("searchHistory", true)) {
            writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("sourceSub", true)) {
            writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("txtTocRule", true)) {
            writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("httpTTS", true)) {
            val snapshot = GlobalContext.get().get<HttpTtsRepository>().backupSnapshot()
            writeListToJson(snapshot.sources, "httpTTS.json", backupPath)
            // 空数组也要写出，用来区分旧备份未保存音色与确实没有音色。
            File(backupPath, "httpTtsVoices.json").writeText(GSON.toJson(snapshot.voices))
        }
        if (BackupConfig.dbIsNotIgnored("keyboardAssists", true)) {
            writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("dictRule", true)) {
            writeListToJson(appDb.dictRuleDao.all, "dictRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("homepageModules", true)) {
            writeListToJson(appDb.homepageModuleDao.getAll(), "homepageModules.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("homepageCustomSets", true)) {
            writeListToJson(
                appDb.homepageCustomSetDao.getAll(),
                "homepageCustomSets.json",
                backupPath
            )
        }
        if (BackupConfig.dbIsNotIgnored("highlightRule", true)) {
            writeListToJson(appDb.highlightRuleDao.getAll(), "highlightRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("highlightTagRule", true)) {
            writeListToJson(appDb.highlightTagRuleDao.getAll(), "highlightTagRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("tagGroupRule", true)) {
            writeListToJson(appDb.tagGroupRuleDao.getAll(), "tagGroupRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("server", true)) {
            GSON.toJson(appDb.serverDao.all).let { json ->
                aes.runCatching {
                    encryptBase64(json)
                }.getOrDefault(json).let {
                    FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                        .writeText(it)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (!BackupConfig.backupIgnoreReadConfig) {
            readStyleGateway.exportConfigsJson().let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.configFileName)
                    .writeText(it)
            }
            readStyleGateway.exportShareConfigJson().let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.shareConfigFileName)
                    .writeText(it)
            }
        }
        if (!BackupConfig.backupIgnoreThemeConfig) {
            GSON.toJson(ThemeConfigStore.configList).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfigStore.configFileName)
                    .writeText(it)
            }
        }
        DirectLinkUpload.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + DirectLinkUpload.ruleFileName)
                .writeText(GSON.toJson(it))
        }
        if (!BackupConfig.backupIgnoreCoverConfig) {
            BookCover.getConfig()?.let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                    .writeText(GSON.toJson(it))
            }
        }
        currentCoroutineContext().ensureActive()
        val configMap = AppConfigStore.preferences.asMap()
            .mapKeys { it.key.name }
            .toMutableMap()
        if (PreferKey.bookshelfSelectedGroupId !in configMap) {
            (configMap[PreferKey.saveTabPosition] as? Long)?.let {
                configMap[PreferKey.bookshelfSelectedGroupId] = it
            }
        }
        if (PreferKey.readBrightnessMode !in configMap) {
            when (val legacy = configMap[PreferKey.showBrightnessView]) {
                is String -> configMap[PreferKey.readBrightnessMode] = legacy
                is Boolean -> configMap[PreferKey.readBrightnessMode] = if (legacy) "1" else "0"
            }
        }
        if (PreferKey.readBrightnessControlPosition !in configMap) {
            when (val legacy = configMap[PreferKey.brightnessVwPos]) {
                is String -> configMap[PreferKey.readBrightnessControlPosition] = legacy
                is Boolean -> configMap[PreferKey.readBrightnessControlPosition] =
                    if (legacy) "1" else "0"
            }
        }
        val xmlBuilder = StringBuilder()
        xmlBuilder.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        xmlBuilder.append("<map>\n")
        configMap.forEach { (key, value) ->
            // 这些旧键在其他 Legado 变体中有不同类型或含义，备份只导出独立键。
            if (key !in setOf(
                    PreferKey.saveTabPosition,
                    PreferKey.showBrightnessView,
                    PreferKey.brightnessVwPos
                )
                && BackupConfig.keyIsNotIgnore(key, true)
            ) {
                val finalValue = if (key == PreferKey.webDavPassword) {
                    aes.runCatching { encryptBase64(value.toString()) }.getOrDefault(value.toString())
                } else value

                when (finalValue) {
                    is String -> xmlBuilder.append("    <string name=\"$key\">${finalValue.replace("&", "&amp;").replace("<", "&lt;")}</string>\n")
                    is Int -> xmlBuilder.append("    <int name=\"$key\" value=\"$finalValue\" />\n")
                    is Long -> xmlBuilder.append("    <long name=\"$key\" value=\"$finalValue\" />\n")
                    is Float -> xmlBuilder.append("    <float name=\"$key\" value=\"$finalValue\" />\n")
                    is Boolean -> xmlBuilder.append("    <boolean name=\"$key\" value=\"$finalValue\" />\n")
                }
            }
        }
        xmlBuilder.append("</map>")
        FileUtils.createFileIfNotExist(backupPath + File.separator + "config.xml")
            .writeText(xmlBuilder.toString())

        currentCoroutineContext().ensureActive()
        backupBubblePackages(backupPath)
        val zipFileName = getNowZipFileName()
        val paths = (
            backupFileNames.map { File(backupPath, it) } +
                listOfNotNull(File(backupPath, bubblePackagesDirName).takeIf { it.isDirectory })
            )
            .filter { it.isFile || it.isDirectory }
            .map(File::getAbsolutePath)
        // 只清临时包；正式的 backup.zip 要等新包复制成功后才被覆盖，避免压缩失败时旧备份先丢
        FileUtils.delete(zipFilePath)
        val backupFileName = if (backupSettingsGateway.currentSettings.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        // 本地复制失败（目录权限被撤销等）不应跳过 WebDAV 上传；但只有至少一个目标实际写入成功，
        // 才更新 lastBackup，避免压缩失败或所有目标都失败后自动备份仍被压住一整天。
        var localCopyError: Exception? = null
        var webDavError: Exception? = null
        var destinationSucceeded = false
        try {
            if (!ZipUtils.zipFiles(paths, zipFilePath)) {
                throw NoStackTraceException("备份压缩失败")
            }
            if (mode == "both" || mode == "local") {
                try {
                    when {
                        path.isNullOrBlank() -> {
                            copyBackup(context.getExternalFilesDir(null)!!, backupFileName)
                        }

                        path.isContentScheme() -> {
                            copyBackup(context, path.toUri(), backupFileName)
                        }

                        else -> {
                            copyBackup(File(path), backupFileName)
                        }
                    }
                    destinationSucceeded = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    localCopyError = e
                    AppLog.put("备份到本地失败\n$e", e)
                }
            }
            if (mode == "both" || mode == "webdav") {
                try {
                    AppWebDav.backUpWebDav(zipFileName)
                    destinationSucceeded = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    webDavError = e
                    AppLog.put("上传备份至webdav失败\n$e", e)
                }
            }
        } finally {
            FileUtils.delete(backupPath)
            FileUtils.delete(zipFilePath)
        }
        currentCoroutineContext().ensureActive()
        if (!destinationSucceeded) {
            throw localCopyError ?: webDavError ?: NoStackTraceException("备份未写入任何目标")
        }
        LocalConfig.lastBackup = backupTimestamp
        readStyleGateway.allBackgroundImagePaths().map {
            if (it.contains(File.separator)) {
                File(it)
            } else {
                appCtx.externalFiles.getFile("bg", it)
            }
        }.let {
            AppWebDav.upBgs(it.toTypedArray())
        }
        localCopyError?.let { throw it }
    }

    /**
     * 段评气泡包不在 [backupFileNames] 那批 json/xml 里，备份里保留整棵 `bubblePackages/` 目录
     * （与 Legado-Arc 的备份布局一致；旧版 `bubblePackages.zip` 仍可恢复）。
     * 只装本地包：内置的、temp、remote_cache 一律不进。
     */
    private suspend fun backupBubblePackages(backupPath: String) = withContext(IO) {
        val target = File(backupPath, bubblePackagesDirName)
        runCatching {
            FileUtils.delete(target)
            val dirs = BubblePackageManager.localPackageDirs()
            if (dirs.isEmpty()) return@withContext
            target.mkdirs()
            dirs.forEach { dir ->
                check(dir.copyRecursively(File(target, dir.name), overwrite = true)) {
                    "failed to copy ${dir.name}"
                }
            }
        }.onFailure { error ->
            AppLog.put("备份段评气泡出错\n${error.localizedMessage}", error)
            FileUtils.delete(target)
        }
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            if (list.isNotEmpty()) {
                LogUtils.d(TAG, "阅读备份 $fileName 列表大小 ${list.size}")
                val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, list)
                }
                LogUtils.d(TAG, "阅读备份 $fileName 写入大小 ${file.length()}")
            } else {
                LogUtils.d(TAG, "阅读备份 $fileName 列表为空")
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(context: Context, uri: Uri, fileName: String) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)
            ?: throw NoStackTraceException("无法打开备份目录")
        // 先写唯一暂存名；不能复用固定 .partial，否则上次失败留下的完整备份可能被本次覆盖。
        val suffix = System.currentTimeMillis()
        val partialName = "$fileName.partial-$suffix"
        val previousName = "$fileName.previous-$suffix"
        val fileDoc = treeDoc.createFile("", partialName)
            ?: throw NoStackTraceException("创建文件失败")
        try {
            val outputS = fileDoc.openOutputStream()
                ?: throw NoStackTraceException("打开OutputStream失败")
            outputS.use { out ->
                FileInputStream(zipFilePath).use { inputS ->
                    inputS.copyTo(out)
                }
            }
        } catch (error: Throwable) {
            fileDoc.delete()
            throw error
        }

        // 不能先删旧备份再 rename 新文件：SAF provider 的 renameTo 可能失败。
        // 先把旧文件改名暂存，只有新文件成功占据正式文件名后才删除旧文件。
        val existing = treeDoc.findFile(fileName)
        val previous = if (existing != null) {
            if (!existing.renameTo(previousName)) {
                fileDoc.delete()
                throw NoStackTraceException("无法暂存旧备份，原文件未改动")
            }
            treeDoc.findFile(previousName) ?: existing
        } else {
            null
        }

        if (!fileDoc.renameTo(fileName)) {
            val restored = previous?.renameTo(fileName) ?: true
            val recovery = if (restored) {
                "旧备份已恢复"
            } else {
                "旧备份仍保留在 $previousName"
            }
            throw NoStackTraceException("替换备份文件失败，$recovery；新备份仍在 $partialName")
        }
        previous?.delete()
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(rootFile: File, fileName: String) {
        // FileOutputStream 一打开就会把已有 backup.zip 截断。改成同目录写临时文件再替换。
        val dest = File(rootFile, fileName)
        FileUtils.copyFileAtomic(File(zipFilePath), dest.absolutePath)
    }

    fun clearCache() {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
    }
}
