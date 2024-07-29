package io.github.gaming32.worldhostserver

const val PUNCH_REQUEST_EXPIRY = 10

data class ActivePunchRequest(
    val cookie: PunchCookie,
    val sourceClient: ConnectionId,
    val targetClient: ConnectionId
)
