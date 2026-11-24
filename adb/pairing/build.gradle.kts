plugins { id("cleanyourtv.jvm") }

// 0.2 workstream (D9): in-app ADB pairing (SPAKE2 over the TV's pairing port), so a TV that only
// offers wireless debugging can be paired from this phone with no computer in between. Pure
// Kotlin: the protocol runs over streams the caller supplies (the TLS layer lives in
// :android:platform), and the curve arithmetic is BigInteger, so no new dependency.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
