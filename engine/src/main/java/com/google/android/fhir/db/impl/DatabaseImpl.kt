/*
 * Copyright 2023-2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.db.impl

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.util.FhirTerser
import com.google.android.fhir.DatabaseErrorStrategy
import com.google.android.fhir.LocalChange
import com.google.android.fhir.LocalChangeToken
import com.google.android.fhir.db.LocalChangeResourceReference
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.db.ResourceWithUUID
import com.google.android.fhir.db.impl.DatabaseImpl.Companion.UNENCRYPTED_DATABASE_NAME
import com.google.android.fhir.db.impl.dao.ForwardIncludeSearchResult
import com.google.android.fhir.db.impl.dao.ReverseIncludeSearchResult
import com.google.android.fhir.db.impl.entities.ResourceEntity
import com.google.android.fhir.index.ResourceIndexer
import com.google.android.fhir.lastUpdated
import com.google.android.fhir.logicalId
import com.google.android.fhir.search.SearchQuery
import com.google.android.fhir.toLocalChange
import com.google.android.fhir.versionId
import java.time.Instant
import java.util.*
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.InstantType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType

/**
 * The implementation for the persistence layer using Room. See docs for
 * [com.google.android.fhir.db.Database] for the API docs.
 */
@Suppress("UNCHECKED_CAST")
internal class DatabaseImpl(
  private val context: Context,
  private val iParser: IParser,
  private val fhirTerser: FhirTerser,
  databaseConfig: DatabaseConfig,
  private val resourceIndexer: ResourceIndexer,
) : com.google.android.fhir.db.Database {

  val db: ResourceDatabase

  init {
    val enableEncryption =
      databaseConfig.enableEncryption &&
        DatabaseEncryptionKeyProvider.isDatabaseEncryptionSupported()

    // The detection of unintentional switching of database encryption across releases can't be
    // placed inside withTransaction because the database is opened within withTransaction. The
    // default handling of corruption upon open in the room database is to re-create the database,
    // which is undesirable.
    val unexpectedDatabaseName =
      if (enableEncryption) {
        UNENCRYPTED_DATABASE_NAME
      } else {
        ENCRYPTED_DATABASE_NAME
      }
    check(!context.getDatabasePath(unexpectedDatabaseName).exists()) {
      "Unexpected database, $unexpectedDatabaseName, has already existed. " +
        "Check if you have accidentally enabled / disabled database encryption across releases."
    }

    @SuppressWarnings("NewApi")
    db =
      // Initializes builder with the database file name
      when {
          databaseConfig.inMemory ->
            Room.inMemoryDatabaseBuilder(context, ResourceDatabase::class.java)
          enableEncryption ->
            Room.databaseBuilder(context, ResourceDatabase::class.java, ENCRYPTED_DATABASE_NAME)
          else ->
            Room.databaseBuilder(context, ResourceDatabase::class.java, UNENCRYPTED_DATABASE_NAME)
        }
        .apply {
          // Provide the SupportSQLiteOpenHelper which enables the encryption.
          if (enableEncryption) {
            openHelperFactory {
              SQLCipherSupportHelper(
                it,
                databaseErrorStrategy = databaseConfig.databaseErrorStrategy,
              ) {
                DatabaseEncryptionKeyProvider.getOrCreatePassphrase(DATABASE_PASSPHRASE_NAME)
              }
            }
          }

          addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
          )
        }
        .build()
  }

  private val resourceDao by lazy {
    db.resourceDao().also {
      it.iParser = iParser
      it.resourceIndexer = resourceIndexer
    }
  }

  private val localChangeDao =
    db.localChangeDao().also {
      it.iParser = iParser
      it.fhirTerser = fhirTerser
    }

  override suspend fun <R : Resource> insert(vararg resource: R): List<String> {
    val logicalIds = mutableListOf<String>()
    db.withTransaction {
      logicalIds.addAll(
        resource.map {
          val timeOfLocalChange = Instant.now()
          val resourceUuid = resourceDao.insertLocalResource(it, timeOfLocalChange)
          localChangeDao.addInsert(it, resourceUuid, timeOfLocalChange)
          it.logicalId
        },
      )
    }
    return logicalIds
  }

  override suspend fun <R : Resource> insertRemote(vararg resource: R) {
    db.withTransaction { resourceDao.insertAllRemote(resource.toList()) }
  }

  override suspend fun update(vararg resources: Resource) {
    db.withTransaction {
      resources.forEach {
        val timeOfLocalChange = Instant.now()
        val oldResourceEntity = selectEntity(it.resourceType, it.logicalId)
        resourceDao.applyLocalUpdate(it, timeOfLocalChange)
        localChangeDao.addUpdate(oldResourceEntity, it, timeOfLocalChange)
      }
    }
  }

  override suspend fun updateVersionIdAndLastUpdated(
    resourceId: String,
    resourceType: ResourceType,
    versionId: String,
    lastUpdated: Instant,
  ) {
    db.withTransaction {
      resourceDao.updateAndIndexRemoteVersionIdAndLastUpdate(
        resourceId,
        resourceType,
        versionId,
        lastUpdated,
      )
      resourceDao.getResourceEntity(resourceId, resourceType)?.let {
        val preSyncResource = iParser.parseResource(it.serializedResource) as Resource
        preSyncResource.meta =
          Meta().apply {
            versionIdElement = IdType(versionId)
            lastUpdatedElement = InstantType(Date.from(lastUpdated))
          }
        resourceDao.updatePayloadPostSync(
          preSyncResource.logicalId,
          preSyncResource.resourceType,
          iParser.encodeResourceToString(preSyncResource),
        )
      }
    }
  }

  override suspend fun updateVersionIdAndLastUpdated(
    resource: Resource,
  ) {
    db.withTransaction {
      resourceDao.updateAndIndexRemoteVersionIdAndLastUpdate(
        resource.logicalId,
        resource.resourceType,
        resource.meta.versionId,
        resource.meta.lastUpdated.toInstant(),
      )
      resourceDao.updatePayloadPostSync(
        resource.logicalId,
        resource.resourceType,
        iParser.encodeResourceToString(resource),
      )
    }
  }

  override suspend fun updateResourcesAndLocalChangesPostSync(
    preSyncResourceId: String,
    postSyncResource: Resource,
  ) {
    val preSyncResource =
      iParser.parseResource(
        selectEntity(postSyncResource.resourceType, preSyncResourceId).serializedResource,
      ) as Resource

    preSyncResource.let {
      db.withTransaction {
        resourceDao.updateResourcePostSync(
          preSyncResourceId,
          postSyncResource,
        )

        updateReferringResources(
          referringResourcesUuids = getResourceUuidsThatRefereceTheGivenResource(it),
          oldResource = it,
          updatedResource = postSyncResource,
        )

        localChangeDao.updateReferencesInLocalChange(
          oldResource = it,
          updatedResource = postSyncResource,
        )
      }
    }
  }

  override suspend fun updateResourcesPostSync(
    preSyncResourceId: String,
    postSyncResourceID: String,
    resourceType: ResourceType,
    postSyncResourceVersionId: String,
    postSyncResourceLastUpdated: Instant,
    dependentResources: List<UUID>,
  ) {
    db.withTransaction {
      resourceDao.updateResourceAndIndexPostSync(
        preSyncResourceId,
        postSyncResourceID,
        resourceType,
        postSyncResourceVersionId,
        postSyncResourceLastUpdated,
      )
      updateResourceReferencesPostSync(
        preSyncResourceId,
        postSyncResourceID,
        resourceType,
        dependentResources,
      )
    }
  }

  override suspend fun select(type: ResourceType, id: String): Resource {
    return db.withTransaction {
      resourceDao.getResource(resourceId = id, resourceType = type)?.let {
        iParser.parseResource(it)
      }
        ?: throw ResourceNotFoundException(type.name, id)
    } as Resource
  }

  override suspend fun insertSyncedResources(resources: List<Resource>) {
    db.withTransaction { insertRemote(*resources.toTypedArray()) }
  }

  override suspend fun delete(type: ResourceType, id: String) {
    db.withTransaction {
      resourceDao.getResourceEntity(id, type)?.let {
        val rowsDeleted = resourceDao.deleteResource(resourceId = id, resourceType = type)
        if (rowsDeleted > 0) {
          localChangeDao.addDelete(
            resourceId = id,
            resourceType = type,
            resourceUuid = it.resourceUuid,
            remoteVersionId = it.versionId,
          )
        }
      }
    }
  }

  override suspend fun <R : Resource> search(
    query: SearchQuery,
  ): List<ResourceWithUUID<R>> {
    return db.withTransaction {
      resourceDao
        .getResources(SimpleSQLiteQuery(query.query, query.args.toTypedArray()))
        .map { ResourceWithUUID(it.uuid, iParser.parseResource(it.serializedResource) as R) }
        .distinctBy { it.uuid }
    }
  }

  override suspend fun searchForwardReferencedResources(
    query: SearchQuery,
  ): List<ForwardIncludeSearchResult> {
    return db.withTransaction {
      resourceDao
        .getForwardReferencedResources(SimpleSQLiteQuery(query.query, query.args.toTypedArray()))
        .map {
          ForwardIncludeSearchResult(
            it.matchingIndex,
            it.baseResourceUUID,
            iParser.parseResource(it.serializedResource) as Resource,
          )
        }
    }
  }

  override suspend fun searchReverseReferencedResources(
    query: SearchQuery,
  ): List<ReverseIncludeSearchResult> {
    return db.withTransaction {
      resourceDao
        .getReverseReferencedResources(SimpleSQLiteQuery(query.query, query.args.toTypedArray()))
        .map {
          ReverseIncludeSearchResult(
            it.matchingIndex,
            it.baseResourceTypeAndId,
            iParser.parseResource(it.serializedResource) as Resource,
          )
        }
    }
  }

  override suspend fun count(query: SearchQuery): Long {
    return db.withTransaction {
      resourceDao.countResources(SimpleSQLiteQuery(query.query, query.args.toTypedArray()))
    }
  }

  override suspend fun getAllLocalChanges(): List<LocalChange> {
    return db.withTransaction { localChangeDao.getAllLocalChanges().map { it.toLocalChange() } }
  }

  override suspend fun getLocalChangesCount(): Int {
    return db.withTransaction { localChangeDao.getLocalChangesCount() }
  }

  override suspend fun getAllChangesForEarliestChangedResource(): List<LocalChange> {
    return localChangeDao.getAllChangesForEarliestChangedResource().map { it.toLocalChange() }
  }

  override suspend fun deleteUpdates(token: LocalChangeToken) {
    db.withTransaction { localChangeDao.discardLocalChanges(token) }
  }

  override suspend fun selectEntity(type: ResourceType, id: String): ResourceEntity {
    return db.withTransaction {
      resourceDao.getResourceEntity(resourceId = id, resourceType = type)
        ?: throw ResourceNotFoundException(type.name, id)
    }
  }

  override suspend fun withTransaction(block: suspend () -> Unit) {
    db.withTransaction(block)
  }

  override suspend fun deleteUpdates(resources: List<Resource>) {
    localChangeDao.discardLocalChanges(resources)
  }

  override suspend fun updateResourceAndReferences(
    currentResourceId: String,
    updatedResource: Resource,
  ) {
    db.withTransaction {
      val currentResourceEntity = selectEntity(updatedResource.resourceType, currentResourceId)
      val oldResource = iParser.parseResource(currentResourceEntity.serializedResource) as Resource
      val resourceUuid = currentResourceEntity.resourceUuid
      updateResourceEntity(resourceUuid, updatedResource)

      val uuidsOfReferringResources =
        updateLocalChangeResourceIdAndReferences(
          resourceUuid = resourceUuid,
          oldResource = oldResource,
          updatedResource = updatedResource,
        )

      updateReferringResources(
        referringResourcesUuids = uuidsOfReferringResources,
        oldResource = oldResource,
        updatedResource = updatedResource,
      )
    }
  }

  /**
   * Calls the [ResourceDao] to update the [ResourceEntity] associated with this resource. The
   * function updates the resource and resourceId of the [ResourceEntity]
   */
  private suspend fun updateResourceEntity(resourceUuid: UUID, updatedResource: Resource) =
    resourceDao.updateResourceWithUuid(resourceUuid, updatedResource)

  /**
   * Update the [LocalChange]s to reflect the change in the resource ID. This primarily includes
   * modifying the [LocalChange.resourceId] for the changes of the affected resource. Also, update
   * any references in the [LocalChange] which refer to the affected resource.
   *
   * The function returns a [List<[UUID]>] which corresponds to the [ResourceEntity.resourceUuid]
   * which contain references to the affected resource.
   */
  private suspend fun updateLocalChangeResourceIdAndReferences(
    resourceUuid: UUID,
    oldResource: Resource,
    updatedResource: Resource,
  ) =
    localChangeDao.updateResourceIdAndReferences(
      resourceUuid = resourceUuid,
      oldResource = oldResource,
      updatedResource = updatedResource,
    )

  /**
   * Update all [Resource] and their corresponding [ResourceEntity] which refer to the affected
   * resource. The update of the references in the [Resource] is also expected to reflect in the
   * [ReferenceIndex] i.e. the references used for search operations should also get updated to
   * reflect the references with the new resource ID of the referred resource.
   */
  private suspend fun updateReferringResources(
    referringResourcesUuids: List<UUID>,
    oldResource: Resource,
    updatedResource: Resource,
  ) {
    val oldReferenceValue = "${oldResource.resourceType.name}/${oldResource.logicalId}"
    val updatedReferenceValue = "${updatedResource.resourceType.name}/${updatedResource.logicalId}"
    updateReferringResources(referringResourcesUuids, oldReferenceValue, updatedReferenceValue)
  }

  private suspend fun updateReferringResources(
    referringResourcesUuids: List<UUID>,
    preSyncReferenceValue: String,
    postSyncReferenceValue: String,
  ) {
    referringResourcesUuids.forEach { resourceUuid ->
      resourceDao.getResourceEntity(resourceUuid)?.let {
        val referringResource = iParser.parseResource(it.serializedResource) as Resource
        val updatedReferringResource =
          addUpdatedReferenceToResource(
            iParser,
            referringResource,
            preSyncReferenceValue,
            postSyncReferenceValue,
          )
        resourceDao.updateResourceWithUuid(resourceUuid, updatedReferringResource)
      }
    }
  }

  override fun close() {
    db.close()
  }

  override suspend fun clearDatabase() {
    db.clearAllTables()
  }

  override suspend fun getLocalChanges(type: ResourceType, id: String): List<LocalChange> {
    return db.withTransaction {
      localChangeDao.getLocalChanges(resourceType = type, resourceId = id).map {
        it.toLocalChange()
      }
    }
  }

  override suspend fun getLocalChanges(resourceUuid: UUID): List<LocalChange> {
    return db.withTransaction {
      localChangeDao.getLocalChanges(resourceUuid = resourceUuid).map { it.toLocalChange() }
    }
  }

  override suspend fun purge(type: ResourceType, id: String, forcePurge: Boolean) {
    db.withTransaction {
      // To check resource is present in DB else throw ResourceNotFoundException()
      selectEntity(type, id)
      val localChangeEntityList = localChangeDao.getLocalChanges(type, id)
      // If local change is not available simply delete resource
      if (localChangeEntityList.isEmpty()) {
        resourceDao.deleteResource(resourceId = id, resourceType = type)
      } else {
        // local change is available with FORCE_PURGE the delete resource and discard changes from
        // localChangeEntity table
        if (forcePurge) {
          resourceDao.deleteResource(resourceId = id, resourceType = type)
          localChangeDao.discardLocalChanges(
            token = LocalChangeToken(localChangeEntityList.map { it.id }),
          )
        } else {
          // local change is available but FORCE_PURGE = false then throw exception
          throw IllegalStateException(
            "Resource with type $type and id $id has local changes, either sync with server or FORCE_PURGE required",
          )
        }
      }
    }
  }

  override suspend fun getLocalChangeResourceReferences(
    localChangeIds: List<Long>,
  ): List<LocalChangeResourceReference> {
    return localChangeDao.getReferencesForLocalChanges(localChangeIds).map {
      LocalChangeResourceReference(
        it.localChangeId,
        it.resourceReferenceValue,
        it.resourceReferencePath,
      )
    }
  }

  override suspend fun getResourceUuidsThatReferenceTheGivenResource(
    preSyncResourceId: String,
    resourceType: ResourceType,
  ): List<UUID> {
    return db.withTransaction {
      val preSyncResource =
        iParser.parseResource(
          selectEntity(resourceType, preSyncResourceId).serializedResource,
        ) as Resource
      getResourceUuidsThatRefereceTheGivenResource(preSyncResource)
    }
  }

  /**
   * Retrieves a list of UUIDs for resources that reference [preSyncResource]. [preSyncResource] can
   * be referenced as the reference value in other resources, returning those resource UUIDs.
   * Essentially, [LocalChangeResourceReference] contains
   * [LocalChangeResourceReference.resourceReferenceValue] and
   * [LocalChangeResourceReference.localChangeId]. [LocalChange] contains UUIDs for every resource.
   *
   * @param preSyncResource The resource that is being referenced.
   * @return A list of UUIDs of resources that reference [preSyncResource].
   */
  private suspend fun getResourceUuidsThatRefereceTheGivenResource(
    preSyncResource: Resource,
  ): List<UUID> {
    return db.withTransaction {
      val preSyncReference = "${preSyncResource.resourceType.name}/${preSyncResource.logicalId}"
      val localChangeIds =
        localChangeDao
          .getLocalChangeReferencesWithValue(preSyncReference)
          .map { it.localChangeId }
          .distinct()
      val localChanges = localChangeDao.getLocalChanges(localChangeIds)
      localChanges.map { it.resourceUuid }.distinct()
    }
  }

  private suspend fun updateResourceReferencesPostSync(
    preSyncResourceId: String,
    postSyncResourceID: String,
    resourceType: ResourceType,
    referringResourcesUuids: List<UUID>,
  ) {
    val preSyncReferenceValue = "$resourceType/$preSyncResourceId"
    val postSyncReferenceValue = "$resourceType/$postSyncResourceID"
    updateReferringResources(
      referringResourcesUuids = referringResourcesUuids,
      preSyncReferenceValue = preSyncReferenceValue,
      postSyncReferenceValue = postSyncReferenceValue,
    )
  }

  companion object {
    /**
     * The name for unencrypted database.
     *
     * We use a separate name for unencrypted & encrypted database in order to detect any
     * unintentional switching of database encryption across releases. When this happens, we throw
     * [IllegalStateException] so that app developers have a chance to fix the issue.
     */
    const val UNENCRYPTED_DATABASE_NAME = "resources.db"

    /**
     * The name for encrypted database.
     *
     * See [UNENCRYPTED_DATABASE_NAME] for the reason we use a separate name.
     */
    const val ENCRYPTED_DATABASE_NAME = "resources_encrypted.db"

    @VisibleForTesting const val DATABASE_PASSPHRASE_NAME = "fhirEngineDbPassphrase"
  }
}

data class DatabaseConfig(
  val inMemory: Boolean,
  val enableEncryption: Boolean,
  val databaseErrorStrategy: DatabaseErrorStrategy,
)
