package org.watsi.domain.usecases

import io.reactivex.Single
import org.watsi.domain.entities.Billable
import org.watsi.domain.relations.BillableWithPriceSchedule
import org.watsi.domain.repositories.BillableRepository

class LoadBillablesOfTypeUseCase(private val billableRepository: BillableRepository) {
    fun execute(type: Billable.Type): Single<List<BillableWithPriceSchedule>> {
        return billableRepository.ofType(type)
    }
}
