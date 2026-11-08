package net.alummahnow.feature.watch

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.design.components.ErrorKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        AunLog.enabled = false
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `loads video and related`() = runTest(dispatcher) {
        WatchViewModel(FakeCatalog(), "v1").state.test {
            assertEquals(WatchUiState.Loading, awaitItem())
            val content = awaitItem() as WatchUiState.Content
            assertEquals("v1", content.video.id)
            assertEquals(listOf("v2", "v3"), content.related.map { it.id })
        }
    }

    @Test fun `related failure does not break the page`() = runTest(dispatcher) {
        WatchViewModel(FakeCatalog(relatedResult = { networkError() }), "v1").state.test {
            skipItems(1)
            assertEquals(emptyList<Any>(), (awaitItem() as WatchUiState.Content).related)
        }
    }

    @Test fun `video still processing is shown as not available`() = runTest(dispatcher) {
        WatchViewModel(FakeCatalog(videoResult = { sampleDetail(ready = false) }), "v1").state.test {
            skipItems(1)
            assertEquals(WatchUiState.Error(ErrorKind.NOT_FOUND), awaitItem())
        }
    }

    @Test fun `errors map to localised kinds`() = runTest(dispatcher) {
        WatchViewModel(FakeCatalog(videoResult = { throw CoreError.NotFound(it) }), "x").state.test {
            skipItems(1)
            assertEquals(WatchUiState.Error(ErrorKind.NOT_FOUND), awaitItem())
        }
        WatchViewModel(FakeCatalog(videoResult = { throw CoreError.Http(500) }), "x").state.test {
            skipItems(1)
            assertEquals(WatchUiState.Error(ErrorKind.GENERIC), awaitItem())
        }
    }
}
