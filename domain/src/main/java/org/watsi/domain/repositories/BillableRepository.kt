package org.watsi.domain.repositories

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import org.watsi.domain.entities.Billable
import org.watsi.domain.entities.Delta
import org.watsi.domain.relations.BillableWithPriceSchedule
import java.util.UUID

interface BillableRepository {
    fun all(): Single<List<BillableWithPriceSchedule>>
    fun countActive(): Single<Int>
    fun ofType(type: Billable.Type): Single<List<BillableWithPriceSchedule>>
    fun find(id: UUID): Maybe<Billable>
    fun find(ids: List<UUID>): Single<List<Billable>>
    fun create(billable: Billable, delta: Delta?): Completable
    fun fetch(): Completable
    fun opdDefaults(): Single<List<BillableWithPriceSchedule>>
    fun sync(delta: Delta): Completable
    fun uniqueTypes(): Flowable<List<Billable.Type>>
}
