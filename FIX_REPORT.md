# Legado-with-MD3 Bug 修复、性能优化与 AI 提示词优化说明（共 26 个文件）

## 一、自查收口与新增 Bug 修复
* `AnalyzeByRegex.kt`：为 `regexCache` 的 `get`/`put` 增加 `synchronized` 锁，消除首次并发编译竞态。
* `StringUtils.kt` & `LogUtils.kt`：将无界 `ConcurrentHashMap<String, ThreadLocal<SimpleDateFormat>>` 替换为有界 `LruCache`（容量 32 / 16），防止非常规动态格式字符串无限驻留内存。
* `BackstageWebView.kt`：`destroy()` 中补充 `mHandler.removeCallbacksAndMessages(null)`，防止 WebView 销毁后延迟的 `EvalJsRunnable` 继续触发回调或持有引用。

## 二、AI 设置默认提示词优化（`values-zh-rCN/strings.xml` & `values/strings.xml`）
1. **对话 (`ai_prompt_default_chat`)**：从空泛的“有帮助的 AI 助手”细化为“严谨、简洁的小说阅读与通用助手”，约束优先依据提供上下文、不捏造未提及情节。
2. **章节翻译 (`ai_prompt_default_translate`)**：修正“仅输出翻译结果”与词典映射格式之间的轻微冲突，明确保留空行/段落结构，并补充防提示词注入约束（将小说原文严格视为数据）。
3. **章节总结 (`ai_prompt_default_summary`)**：补充防剧透、条理化输出及防正文提示词注入约束。
4. **多角色朗读分析 (`ai_prompt_default_analyze_speech`)**：明确“每个字与标点不增删、不改写”，降低模型擅自改写标点导致对齐失败的概率。
5. **人物识别 (`ai_prompt_default_identify_characters`)**：修复 `role` 枚举末尾悬空竖线（`female_supporting|` → `female_supporting|""`），避免模型对空值合法性产生歧义。

## 三、前两轮已包含的核心修复与优化（23 个文件）
* 详见前两轮清单（`StringUtils` 天数计算 Bug、`BookCover` 缓存泄漏、`BookController` 并发竞态、`ReadBook`/`Restore` 协程取消异常、`ZipUtils` 句柄泄漏、`ContentHelp`/`LocalBookSemanticHtml`/`BookExtensions`/`TitleStyleParser` 正则缓存、`BookDomainRepositoryImpl`/`BookshelfAutoGroupRepository` 批量查询、Compose `key` 与 `collectAsStateWithLifecycle()` 迁移等）。
