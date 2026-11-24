package tv.cleanyour.app

import tv.cleanyour.android.platform.DialClient
import tv.cleanyour.core.flows.CastApp
import tv.cleanyour.core.flows.CastClient

// The CastClient over DIAL: YouTube and Netflix are the two apps every Cast TV offers under
// those names. Any other name is one the TV does not know, so the launch fails and the caller
// says so. (The DIAL client itself lives in :android:platform; this adapter is the only part
// that knows the flow's CastApp.)
class AndroidCastClient : CastClient {
    override suspend fun launchApp(host: String, app: CastApp): Boolean {
        val name = when (app) {
            CastApp.YOUTUBE -> "YouTube"
            CastApp.NETFLIX -> "Netflix"
        }
        return DialClient.launch(host, name)
    }
}
