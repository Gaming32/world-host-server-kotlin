package io.github.gaming32.worldhostserver

import java.util.*

const val PORT_LOOKUP_EXPIRY = 10

data class ActivePortLookup(val lookupId: UUID, val sourceClient: ConnectionId)
