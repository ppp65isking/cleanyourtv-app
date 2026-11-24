package tv.cleanyour.core.flows

import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.store.DocumentStore
import java.util.UUID

// Profiles (0.2): a named profile with its own saved TV and interface style, so one phone can
// switch between the living-room TV and the bedroom TV (or between two people's setups) from
// App settings. A profile with no TV is a style-only profile; a profile with no style keeps
// the current one when switched to. Stored as lines in profiles.txt, the same key=value
// shape as the other small stores (no new dependency); the active profile id lives in
// active-profile.txt. A profile whose TV was forgotten keeps its id but navigates nowhere.
data class UserProfile(
    val id: String,
    val name: String,
    val tvId: TvId?,
    val tvName: String?,
    val uiStyle: UiStyle?,
)

class ProfileStore(private val store: DocumentStore) {
    private var loaded: List<UserProfile>? = null
    private var activeId: String? = null
    private var activeLoaded = false

    suspend fun all(): List<UserProfile> = loaded ?: read().also { loaded = it }

    suspend fun activeId(): String? {
        if (!activeLoaded) {
            activeId = store.read(ACTIVE_PATH) { it.decodeToString().trim() }.takeIf { !it.isNullOrBlank() }
            activeLoaded = true
        }
        return activeId
    }

    suspend fun save(profile: UserProfile) {
        val list = all().filterNot { it.id == profile.id } + profile
        write(list)
    }

    suspend fun delete(id: String) {
        write(all().filterNot { it.id == id })
        if (activeId() == id) setActive(null)
    }

    suspend fun setActive(id: String?) {
        activeId = id
        if (id == null) store.delete(ACTIVE_PATH) else store.write(ACTIVE_PATH, id.encodeToByteArray())
    }

    private suspend fun read(): List<UserProfile> = store.read(PATH) { bytes ->
        bytes.decodeToString().lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 5) null else UserProfile(
                id = parts[0], name = parts[1],
                tvId = parts[2].takeIf { it.isNotEmpty() }?.let { TvId(it) },
                tvName = parts[3].takeIf { it.isNotEmpty() },
                uiStyle = parts[4].takeIf { it.isNotEmpty() }?.let { runCatching { UiStyle.valueOf(it) }.getOrNull() },
            )
        }
    } ?: emptyList()

    private suspend fun write(list: List<UserProfile>) {
        loaded = list.sortedBy { it.name.lowercase() }
        val clean: (String) -> String = { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
        store.write(PATH, loaded!!.joinToString("") {
            "${it.id}\t${clean(it.name)}\t${it.tvId?.value.orEmpty()}\t${clean(it.tvName.orEmpty())}\t${it.uiStyle?.name.orEmpty()}\n"
        }.encodeToByteArray())
    }

    companion object {
        const val PATH = "profiles.txt"
        const val ACTIVE_PATH = "active-profile.txt"
        fun newId(): String = UUID.randomUUID().toString().take(8)
    }
}
