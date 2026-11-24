package tv.cleanyour.core.flows

import tv.cleanyour.core.model.TvId

// Casting (0.2): what a third-party app can honestly do. The TV's Cast receiver belongs to
// Google and cannot be replaced, and there is no screen-mirroring receiver a third-party app
// can run — so this app does the two real things: open an app on the TV over DIAL (the
// protocol every Cast TV already speaks on port 8008), and hand screen casting to Android's
// own Cast settings. A TV with DIAL off, or one that is not a Cast TV, simply does not answer,
// and the launch says so instead of pretending it worked.
enum class CastApp { YOUTUBE, NETFLIX }

data class CastTv(val id: TvId, val name: String, val host: String)

interface CastClient {
    suspend fun launchApp(host: String, app: CastApp): Boolean
}
