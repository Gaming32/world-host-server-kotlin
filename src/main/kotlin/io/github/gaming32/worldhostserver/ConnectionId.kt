package io.github.gaming32.worldhostserver

@JvmInline
value class ConnectionId(val id: Long) {
    companion object {
        const val MAX_CONNECTION_IDS = 1L shl 42

        val WORDS_FOR_CID = WorldHostServer::class.java
            .getResourceAsStream("/16k.txt")
            ?.bufferedReader()
            ?.lineSequence()
            ?.filter { !it.startsWith("//") }
            ?.toList()
            ?: throw IllegalStateException("Unable to find 16k.txt")

        val WORDS_FOR_CID_INVERSE = sortedMapOf<String, Int>(String.CASE_INSENSITIVE_ORDER).apply {
            WORDS_FOR_CID.forEachIndexed { i, word ->
                put(word, i)
            }
        }

//        fun random() = ConnectionId(SecureRandom().nextLong(MAX_CONNECTION_IDS))

        fun String.toConnectionId(): ConnectionId {
            val words = split("-")
            if (words.size != 3) {
                throw IllegalArgumentException("Three words are expected. Found ${words.size}.")
            }
            var result = 0L
            var shift = 0
            for (word in words) {
                val part = WORDS_FOR_CID_INVERSE[word]
                    ?: throw IllegalArgumentException("Unknown word $word")
                result = result or (part.toLong() shl shift)
                shift += 14
            }
            return ConnectionId(result)
        }
    }

    init {
        require(id in 0..<MAX_CONNECTION_IDS) { "Invalid connection ID $id" }
    }

    override fun toString(): String {
        val first = (id and 0x3fffL).toInt()
        val second = (id ushr 14).toInt() and 0x3fff
        val third = (id ushr 28).toInt() and 0x3fff
        return WORDS_FOR_CID[first] + '-' +
                WORDS_FOR_CID[second] + '-' +
                WORDS_FOR_CID[third]
    }
}
