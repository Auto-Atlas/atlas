package app.eve.vision

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure capture-routing matrix — the single guard that "phone events never hit the glasses
 * source" and "an explicit glasses request is an honest error, never a phone fallback". No Android
 * runtime.
 */
class CaptureRouterTest {

    // ---- source parsing (wire → enum) ----

    @Test
    fun `source wire values map to enum, unknown falls back to any`() {
        assertEquals(CaptureSource.GLASSES, CaptureSource.fromWire("glasses"))
        assertEquals(CaptureSource.PHONE, CaptureSource.fromWire("phone"))
        assertEquals(CaptureSource.ANY, CaptureSource.fromWire("any"))
        assertEquals(CaptureSource.ANY, CaptureSource.fromWire(null))
        assertEquals(CaptureSource.ANY, CaptureSource.fromWire(""))
        assertEquals(CaptureSource.ANY, CaptureSource.fromWire("something-new"))
        // case / whitespace tolerant
        assertEquals(CaptureSource.GLASSES, CaptureSource.fromWire("  GLASSES "))
        assertEquals(CaptureSource.PHONE, CaptureSource.fromWire("Phone"))
    }

    // ---- phone-sourced: always phone, glasses state irrelevant ----

    @Test
    fun `phone source always routes to phone, never glasses`() {
        for (enabled in listOf(true, false)) {
            for (connected in listOf(true, false)) {
                assertEquals(
                    CaptureRoute.PHONE,
                    CaptureRouter.route(CaptureSource.PHONE, enabled, connected),
                    "phone event must ignore glasses (enabled=$enabled connected=$connected)",
                )
            }
        }
    }

    // ---- glasses-sourced: honest error unless enabled AND connected ----

    @Test
    fun `glasses source routes to glasses only when enabled and connected`() {
        assertEquals(
            CaptureRoute.GLASSES,
            CaptureRouter.route(CaptureSource.GLASSES, glassesEnabled = true, glassesConnected = true),
        )
    }

    @Test
    fun `glasses source with toggle off is an honest error, not a phone fallback`() {
        assertEquals(
            CaptureRoute.ERROR_GLASSES_UNAVAILABLE,
            CaptureRouter.route(CaptureSource.GLASSES, glassesEnabled = false, glassesConnected = true),
        )
    }

    @Test
    fun `glasses source with glasses disconnected is an honest error, not a phone fallback`() {
        assertEquals(
            CaptureRoute.ERROR_GLASSES_UNAVAILABLE,
            CaptureRouter.route(CaptureSource.GLASSES, glassesEnabled = true, glassesConnected = false),
        )
        assertEquals(
            CaptureRoute.ERROR_GLASSES_UNAVAILABLE,
            CaptureRouter.route(CaptureSource.GLASSES, glassesEnabled = false, glassesConnected = false),
        )
    }

    // ---- any-sourced: prefer glasses when ready, else phone ----

    @Test
    fun `any source prefers glasses when enabled and connected`() {
        assertEquals(
            CaptureRoute.GLASSES,
            CaptureRouter.route(CaptureSource.ANY, glassesEnabled = true, glassesConnected = true),
        )
    }

    @Test
    fun `any source falls back to phone when glasses off or disconnected`() {
        assertEquals(
            CaptureRoute.PHONE,
            CaptureRouter.route(CaptureSource.ANY, glassesEnabled = false, glassesConnected = true),
        )
        assertEquals(
            CaptureRoute.PHONE,
            CaptureRouter.route(CaptureSource.ANY, glassesEnabled = true, glassesConnected = false),
        )
        assertEquals(
            CaptureRoute.PHONE,
            CaptureRouter.route(CaptureSource.ANY, glassesEnabled = false, glassesConnected = false),
        )
    }
}
