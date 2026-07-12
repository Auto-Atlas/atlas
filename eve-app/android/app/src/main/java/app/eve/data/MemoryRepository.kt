package app.eve.data

import app.eve.data.models.MemoryItem

open class MemoryRepository(private val api: ApiClient) {

    /** Null speaker -> the owner page (the owner's real memory, the boot pack). Raw bullets (back-compat). */
    open suspend fun facts(speaker: String? = null): ApiResult<List<String>> =
        api.getMemory(speaker).map { it.facts }

    /** Structured, newest-first facts Atlas knows about the owner — what the Memory tab renders. */
    open suspend fun items(speaker: String? = null): ApiResult<List<MemoryItem>> =
        api.getMemory(speaker).map { it.items }

    open suspend fun remember(speaker: String? = null, fact: String): ApiResult<String> =
        api.addMemory(speaker, fact).map { it.remembered }
}
