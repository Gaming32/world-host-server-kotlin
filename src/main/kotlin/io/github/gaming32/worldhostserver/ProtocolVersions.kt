package io.github.gaming32.worldhostserver

object ProtocolVersions {
    const val CURRENT = 7
    const val STABLE = 6
    val SUPPORTED = 2..CURRENT
    val TO_NAME = mapOf(
        2 to "0.3.2",
        3 to "0.3.4",
        4 to "0.4.3",
        5 to "0.4.4",
        6 to "0.4.14",
        7 to "0.4.15",
    )

    const val NEW_AUTH_PROTOCOL = 6
    const val ENCRYPTED_PROTOCOL = 7

    init {
        check(SUPPORTED.all(TO_NAME::containsKey)) {
            "TO_NAME missing the following keys: ${(SUPPORTED.toSet() - TO_NAME.keys).joinToString()}"
        }
    }
}