package io.legado.app.data.repository

import io.legado.app.data.dao.CloudTtsEngineDao
import io.legado.app.data.entities.CloudTtsEngineEntity
import io.legado.app.data.security.CloudTtsCredentialCipher
import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.CloudTtsProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CloudTtsEngineRepositoryTest {

    @Test
    fun `get reuses large engine options until update or delete`() = runBlocking {
        val optionsJson = """{"referenceAudio":"data:audio/mpeg;base64,${"A".repeat(1_000_000)}"}"""
        val dao = FakeCloudTtsEngineDao().apply {
            stored = entity(optionsJson = optionsJson)
        }
        val repository = CloudTtsEngineRepository(dao, CloudTtsCredentialCipher())

        val first = requireNotNull(repository.get("engine-1"))
        val second = requireNotNull(repository.get("engine-1"))

        assertEquals(1, dao.getCalls)
        assertSame(first.optionsJson, second.optionsJson)
        assertEquals(optionsJson, second.optionsJson)

        val updated = first.copy(
            name = "Updated engine",
            optionsJson = """{"referenceAudio":"new"}""",
            updatedAt = first.updatedAt + 1,
        )
        repository.upsert(updated)

        assertEquals("Updated engine", repository.get("engine-1")?.name)
        assertEquals(updated.optionsJson, repository.get("engine-1")?.optionsJson)
        assertEquals(1, dao.getCalls)

        repository.delete(updated)

        assertNull(repository.get("engine-1"))
        assertEquals(2, dao.getCalls)
    }

    @Test
    fun `engine cache is bounded and evicts least recently used entry`() = runBlocking {
        val dao = FakeCloudTtsEngineDao().apply {
            stored = entity(id = "engine-a")
        }
        val repository = CloudTtsEngineRepository(dao, CloudTtsCredentialCipher())

        repository.get("engine-a")
        dao.stored = entity(id = "engine-b")
        repository.get("engine-b")
        repository.get("engine-a")
        dao.stored = entity(id = "engine-c")
        repository.get("engine-c")

        // B was least recently used and should be loaded again after C is inserted.
        dao.stored = entity(id = "engine-b")
        repository.get("engine-b")

        assertEquals(4, dao.getCalls)
    }

    private class FakeCloudTtsEngineDao : CloudTtsEngineDao {
        var stored: CloudTtsEngineEntity? = null
        var getCalls = 0

        override fun observeAll(): Flow<List<CloudTtsEngineEntity>> =
            flowOf(listOfNotNull(stored))

        override suspend fun getAll(): List<CloudTtsEngineEntity> =
            listOfNotNull(stored)

        override suspend fun get(id: String): CloudTtsEngineEntity? {
            getCalls++
            return stored?.takeIf { it.id == id }
        }

        override fun getUpdatedAt(id: String): Long? =
            stored?.takeIf { it.id == id }?.updatedAt

        override suspend fun upsert(engine: CloudTtsEngineEntity) {
            stored = engine
        }

        override suspend fun delete(engine: CloudTtsEngineEntity) {
            if (stored?.id == engine.id) stored = null
        }
    }

    private fun entity(
        id: String = "engine-1",
        optionsJson: String = "{}",
    ) = CloudTtsEngineEntity(
        id = id,
        name = id,
        provider = CloudTtsProviderType.Mimo.storageValue,
        model = "mimo-v2.5-tts-voiceclone",
        optionsJson = optionsJson,
        updatedAt = 1L,
    )
}
