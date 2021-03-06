package org.watsi.device.db.repositories

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import okhttp3.MediaType
import okhttp3.RequestBody
import org.threeten.bp.Clock
import org.watsi.device.api.CoverageApi
import org.watsi.device.api.models.MemberApi
import org.watsi.device.db.DbHelper
import org.watsi.device.db.daos.IdentificationEventDao
import org.watsi.device.db.daos.MemberDao
import org.watsi.device.db.daos.PhotoDao
import org.watsi.device.db.models.DeltaModel
import org.watsi.device.db.models.MemberModel
import org.watsi.device.db.models.PhotoModel
import org.watsi.device.managers.PreferencesManager
import org.watsi.device.managers.SessionManager
import org.watsi.domain.entities.AuthenticationToken
import org.watsi.domain.entities.Delta
import org.watsi.domain.entities.Member
import org.watsi.domain.entities.Photo
import org.watsi.domain.relations.MemberWithIdEventAndThumbnailPhoto
import org.watsi.domain.relations.MemberWithThumbnail
import org.watsi.domain.repositories.MemberRepository
import java.util.UUID

class MemberRepositoryImpl(
    private val memberDao: MemberDao,
    private val identificationEventDao: IdentificationEventDao,
    private val api: CoverageApi,
    private val sessionManager: SessionManager,
    private val preferencesManager: PreferencesManager,
    private val photoDao: PhotoDao,
    private val clock: Clock
) : MemberRepository {

    override fun all(excludeArchived: Boolean): Flowable<List<Member>> {
        return if (excludeArchived) {
            memberDao.allUnarchived().map { it.map { it.toMember() } }.subscribeOn(Schedulers.io())
        } else {
            memberDao.all().map { it.map { it.toMember() } }.subscribeOn(Schedulers.io())
        }
    }

    override fun count(): Flowable<Int> {
        return memberDao.count()
    }

    override fun find(id: UUID): Maybe<Member> {
        return memberDao.find(id).map { it.toMember() }.subscribeOn(Schedulers.io())
    }

    override fun findAll(ids: List<UUID>): Single<List<Member>> {
        return Single.fromCallable {
            ids.chunked(DbHelper.SQLITE_MAX_VARIABLE_NUMBER).map {
                memberDao.findAll(it).blockingGet()
            }.flatten().map {
                it.toMemberWithThumbnailPhoto().member
            }
        }
    }

    override fun findMemberWithThumbnailFlowable(id: UUID): Flowable<MemberWithThumbnail> {
        return memberDao.findFlowableMemberWithThumbnail(id).map { it.toMemberWithThumbnail() }
    }

    override fun findByCardId(cardId: String, excludeArchived: Boolean): Maybe<Member> {
        return if (excludeArchived) {
            memberDao.findByCardIdUnarchived(cardId).map { it.toMember() }.subscribeOn(Schedulers.io())
        } else {
            memberDao.findByCardId(cardId).map { it.toMember() }.subscribeOn(Schedulers.io())
        }
    }

    override fun findHouseholdIdByMembershipNumber(membershipNumber: String, excludeArchived: Boolean): Maybe<UUID> {
        return if (excludeArchived) {
            memberDao.findHouseholdIdByMembershipNumberUnarchived(membershipNumber).subscribeOn(Schedulers.io())
        } else {
            memberDao.findHouseholdIdByMembershipNumber(membershipNumber).subscribeOn(Schedulers.io())
        }
    }

    override fun findHouseholdIdByCardId(cardId: String, excludeArchived: Boolean): Maybe<UUID> {
        return if (excludeArchived) {
            memberDao.findHouseholdIdByCardIdUnarchived(cardId).subscribeOn(Schedulers.io())
        } else {
            memberDao.findHouseholdIdByCardId(cardId).subscribeOn(Schedulers.io())
        }
    }

    override fun byIds(ids: List<UUID>): Single<List<MemberWithIdEventAndThumbnailPhoto>> {
        return Single.fromCallable {
            ids.chunked(DbHelper.SQLITE_MAX_VARIABLE_NUMBER).map {
                memberDao.findMemberRelationsByIds(it).blockingGet()
            }.flatten().map { it.toMemberWithIdEventAndThumbnailPhoto() }
        }.subscribeOn(Schedulers.io())
    }

    override fun byNames(names: List<String>): Single<List<MemberWithIdEventAndThumbnailPhoto>> {
        return Single.fromCallable {
            names.chunked(DbHelper.SQLITE_MAX_VARIABLE_NUMBER).map {
                memberDao.findMemberRelationsByNames(it).blockingGet()
            }.flatten().map { it.toMemberWithIdEventAndThumbnailPhoto() }
        }.subscribeOn(Schedulers.io())
    }

    override fun byCardIds(names: List<String>): Single<List<MemberWithIdEventAndThumbnailPhoto>> {
        return Single.fromCallable {
            names.chunked(DbHelper.SQLITE_MAX_VARIABLE_NUMBER).map {
                memberDao.findMemberRelationsByCardIds(it).blockingGet()
            }.flatten().map { it.toMemberWithIdEventAndThumbnailPhoto() }
        }.subscribeOn(Schedulers.io())
    }

    override fun checkedInMembers(): Flowable<List<MemberWithIdEventAndThumbnailPhoto>> {
        return identificationEventDao.activeCheckIns().map { identificationEventModels ->
            val identificationEvents = identificationEventModels.map { it.toIdentificationEvent() }
            val memberIds = identificationEvents.map { it.memberId }.distinct()
            val memberWithPhotosById = mutableMapOf<UUID, MemberWithThumbnail>()
            memberDao.findAll(memberIds).blockingGet().map { it.toMemberWithThumbnailPhoto() }
                    .forEach { memberWithPhoto ->
                        memberWithPhotosById[memberWithPhoto.member.id] = memberWithPhoto
                    }
            identificationEvents.sortedBy { it.occurredAt }.map { identificationEvent ->
                val memberWithThumbnail = memberWithPhotosById[identificationEvent.memberId]
                MemberWithIdEventAndThumbnailPhoto(
                    memberWithThumbnail!!.member,
                    identificationEvent,
                    memberWithThumbnail.photo

                )
            }
        }
    }

    override fun isMemberCheckedIn(memberId: UUID): Flowable<Boolean> {
        return memberDao.isMemberCheckedIn(memberId).subscribeOn(Schedulers.io())
    }

    override fun allDistinctCardIds(): Single<List<String>> {
        return memberDao.allDistinctCardIds().subscribeOn(Schedulers.io())
    }

    override fun allDistinctNames(): Single<List<String>> {
        return memberDao.allDistinctNames().subscribeOn(Schedulers.io())
    }

    override fun findHouseholdMembers(
        householdId: UUID,
        excludeArchived: Boolean
    ): Flowable<List<MemberWithIdEventAndThumbnailPhoto>> {
        return if (excludeArchived) {
            memberDao.findHouseholdMembersUnarchived(householdId).map { memberWithIdEventAndThumbnailModels ->
                memberWithIdEventAndThumbnailModels.map { memberWithIdEventAndThumbnailModel ->
                    memberWithIdEventAndThumbnailModel.toMemberWithIdEventAndThumbnailPhoto()
                }
            }.subscribeOn(Schedulers.io())
        } else {
            memberDao.findHouseholdMembers(householdId).map { memberWithIdEventAndThumbnailModels ->
                memberWithIdEventAndThumbnailModels.map { memberWithIdEventAndThumbnailModel ->
                    memberWithIdEventAndThumbnailModel.toMemberWithIdEventAndThumbnailPhoto()
                }
            }.subscribeOn(Schedulers.io())
        }
    }

    override fun upsert(member: Member, deltas: List<Delta>): Completable {
        return Completable.fromAction {
            val deltaModels = deltas.map { DeltaModel.fromDelta(it, clock) }
            memberDao.upsert(MemberModel.fromMember(member, clock), deltaModels)
        }.subscribeOn(Schedulers.io())
    }

    /**
     * Fetches members using pagination. Always overwrites local data with updated
     * server data, unless the local data has unsynced changes.
     */
    override fun fetch(): Completable {
        return sessionManager.currentAuthenticationToken()?.let { token ->
            Completable.fromAction {
                var hasMore = true

                if (sessionManager.shouldClearPageKey(memberDao.count().blockingFirst())) {
                    preferencesManager.updateMembersPageKey(null)
                }
                while (hasMore) {
                    hasMore = paginatedFetch(token).blockingGet()
                }
                preferencesManager.updateMemberLastFetched(clock.instant())
            }.subscribeOn(Schedulers.io())
        } ?: Completable.error(Exception("Current token is null while calling MemberRepositoryImpl.fetch"))
    }

    override fun fetchHouseholdIdByCardId(cardId: String): Flowable<UUID> {
        return sessionManager.currentAuthenticationToken()?.let { token ->
            Flowable.fromCallable {
                val households = api.getHouseholdByCardId(
                    token.getHeaderString(),
                    cardId
                ).blockingGet()

                households.forEach { household ->
                    upsertMembers(household.members)
                }

                // This search should only return one household, so consider the first the canonical
                // value and return its household id. If we somehow get more than one household returned
                // this is essentially picking at random and we would need to update the logic to show
                // all returned households. If none are returned we pass up an exception that can be handled.
                households.firstOrNull()?.householdId ?: throw Member.MemberNotFoundException("Server returned no results")
            }.subscribeOn(Schedulers.io())
        } ?: Flowable.error(Exception("Current token is null while calling MemberRepositoryImpl.fetchHouseholdByCardId"))
    }

    override fun fetchHouseholdIdByMembershipNumber(membershipNumber: String): Flowable<UUID> {
        return sessionManager.currentAuthenticationToken()?.let { token ->
            Flowable.fromCallable {
                val households = api.getHouseholdByMembershipNumber(
                    token.getHeaderString(),
                    membershipNumber
                ).blockingGet()

                households.forEach { household ->
                    upsertMembers(household.members)
                }

                // This search should only return one household, so consider the first the canonical
                // value and return its household id. If we somehow get more than one household returned
                // this is essentially picking at random and we would need to update the logic to show
                // all returned households. If none are returned we pass up an exception that can be handled.
                households.firstOrNull()?.householdId ?: throw Member.MemberNotFoundException("Server returned no results")
            }.subscribeOn(Schedulers.io())
        } ?: Flowable.error(Exception("Current token is null while calling MemberRepositoryImpl.fetchHouseholdByMembershipNumber"))
    }

    private fun paginatedFetch(token: AuthenticationToken): Single<Boolean> {
        val paginatedResponse = api.getMembers(
            token.getHeaderString(),
            token.user.providerId,
            preferencesManager.getMembersPageKey()
        ).blockingGet()
        val serverMembers = paginatedResponse.members
        val hasMore = paginatedResponse.hasMore
        val updatedPageKey = paginatedResponse.pageKey

        if (serverMembers.isNotEmpty()) {
            upsertMembers(serverMembers)
            preferencesManager.updateMembersPageKey(updatedPageKey)
            preferencesManager.updateMembersCountForCurrentPageKey(memberDao.count().blockingFirst())
        }

        return Single.just(hasMore)
    }

    private fun upsertMembers(serverMembers: List<MemberApi>) {
        // Do not update local members with unsynced changes; after changes sync they're guaranteed to be returned in a future fetch.
        val unsyncedClientMemberIds = memberDao.unsynced().blockingGet().map { it.id }
        val serverMembersWithoutUnsynced = serverMembers.filter { !unsyncedClientMemberIds.contains(it.id) }
        val persistedLocalMembers = findAll(serverMembersWithoutUnsynced.map { it.id }).blockingGet()

        memberDao.upsert(serverMembersWithoutUnsynced.map { memberApi ->
            val persistedMember = persistedLocalMembers.find { it.id == memberApi.id }
            // Pass local member to upsert logic to preserve photo
            MemberModel.fromMember(memberApi.toMember(persistedMember), clock)
        })
    }

    override fun downloadPhotos(): Completable {
            return Completable.fromCallable {
                memberDao.needPhotoDownload().flatMapCompletable { memberModels ->
                    Completable.concat(memberModels.map { memberModel ->
                        val member = memberModel.toMember()
                        api.fetchPhoto(member.photoUrl!!).flatMapCompletable {
                            Completable.fromAction {
                                val photo = Photo(UUID.randomUUID(), it.bytes())
                                photoDao.insert(PhotoModel.fromPhoto(photo, clock))
                                memberDao.upsert(memberModel.copy(thumbnailPhotoId = photo.id))
                            }
                        }
                    })
                }.blockingAwait()
                preferencesManager.updateMemberPhotosLastFetched(clock.instant())
            }.subscribeOn(Schedulers.io())
    }

    override fun withPhotosToFetchCount(): Flowable<Int> {
        return memberDao.needPhotoDownloadCount()
    }

    override fun sync(deltas: List<Delta>): Completable {
        return sessionManager.currentAuthenticationToken()?.let { token ->
            find(deltas.first().modelId).flatMapCompletable { member ->
                if (deltas.any { it.action == Delta.Action.ADD }) {
                    api.postMember(token.getHeaderString(), MemberApi(member))
                } else {
                    api.patchMember(token.getHeaderString(), member.id, MemberApi.patch(member, deltas))
                }
            }.subscribeOn(Schedulers.io())
        } ?: Completable.error(Exception("Current token is null while calling MemberRepositoryImpl.sync"))
    }

    override fun syncPhotos(deltas: List<Delta>): Completable {
        return sessionManager.currentAuthenticationToken()?.let { token ->
            // the modelId in a photo delta corresponds to the member ID and not the photo ID
            // to make this querying and formatting of the sync request simpler
            val memberId = deltas.first().modelId
            photoDao.findMemberWithRawPhoto(memberId).flatMapCompletable { memberWithRawPhotoModel ->
                val memberWithRawPhoto = memberWithRawPhotoModel.toMemberWithRawPhoto()
                val requestBody = RequestBody.create(MediaType.parse("image/jpg"), memberWithRawPhoto.photo.bytes)
                Completable.concatArray(
                    api.patchPhoto(token.getHeaderString(), memberId, requestBody),
                    upsert(memberWithRawPhoto.member.copy(photoId = null), emptyList())
                )
            }.subscribeOn(Schedulers.io())
        } ?: Completable.error(Exception("Current token is null while calling MemberRepositoryImpl.syncPhotos"))
    }
}
