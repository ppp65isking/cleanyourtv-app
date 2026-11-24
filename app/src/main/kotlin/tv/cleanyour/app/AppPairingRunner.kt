package tv.cleanyour.app

import tv.cleanyour.adb.pairing.PairingError
import tv.cleanyour.adb.pairing.PairingResult
import tv.cleanyour.android.platform.AdbPairingTls
import tv.cleanyour.android.platform.KeystoreAdbSigner
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.flows.PairingFailure
import tv.cleanyour.core.flows.PairingOutcome
import tv.cleanyour.core.flows.PairingRunner

// In-app pairing (0.2): the TV's pairing port from the discovery record, the phone's ADB key
// (through the deferred signer) and the TLS pairing client. The protocol's errors map to the
// flow's, so the UI never sees the wire's words; the code itself never leaves this call.
// `log` carries the pairing's unclassified failures to the app log.
class AppPairingRunner(private val signer: DeferredAdbSigner, private val log: (String) -> Unit = {}) : PairingRunner {
    override suspend fun pair(tv: FoundTv, code: String): PairingOutcome {
        val port = tv.pairingService?.port ?: return PairingOutcome.Failed(PairingFailure.UNKNOWN)
        val key = signer.get() as? KeystoreAdbSigner ?: return PairingOutcome.Failed(PairingFailure.UNKNOWN)
        val identity = runCatching { key.tlsIdentity() }.getOrNull()
            ?: return PairingOutcome.Failed(PairingFailure.TLS_FAILED)
        return when (val result = AdbPairingTls.pair(tv.host, port, code, key.publicKeyLine, identity, log = log)) {
            is PairingResult.Paired -> PairingOutcome.Paired(result.guid)
            is PairingResult.Failed -> PairingOutcome.Failed(when (result.error) {
                PairingError.WRONG_CODE -> PairingFailure.WRONG_CODE
                PairingError.UNREACHABLE -> PairingFailure.UNREACHABLE
                PairingError.TLS_FAILED -> PairingFailure.TLS_FAILED
                PairingError.TIMEOUT -> PairingFailure.TIMEOUT
                PairingError.PROTOCOL, PairingError.UNKNOWN -> PairingFailure.UNKNOWN
            })
        }
    }
}
