package tv.cleanyour.ui.design.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf

// The Gemini button (0.2): there is no in-app Gemini API — no key is invented, and the app has
// no Gemini account of its own — so the button opens Gemini where the phone already has it:
// the Gemini app when installed, else the phone's assistant (ACTION_ASSIST, which is Gemini
// on phones where Gemini is the assistant), else gemini.google.com in the browser. The button
// is a launcher, not a conversation inside this app; the Gemini app is where the talking
// happens. Returns true when something opened.
//
// TV first: when a TV session is live the app provides LocalGeminiOnTv, a handler that fires
// Gemini ON THE TV screen (KEYCODE_ASSIST, the remote's Assistant button) and returns true
// when it did. The top bar tries that first and only falls back to this phone launcher when
// there is no TV to fire it on. The design module owns only the hook's type; the app module
// owns the session behind it, so this module still depends on nothing but :core:flows.
val LocalGeminiOnTv = staticCompositionLocalOf<(suspend () -> Boolean)?> { null }
fun openGemini(context: Context): Boolean {
    val pm = context.packageManager
    val gemini = Intent(Intent.ACTION_MAIN).apply { setPackage("com.google.android.apps.bard") }
    if (gemini.resolveActivity(pm) != null) {
        runCatching { context.startActivity(gemini.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }.onSuccess { return true }
    }
    val assist = Intent(Intent.ACTION_ASSIST).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
    if (assist.resolveActivity(pm) != null) {
        runCatching { context.startActivity(assist) }.onSuccess { return true }
    }
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        true
    }.getOrDefault(false)
}
