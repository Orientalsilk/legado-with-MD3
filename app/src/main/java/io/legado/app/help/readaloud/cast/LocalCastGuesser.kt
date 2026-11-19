package io.legado.app.help.readaloud.cast

/**
 * 离线（免费、不联网、不用 AI）的台词说话人判断。
 *
 * 输入与 AI 分配收到的完全一样：带 `⟦序号⟧` 锚点的连续正文（锚点插在开引号之后）。
 * 只做两件事：
 *  1. 从引号前后的「某某说/道/问…」里读出说话人；认得出才给，认不出一律不给，
 *     由那句话走默认旁白——宁可少分，也不安错人（与 AI 分配「模型没给的锚点一律留空」同口径）。
 *  2. 新人物按称呼里的性别/年龄词、上下文里的「他/她」挑一个声音池；已有角色一律沿用原池。
 *
 * 纯函数、没有 Android 依赖，便于单元测试。
 */
internal object LocalCastGuesser {

    /** 本书已有角色（主名 + 池 + 别名），命中就原样复用。 */
    data class Known(val name: String, val pool: String, val aliases: List<String> = emptyList())

    data class Guess(val ordinal: Int, val name: String, val pool: String)

    private enum class Age { YOUTH, ADULT, MIDDLE, ELDER }

    private val anchorRegex = Regex("⟦(\\d+)⟧")
    private val closers = mapOf('“' to '”', '「' to '」', '『' to '』', '"' to '"', '‘' to '’')

    private const val VERBS =
        "(?:冷笑道|苦笑道|低声道|沉声道|轻声道|大声道|冷声道|淡淡道|说道|问道|答道|笑道|叹道|怒道|" +
            "喝道|喊道|骂道|吼道|叫道|嚷道|应道|回道|劝道|哼道|接着道|说|问|答|道)"

    /** 引号后面紧跟的「某某说」：subject 在动词之前，最多 8 个字。 */
    private val afterRegex = Regex("^([一-龥]{1,8}?)$VERBS")

    /** 引号前面最后一个小句「某某说：」。 */
    private val beforeRegex = Regex("^([一-龥]{1,8}?)$VERBS$")

    private val clauseSplit = Regex("[。！？…；，,、\\s“”‘’「」『』:：]")

    private val adverbTail = Regex(
        "(?:又|也|却|便|就|还|都|更|轻轻|缓缓|淡淡|冷冷|低声|沉声|轻声|大声|微微|忍不住|不由|连忙|急忙|" +
            "忙|随即|接着|终于|立刻|忽然|突然|笑着|冷笑|苦笑|冷哼|笑|冷|苦|轻|淡|地)+$"
    )
    private val leadingJunk = Regex(
        "^(?:只见|但见|却见|只听|听到|听见|这时|此时|然后|随后|接着|于是|而|但|便|又|可|却|就|那个|这个|" +
            "另一个|另一名|那名|这名|一名|一位|那位|这位|那|这)+"
    )

    /** 出现在 subject 里说明这是「对谁说」之类的宾语从句，说话人不确定，放弃。 */
    private const val OBJECT_MARKERS = "对把将跟给向被让叫请令使找喊唤骂瞪盯拉推扶抱拍抓碰摸拦挡追随陪替从与和同"
    private const val BAD_START = "的了是在也都就还又却但只于而且并这那有被把将对向跟给和与及或如若虽既因所其"

    /** 一次性群体/泛指，不是任何一个人。 */
    private val throwaway = setOf(
        "众人", "大家", "所有人", "有人", "对方", "那人", "此人", "旁人", "众", "声音", "话音", "其他人",
        "大伙", "他们", "她们", "我们", "你们", "咱们", "两人", "三人", "二人", "几人", "一人", "来人",
    )

    private val femaleTitle = Regex(
        "女|姑娘|小姐|夫人|太太|妈|娘|姐|妹|嫂|婆|姨|婶|妃|皇后|太后|王后|姬|妾|婢|丫鬟|丫头|公主|格格|仙子|少妇|闺|媳|妇"
    )
    private val maleTitle = Regex(
        "男|少爷|公子|大叔|叔|伯|爷|父|哥|弟|兄|汉|先生|将军|老者|老头|老翁|道长|和尚|相公|官人|丈夫|" +
            "皇帝|太子|大侠|书生|郎|翁|叟|僧|师傅|掌柜|员外|老爷|公公"
    )
    private val femaleNameChars = "娘妹姐嫂婆姬妃娥莲梅芳婷娟霞燕琳蓉薇颖倩黛珊娜娇媛婉嫣妍菲菁蕊玲琴"

    private val elderTitle = Regex("老[者头翁妇妪人太婆汉爷]|爷爷|奶奶|婆婆|祖|翁|叟|太后|老太|公公|老爷子")
    private val youthTitle = Regex("少年|少女|孩|童|小姑娘|丫头|小子|小[男女]|公主|幼|娃")
    private val middleTitle = Regex("大叔|大婶|大妈|阿姨|掌柜|员外|老板|夫人|太太|妈|爸|父|母|叔|伯|婶|姨|中年")

    /**
     * @param excerpt 带 `⟦序号⟧` 锚点的正文（与 AI 分配同一份窗口）
     * @param pending 本窗口要回答的锚点序号
     * @param pools   启用的声音池名（如「男青年」「女中年」）
     */
    fun guess(
        excerpt: String,
        pending: Collection<Int>,
        known: List<Known>,
        pools: List<String>,
    ): List<Guess> {
        val wanted = pending.toSet()
        val result = ArrayList<Guess>()
        // 最近一次点名的男/女说话人，供「他说/她说」承接；超过 [PRONOUN_REACH] 个锚点就不再承接
        val lastMale = Recent()
        val lastFemale = Recent()
        var anchorCount = 0
        val created = HashMap<String, String>() // 本次新定的名字 → 池

        for (line in excerpt.lineSequence()) {
            val anchors = anchorRegex.findAll(line).toList()
            if (anchors.isEmpty()) continue
            var previousClose = 0
            for ((index, anchor) in anchors.withIndex()) {
                anchorCount++
                val ordinal = anchor.groupValues[1].toIntOrNull() ?: continue
                val openPos = anchor.range.first - 1
                val opener = line.getOrNull(openPos)
                val closeChar = opener?.let { closers[it] } ?: '”'
                val closePos = line.indexOf(closeChar, anchor.range.last + 1)
                    .let { if (it < 0) line.length else it }
                val beforeText = strip(line.substring(previousClose.coerceAtMost(maxOf(openPos, 0)), maxOf(openPos, 0)))
                val nextOpen = anchors.getOrNull(index + 1)?.range?.first?.minus(1) ?: line.length
                val afterStart = (closePos + 1).coerceAtMost(line.length)
                val afterText = strip(line.substring(afterStart, maxOf(nextOpen, afterStart)))
                previousClose = afterStart

                if (ordinal !in wanted) continue
                val subject = subjectAfter(afterText) ?: subjectBefore(beforeText) ?: continue

                val named = when (subject) {
                    "他" -> lastMale.get(anchorCount)
                    "她" -> lastFemale.get(anchorCount)
                    else -> null
                }
                val resolved: Pair<String, String>? = if (subject == "他" || subject == "她") {
                    named?.let { it to poolOf(it, known, created) }
                } else {
                    resolveName(subject, known)?.let { known ->
                        known.name to known.pool
                    } ?: newName(subject)?.let { name ->
                        val pool = created.getOrPut(name) {
                            pickPool(pools, subject, name, beforeText + afterText)
                        }
                        name to pool
                    }
                }
                val (name, pool) = resolved ?: continue
                result += Guess(ordinal, name, pool)
                when (genderOfPool(pool, subject, name, beforeText + afterText)) {
                    true -> lastMale.set(name, anchorCount)
                    false -> lastFemale.set(name, anchorCount)
                    null -> Unit
                }
            }
        }
        return result
    }

    private class Recent {
        private var name: String? = null
        private var at = Int.MIN_VALUE
        fun set(value: String, position: Int) {
            name = value
            at = position
        }

        fun get(position: Int): String? = name?.takeIf { position - at <= PRONOUN_REACH }
    }

    private const val PRONOUN_REACH = 6

    private fun strip(text: String): String = anchorRegex.replace(text, "")

    private fun subjectAfter(after: String): String? {
        val head = after.trimStart('，', ',', '、', ' ').substringBefore('。')
            .substringBefore('！').substringBefore('？').substringBefore('；')
        val match = afterRegex.find(head) ?: return null
        return cleanSubject(match.groupValues[1])
    }

    private fun subjectBefore(before: String): String? {
        val tail = before.trimEnd('：', ':', '，', ',', ' ').split(clauseSplit).lastOrNull().orEmpty()
        if (tail.isEmpty()) return null
        // 只认以「说/道」之类收尾、且紧挨着引号的那个小句
        val closed = if (before.trimEnd().let { it.endsWith("：") || it.endsWith(":") || it.endsWith("，") || it.endsWith(",") }) {
            tail
        } else {
            return null
        }
        val match = beforeRegex.find(closed) ?: return null
        return cleanSubject(match.groupValues[1])
    }

    private fun cleanSubject(raw: String): String? {
        var subject = raw
        subject = leadingJunk.replace(subject, "")
        subject = adverbTail.replace(subject, "")
        if (subject.isEmpty()) return null
        if (subject == "他" || subject == "她") return subject
        if (subject == "我" || subject == "你" || subject == "您" || subject == "它") return null
        if (subject.length > 6) return null
        if (subject.any { it in OBJECT_MARKERS }) return null
        if (subject[0] in BAD_START) return null
        if (subject in throwaway) return null
        return subject
    }

    /** subject 里含有已有角色的主名或别名就归到该角色（最长优先）。 */
    private fun resolveName(subject: String, known: List<Known>): Known? =
        known.filter { it.name.isNotBlank() }
            .mapNotNull { character ->
                val names = listOf(character.name) + character.aliases
                val hit = names.filter { it.length >= 2 && subject.contains(it) }
                    .maxByOrNull { it.length }
                hit?.let { character to it.length }
            }
            .maxByOrNull { it.second }
            ?.first

    /** 新人物的名字：2~4 个汉字；更长的取最后 3 字（名字多在称呼末尾）。 */
    private fun newName(subject: String): String? {
        val name = if (subject.length > 4) subject.takeLast(3) else subject
        if (name.length < 2) return null
        if (name in throwaway) return null
        if (name.any { it in OBJECT_MARKERS }) return null
        return name
    }

    private fun poolOf(name: String, known: List<Known>, created: Map<String, String>): String =
        known.firstOrNull { it.name == name }?.pool ?: created[name].orEmpty()

    private fun genderOfPool(pool: String, subject: String, name: String, context: String): Boolean? = when {
        pool.startsWith("男") -> true
        pool.startsWith("女") -> false
        else -> maleOf(subject, name, context)
    }

    /** true = 男，false = 女，null = 看不出。 */
    private fun maleOf(subject: String, name: String, context: String): Boolean? {
        val title = subject + name
        val female = femaleTitle.containsMatchIn(title)
        val male = maleTitle.containsMatchIn(title)
        if (female && !male) return false
        if (male && !female) return true
        val she = context.count { it == '她' }
        val he = context.count { it == '他' }
        if (she != he) return she > he
        if (name.last() in femaleNameChars) return false
        return null
    }

    private fun ageOf(subject: String, name: String): Age {
        val title = subject + name
        return when {
            youthTitle.containsMatchIn(title) -> Age.YOUTH
            elderTitle.containsMatchIn(title) -> Age.ELDER
            middleTitle.containsMatchIn(title) -> Age.MIDDLE
            else -> Age.ADULT
        }
    }

    /**
     * 按性别与年龄挑声音池名；同性别里没有对应年龄就取最接近的，性别都看不出来时按男声处理
     * （与旁白的默认男声一致，错了也只是这个人换个性别的声音，不会读不出来）。
     */
    private fun pickPool(pools: List<String>, subject: String, name: String, context: String): String {
        if (pools.isEmpty()) return ""
        val male = maleOf(subject, name, context) ?: true
        val sameGender = pools.filter { if (male) it.startsWith("男") else it.startsWith("女") }
        if (sameGender.isEmpty()) return ""
        val order = when (ageOf(subject, name)) {
            Age.YOUTH -> listOf("少", "童", "青年")
            Age.ELDER -> listOf("老年", "中年")
            Age.MIDDLE -> listOf("中年", "青年", "老年")
            Age.ADULT -> listOf("青年", "中年")
        }
        for (keyword in order) {
            sameGender.firstOrNull { it.contains(keyword) }?.let { return it }
        }
        return sameGender.first()
    }
}
