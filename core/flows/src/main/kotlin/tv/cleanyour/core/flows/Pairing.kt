package tv.cleanyour.core.flows

import tv.cleanyour.core.discovery.FoundTv

// In-app ADB pairing (0.2): the TV shows a 6-digit code on its wireless-debugging screen and
// this phone completes the PAKE over TLS, so no computer is needed. The runner is a seam the
// app supplies (:android:platform's AdbPairingTls through the app's AppPairingRunner); null
// means the build cannot pair in-app and the PairingNeeded screen keeps the computer steps.
// The outcome never carries the code, the keying material or any address.
enum class PairingPhase { IDLE, RUNNING, PAIRED, FAILED }

enum class PairingFailure { WRONG_CODE, UNREACHABLE, TLS_FAILED, TIMEOUT, UNKNOWN }

sealed interface PairingOutcome {
    data class Paired(val guid: String?) : PairingOutcome
    data class Failed(val failure: PairingFailure) : PairingOutcome
}

interface PairingRunner {
    suspend fun pair(tv: FoundTv, code: String): PairingOutcome
}
