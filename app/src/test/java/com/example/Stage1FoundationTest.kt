package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.common.model.SpaceApp
import com.vcameraspace.core.common.model.ThemePreference
import com.vcameraspace.core.database.repository.SpaceRepository
import com.vcameraspace.core.datastore.UserPreferencesRepository
import com.vcameraspace.core.datastore.UserPreferencesSnapshot
import com.vcameraspace.feature.home.HomeViewModel
import com.vcameraspace.feature.onboarding.OnboardingViewModel
import com.vcameraspace.feature.splash.SplashNavigationTarget
import com.vcameraspace.feature.splash.SplashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal class FakeUserPreferencesRepository(
    initial: UserPreferencesSnapshot = UserPreferencesSnapshot()
) : UserPreferencesRepository {
    private val state = MutableStateFlow(initial)
    override val userPreferences: Flow<UserPreferencesSnapshot> = state.asStateFlow()

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        state.update { it.copy(onboardingCompleted = completed) }
    }

    override suspend fun setDefaultSpaceId(spaceId: String) {
        state.update { it.copy(defaultSpaceId = spaceId) }
    }

    override suspend fun setActiveSpaceId(spaceId: String) {
        state.update { it.copy(activeSpaceId = spaceId) }
    }

    override suspend fun setThemePreference(preference: ThemePreference) {
        state.update { it.copy(themePreference = preference) }
    }

    override suspend fun setMockLocationEnabled(enabled: Boolean) {
        state.update { it.copy(mockLocationEnabled = enabled) }
    }

    override suspend fun setNormalModeEnabled(enabled: Boolean) {
        state.update { it.copy(normalModeEnabled = enabled) }
    }
}

internal class FakeSpaceRepository : SpaceRepository {
    private val spaces = MutableStateFlow(
        listOf(
            Space(id = "space_1", name = "Space 1", isDefault = true, sortOrder = 0),
            Space(id = "space_2", name = "Space 2", isDefault = false, sortOrder = 1)
        )
    )
    private val apps = MutableStateFlow<Map<String, List<SpaceApp>>>(emptyMap())

    override fun observeSpaces(): Flow<List<Space>> = spaces.asStateFlow()
    override fun observeAppsBySpace(): Flow<Map<String, List<SpaceApp>>> = apps.asStateFlow()
    override suspend fun ensureDefaultSpacesSeeded() = Unit

    override suspend fun createSpace(
        name: String,
        iconKey: String,
        setAsDefault: Boolean
    ): AppResult<Space> {
        val created = Space(
            id = "space_${spaces.value.size + 1}",
            name = name,
            iconKey = iconKey,
            isDefault = setAsDefault,
            sortOrder = spaces.value.size
        )
        spaces.update { it + created }
        return AppResult.Success(created)
    }

    override suspend fun renameSpace(spaceId: String, newName: String): AppResult<Unit> {
        spaces.update { list -> list.map { if (it.id == spaceId) it.copy(name = newName) else it } }
        return AppResult.Success(Unit)
    }

    override suspend fun updateSpaceIcon(spaceId: String, iconKey: String): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun setDefaultSpace(spaceId: String): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun reorderSpaces(orderedSpaceIds: List<String>): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun moveSpace(spaceId: String, delta: Int): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun updateSpaceSettings(
        spaceId: String,
        isolatedCameraByDefault: Boolean,
        requireLaunchConfirm: Boolean
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun deleteSpace(spaceId: String): AppResult<Unit> {
        spaces.update { list -> list.filterNot { it.id == spaceId } }
        return AppResult.Success(Unit)
    }

    override suspend fun removeAppFromSpace(spaceId: String, packageName: String): AppResult<Unit> =
        AppResult.Success(Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
class Stage1ViewModelTests {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `splash navigates to onboarding when onboarding not completed`() = runTest {
        val repo = FakeUserPreferencesRepository(UserPreferencesSnapshot(onboardingCompleted = false))
        val viewModel = SplashViewModel(repo)
        advanceUntilIdle()
        assertEquals(SplashNavigationTarget.Onboarding, viewModel.target.value)
    }

    @Test
    fun `splash navigates to home when onboarding already completed`() = runTest {
        val repo = FakeUserPreferencesRepository(UserPreferencesSnapshot(onboardingCompleted = true))
        val viewModel = SplashViewModel(repo)
        advanceUntilIdle()
        assertEquals(SplashNavigationTarget.Home, viewModel.target.value)
    }

    @Test
    fun `onboarding completion persists state`() = runTest {
        val repo = FakeUserPreferencesRepository(UserPreferencesSnapshot(onboardingCompleted = false))
        val viewModel = OnboardingViewModel(repo)
        viewModel.completeOnboarding()
        advanceUntilIdle()
        assertTrue(viewModel.completed.value)
    }

    @Test
    fun `home viewmodel updates active space on page selection`() = runTest {
        val prefs = FakeUserPreferencesRepository()
        val viewModel = HomeViewModel(FakeSpaceRepository(), prefs)
        viewModel.onPageSelected(1)
        advanceUntilIdle()
        assertEquals("space_2", viewModel.uiState.value.spaces[1].id)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Stage1ComposeUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `splash screen renders branding and progress indicator`() {
        composeRule.onNodeWithTag("splash_screen").assertIsDisplayed()
        composeRule.onNodeWithText("VCamera Space").assertIsDisplayed()
        composeRule.onNodeWithText("Your Space. Your Control.").assertIsDisplayed()
        composeRule.onNodeWithTag("splash_progress_indicator").assertIsDisplayed()
    }
}
