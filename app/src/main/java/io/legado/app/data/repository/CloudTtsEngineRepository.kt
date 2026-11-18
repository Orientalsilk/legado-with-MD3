package io.legado.app.data.repository

import io.legado.app.data.dao.CloudTtsEngineDao
import io.legado.app.data.entities.CloudTtsEngineEntity
import io.legado.app.data.security.CloudTtsCredentialCipher
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.CloudTtsProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class CloudTtsEngineRepository(
    private val dao: CloudTtsEngineDao,
    private val credentialCipher: CloudTtsCredentialCipher,
) : CloudTtsEngineGateway {
    /*
     * MiMo voice-clone settings may carry a multi-megabyte reference-audio Data URL in
     * optionsJson. Read each engine from Room once, then reuse the encrypted entity while
     * successive speech segments are synthesized. Keep this deliberately small so several
     * unused clone clips cannot accumulate in the app heap.
     */
    private val engineCacheMutex = Mutex()
    private val engineCache = LinkedHashMap<String, CloudTtsEngineEntity>(
        ENGINE_CACHE_CAPACITY,
        0.75f,
        true,
    )

    override fun observeAll(): Flow<List<CloudTtsEngine>> =
        dao.observeAll()
            .onEach(::encryptLegacyCredentials)
            .map { engines -> engines.map(::toDomain) }

    override suspend fun getAll(): List<CloudTtsEngine> = withContext(Dispatchers.IO) {
        dao.getAll().also { encryptLegacyCredentials(it) }.map(::toDomain)
    }

    override suspend fun get(id: String): CloudTtsEngine? = withContext(Dispatchers.IO) {
        engineCacheMutex.withLock {
            val entity = engineCache[id] ?: dao.get(id)?.let { stored ->
                val encrypted = stored.encryptCredentials(credentialCipher)
                if (encrypted != stored) dao.upsert(encrypted)
                cacheEntity(encrypted)
                encrypted
            }
            entity?.toDomain(credentialCipher)
        }
    }

    override suspend fun upsert(engine: CloudTtsEngine) = withContext(Dispatchers.IO) {
        engineCacheMutex.withLock {
            val entity = engine.toEntity(credentialCipher)
            dao.upsert(entity)
            cacheEntity(entity)
        }
    }

    override suspend fun delete(engine: CloudTtsEngine) {
        withContext(Dispatchers.IO) {
            engineCacheMutex.withLock {
                dao.delete(engine.toEntity(credentialCipher))
                engineCache.remove(engine.id)
                Unit
            }
        }
    }

    private fun cacheEntity(entity: CloudTtsEngineEntity) {
        engineCache[entity.id] = entity
        while (engineCache.size > ENGINE_CACHE_CAPACITY) {
            val eldest = engineCache.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
    }

    private fun toDomain(entity: CloudTtsEngineEntity) = entity.toDomain(credentialCipher)

    private suspend fun encryptLegacyCredentials(engines: List<CloudTtsEngineEntity>) {
        engines.forEach { engine ->
            val encrypted = engine.encryptCredentials(credentialCipher)
            if (encrypted != engine) dao.upsert(encrypted)
        }
    }

    private companion object {
        const val ENGINE_CACHE_CAPACITY = 2
    }
}

private fun CloudTtsEngineEntity.encryptCredentials(cipher: CloudTtsCredentialCipher) = copy(
    apiKey = cipher.encrypt(apiKey),
    secretKey = cipher.encrypt(secretKey),
)

private fun CloudTtsEngineEntity.toDomain(cipher: CloudTtsCredentialCipher) = CloudTtsEngine(
    id = id,
    name = name,
    provider = CloudTtsProviderType.fromStorage(provider),
    baseUrl = baseUrl,
    apiKey = cipher.decrypt(apiKey),
    secretKey = cipher.decrypt(secretKey),
    region = region,
    appId = appId,
    model = model,
    optionsJson = optionsJson,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun CloudTtsEngine.toEntity(cipher: CloudTtsCredentialCipher) = CloudTtsEngineEntity(
    id = id,
    name = name,
    provider = provider.storageValue,
    baseUrl = baseUrl,
    apiKey = cipher.encrypt(apiKey),
    secretKey = cipher.encrypt(secretKey),
    region = region,
    appId = appId,
    model = model,
    optionsJson = optionsJson,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
