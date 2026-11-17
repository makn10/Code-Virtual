package com.example

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.VCameraAppTheme
import com.vcameraspace.core.common.coroutines.DispatcherProvider
import com.vcameraspace.core.common.model.AppResult
import com.vcameraspace.core.common.model.Space
import com.vcameraspace.core.database.VCameraDatabase
import com.vcameraspace.core.database.repository.RoomSpaceRepository
import com.vcameraspace.feature.spaces.SpaceManagerContent
import com.vcameraspace.feature.spaces.SpaceManagerUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private class DirectTestDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomSpaceRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: VCameraDatabase
    private lateinit var prefsRepo: FakeUserPreferencesRepository
    private lateinit var repository: RoomSpaceRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = VCameraDatabase.createInMemory(context)
        prefsRepo = FakeUserPreferencesRepository()
        repository = RoomSpaceRepository(
            context = context,
            spaceDao = database.spaceDao(),
            virtualAppDao = database.virtualAppDao(),
            userPreferencesRepository = prefsRepo,
            dispatchers = DirectTestDispatcherProvider()
        )
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, "spaces").deleteRecursively()
    }

    @Test
    fun `ensureDefaultSpacesSeeded populates Space 1 and Space 2 with directories`() = runTest {
        repository.ensureDefaultSpacesSeeded()
        val spaces = repository.observeSpaces().first()
        assertEquals(2, spaces.size)
        assertEquals("Space 1", spaces[0].name)
        assertTrue(spaces[0].isDefault)
        assertEquals("Space 2", spaces[1].name)
        assertTrue(File(context.filesDir, "spaces/space_1/config").exists())
        assertTrue(File(context.filesDir, "spaces/space_2/camera").exists())
    }

    @Test
    fun `create rename reorder and delete space updates room and directories`() = runTest {
        repository.ensureDefaultSpacesSeeded()

        val created = repository.createSpace("Studio", "camera", setAsDefault = true)
        assertTrue(created is AppResult.Success)
        val newSpace = (created as AppResult.Success).data
        assertEquals("Studio", newSpace.name)
        assertEquals(newSpace.id, prefsRepo.userPreferences.first().defaultSpaceId)

        assertTrue(repository.renameSpace(newSpace.id, "Studio Pro") is AppResult.Success)
        assertTrue(repository.moveSpace(newSpace.id, -1) is AppResult.Success)

        val updatedList = repository.observeSpaces().first()
        assertEquals(3, updatedList.size)
        assertEquals("Studio Pro", updatedList[1].name)

        assertTrue(repository.deleteSpace(newSpace.id) is AppResult.Success)
        val afterDelete = repository.observeSpaces().first()
        assertEquals(2, afterDelete.size)
        assertFalse(File(context.filesDir, "spaces/${newSpace.id}").exists())
    }

    @Test
    fun `cannot delete last remaining workspace`() = runTest {
        repository.ensureDefaultSpacesSeeded()
        assertTrue(repository.deleteSpace("space_2") is AppResult.Success)
        val result = repository.deleteSpace("space_1")
        assertTrue(result is AppResult.Failure)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpaceManagerUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `space manager displays spaces and delete warning dialog`() {
        val spaces = listOf(
            Space(id = "space_1", name = "Space 1", isDefault = true, sortOrder = 0, appCount = 2),
            Space(id = "space_2", name = "Work Cam", isDefault = false, sortOrder = 1, appCount = 0)
        )
        composeRule.setContent {
            VCameraAppTheme {
                SpaceManagerContent(
                    uiState = SpaceManagerUiState(
                        spaces = spaces,
                        activeSpaceId = "space_1",
                        isLoading = false
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBackClick = {},
                    onActivateSpace = {},
                    onCreateSpace = { _, _, _ -> },
                    onRenameSpace = { _, _ -> },
                    onUpdateSpaceIcon = { _, _ -> },
                    onSetDefaultSpace = {},
                    onMoveSpaceUp = {},
                    onMoveSpaceDown = {},
                    onUpdateSpaceSettings = { _, _, _ -> },
                    onDeleteSpace = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Space 1").assertIsDisplayed()
        composeRule.onNodeWithTag("space_manager_list")
            .performScrollToNode(hasTestTag("space_card_space_2"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Work Cam").assertIsDisplayed()
        composeRule.onNodeWithTag("space_delete_space_2").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("confirmation_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Delete \"Work Cam\"?").assertIsDisplayed()
    }
}
