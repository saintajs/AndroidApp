/*
 * Plain JVM unit tests for the S-03 new-incident ViewModel (US-008,
 * testing.yaml critical_cases). Drives the real in-memory
 * FakeIncidentRepository under virtual time — no mocks, no Robolectric, no
 * network. Asserts the BR-013/BR-014 boundary matrix (title 4/5/100/101,
 * description 9/10/1000/1001 — Spanish per-field errors, repository never
 * called on any invalid case), the BR-015/BR-016 missing choice errors, the
 * CA-04 duplicate-submission lock observed in flight with virtual time, the
 * success state (fake-forced Pendiente, one constant created_at stamp as the
 * created_at == updated_at stand-in, initial history entry with
 * previous_status null, exactly one NavigateBackToS02), determinism against
 * two freshly seeded fakes (CA-06), and that the create call carries only
 * title/description/category/priority — never status or owner fields
 * (INSTR-002, BR-011) — plus the design.md Risk 3 touch-timing rule: a
 * pristine form never flashes errors, and a field re-validates live only
 * once it has been touched (first change or a submit attempt).
 */
package com.nexti.minisoporte.ui.newincident

import com.nexti.minisoporte.data.fake.FakeIncidentRepository
import com.nexti.minisoporte.data.fake.FakeSeed
import com.nexti.minisoporte.data.model.Incident
import com.nexti.minisoporte.data.model.IncidentCategory
import com.nexti.minisoporte.data.model.IncidentPriority
import com.nexti.minisoporte.data.model.IncidentStatus
import com.nexti.minisoporte.ui.signin.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewIncidentViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // BR-013 boundary matrix: title of 4 chars errors, 5 passes, 100 passes,
    // 101 errors — the exact Spanish title error, every other field clean,
    // the repository never creating on the invalid cases, no event ever.
    @Test
    fun titleBoundaryMatrixRejects4And101AndAccepts5And100() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cases = listOf(4 to false, 5 to true, 100 to true, 101 to false)
            for ((length, isValid) in cases) {
                val repository = FakeIncidentRepository()
                val viewModel = NewIncidentViewModel(repository)
                val events = mutableListOf<NewIncidentViewModel.NewIncidentEvent>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.events.toList(events)
                }
                val ownedBefore = ownedIncidentCount(repository)

                viewModel.onDescriptionChange(VALID_DESCRIPTION)
                viewModel.onCategorySelect(IncidentCategory.EQUIPMENT)
                viewModel.onPrioritySelect(IncidentPriority.MEDIUM)
                viewModel.onTitleChange("a".repeat(length))
                viewModel.submit()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                if (isValid) {
                    assertNull("title of $length chars must pass", state.titleError)
                    assertEquals(
                        "valid title of $length chars must create exactly once",
                        ownedBefore + 1,
                        ownedIncidentCount(repository),
                    )
                    assertEquals("valid title of $length chars must navigate once", 1, events.size)
                } else {
                    assertEquals(TITLE_ERROR, state.titleError)
                    assertNull("description must stay error-free", state.descriptionError)
                    assertNull("category must stay error-free", state.categoryError)
                    assertNull("priority must stay error-free", state.priorityError)
                    assertEquals(
                        "invalid title of $length chars must never reach the repository",
                        ownedBefore,
                        ownedIncidentCount(repository),
                    )
                    assertTrue("no navigation event on an invalid title", events.isEmpty())
                }
                assertFalse("the lock must never engage on an invalid title", state.isSubmitting)
            }
        }

    // BR-014 boundary matrix: description of 9 chars errors, 10 passes,
    // 1000 passes, 1001 errors — same never-creates / no-event guarantees.
    @Test
    fun descriptionBoundaryMatrixRejects9And1001AndAccepts10And1000() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cases = listOf(9 to false, 10 to true, 1000 to true, 1001 to false)
            for ((length, isValid) in cases) {
                val repository = FakeIncidentRepository()
                val viewModel = NewIncidentViewModel(repository)
                val events = mutableListOf<NewIncidentViewModel.NewIncidentEvent>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.events.toList(events)
                }
                val ownedBefore = ownedIncidentCount(repository)

                viewModel.onTitleChange(VALID_TITLE)
                viewModel.onCategorySelect(IncidentCategory.ACCESS)
                viewModel.onPrioritySelect(IncidentPriority.LOW)
                viewModel.onDescriptionChange("b".repeat(length))
                viewModel.submit()
                advanceUntilIdle()

                val state = viewModel.uiState.value
                if (isValid) {
                    assertNull("description of $length chars must pass", state.descriptionError)
                    assertEquals(
                        "valid description of $length chars must create exactly once",
                        ownedBefore + 1,
                        ownedIncidentCount(repository),
                    )
                    assertEquals("valid description of $length chars must navigate once", 1, events.size)
                } else {
                    assertEquals(DESCRIPTION_ERROR, state.descriptionError)
                    assertNull("title must stay error-free", state.titleError)
                    assertNull("category must stay error-free", state.categoryError)
                    assertNull("priority must stay error-free", state.priorityError)
                    assertEquals(
                        "invalid description of $length chars must never reach the repository",
                        ownedBefore,
                        ownedIncidentCount(repository),
                    )
                    assertTrue("no navigation event on an invalid description", events.isEmpty())
                }
                assertFalse("the lock must never engage on an invalid description", state.isSubmitting)
            }
        }

    // BR-015: texts valid, priority chosen, category missing — ONLY the
    // category group errors, the repository is never called.
    @Test
    fun missingCategoryFlagsOnlyCategoryErrorAndNeverCreates() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = NewIncidentViewModel(repository)
            val events = mutableListOf<NewIncidentViewModel.NewIncidentEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            val ownedBefore = ownedIncidentCount(repository)

            viewModel.onTitleChange(VALID_TITLE)
            viewModel.onDescriptionChange(VALID_DESCRIPTION)
            viewModel.onPrioritySelect(IncidentPriority.HIGH)
            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(CATEGORY_ERROR, state.categoryError)
            assertNull("priority was chosen — no priority error", state.priorityError)
            assertNull("valid title carries no error", state.titleError)
            assertNull("valid description carries no error", state.descriptionError)
            assertEquals(
                "a missing category must never reach the repository",
                ownedBefore,
                ownedIncidentCount(repository),
            )
            assertTrue("no navigation event on a missing category", events.isEmpty())
            assertFalse(state.isSubmitting)
        }

    // BR-016: texts valid, category chosen, priority missing — ONLY the
    // priority group errors, the repository is never called.
    @Test
    fun missingPriorityFlagsOnlyPriorityErrorAndNeverCreates() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = NewIncidentViewModel(repository)
            val events = mutableListOf<NewIncidentViewModel.NewIncidentEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            val ownedBefore = ownedIncidentCount(repository)

            viewModel.onTitleChange(VALID_TITLE)
            viewModel.onDescriptionChange(VALID_DESCRIPTION)
            viewModel.onCategorySelect(IncidentCategory.SYSTEMS)
            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(PRIORITY_ERROR, state.priorityError)
            assertNull("category was chosen — no category error", state.categoryError)
            assertNull("valid title carries no error", state.titleError)
            assertNull("valid description carries no error", state.descriptionError)
            assertEquals(
                "a missing priority must never reach the repository",
                ownedBefore,
                ownedIncidentCount(repository),
            )
            assertTrue("no navigation event on a missing priority", events.isEmpty())
            assertFalse(state.isSubmitting)
        }

    // BR-015 + BR-016: both choice groups missing — both errors set, the
    // valid text fields stay clean, the repository is never called.
    @Test
    fun missingCategoryAndPriorityFlagBothChoiceErrorsAndNeverCreate() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = NewIncidentViewModel(repository)
            val events = mutableListOf<NewIncidentViewModel.NewIncidentEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            val ownedBefore = ownedIncidentCount(repository)

            viewModel.onTitleChange(VALID_TITLE)
            viewModel.onDescriptionChange(VALID_DESCRIPTION)
            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(CATEGORY_ERROR, state.categoryError)
            assertEquals(PRIORITY_ERROR, state.priorityError)
            assertNull(state.titleError)
            assertNull(state.descriptionError)
            assertEquals(
                "missing choices must never reach the repository",
                ownedBefore,
                ownedIncidentCount(repository),
            )
            assertTrue("no navigation event on missing choices", events.isEmpty())
            assertFalse(state.isSubmitting)
        }

    // CA-04 duplicate-submission lock: on a valid form the first submit()
    // flips isSubmitting synchronously while the create coroutine is still
    // queued under the StandardTestDispatcher, so the second submit() hits
    // the lock and is ignored — exactly one creation, exactly one event.
    @Test
    fun duplicateSubmissionLockIgnoresSecondSubmitAndCreatesExactlyOnce() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = NewIncidentViewModel(repository)
            val events = mutableListOf<NewIncidentViewModel.NewIncidentEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            val ownedBefore = ownedIncidentCount(repository)
            fillValidForm(viewModel)

            viewModel.submit()
            assertTrue(
                "isSubmitting must be observable in flight, before any virtual time advances",
                viewModel.uiState.value.isSubmitting,
            )
            viewModel.submit()
            assertTrue(
                "the second submit must not release or re-enter the lock",
                viewModel.uiState.value.isSubmitting,
            )
            advanceUntilIdle()

            assertEquals(
                "two rapid submits must create exactly one incident",
                ownedBefore + 1,
                ownedIncidentCount(repository),
            )
            assertEquals(
                "exactly one NavigateBackToS02 despite the double submit",
                listOf(NewIncidentViewModel.NewIncidentEvent.NavigateBackToS02),
                events,
            )
            assertFalse(viewModel.uiState.value.isSubmitting)
        }

    // Success: the fake stores exactly one incident — id fake-1, status
    // forced Pendiente, the single constant created_at stamp (the model has
    // no updated_at field; created_at == updated_at maps to the fake's one
    // constant timestamp), an initial history entry with previous_status
    // null, and exactly one NavigateBackToS02 event.
    @Test
    fun successfulSubmitCreatesPendingWithInitialHistoryAndSingleNavigationEvent() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = NewIncidentViewModel(repository)
            val events = mutableListOf<NewIncidentViewModel.NewIncidentEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }
            val ownedBefore = ownedIncidentCount(repository)
            fillValidForm(viewModel)

            viewModel.submit()
            advanceUntilIdle()

            val created =
                requireNotNull(repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, CREATED_ID)) { "the fake must store the created incident" }
            assertEquals(
                "exactly one new incident stored",
                ownedBefore + 1,
                ownedIncidentCount(repository),
            )
            assertEquals("the fake forces Pendiente on creation", IncidentStatus.PENDING, created.status)
            assertEquals(
                "created_at == updated_at maps to the fake's single constant stamp",
                FakeSeed.CREATED_AT_PLACEHOLDER,
                created.createdAt,
            )
            assertNull("a brand-new incident is not resolved", created.resolvedAt)
            assertEquals(CREATED_ID, created.id)
            val entry = created.history.single()
            assertNull("the initial history entry has previous_status null", entry.previousStatus)
            assertEquals(IncidentStatus.PENDING, entry.newStatus)
            assertEquals(CREATED_ID, entry.incidentId)
            assertEquals(
                "exactly one NavigateBackToS02 event on success",
                listOf(NewIncidentViewModel.NewIncidentEvent.NavigateBackToS02),
                events,
            )
            assertFalse(viewModel.uiState.value.isSubmitting)
        }

    // CA-06 determinism: the same valid submission against two freshly
    // seeded fakes yields identical created incidents (data-class equality,
    // history included) and identical history entries.
    @Test
    fun sameValidSubmissionAgainstTwoFreshFakesYieldsIdenticalIncidents() =
        runTest(mainDispatcherRule.testDispatcher) {
            val first = createdIncidentFrom(FakeIncidentRepository())
            val second = createdIncidentFrom(FakeIncidentRepository())

            assertEquals(
                "both fresh fakes must store an identical created incident",
                first,
                second,
            )
            assertEquals("both histories must be identical", first.history, second.history)
        }

    // INSTR-002 / BR-011: the ViewModel passes only title/description/
    // category/priority to the repository — the created row's owner id/email
    // and Pendiente status are the FAKE's assignments, and the four form
    // inputs round-trip exactly.
    @Test
    fun createdIncidentCarriesOnlyFormInputsAndFakeAssignedOwnerAndStatus() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = NewIncidentViewModel(repository)
            fillValidForm(viewModel)

            viewModel.submit()
            advanceUntilIdle()

            val created =
                requireNotNull(repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, CREATED_ID)) { "the fake must store the created incident" }
            assertEquals("the default owner id is the demo employee", FakeSeed.DEMO_EMPLOYEE_ID, created.ownerUserId)
            assertEquals("the owner email is the fake's lookup", FakeSeed.DEMO_EMPLOYEE.email, created.ownerEmail)
            assertEquals("status is the fake's assignment, never a ViewModel input", IncidentStatus.PENDING, created.status)
            assertEquals("the typed title round-trips exactly", VALID_TITLE, created.title)
            assertEquals("the typed description round-trips exactly", VALID_DESCRIPTION, created.description)
            assertEquals(IncidentCategory.SYSTEMS, created.category)
            assertEquals(IncidentPriority.HIGH, created.priority)
        }

    // design.md Risk 3 touch timing: a pristine form shows NO errors, the
    // first change touches the field and re-validates it live (a 3-char
    // title flags immediately, returning to 5 chars clears it), and a
    // submit attempt touches everything — the untouched description only
    // errors once submit runs, never before.
    @Test
    fun touchTimingPristineFormShowsNoErrorsAndFieldsRevalidateOnceTouched() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeIncidentRepository()
            val viewModel = NewIncidentViewModel(repository)
            val events = mutableListOf<NewIncidentViewModel.NewIncidentEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.events.toList(events)
            }

            var state = viewModel.uiState.value
            assertNull("a pristine form must not flag the title", state.titleError)
            assertNull("a pristine form must not flag the description", state.descriptionError)
            assertNull("a pristine form must not flag the category", state.categoryError)
            assertNull("a pristine form must not flag the priority", state.priorityError)

            viewModel.onTitleChange(SHORT_TITLE)

            state = viewModel.uiState.value
            assertEquals("touched by the first change — the 3-char title flags live", TITLE_ERROR, state.titleError)
            assertNull("the untouched description stays error-free", state.descriptionError)

            viewModel.onTitleChange(VALID_TITLE)

            state = viewModel.uiState.value
            assertNull("back inside the range the error clears live", state.titleError)

            viewModel.onTitleChange(SHORT_TITLE)
            viewModel.submit()
            advanceUntilIdle()

            state = viewModel.uiState.value
            assertEquals("the submit attempt re-validates the short title", TITLE_ERROR, state.titleError)
            assertEquals(
                "an invalid submit must never reach the repository",
                SEEDED_OWNED_COUNT,
                ownedIncidentCount(repository),
            )
            assertTrue("no navigation event on an invalid submit", events.isEmpty())
        }

    /** Fills every field of the form with a valid value (texts + both choices). */
    private fun fillValidForm(viewModel: NewIncidentViewModel) {
        viewModel.onTitleChange(VALID_TITLE)
        viewModel.onDescriptionChange(VALID_DESCRIPTION)
        viewModel.onCategorySelect(IncidentCategory.SYSTEMS)
        viewModel.onPrioritySelect(IncidentPriority.HIGH)
    }

    /** The demo employee's owned count, read straight from the fake. */
    private suspend fun ownedIncidentCount(repository: FakeIncidentRepository): Int =
        repository.listIncidents(FakeSeed.DEMO_EMPLOYEE_ID, null).size

    /**
     * Runs one full valid submission against a fresh fake and returns the
     * incident the fake stored (fresh seed per run — CA-06 determinism).
     */
    private suspend fun TestScope.createdIncidentFrom(repository: FakeIncidentRepository): Incident {
        val viewModel = NewIncidentViewModel(repository)
        fillValidForm(viewModel)
        viewModel.submit()
        advanceUntilIdle()
        return requireNotNull(repository.getIncident(FakeSeed.DEMO_EMPLOYEE_ID, CREATED_ID)) { "the fake must store the created incident" }
    }

    private companion object {
        const val VALID_TITLE = "Impresora averiada en la planta primera"
        const val VALID_DESCRIPTION = "El equipo no arranca desde esta mañana en la oficina de soporte."
        const val SHORT_TITLE = "abc"
        const val TITLE_ERROR = "El título debe tener entre 5 y 100 caracteres"
        const val DESCRIPTION_ERROR = "La descripción debe tener entre 10 y 1000 caracteres"
        const val CATEGORY_ERROR = "Selecciona una categoría"
        const val PRIORITY_ERROR = "Selecciona una prioridad"
        const val CREATED_ID = "fake-1"
        const val SEEDED_OWNED_COUNT = 5
    }
}
