package app.eve.data

import kotlinx.serialization.json.Json

/**
 * THE wire Json config for every EVE surface — phone HTTP/WS (ApiClient/StreamClient), and the
 * phone<->watch Data Layer payloads. One definition so the phone and the watch can never drift
 * apart on how the same DTO bytes decode.
 *
 * ignoreUnknownKeys/isLenient: schema drift on the server must never crash a client decode.
 * explicitNulls=false: absent-vs-null matters to the API (e.g. a null speaker -> owner page).
 */
val EveWireJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}
