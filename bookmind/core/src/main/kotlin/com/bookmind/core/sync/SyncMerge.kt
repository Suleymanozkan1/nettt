package com.bookmind.core.sync

/**
 * Conflict policy for local-first sync. Mutable rows use last-writer-wins on `updatedAt`; append-only rows
 * (review logs, quiz answers, XP events) are merged as a union so no learning history is ever lost.
 * Tombstones (deletedAt) win over edits made before the deletion.
 */
object SyncMerge {
    enum class Decision { KEEP_LOCAL, TAKE_REMOTE }

    fun resolve(localUpdatedAt: Long?, localDeletedAt: Long?, remoteUpdatedAt: Long, remoteDeletedAt: Long?): Decision {
        if (localUpdatedAt == null) return Decision.TAKE_REMOTE
        val localStamp = maxOf(localUpdatedAt, localDeletedAt ?: 0)
        val remoteStamp = maxOf(remoteUpdatedAt, remoteDeletedAt ?: 0)
        return when {
            remoteStamp > localStamp -> Decision.TAKE_REMOTE
            remoteStamp < localStamp -> Decision.KEEP_LOCAL
            // Same timestamp: a deletion wins, otherwise keep local (it is already pushed or queued).
            remoteDeletedAt != null && localDeletedAt == null -> Decision.TAKE_REMOTE
            else -> Decision.KEEP_LOCAL
        }
    }

    /** Review state conflicts: the state that has seen more reviews (then the later review) is the truth. */
    fun resolveReviewState(localCount: Int, localLast: Long?, remoteCount: Int, remoteLast: Long?): Decision = when {
        remoteCount > localCount -> Decision.TAKE_REMOTE
        remoteCount < localCount -> Decision.KEEP_LOCAL
        (remoteLast ?: 0) > (localLast ?: 0) -> Decision.TAKE_REMOTE
        else -> Decision.KEEP_LOCAL
    }
}
