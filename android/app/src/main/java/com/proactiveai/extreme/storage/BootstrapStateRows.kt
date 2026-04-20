package com.proactiveai.extreme.storage

data class ContactDirectoryRow(
    val contactId: Long,
    val lookupKey: String?,
    val displayName: String,
    val displayNameAlt: String?,
    val photoUri: String?,
    val starred: Boolean,
    val organization: String?,
    val title: String?,
    val note: String?,
    val relationsJson: String,
    val eventsJson: String,
    val groupsJson: String,
    val accountType: String?,
    val accountName: String?,
    val lastUpdatedTs: Long?,
    val lastContactedTs: Long?,
    val timesContacted: Int?,
    val lastSeenScanId: String,
    val contentHash: String,
)

data class ContactIdentityRow(
    val contactId: Long,
    val kind: String,
    val normalizedValue: String,
    val normalizedHash: String,
    val label: String?,
    val isPrimary: Boolean,
)

data class AppInventoryRow(
    val packageName: String,
    val appLabel: String,
    val versionName: String?,
    val versionCode: Long?,
    val firstInstallTime: Long?,
    val lastUpdateTime: Long?,
    val contentHash: String,
)
