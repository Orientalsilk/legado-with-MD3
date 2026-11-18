package io.legado.app.ui.book.readaloud.cast

import android.app.Application
import android.content.Context
import io.legado.app.help.readaloud.cast.CastSyntaxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MultiRoleRecognitionViewModelTest {

    private lateinit var application: Application
    private val dispatcher = StandardTestDispatcher()
    private val defaults = CastSyntaxStore.Config()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        stopKoin()
        application = RuntimeEnvironment.getApplication()
        application.injectAsAppCtx()
        startKoin {
            modules(module {
                single<Context> { application }
            })
        }
        CastSyntaxStore.save(defaults)
    }

    @After
    fun tearDown() {
        CastSyntaxStore.save(defaults)
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun `loads persisted symbols before first state and retains rapid consecutive edits`() {
        val initial = CastSyntaxStore.Config(
            markStart = "##",
            markEnd = "\$\$",
            poolStart = "<<",
            poolEnd = ">>",
        )
        assertEquals(CastSyntaxStore.ValidationError.NONE, CastSyntaxStore.save(initial))

        val viewModel = MultiRoleRecognitionViewModel(application)

        assertEquals(initial, viewModel.uiState.value.config)

        viewModel.onIntent(MultiRoleRecognitionIntent.SetSymbol(CastSymbolField.MARK_START, "{{"))
        viewModel.onIntent(MultiRoleRecognitionIntent.SetSymbol(CastSymbolField.MARK_END, "}}"))

        val expected = initial.copy(markStart = "{{", markEnd = "}}")
        assertEquals(expected, viewModel.uiState.value.config)
        assertEquals(expected, CastSyntaxStore.config())
    }
}
