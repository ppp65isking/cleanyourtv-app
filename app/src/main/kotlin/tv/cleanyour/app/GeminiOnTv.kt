package tv.cleanyour.app

import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.probe.ProbeScripts
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.SessionManager

// The Gemini button, TV-first: with a TV session live, the button is the remote's Assistant
// button — `input keyevent KEYCODE_ASSIST` (219) opens the Assistant / Gemini overlay on the
// TV screen. A TV that ignores the keyevent (the command itself fails) gets the fallback,
// `am start -a android.intent.action.ASSIST`. Returns true when either fired on a connected
// TV, false when no TV is connected (or neither command worked), so the caller falls back
// to the phone launcher. The first connected TV wins; the app holds one live session at a
// time in practice. Nothing is sent when there is no session: the keyevent never goes to a
// TV that is not Connected.
suspend fun openGeminiOnTv(sessions: SessionManager): Boolean {
    val session = sessions.states.value.entries
        .firstOrNull { it.value is ConnectionState.Connected }
        ?.let { sessions.existing(it.key) } ?: return false
    val spec = ExecSpec(timeoutMs = 10_000, idleTimeoutMs = 5_000)
    val keyOk = runCatching { session.exec(ProbeScripts.GEMINI_ASSIST_KEY, spec).exit == 0 }.getOrDefault(false)
    if (keyOk) return true
    return runCatching { session.exec(ProbeScripts.GEMINI_ASSIST_INTENT, spec).exit == 0 }.getOrDefault(false)
}
