package app.eve.data

/**
 * Honest, exhaustive error mapping for the approval API. Every failure mode the backend or
 * transport can produce maps to exactly one of these — no silent swallowing, no false success.
 */
sealed interface ApiError {
    /** No base URL / token configured yet — the app should route to the connect screen. */
    data object NotConfigured : ApiError

    /** Could not reach EVE (off the tailnet, DNS, connection refused, timeout). */
    data class Offline(val cause: String) : ApiError

    /** 401 — bad/expired app token. */
    data object Unauthorized : ApiError

    /** 404 — the approval (or resource) no longer exists. */
    data object NotFound : ApiError

    /**
     * 409 — the approval is not available: consumed, denied, expired, or a tier/risk mismatch.
     * Surfaces in the UI as "Already handled".
     */
    data object AlreadyResolved : ApiError

    /** Any other non-2xx HTTP status. */
    data class Http(val status: Int, val detail: String) : ApiError

    /** Body did not decode against the model (schema drift / corrupt payload). */
    data class Decode(val message: String) : ApiError

    /** Anything else, captured honestly. */
    data class Unknown(val message: String) : ApiError
}

/** A Result type that never throws into callers and never invents success. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.Err -> this
}
