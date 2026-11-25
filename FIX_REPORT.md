# Legado-with-MD3 Bug 修复与性能优化说明

## 一、Bug 修复（6 项）
1. **日期天数计算错误** (`app/src/main/java/io/legado/app/utils/StringUtils.kt`)
   - 修复 `dateConvert` 中 `val difDate = difHour / 60` 的错误（1 天为 24 小时而非 60 小时，原代码导致 24~59 小时前的内容被误判为“今天”），改为 `difHour / HOUR_OF_DAY`。
   - 同步为 `SimpleDateFormat` 增加基于 `ConcurrentHashMap + ThreadLocal` 的线程安全缓存，避免并发问题及重复分配开销。
2. **随机封面缓存并发竞争与内存泄漏** (`app/src/main/java/io/legado/app/model/BookCover.kt`)
   - 将无界且非线程安全的 `mutableMapOf<String, Drawable>()` 替换为有界 `LruCache<String, Drawable>(32)` 并加 `synchronized` 保护，防止多线程 `ConcurrentModificationException` 及封面图无限驻留内存。
3. **Web 服务控制器并发竞态与非线程安全缓存** (`app/src/main/java/io/legado/app/api/controller/BookController.kt`)
   - 移除 `object BookController` 上被多线程共享修改的 `book`、`bookSource`、`bookUrl` 可变字段，改为 `getImg` 局部变量，消除并发请求不同书籍图片时的数据覆盖竞态。
   - 使用 `Collections.synchronizedMap(WeakHashMap())` 并加锁保护 `defaultCoverCache`。
4. **阅读核心协程取消异常被吞没** (`app/src/main/java/io/legado/app/model/ReadBook.kt`)
   - 在 `commitSessionInternal`、`saveSessionToDb` 与 `download` 的 `catch (e: Exception)` 中补充 `if (e is CancellationException) throw e`，保证切章或退出阅读时协程能正常取消，不再误报 `"加载正文出错 Job was cancelled"`。
5. **日志格式化器并发不安全与高频对象分配** (`app/src/main/java/io/legado/app/utils/LogUtils.kt`)
   - 将共享 `SimpleDateFormat` 改造为 `ThreadLocal` 缓存，修复多线程写日志时 `SimpleDateFormat` 线程安全隐患并消除每行日志重复创建格式化器的开销。
6. **ZIP 文件句柄潜在泄漏** (`app/src/main/java/io/legado/app/utils/compress/ZipUtils.kt`)
   - `getFilesPath` 改用 `ZipFile(zipFile).use { ... }`，确保遍历异常时仍能可靠释放文件描述符。

## 二、性能优化（5 项）
1. **章节重排正则预编译** (`app/src/main/java/io/legado/app/help/book/ContentHelp.kt`)
   - 将 `reSegmentMapped` 中每次调用（含段落 `for` 循环内）重复编译的 14 个 `Regex` 提升为 `object ContentHelp` 的静态常量，并将 `split("\n".toRegex())` 替换为无正则开销的 `split('\n')`。
2. **EPUB/本地 HTML CSS 解析正则预编译** (`app/src/main/java/io/legado/app/model/localBook/LocalBookSemanticHtml.kt`)
   - 将 `parseCssRules` 与 `parseSimpleSelector` 中的 6 个高频 `Regex` 提升为静态常量。
3. **书源正则规则缓存与单次扫描** (`app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeByRegex.kt`)
   - 为 `Regex` 增加 `LruCache<String, Regex>(64)` 缓存；重构 `getElements` 消除先 `find` 再 `findAll` 的双重全文扫描。
4. **正文搜索结果列表项 Key 优化** (`app/src/main/java/io/legado/app/ui/book/searchContent/SearchContentScreen.kt`)
   - 为 `FastScrollLazyColumn` 的 `itemsIndexed` 补充稳定唯一 `key`，减少滚动与增量更新时的 Compose 重组。
5. **换源目录弹窗列表 Key 与越界保护** (`app/src/main/java/io/legado/app/ui/book/read/sheet/ChangeChapterSourceSheet.kt`)
   - 为 `LazyColumn` 的 `itemsIndexed` 补充唯一 `key`，并在 `scrollToItem` 前校验 `state.currentTocIndex in state.tocItems.indices`。

## 三、验证说明
- 已在本地完成 11 个修改文件的源码核对与行数/结构校验。
- 因当前沙箱环境无网络及 Android SDK / Gradle 缓存，尚未在本地执行 `./gradlew :app:compileAppCoreDebugKotlin` 与单元测试；请解压覆盖至仓库根目录后运行 Gradle 构建验证。
