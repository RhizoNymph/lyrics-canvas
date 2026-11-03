package dev.nymph.lyrics.app.tags

import app.cash.turbine.test
import dev.nymph.lyrics.app.MainDispatcherExtension
import dev.nymph.lyrics.app.fakes.CANVAS_ID
import dev.nymph.lyrics.app.fakes.snippet
import dev.nymph.lyrics.logic.tags.TagCheck
import dev.nymph.lyrics.logic.tags.TagError
import dev.nymph.lyrics.logic.tags.TagExprErrorCode
import dev.nymph.lyrics.logic.tags.TagFilter
import dev.nymph.lyrics.logic.tags.format
import dev.nymph.lyrics.model.TagErrorCode
import dev.nymph.lyrics.model.TagPresetUpdateRequest
import dev.nymph.lyrics.model.TagUpdateRequest
import dev.nymph.lyrics.model.error.ApiError
import dev.nymph.lyrics.model.error.ApiResult
import dev.nymph.lyrics.model.error.ExpressionErrorBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class TagsViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val love = userTag("love")
    private val night = userTag("night", "#00ff00")
    private val a = snippet("a", tags = listOf(love.toTag()))
    private val b = snippet("b")

    private val tagRepo = FakeTagRepository(listOf(love, night))
    private val presetRepo = FakeTagPresetRepository(listOf(preset("Dark", "night & !love")))
    private val store = FakeTagFilterStore()
    private val port = FakeTagCanvasPort(listOf(a, b))

    private fun TestScope.vm(): TagsViewModel {
        val vm = TagsViewModel(CANVAS_ID, tagRepo, presetRepo, store, port)
        runCurrent()
        return vm
    }

    private val TagsViewModel.s get() = state.value

    // ---- Loading and the filter bar ----

    @Test
    fun `loads tags and presets`() = runTest(main.dispatcher) {
        val vm = vm()
        assertEquals(listOf(love, night), vm.s.tags)
        assertEquals(listOf("Dark"), vm.s.presets.map { it.name })
        assertEquals(listOf("love", "night"), vm.s.tagNames)
        assertNull(vm.s.loadError)
    }

    @Test
    fun `a failed load is a typed error`() = runTest(main.dispatcher) {
        tagRepo.listResult = { ApiResult.Failure(ApiError.Unauthorized(401, null)) }
        val vm = vm()
        assertEquals(TagError.SessionExpired, vm.s.loadError)
    }

    @Test
    fun `the remembered filter is restored and applied`() = runTest(main.dispatcher) {
        store.saved[CANVAS_ID] = "love | night"
        val vm = vm()
        assertEquals("love | night", vm.s.filterBar.input.text)
        assertEquals("love | night", port.filters.last().format())
    }

    @Test
    fun `a valid edit applies and is remembered, an invalid one keeps the last valid filter`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.editFilter("love", 4)
        runCurrent()
        assertEquals("love", port.filters.last().format())
        assertEquals("love", store.saved[CANVAS_ID])
        vm.editFilter("love &", 6)
        runCurrent()
        assertEquals("love", port.filters.last().format())
        assertEquals("love", store.saved[CANVAS_ID])
        assertEquals(TagExprErrorCode.UNEXPECTED_END, vm.s.filterBar.input.error?.code)
    }

    @Test
    fun `an edit before the saved filter arrives wins`() = runTest(main.dispatcher) {
        store.saved[CANVAS_ID] = "night"
        val vm = TagsViewModel(CANVAS_ID, tagRepo, presetRepo, store, port)
        vm.editFilter("love", 4)
        runCurrent()
        assertEquals("love", vm.s.filterBar.input.text)
        assertEquals("love", port.filters.last().format())
    }

    @Test
    fun `clear, pills, completions and presets drive the same filter`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.insertFilterTag("love")
        assertEquals("love", vm.s.filterBar.appliedText)
        vm.editFilter("love & ni", 9)
        val completion = vm.s.filterBar.input.completion(vm.s.tagNames)
        vm.acceptFilterSuggestion(completion, completion.suggestions.first())
        assertEquals("love & night ", vm.s.filterBar.appliedText)
        vm.applyPreset(presetRepo.presets.first().id)
        assertEquals("night & !love", vm.s.filterBar.appliedText)
        vm.clearFilter()
        runCurrent()
        assertEquals(TagFilter.Everything, port.filters.last())
        assertEquals("", store.saved[CANVAS_ID])
    }

    // ---- Picker ----

    @Test
    fun `the picker shows check states for the picked snippets`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.openPicker(setOf(a.id, b.id))
        assertEquals(listOf(TagCheck.SOME, TagCheck.NONE), vm.s.pickerRows.map { it.check })
        vm.setPickerQuery("NI")
        assertEquals(listOf("night"), vm.s.pickerRows.map { it.tag.name })
        vm.closePicker()
        assertNull(vm.s.picker)
    }

    @Test
    fun `toggling a mixed tag links it to the rest, toggling again unlinks from all`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.openPicker(setOf(a.id, b.id))
        vm.togglePickerTag(love.id)
        runCurrent()
        assertEquals(listOf(b.id to love.id), tagRepo.links)
        assertEquals(listOf(love.toTag()), port.snippets[1].tags)
        assertEquals(TagCheck.ALL, vm.s.pickerRows.first { it.tag.id == love.id }.check)
        vm.togglePickerTag(love.id)
        runCurrent()
        assertEquals(setOf(a.id to love.id, b.id to love.id), tagRepo.unlinks.toSet())
        assertTrue(port.snippets.all { it.tags.isEmpty() })
        assertEquals(TagCheck.NONE, vm.s.pickerRows.first { it.tag.id == love.id }.check)
        assertTrue(vm.s.picker!!.pending.isEmpty())
    }

    @Test
    fun `a failed link leaves that snippet unchanged and says why`() = runTest(main.dispatcher) {
        tagRepo.linkResult = { s, _ -> if (s == b.id) ApiResult.Failure(ApiError.Network(java.io.IOException("x"))) else ApiResult.Success(Unit) }
        val vm = vm()
        vm.openPicker(setOf(a.id, b.id))
        vm.togglePickerTag(night.id)
        runCurrent()
        assertEquals(listOf(night.toTag()), port.snippets[0].tags.filter { it.id == night.id })
        assertTrue(port.snippets[1].tags.isEmpty())
        assertEquals(TagCheck.SOME, vm.s.pickerRows.first { it.tag.id == night.id }.check)
        // Shown in the sheet (it is also opened from the editor, where the canvas snackbar is not visible).
        assertEquals("Can't reach the server. Check your connection.", vm.s.picker?.error)
        vm.setPickerQuery("n")
        assertNull(vm.s.picker?.error)
    }

    @Test
    fun `a failed delete outside the picker goes to the snackbar`() = runTest(main.dispatcher) {
        tagRepo.deleteResult = { ApiResult.Failure(ApiError.Server(500, "boom")) }
        val vm = vm()
        vm.messages.test {
            vm.deleteTag(love.id)
            runCurrent()
            assertEquals("boom", awaitItem())
            assertEquals(listOf(love, night), vm.s.tags)
        }
    }

    @Test
    fun `create from the query makes the tag and links it to the picked snippets`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.openPicker(setOf(a.id, b.id))
        vm.setPickerQuery("  Dream ")
        assertEquals("Dream", vm.s.createOffer)
        vm.createTagFromPicker()
        runCurrent()
        assertEquals(listOf("Dream" to null), tagRepo.created)
        assertEquals(listOf("Dream", "love", "night"), vm.s.tags.map { it.name })
        assertEquals(setOf(a.id, b.id), tagRepo.links.map { it.first }.toSet())
        assertEquals("", vm.s.picker!!.query)
        assertEquals(TagCheck.ALL, vm.s.pickerRows.first { it.tag.name == "Dream" }.check)
    }

    @Test
    fun `no create offer for an existing name, and a taken name shows inline`() = runTest(main.dispatcher) {
        tagRepo.createResult = { _, _ -> ApiResult.Failure(ApiError.Server(409, "taken", "tag_name_taken")) }
        val vm = vm()
        vm.openPicker(setOf(a.id))
        vm.setPickerQuery("LOVE")
        assertNull(vm.s.createOffer)
        vm.setPickerQuery("Other")
        vm.createTagFromPicker()
        runCurrent()
        assertEquals("A tag with this name already exists.", vm.s.picker!!.error)
        assertTrue(tagRepo.links.isEmpty())
    }

    // ---- Editing tags ----

    @Test
    fun `renaming a tag updates snippets, reloads presets and rewrites the filter`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.editFilter("Love & night", 12)
        vm.startEditTag(love.id)
        vm.setTagEditorName("romance")
        vm.saveTagEditor()
        runCurrent()
        assertEquals(listOf(love.id to TagUpdateRequest(name = "romance")), tagRepo.updates)
        assertEquals(listOf("night", "romance"), vm.s.tags.map { it.name })
        assertEquals("romance", port.snippets[0].tags.single().name)
        assertEquals(2, presetRepo.listCalls)
        assertEquals("romance & night", vm.s.filterBar.appliedText)
        assertNull(vm.s.tagEditor)
    }

    @Test
    fun `recolouring sends only the colour, and a taken name shows inline`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.startEditTag(night.id)
        vm.setTagEditorColor("#ef4444")
        vm.saveTagEditor()
        runCurrent()
        assertEquals(TagUpdateRequest(color = "#ef4444"), tagRepo.updates.single().second)
        assertEquals(1, presetRepo.listCalls)
        tagRepo.updateResult = { _, _ -> ApiResult.Failure(ApiError.Server(409, "taken", "tag_name_taken")) }
        vm.startEditTag(night.id)
        vm.setTagEditorName("love")
        vm.saveTagEditor()
        runCurrent()
        assertEquals("A tag with this name already exists.", vm.s.tagEditor?.error)
    }

    @Test
    fun `an invalid name is refused locally`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.startEditTag(night.id)
        vm.setTagEditorName("   ")
        vm.saveTagEditor()
        runCurrent()
        assertTrue(tagRepo.updates.isEmpty())
        assertEquals("Enter a name.", vm.s.tagEditor?.error)
    }

    @Test
    fun `deleting a tag removes it everywhere`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.openPicker(setOf(a.id))
        vm.deleteTag(love.id)
        runCurrent()
        assertEquals(listOf(love.id), tagRepo.deleted)
        assertEquals(listOf(night), vm.s.tags)
        assertTrue(port.snippets[0].tags.isEmpty())
        assertEquals(listOf(TagCheck.NONE), vm.s.pickerRows.map { it.check })
    }

    // ---- Presets ----

    @Test
    fun `save as preset sends the normalized filter`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.editFilter("love night", 10)
        vm.startSaveAsPreset()
        vm.setPresetName("Both")
        vm.submitPresetEditor()
        runCurrent()
        assertEquals(listOf("Both" to "love & night"), presetRepo.created)
        assertEquals(listOf("Both", "Dark"), vm.s.presets.map { it.name })
        assertNull(vm.s.presetEditor)
    }

    @Test
    fun `save as preset refuses an empty or invalid filter locally and shows a taken name inline`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.startSaveAsPreset()
        vm.setPresetName("All")
        vm.submitPresetEditor()
        assertEquals("A preset needs a filter. Type one first.", vm.s.presetEditor?.error)
        vm.editFilter("love &", 6)
        vm.submitPresetEditor()
        assertTrue(vm.s.presetEditor!!.error!!.startsWith("The filter is invalid"))
        assertTrue(presetRepo.created.isEmpty())
        presetRepo.createResult = { _, _ -> ApiResult.Failure(ApiError.Server(409, "taken", "preset_name_taken")) }
        vm.editFilter("love", 4)
        vm.submitPresetEditor()
        runCurrent()
        assertEquals("A preset with this name already exists.", vm.s.presetEditor?.error)
    }

    @Test
    fun `server expression errors are shown with their location`() = runTest(main.dispatcher) {
        presetRepo.createResult = { _, _ ->
            ApiResult.Failure(ApiError.Validation(422, "bad", "invalid_tag_expression", ExpressionErrorBody("unexpected_end", 4, "m")))
        }
        val vm = vm()
        vm.editFilter("love", 4)
        vm.startSaveAsPreset()
        vm.setPresetName("X")
        vm.submitPresetEditor()
        runCurrent()
        assertEquals("The server rejected the filter: The filter ends too early (at 5).", vm.s.presetEditor?.error)
    }

    @Test
    fun `rename, overwrite and delete presets`() = runTest(main.dispatcher) {
        val vm = vm()
        val dark = presetRepo.presets.first()
        vm.startRenamePreset(dark.id)
        assertEquals("Dark", vm.s.presetEditor?.name)
        vm.setPresetName("Darker")
        vm.submitPresetEditor()
        runCurrent()
        assertEquals(dark.id to TagPresetUpdateRequest(name = "Darker"), presetRepo.updates.last())
        assertEquals(listOf("Darker"), vm.s.presets.map { it.name })
        presetRepo.presets = vm.s.presets
        vm.editFilter("love | night", 12)
        vm.overwritePreset(dark.id)
        runCurrent()
        assertEquals(dark.id to TagPresetUpdateRequest(expression = "love | night"), presetRepo.updates.last())
        assertEquals("love | night", vm.s.presets.single().expression)
        vm.deletePreset(dark.id)
        runCurrent()
        assertEquals(listOf(dark.id), presetRepo.deleted)
        assertTrue(vm.s.presets.isEmpty())
    }

    @Test
    fun `overwrite with an invalid filter is refused locally`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.messages.test {
            vm.editFilter("(", 1)
            vm.overwritePreset(presetRepo.presets.first().id)
            runCurrent()
            assertTrue(presetRepo.updates.isEmpty())
            assertTrue(awaitItem().startsWith("The filter is invalid"))
        }
    }

    @Test
    fun `a missing preset reads as such`() = runTest(main.dispatcher) {
        presetRepo.deleteResult = { ApiResult.Failure(ApiError.NotFound("gone", TagErrorCode.PRESET_NOT_FOUND.wire)) }
        val vm = vm()
        vm.messages.test {
            vm.deletePreset(presetRepo.presets.first().id)
            runCurrent()
            assertEquals("That preset no longer exists.", awaitItem())
        }
    }
}
