package ani.sanin.connections

import ani.sanin.R
import ani.sanin.Refresh
import ani.sanin.connections.anilist.Anilist
import ani.sanin.connections.mal.MAL
import ani.sanin.currContext
import ani.sanin.media.Media
import ani.sanin.media.emptyMedia
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import android.content.Intent
import ani.sanin.App
import ani.sanin.notifications.subscription.NotificationPopupActivity
import ani.sanin.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun updateProgress(media: Media, number: String) {
    val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
    val rescueMode: Boolean = PrefManager.getVal(PrefName.RescueMode)
    val autoSync: Boolean = PrefManager.getVal(PrefName.AutoSyncAniList)
    val progressInt = ani.sanin.media.MediaNameAdapter.findEpisodeNumber(number)?.toInt()
        ?: number.toFloatOrNull()?.toInt()
        ?: return
    if (!incognito) {
        if (rescueMode) {

            val a = progressInt
            if (a > (media.userProgress ?: -1)) {
                val status = if (media.userStatus == "REPEATING") media.userStatus!! else "CURRENT"
                val pending = PendingProgressUpdate(
                    mediaId = media.id,
                    idMAL = media.idMAL,
                    isAnime = media.anime != null,
                    progress = a,
                    status = status,
                )
                val existing: List<PendingProgressUpdate> =
                    PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
                val updated = existing.filterNot { it.mediaId == media.id } + pending
                PrefManager.setVal(PrefName.PendingProgressUpdates, updated)
                CoroutineScope(Dispatchers.IO).launch {
                    MAL.query.editList(
                        media.idMAL,
                        media.anime != null,
                        a, null, status
                    )
                    toast(currContext()?.getString(R.string.setting_progress, a))
                }
            }
            media.userProgress = progressInt
            Refresh.all()
        } else if (Anilist.userid != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val a = progressInt
                if (a > (media.userProgress ?: -1)) {
                    if (autoSync) {
                        Anilist.mutation.editList(
                            media.id,
                            a,
                            status = if (media.userStatus == "REPEATING") media.userStatus else "CURRENT"
                        )
                    }
                    MAL.query.editList(
                        media.idMAL,
                        media.anime != null,
                        a, null,
                        if (media.userStatus == "REPEATING") media.userStatus!! else "CURRENT"
                    )
                    if (PrefManager.getVal<Boolean>(PrefName.ListStatusNotification)) {
                        val newStatus = if (media.userStatus == "REPEATING") "REPEATING" else "CURRENT"
                        App.currentActivity()?.let { activity ->
                            val intent = Intent(activity, NotificationPopupActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
                                putExtra("title", statusNotificationPhrase(media, newStatus))
                                putExtra("text", "")
                                putExtra("coverUrl", media.cover)
                            }
                            activity.startActivity(intent)
                        }
                    }
                    toast(currContext()?.getString(R.string.setting_progress, a))
                }
                media.userProgress = a
                Refresh.all()
            }
        } else {
            toast(currContext()?.getString(R.string.login_anilist_account))
        }
    } else {
        toast("Sneaky sneaky :3")
    }
}

fun syncPendingProgressUpdates() {

    if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) return
    if (Anilist.userid == null) return
    val pending: List<PendingProgressUpdate> = try {
        PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
    } catch (e: Exception) {
        PrefManager.setVal(PrefName.PendingProgressUpdates, listOf<PendingProgressUpdate>())
        return
    }
    if (pending.isEmpty()) return
    toast(currContext()?.getString(R.string.syncing_progress, pending.size))
    CoroutineScope(Dispatchers.IO).launch {
        val remaining = pending.toMutableList()
        for (update in pending) {
            try {

                val anilistId: Int = if (update.idMAL != null && update.mediaId == update.idMAL) {
                    val type = if (update.isAnime) "ANIME" else "MANGA"
                    val resolved = Anilist.query.getMedia(update.idMAL, mal = true, type = type)?.id
                    if (resolved == null) {
                        if (Anilist.anilistDisabledSignal) break
                        remaining.remove(update)
                        continue
                    }
                    resolved
                } else {
                    update.mediaId
                }
                Anilist.mutation.editList(
                    mediaID = anilistId,
                    progress = update.progress,
                    score = update.score,
                    repeat = update.rewatch,
                    notes = update.notes,
                    status = update.status,
                    private = update.isPrivate,
                    startedAt = update.startDate,
                    completedAt = update.endDate,
                    customList = update.customLists,
                )
                if (!Anilist.anilistDisabledSignal) {
                    remaining.remove(update)
                } else {
                    break
                }
            } catch (_: Exception) {
            }
        }
        PrefManager.setVal(PrefName.PendingProgressUpdates, remaining)
        if (remaining.isEmpty()) {
            toast(currContext()?.getString(R.string.sync_complete))
        } else {
            toast(currContext()?.getString(R.string.sync_partial, remaining.size))
        }
        Refresh.all()
    }
}

fun statusNotificationPhrase(media: Media, newStatus: String): String {
    val name = Anilist.username ?: "User"
    val title = media.userPreferredName.ifEmpty { media.nameRomaji.ifEmpty { media.name ?: "Unknown" } }
    val isAnime = media.anime != null
    return when (newStatus) {
        "CURRENT" -> if (isAnime) "$name is watching $title" else "$name is reading $title"
        "PLANNING" -> if (isAnime) "$name is planning to watch $title" else "$name is planning to read $title"
        "COMPLETED" -> "$name has completed $title"
        "PAUSED" -> "$name paused $title"
        "DROPPED" -> "$name dropped $title"
        "REPEATING" -> if (isAnime) "$name is rewatching $title" else "$name is rereading $title"
        else -> "$name updated $title"
    }
}

fun syncPendingDeletions() {
    if (PrefManager.getVal<Boolean>(PrefName.RescueMode)) return
    if (Anilist.userid == null) return
    val pending: List<PendingDeletion> = try {
        PrefManager.getVal(PrefName.PendingDeletions, listOf())
    } catch (e: Exception) {
        PrefManager.setVal(PrefName.PendingDeletions, listOf<PendingDeletion>())
        return
    }
    if (pending.isEmpty()) return
    toast(currContext()?.getString(R.string.syncing_deletions, pending.size))
    CoroutineScope(Dispatchers.IO).launch {
        val remaining = pending.toMutableList()
        for (deletion in pending) {
            if (Anilist.anilistDisabledSignal) break
            try {
                val anilistId: Int = if (deletion.idMAL != null && deletion.mediaId == deletion.idMAL) {
                    val type = if (deletion.isAnime) "ANIME" else "MANGA"
                    val resolved = Anilist.query.getMedia(deletion.idMAL, mal = true, type = type)?.id
                    if (resolved == null) {
                        if (Anilist.anilistDisabledSignal) break
                        remaining.remove(deletion)
                        continue
                    }
                    resolved
                } else {
                    deletion.mediaId
                }
                val fakeMedia = emptyMedia().copy(id = anilistId, idMAL = deletion.idMAL)
                val listId = Anilist.query.userMediaDetails(fakeMedia).userListId
                if (listId != null) {
                    Anilist.mutation.deleteList(listId)
                }
                val removeList = PrefManager.getCustomVal("removeList", setOf<Int>())
                PrefManager.setCustomVal("removeList", removeList.minus(anilistId))
                val progressUpdates: List<PendingProgressUpdate> =
                    PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
                val filteredUpdates = progressUpdates.filterNot { update ->
                    update.mediaId == deletion.mediaId ||
                        (deletion.idMAL != null && update.idMAL == deletion.idMAL && update.mediaId == update.idMAL)
                }
                if (filteredUpdates.size != progressUpdates.size) {
                    PrefManager.setVal(PrefName.PendingProgressUpdates, filteredUpdates)
                }
                if (!Anilist.anilistDisabledSignal) {
                    remaining.remove(deletion)
                }
            } catch (_: Exception) {
            }
        }
        PrefManager.setVal(PrefName.PendingDeletions, remaining)
        if (remaining.isEmpty()) {
            toast(currContext()?.getString(R.string.sync_complete))
        } else {
            toast(currContext()?.getString(R.string.sync_partial, remaining.size))
        }

        Refresh.all()
    }
}