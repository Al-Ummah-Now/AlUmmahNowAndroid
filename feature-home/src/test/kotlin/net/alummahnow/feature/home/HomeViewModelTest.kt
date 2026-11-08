package net.alummahnow.feature.home

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.design.components.ErrorKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        AunLog.enabled = false
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `loads rails`() = runTest(dispatcher) {
        val vm = HomeViewModel(FakeCatalog())
        vm.state.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            val content = awaitItem() as HomeUiState.Content
            assertEquals(listOf(RailKind.TRENDING, RailKind.RECENT), content.rails.map { it.kind })
        }
    }

    @Test fun `first load failure shows a network error, retry recovers`() = runTest(dispatcher) {
        val catalog = FakeCatalog(railsResult = { networkError() })
        val vm = HomeViewModel(catalog)
        vm.state.test {
            assertEquals(HomeUiState.Loading, awaitItem())
            assertEquals(HomeUiState.Error(ErrorKind.NETWORK), awaitItem())
            catalog.railsResult = { emptyList() }
            vm.load()
            assertEquals(HomeUiState.Loading, awaitItem())
            assertTrue(awaitItem() is HomeUiState.Content)
        }
    }

    @Test fun `failed refresh keeps the rails already on screen`() = runTest(dispatcher) {
        val catalog = FakeCatalog()
        val vm = HomeViewModel(catalog)
        vm.state.test {
            skipItems(1)
            val loaded = awaitItem() as HomeUiState.Content
            catalog.railsResult = { networkError() }
            vm.load()
            assertEquals(loaded.copy(isRefreshing = true), awaitItem())
            assertEquals(loaded.copy(isRefreshing = false), awaitItem())
        }
        assertEquals(2, catalog.homeCalls)
    }
}
