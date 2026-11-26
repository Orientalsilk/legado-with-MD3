# Legado with MD3

<p align="left">
  <a href="https://github.com/HapeLee/legado-with-MD3">简体中文</a> ｜ 
  <a href="https://github.com/HapeLee/legado-with-MD3/blob/main/English.md">English</a>
</p>

## 📖 介绍

**Legado with MD3** 是基于开源项目 [阅读 (Legado)](https://github.com/gedoor/legado) 开发的 Material
Design 3 风格重构版本。

本项目在对 UI 进行重绘的基础上，加入了多项分支独有功能，并正在逐步从传统 View 迁移至 Jetpack Compose
框架，目标是提供更加现代、流畅且一致的阅读体验。

---

## ✨ 分支特性

相比于官方版本，本项目具有以下独有特性：

* **全新主题：** 全新 Material Design 3 设计界面，支持 **预测性返回手势** 与 **共享元素动画**。
* **阅读界面：** 更加个性化的阅读界面与菜单配置。
* **阅读记录：** 提供详尽的阅读记录，支持 **时间轴** 与 **章节维度** 统计。
* **体验增强：** 更健全的 **漫画阅读** 、 **有声书** 与 **发现** 等界面体验。
* **书架布局：** 更多的书架布局选择，针对 **平板端** 进行了专门的界面优化。
* **实用功能：** 新增书籍备注、智能伴生分组（自动归类已读/未读），支持**手柄**上下**翻页**。

---

## 🛠️ 核心功能

1. **多格式支持：** 支持本地 TXT、EPUB 阅读，智能扫描本地文件。
2. **高度自定义：** 切换字体、背景、行距、段距、加粗、简繁转换等。
3. **净化替换：** 强力去除广告，替换正文内容。
4. **翻页模式：** 覆盖、仿真、滑动、滚动等多种模式随心切换。
5. **完全开源：** 无广告，持续迭代优化。

---

## 🚀 相比原版 (HapeLee/legado-with-MD3) 的修复与优化

基于上游 [HapeLee/legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) 源码，本分支额外完成了 **40 个源码与资源文件** 的稳定性修复、性能调优与 AI 体验改进：

### 1. Bug 修复与稳定性加固
* **日期天数计算修正**（`StringUtils.kt`）：修复 `dateConvert` 将小时数误除以 `60` 导致 24~59 小时前被误判为“今天”的逻辑错误（改为除以 `HOUR_OF_DAY`）。
* **协程取消异常保护**（`ReadBook.kt`、`Restore.kt`）：在阅读正文加载、阅读会话保存及配置恢复的 `catch (e: Exception)` 中显式重抛 `CancellationException`，避免切章/取消任务时吞掉取消信号并误报错误日志。
* **并发竞态与内存/句柄泄漏修复**：
  * `BookCover.kt`：将无界非线程安全 `randomDrawableCache` 改为加锁的有界 `LruCache<String, Drawable>(32)`，防止并发 `ConcurrentModificationException` 与封面驻留内存泄漏。
  * `BookController.kt`：移除 Web 服务 `getImg` 中共享可变字段消除并发请求不同书籍图片时的数据竞态，并对 `defaultCoverCache`（`WeakHashMap`）加同步保护。
  * `BackstageWebView.kt`：在 `destroy()` 中补充 `mHandler.removeCallbacksAndMessages(null)`，防止 WebView 销毁后延迟回调继续执行。
  * `ZipUtils.kt`：`getFilesPath` 改用 `ZipFile(zipFile).use { ... }`，确保异常时也能释放文件描述符。
  * `LogUtils.kt`：将共享非线程安全 `SimpleDateFormat` 改为有界 `LruCache + ThreadLocal` 缓存。
* **进度计算除零防护**（`ExportBookService.kt`）：为分卷导出进度累加增加 `scope.isNotEmpty()` 与 `epubList.isNotEmpty() && total > 0` 守卫，避免出现 `NaN`/`Infinity`。

### 2. 核心性能优化
* **正则预编译与有界缓存**（`ContentHelp.kt`、`LocalBookSemanticHtml.kt`、`AnalyzeByRegex.kt`、`BookExtensions.kt`、`TitleStyleParser.kt`、`ContentProcessor.kt`）：
  * 将章节段落重排（含段落循环内）14 处内联 `.toRegex()` 与 EPUB CSS 解析 6 处 `Regex` 提升为静态常量，并将 `
` 正则切分替换为 `split('\n')`。
  * 为书源正则提取、标签高亮/自动分组、标题分段与去重标题正则增加线程安全 `LruCache`，并消除 `AnalyzeByRegex.getElements` 先 `find` 再 `findAll` 的双重全文扫描。
* **消除数据库 N+1 查询**：
  * `BookDomainRepositoryImpl.kt`、`BookshelfAutoGroupRepository.kt`：改用 `bookDao.getBooksByUrls` 按 500 条分片批量 `IN` 查询，书架分组名改用内存位掩码匹配替代逐本 SQL。
  * `ReadAloudVoiceDao.kt` / `HttpTtsRepository.kt`、`BookSourceDao.kt` / `SearchRepository.kt`：分别新增 `deleteHttpVoicesByIds` 与 `getBookSourceParts` 批量 `IN` 操作，按 500 条分片替代循环单条删查。
* **主线程阻塞缓解**（`LocalConfig.kt`）：为 `password`、`lastBackup`、`privacyPolicyOk` 增加 `@Volatile` 内存缓存，首读后直接命中内存，避免反复在主线程 `runBlocking` 等待 DataStore。
* **Compose UI 与列表流畅度**：
  * `MangaZoomableImageSource.kt`、`DownloadSheet.kt`、`ReaderAudioDownloadSheet.kt`、`ReaderBookSheet.kt`、`GroupManageSheet.kt`、`HtmlContent.kt`：将残余 `collectAsState()` 统一迁移为 `collectAsStateWithLifecycle()`，避免后台继续收集 Flow。
  * `SearchContentScreen.kt`、`ChangeChapterSourceSheet.kt`、`ThemeConfigScreen.kt`、`CrashLogSheet.kt`、`VideoPlayerScreen.kt`、`ImportBookScreen.kt`、`RemoteBookScreen.kt`、`AppCouiTabRow.kt`：补齐 `LazyColumn`/`LazyRow` 稳定唯一 `key` 及 `scrollToItem` 索引边界校验。
  * `AiChatScreen.kt` & `StringUtils.kt`：通过 `ThreadLocal<SimpleDateFormat>` 缓存避免列表渲染与高频工具调用时重复分配格式化器。

### 3. AI 提示词与防呆体验优化
* **默认提示词精修**（`values-zh-rCN/strings.xml`、`values/strings.xml`）：
  * **对话 (`ai_prompt_default_chat`)**：细化为严谨、简洁的阅读与通用助手约束，要求基于上下文回答、不捏造情节。
  * **章节翻译 (`ai_prompt_default_translate`)**：消除“仅输出译文”与词典映射格式之间的冲突，明确保留空行与段落结构，并补充防正文提示词注入约束。
  * **章节总结 (`ai_prompt_default_summary`)**：补充防剧透、条理化输出与防注入约束。
  * **多角色朗读分析 (`ai_prompt_default_analyze_speech`)**：明确每个字与标点不增删、不改写，降低对齐失败率。
  * **人物识别 (`ai_prompt_default_identify_characters`)**：修复 `role` 枚举末尾悬空竖线（`female_supporting|` → `female_supporting|""`）。
* **提示词编辑防呆提示**（`AiPromptConfigScreen.kt`）：编辑翻译或 JSON 结构化任务提示词时，在输入框下方展示格式约束提示，降低误改导致解析失败的概率。
* **云端角色音色失败同池自动替换**（`HttpReadAloudService.kt`、`CastVoicePicker.kt`、`SystemTtsFileSynthesizer.kt`）：多角色朗读期间若某个云端音色（含 Legado 云 TTS、HTTP TTS 及 MultiTTS 中 `isNetworkConnectionRequired = true` 的云音色）合成失败，自动从该角色所属声音池中挑选可用备选音色重试并同步更新绑定，离线音色则保持永不自动替换。

---

## 从源码构建

首次检出后运行 `git submodule update --init modules/miuix modules/coui modules/mochi`。使用 JDK 21、Python 3.11+，在 `local.properties` 配置 `sdk.dir` 或设置 `ANDROID_HOME`，安装 Android 36、37.0、37.1 平台后执行：

```shell
python scripts/prepare-ui-forks.py
```

这会将固定提交的 COUI、Mochi 构建到本项目 `build/ui-forks/maven`，随后可照常运行 Gradle；Miuix 继续通过 `includeBuild` 编译。GitHub 的验证、Release 和手动 APK 工作流自动执行相同步骤，不需要 Maven 发布凭据。具体更新流程见 [UI fork 构建说明](docs/dev/ui-fork-builds.md)。

## 💬 交流

* **Telegram 群组：** [Legado with MD3](https://t.me/materado)
* **版本发布：**
  最新版本会自动发布至群组，也可在 [GitHub Releases](https://github.com/HapeLee/legado-with-MD3/releases)
  下载。

---

## ❤️ 致谢

感谢以下优秀开源项目提供的灵感与技术支持：

* [gedoor/legado](https://github.com/gedoor/legado) (这个项目最吊的老爹)
* [Luoyacheng/legado](https://github.com/Luoyacheng/legado) (提供了更多的扩展功能)
* [rimvoid/legado](https://github.com/rimvoid/legado) (提供了更多的扩展功能)
* [ItosEO/COUI-Kit](https://github.com/ItosEO/COUI-Kit) (使用 Compose 复刻和优化 ColorOS 17 UI 风格的UI库)
* [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) (使用 Compose 复刻和优化 HyperOS 4 UI 风格的UI库)
* [feice11/mochi-compose](https://github.com/feice11/mochi-compose) (软、弹、跟手的 Jetpack Compose 组件库)
* [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (适用于 Compose Multiplatform 的可自定义 Liquid Glass 特效库)
* [komikku-app/komikku](https://github.com/komikku-app/komikku) (提供了界面灵感与漫画相关实现参考)
* [FoedusProgramme/Gramophone](https://github.com/FoedusProgramme/Gramophone) (提供了界面灵感)
* [jordond/MaterialKolor](https://github.com/jordond/MaterialKolor) (基于 Jetpack Compose
  优秀的取色实现)
* [Calvin-LL/Reorderable](https://github.com/Calvin-LL/Reorderable) (基于 Jetpack Compose
  优秀的拖动排序实现)
* 以及更多开源项目...

## ⚠️ 用户协议与免责声明

> **【特别提示】**
> 在下载、安装或使用本软件前，请您务必仔细阅读并充分理解本协议及免责声明的全部内容。您一旦下载、安装或使用本软件，即视为已阅读、理解并同意接受本声明的全部内容。
> 联系方式：kudomagaz@gmail.com

### 一、软件性质说明

1. 本软件是一款用户可配置的本地化网页内容浏览工具，提供网页访问、内容解析、文本提取、阅读排版与数据管理等技术功能。
2. 本软件默认状态下不预置、不内置、不提供任何第三方网站内容、数据资源或解析规则。
3. 本软件开发者不提供任何内容运营、内容存储、内容发布或内容传播服务。
4. 用户可基于自身需求，自行配置或导入第三方规则，以实现对公开网页内容的个性化浏览与处理。

### 二、用户行为与规则说明

1. 用户可自行创建、编辑、导入或使用第三方分享的解析规则（以下简称"规则"）。
2. 相关规则仅用于定义网页内容的获取、提取与显示方式，其来源、合法性、准确性与适用性均由用户自行判断并承担责任。
3. 用户使用规则访问第三方网站时，相关网络请求由用户设备直接向目标网站发起并接收数据。本软件仅提供本地解析与展示能力，不对第三方网站内容进行修改、编辑或再分发。
4. 用户应遵守所在地法律法规、网络安全要求以及相关网站的服务协议与版权规范，不得利用本软件从事侵犯知识产权、非法传播、未授权数据获取、破坏网络服务或其他违法违规行为。

### 三、第三方内容与社区说明

1. 任何由第三方建立或维护的规则分享平台、论坛、交流群组、网站或其他社区，均属于独立运营的第三方平台，与本软件开发者无隶属关系。
2. 开发者不参与第三方规则、内容或社区的制作、发布、运营、维护与传播行为，亦不对相关内容承担主动审查义务。
3. 用户因使用第三方规则或访问第三方网站而产生的风险，包括但不限于版权纠纷、数据安全风险、网络访问风险或其他法律风险，应由相关行为主体依法承担相应责任。

### 四、隐私与数据说明

1. 本软件主要功能运行于用户本地设备，不设立用于提供网页内容服务的自有内容服务器。
2. 本软件不会主动收集、上传或存储用户的阅读内容、规则列表、浏览记录或其他个人隐私数据。
3. 为提升软件稳定性与兼容性，本软件可能集成第三方统计或崩溃分析服务（如 Firebase Crashlytics
   等），用于收集匿名化的崩溃日志、性能信息及基础设备信息。
4. 部分网络、存储或同步权限，仅用于实现用户主动启用的本地备份、WebDAV 同步或跨设备数据同步等功能。

### 五、知识产权保护

1. 开发者尊重并保护知识产权权利人的合法权益，并反对任何侵犯著作权、商标权或其他合法权益的行为。
2. 用户应确保其使用本软件获取、处理或访问相关内容的行为符合适用法律法规及权利要求。
3. 若权利人认为某些第三方规则存在涉嫌侵权情形，可依法向相关内容实际托管方主张权利。
4. 权利人亦可向开发者提交包含身份证明、权属证明、具体规则信息及相关说明的有效通知。开发者将在合理技术能力范围内，对涉嫌违规规则采取必要处理措施。

