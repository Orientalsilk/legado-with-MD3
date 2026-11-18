package io.legado.app.domain.usecase

import com.google.gson.JsonParser
import io.legado.app.data.entities.AiArtifact
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.AiArtifactGateway
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.AiToolContext
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlin.uuid.Uuid

class IdentifyBookCharactersUseCase(
    private val aiProfileGateway: AiProfileGateway,
    private val aiToolAwareGenerationUseCase: AiToolAwareGenerationUseCase,
    private val aiArtifactGateway: AiArtifactGateway,
    private val aiTaskManager: AiTaskManager,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
) {
    data class Candidate(
        val name: String,
        val aliases: List<String>,
        val personality: String,
        val summary: String,
        val evidence: String,
        val confidence: Float,
    )

    sealed interface Progress {
        data class Reasoning(val text: String) : Progress
        data class ToolCall(val name: String) : Progress
        data class Done(val candidates: List<Candidate>) : Progress
    }

    suspend fun identify(bookUrl: String): List<Candidate> {
        var candidates = emptyList<Candidate>()
        identifyStream(bookUrl).collect { progress ->
            if (progress is Progress.Done) candidates = progress.candidates
        }
        return candidates
    }

    suspend fun loadLatest(bookUrl: String): List<Candidate> {
        val artifact =
            aiArtifactGateway.observeBookArtifacts(bookUrl, AiTaskType.IDENTIFY_CHARACTERS)
                .first()
                .firstOrNull { it.status == AiArtifact.STATUS_SUCCESS && !it.output.isNullOrBlank() }
                ?: return emptyList()
        return decodeCandidates(artifact.output)
    }

    fun observeTask(bookUrl: String) = aiTaskManager.observeBookTask(
        bookUrl = bookUrl,
        taskType = AiTaskType.IDENTIFY_CHARACTERS,
    )

    suspend fun start(
        bookUrl: String,
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.AUTO,
    ): String {
        val identifyPreset = aiProfileGateway.getTaskPreset(AiTaskType.IDENTIFY_CHARACTERS)
        val preset = identifyPreset
            ?: aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
            ?: error("No AI model configured for character identification")
        val prompt = enforceCharacterDiscoveryBoundary(identifyPreset?.promptTemplate)
        val now = System.currentTimeMillis()
        val contentHash = MD5Utils.md5Encode(bookUrl)
        val promptHash =
            MD5Utils.md5Encode(prompt + AiToolAwareGenerationUseCase.CACHE_PROMPT_VERSION)
        val artifact = AiArtifact(
            id = "${contentHash}_${AiTaskType.IDENTIFY_CHARACTERS}_${promptHash}_${preset.model.id}",
            taskType = AiTaskType.IDENTIFY_CHARACTERS,
            bookUrl = bookUrl,
            contentHash = contentHash,
            promptHash = promptHash,
            modelProfileId = preset.model.id,
            createdAt = now,
            updatedAt = now,
        )
        return aiTaskManager.submit(artifact) {
            var candidates = emptyList<Candidate>()
            identifyStream(bookUrl, reasoningLevel).collect { progress ->
                when (progress) {
                    is Progress.Reasoning -> appendReasoning(progress.text)
                    is Progress.ToolCall -> reportToolCall(progress.name)
                    is Progress.Done -> candidates = progress.candidates
                }
            }
            GSON.toJson(candidates)
        }
    }

    fun decodeCandidates(output: String?): List<Candidate> =
        output?.let { GSON.fromJsonArray<Candidate>(it).getOrNull() }.orEmpty()

    fun identifyStream(
        bookUrl: String,
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.AUTO,
    ): Flow<Progress> = flow {
        val identifyPreset = aiProfileGateway.getTaskPreset(AiTaskType.IDENTIFY_CHARACTERS)
        val preset = identifyPreset
            ?: aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
            ?: error("No AI model configured for character identification")
        val prompt = enforceCharacterDiscoveryBoundary(identifyPreset?.promptTemplate)
        val response = StringBuilder()
        aiToolAwareGenerationUseCase.generateStream(
            AiGenerateRequest(
                model = preset.model,
                messages = listOf(
                    AiMessage(AiMessageRole.SYSTEM, prompt),
                    AiMessage(
                        AiMessageRole.USER,
                        "识别本书已下载章节中的人物。请先用工具读取已缓存章节，再只返回要求的 JSON。"
                    ),
                ),
                params = preset.params.copy(
                    temperature = 0f,
                    reasoningLevel = reasoningLevel.takeUnless { it == AiReasoningLevel.AUTO }
                        ?: preset.params.reasoningLevel,
                ),
                toolContext = AiToolContext(bookUrl = bookUrl),
            )
        ).collect { event ->
            when (event) {
                is io.legado.app.domain.gateway.AiStreamEvent.Content -> response.append(event.text)
                is io.legado.app.domain.gateway.AiStreamEvent.Reasoning -> emit(
                    Progress.Reasoning(
                        event.text
                    )
                )

                is io.legado.app.domain.gateway.AiStreamEvent.ToolCallDelta -> {
                    event.name?.takeIf(String::isNotBlank)?.let { emit(Progress.ToolCall(it)) }
                }
            }
        }
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')
        require(jsonStart >= 0 && jsonEnd > jsonStart) {
            "Character identification did not return a JSON object"
        }
        val root = JsonParser.parseString(response.substring(jsonStart, jsonEnd + 1)).asJsonObject
        val candidates = root.getAsJsonArray("characters").map { element ->
            val item = element.asJsonObject
            Candidate(
                name = item.get("name")?.asString?.trim().orEmpty(),
                aliases = item.getAsJsonArray("aliases")?.map { it.asString.trim() }.orEmpty(),
                personality = item.get("personality")?.asString.orEmpty(),
                summary = item.get("summary")?.asString.orEmpty(),
                evidence = item.get("evidence")?.asString.orEmpty(),
                confidence = item.get("confidence")?.asFloat?.coerceIn(0f, 1f) ?: 0f,
            )
        }.filter { it.name.isNotBlank() && it.confidence >= MIN_CONFIDENCE }
            .distinctBy { it.name }
        val now = System.currentTimeMillis()
        val contentHash = MD5Utils.md5Encode(bookUrl)
        val promptHash =
            MD5Utils.md5Encode(prompt + AiToolAwareGenerationUseCase.CACHE_PROMPT_VERSION)
        aiArtifactGateway.upsertArtifact(
            AiArtifact(
                id = "${contentHash}_${AiTaskType.IDENTIFY_CHARACTERS}_${promptHash}_${preset.model.id}",
                taskType = AiTaskType.IDENTIFY_CHARACTERS,
                bookUrl = bookUrl,
                contentHash = contentHash,
                promptHash = promptHash,
                modelProfileId = preset.model.id,
                status = AiArtifact.STATUS_SUCCESS,
                output = GSON.toJson(candidates),
                createdAt = now,
                updatedAt = now,
            )
        )
        emit(Progress.Done(candidates))
    }

    suspend fun save(bookUrl: String, candidates: List<Candidate>) {
        // Load once: matching every candidate through the DAO would both be slow and
        // make alias matching depend on a SQL LIKE substring hit.
        val profiles = bookKnowledgeGateway.getCharacterProfiles(bookUrl, 500).toMutableList()
        for (candidate in candidates) {
            val candidateName = candidate.name.trim()
            if (candidateName.isBlank()) continue
            val candidateAliases = candidate.aliases
                .flatMap { it.split('、', '，', ',', '/', '；', ';') }
                .map(String::trim)
                .filter(String::isNotBlank)
            // Resolve from the canonical name only. Proposed aliases are descriptive data,
            // not proof that two characters are the same person.
            val existing = findUniqueCharacterProfile(candidateName, profiles)
            // Null means either no match (create) or an ambiguous alias (skip safely).
            val ambiguous = profiles.count { profile ->
                profile.status != BookCharacterProfile.STATUS_DELETED &&
                    normalizeCharacterIdentityKey(profile.name) != normalizeCharacterIdentityKey(candidateName) &&
                    GSON.fromJsonArray<String>(profile.aliasesJson).getOrNull().orEmpty()
                        .any { normalizeCharacterIdentityKey(it) == normalizeCharacterIdentityKey(candidateName) }
            } > 1 && profiles.none {
                it.status != BookCharacterProfile.STATUS_DELETED &&
                    normalizeCharacterIdentityKey(it.name) == normalizeCharacterIdentityKey(candidateName)
            }
            if (ambiguous) continue
            // A user-disabled character must not be silently resurrected by another AI scan.
            if (existing?.status == BookCharacterProfile.STATUS_DISABLED) continue

            val canonicalName = existing?.name ?: candidateName
            // Do not steal another character's canonical name or alias just because the
            // model listed it as a nickname. Existing user-entered aliases are retained.
            val claimedByOthers = profiles.asSequence()
                .filter { it.id != existing?.id && it.status != BookCharacterProfile.STATUS_DELETED }
                .flatMap { profile ->
                    sequenceOf(profile.name) +
                        GSON.fromJsonArray<String>(profile.aliasesJson).getOrNull().orEmpty().asSequence()
                }
                .map(::normalizeCharacterIdentityKey)
                .filter(String::isNotBlank)
                .toSet()
            val mergedAliases = mergeCharacterAliases(
                canonicalName = canonicalName,
                existingAliases = GSON.fromJsonArray<String>(existing?.aliasesJson.orEmpty())
                    .getOrNull().orEmpty(),
                candidateAliases = candidateAliases,
                candidateName = candidateName,
                claimedByOthers = claimedByOthers,
            )

            val next = BookCharacterProfile(
                id = existing?.id ?: Uuid.random().toString(),
                bookUrl = bookUrl,
                name = canonicalName,
                aliasesJson = GSON.toJson(mergedAliases),
                avatarUri = existing?.avatarUri,
                tagsJson = existing?.tagsJson ?: "[]",
                role = existing?.role.orEmpty(),
                // AI discovery never assigns a voice or overwrites an existing mapping.
                voiceGender = existing?.voiceGender ?: BookCharacterProfile.VOICE_GENDER_UNKNOWN,
                voiceAgeBand = existing?.voiceAgeBand ?: BookCharacterProfile.VOICE_AGE_UNKNOWN,
                // Without the ownership ledger, never overwrite text a user may have edited.
                personality = existing?.personality?.takeIf(String::isNotBlank) ?: candidate.personality,
                summary = existing?.summary?.takeIf(String::isNotBlank) ?: candidate.summary,
                status = existing?.status ?: BookCharacterProfile.STATUS_ACTIVE,
                source = existing?.source ?: BookCharacterProfile.SOURCE_AI,
                confidence = maxOf(existing?.confidence ?: 0f, candidate.confidence),
                schemaVersion = existing?.schemaVersion ?: 1,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            bookKnowledgeGateway.upsertCharacterProfile(next)
            if (existing == null) profiles += next
            else profiles[profiles.indexOf(existing)] = next
        }
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.65f

        /**
         * 人物识别只提取小说事实，不做声音性别推断、音色挑选或声音池分配。
         * MultiTTS 的音色映射由独立的声音分配流程负责。
         */
        fun defaultPrompt(): String =
            "You identify stable fictional characters from downloaded local chapters. Use read-only tools to list and read cached chapters and inspect existing characters. Return JSON only: {\"characters\":[{\"name\":string,\"aliases\":[string],\"personality\":string,\"summary\":string,\"evidence\":string,\"confidence\":number}]}. Describe only facts supported by the text. Do not infer voice gender, voice age, voice pool, voice identity, or assign any TTS voice. Do not include pronouns, generic titles, or one-off passers-by. Do not create duplicates of existing names or aliases."
    }
}


/**
 * Match by canonical name first, then by the candidate's own name appearing as an existing alias.
 * Proposed aliases never participate in identity resolution. Ambiguous aliases return null.
 */
internal fun findUniqueCharacterProfile(
    candidateName: String,
    profiles: List<BookCharacterProfile>,
): BookCharacterProfile? {
    val key = normalizeCharacterIdentityKey(candidateName)
    if (key.isBlank()) return null
    val active = profiles.filter { it.status != BookCharacterProfile.STATUS_DELETED }
    val exact = active.filter { normalizeCharacterIdentityKey(it.name) == key }
    if (exact.isNotEmpty()) return exact.singleOrNull()
    val aliases = active.filter { profile ->
        GSON.fromJsonArray<String>(profile.aliasesJson).getOrNull().orEmpty()
            .any { normalizeCharacterIdentityKey(it) == key }
    }
    return aliases.singleOrNull()
}

/** Normalize harmless typography differences without collapsing genuinely different names. */
internal fun normalizeCharacterIdentityKey(value: String): String =
    value.trim().lowercase().filterNot {
        it.isWhitespace() || it in "·•・_—-（）()【】[]「」『』"
    }

/**
 * Keep stored aliases, add only unclaimed AI suggestions, and never repeat the canonical name.
 * [claimedByOthers] contains normalized names and aliases belonging to other profiles.
 */
internal fun mergeCharacterAliases(
    canonicalName: String,
    existingAliases: List<String>,
    candidateAliases: List<String>,
    candidateName: String,
    claimedByOthers: Set<String>,
): List<String> {
    val proposed = candidateAliases + listOf(candidateName).filter { it != canonicalName }
    return (existingAliases + proposed.filter {
        normalizeCharacterIdentityKey(it) !in claimedByOthers
    })
        .map(String::trim)
        .filter {
            it.isNotBlank() &&
                normalizeCharacterIdentityKey(it) != normalizeCharacterIdentityKey(canonicalName)
        }
        .distinctBy(::normalizeCharacterIdentityKey)
}

/** Applies even to persisted custom prompts created before the character/voice split. */
internal fun enforceCharacterDiscoveryBoundary(customPrompt: String?): String {
    val base = customPrompt?.takeIf(String::isNotBlank)
        ?: IdentifyBookCharactersUseCase.defaultPrompt()
    return base + "\n\nNON-NEGOTIABLE CHARACTER-DISCOVERY BOUNDARY: " +
        "Extract only character identity and novel-supported facts. Do not infer voice gender " +
        "or voice age, choose a voice pool, select a speaker, assign a TTS voice, or write " +
        "voiceGender/voiceAgeBand/pool fields. Voice assignment is a separate workflow."
}
