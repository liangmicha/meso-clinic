package org.watsi.domain.usecases

import com.nhaarman.mockito_kotlin.whenever
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.threeten.bp.Clock
import org.threeten.bp.Instant
import org.threeten.bp.ZoneId
import org.watsi.domain.entities.Delta
import org.watsi.domain.factories.BillableFactory
import org.watsi.domain.factories.BillableWithPriceScheduleFactory
import org.watsi.domain.factories.EncounterFactory
import org.watsi.domain.factories.EncounterFormFactory
import org.watsi.domain.factories.EncounterItemFactory
import org.watsi.domain.factories.EncounterItemWithBillableAndPriceFactory
import org.watsi.domain.factories.EncounterWithExtrasFactory
import org.watsi.domain.factories.PriceScheduleFactory
import org.watsi.domain.repositories.BillableRepository
import org.watsi.domain.repositories.EncounterRepository
import org.watsi.domain.repositories.PriceScheduleRepository

@RunWith(MockitoJUnitRunner::class)
class CreateEncounterUseCaseTest {

    @Mock lateinit var mockEncounterRepository: EncounterRepository
    @Mock lateinit var mockBillableRepository: BillableRepository
    @Mock lateinit var mockPriceScheduleRepository: PriceScheduleRepository
    lateinit var useCase: CreateEncounterUseCase
    lateinit var fixedClock: Clock

    @Before
    fun setup() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        useCase = CreateEncounterUseCase(mockEncounterRepository, mockBillableRepository, mockPriceScheduleRepository)
        fixedClock = Clock.fixed(Instant.now(), ZoneId.of("UTC"))
    }

    @Test
    fun execute_encounterDoesNotHaveNewBillablesOrEncounterForms_submitNowTrue_createsEncounterWithDeltaAndSetsSubmittedAt() {
        val encounterWithExtras = EncounterWithExtrasFactory.build()
        val encounterDelta = Delta(
                action = Delta.Action.ADD,
                modelName = Delta.ModelName.ENCOUNTER,
                modelId = encounterWithExtras.encounter.id
        )

        val encounterWithExtrasWithTimestamps = encounterWithExtras.copy(
            encounter = encounterWithExtras.encounter.copy(
                preparedAt = Instant.now(fixedClock),
                submittedAt = Instant.now(fixedClock)
            )
        )

        whenever(mockEncounterRepository.insert(encounterWithExtrasWithTimestamps, listOf(encounterDelta)))
                .thenReturn(Completable.complete())

        useCase.execute(encounterWithExtras, true, false, fixedClock).test().assertComplete()
    }

    @Test
    fun execute_encounterDoesNotHaveNewBillablesOrEncounterForms_submitNowFalse_createsEncounterWithoutDeltaAndWithoutSubmittedAt() {
        val encounterWithExtras = EncounterWithExtrasFactory.build()

        val encounterWithExtrasWithTimestamps = encounterWithExtras.copy(
            encounter = encounterWithExtras.encounter.copy(
                preparedAt = Instant.now(fixedClock)
            )
        )

        whenever(mockEncounterRepository.insert(encounterWithExtrasWithTimestamps, emptyList()))
            .thenReturn(Completable.complete())

        useCase.execute(encounterWithExtras, false, false, fixedClock).test().assertComplete()
    }

    @Test
    fun execute_encounterHasNewBillables_submitNowTrue_createsEncounterWithDeltaAndBillablesWithDeltasAndSetsSubmittedAt() {
        val encounter = EncounterFactory.build()
        val billable = BillableFactory.build()
        val priceSchedule = PriceScheduleFactory.build(billableId = billable.id)
        val encounterItem = EncounterItemFactory.build(encounterId = encounter.id, priceScheduleId = priceSchedule.id)
        val encounterItemRelation = EncounterItemWithBillableAndPriceFactory.build(
            BillableWithPriceScheduleFactory.build(billable, priceSchedule), encounterItem
        )
        val encounterWithExtras = EncounterWithExtrasFactory.build(
            encounter = encounter,
            encounterItemRelations = listOf(encounterItemRelation)
        )
        val encounterDelta = Delta(
            action = Delta.Action.ADD,
            modelName = Delta.ModelName.ENCOUNTER,
            modelId = encounter.id
        )
        val billableDelta = Delta(
            action = Delta.Action.ADD,
            modelName = Delta.ModelName.BILLABLE,
            modelId = billable.id
        )
        val priceScheduleDelta = Delta(
            action = Delta.Action.ADD,
            modelName = Delta.ModelName.PRICE_SCHEDULE,
            modelId = priceSchedule.id
        )

        val encounterWithExtrasWithTimestamps = encounterWithExtras.copy(
            encounter = encounterWithExtras.encounter.copy(
                preparedAt = Instant.now(fixedClock),
                submittedAt = Instant.now(fixedClock)
            )
        )

        whenever(mockBillableRepository.find(billable.id)).thenReturn(Maybe.empty())
        whenever(mockBillableRepository.create(billable, billableDelta))
            .thenReturn(Completable.complete())
        whenever(mockPriceScheduleRepository.create(priceSchedule, priceScheduleDelta))
            .thenReturn(Completable.complete())
        whenever(mockEncounterRepository.insert(encounterWithExtrasWithTimestamps, listOf(encounterDelta)))
            .thenReturn(Completable.complete())

        useCase.execute(encounterWithExtras, true, false, fixedClock).test().assertComplete()
    }

    @Test
    fun execute_encounterHasNewPriceSchedules_submitNowTrue_createsEncounterWithDeltaAndPriceSchedulesWithDeltasAndSetsSubmittedAt() {
        val encounter = EncounterFactory.build()
        val billable = BillableFactory.build()
        val priceSchedule = PriceScheduleFactory.build(billableId = billable.id)
        val encounterItem = EncounterItemFactory.build(
            encounterId = encounter.id,
            priceScheduleId = priceSchedule.id,
            priceScheduleIssued = true
        )
        val encounterItemRelation = EncounterItemWithBillableAndPriceFactory.build(
            BillableWithPriceScheduleFactory.build(billable, priceSchedule), encounterItem
        )
        val encounterWithExtras = EncounterWithExtrasFactory.build(
            encounter = encounter,
            encounterItemRelations = listOf(encounterItemRelation)
        )
        val encounterDelta = Delta(
            action = Delta.Action.ADD,
            modelName = Delta.ModelName.ENCOUNTER,
            modelId = encounter.id
        )
        val priceScheduleDelta = Delta(
            action = Delta.Action.ADD,
            modelName = Delta.ModelName.PRICE_SCHEDULE,
            modelId = priceSchedule.id
        )

        val encounterWithExtrasWithTimestamps = encounterWithExtras.copy(
            encounter = encounterWithExtras.encounter.copy(
                preparedAt = Instant.now(fixedClock),
                submittedAt = Instant.now(fixedClock)
            )
        )

        whenever(mockBillableRepository.find(billable.id)).thenReturn(Maybe.just(billable))
        whenever(mockPriceScheduleRepository.find(priceSchedule.id)).thenReturn(Maybe.empty())
        whenever(mockPriceScheduleRepository.create(priceSchedule, priceScheduleDelta))
            .thenReturn(Completable.complete())
        whenever(mockEncounterRepository.insert(encounterWithExtrasWithTimestamps, listOf(encounterDelta)))
            .thenReturn(Completable.complete())

        useCase.execute(encounterWithExtras, true, false, fixedClock).test().assertComplete()
    }

    @Test
    fun execute_encounterHasEncounterForms_submitNowTrue_createsEncounterWithDeltaAndEncounterFormDeltasAndSetsSubmittedAt() {
        val encounter = EncounterFactory.build()
        val encounterForm = EncounterFormFactory.build(encounterId = encounter.id)
        val encounterWithExtras = EncounterWithExtrasFactory.build(
                encounter = encounter,
                encounterForms = listOf(encounterForm)
        )
        val encounterDelta = Delta(
                action = Delta.Action.ADD,
                modelName = Delta.ModelName.ENCOUNTER,
                modelId = encounter.id
        )
        val encounterFormDelta = Delta(
                action = Delta.Action.ADD,
                modelName = Delta.ModelName.ENCOUNTER_FORM,
                modelId = encounterForm.id
        )

        val encounterWithExtrasWithTimestamps = encounterWithExtras.copy(
            encounter = encounterWithExtras.encounter.copy(
                preparedAt = Instant.now(fixedClock),
                submittedAt = Instant.now(fixedClock)
            )
        )

        whenever(mockEncounterRepository.insert(encounterWithExtrasWithTimestamps, listOf(encounterDelta, encounterFormDelta)))
                .thenReturn(Completable.complete())

        useCase.execute(encounterWithExtras, true, false, fixedClock).test().assertComplete()
    }

    @Test
    fun execute_encounterDoesNotHaveNewBillablesOrEncounterForms_submitNowTrue_isPartialTrue_createsEncounterWithDeltaAndDoesNotSetSubmittedAtOrPreparedAt() {
        val encounterWithExtras = EncounterWithExtrasFactory.build()
        val encounterDelta = Delta(
            action = Delta.Action.ADD,
            modelName = Delta.ModelName.ENCOUNTER,
            modelId = encounterWithExtras.encounter.id
        )

        whenever(mockEncounterRepository.insert(encounterWithExtras, listOf(encounterDelta)))
                .thenReturn(Completable.complete())

        useCase.execute(encounterWithExtras, true, true, fixedClock).test().assertComplete()
    }
}
