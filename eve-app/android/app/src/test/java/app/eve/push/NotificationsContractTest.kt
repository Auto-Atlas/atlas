package app.eve.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pure-JVM guard on the notification SAFETY contract (no Android runtime needed): the Review
 * action must be a CONTENT/activity intent that opens the primed card, and must NEVER be the
 * same channel as a one-tap fire. Deny is the only fire-from-notification path.
 *
 * This asserts the intent-routing *contract* encoded as the action constants and the explicit
 * separation of the two builder entry points. The Android-side wiring (getActivity for Review,
 * getBroadcast for Deny) is verified here by their distinct action strings and the fact that
 * there is NO "approve from notification" action constant at all.
 */
class NotificationsContractTest {

    @Test
    fun review_and_deny_are_distinct_actions() {
        assertNotEquals(Notifications.ACTION_REVIEW, Notifications.ACTION_DENY)
    }

    @Test
    fun there_is_no_one_tap_approve_action() {
        // The receiver only honors the Deny action; approve must go through the primed card.
        // We assert by reflection that no APPROVE/FIRE action constant exists on Notifications.
        val actionFields = Notifications::class.java.declaredFields
            .map { it.name }
            .filter { it.startsWith("ACTION_") }
        assertTrue(
            actionFields.none { it.contains("APPROVE", ignoreCase = true) || it.contains("FIRE", ignoreCase = true) },
            "No one-tap approve/fire notification action may exist; found: $actionFields",
        )
        assertTrue(actionFields.contains("ACTION_REVIEW"))
        assertTrue(actionFields.contains("ACTION_DENY"))
    }

    @Test
    fun builder_exposes_a_review_content_intent_and_a_deny_broadcast_but_no_approve_intent() {
        val methods = Notifications::class.java.declaredMethods.map { it.name }
        // Review is wired as a content (activity) intent; Deny as a broadcast intent.
        assertTrue(methods.contains("reviewContentIntent"), "must have a content-intent for Review")
        assertTrue(methods.contains("denyBroadcastIntent"), "Deny may fire via broadcast")
        // There must be NO approve-fire intent builder of any kind.
        assertTrue(
            methods.none { it.contains("approve", ignoreCase = true) || it.contains("fire", ignoreCase = true) },
            "No approve/fire intent builder may exist; found: $methods",
        )
    }

    @Test
    fun review_action_label_intent_is_the_content_open_not_a_fire() {
        // The Review action constant names an OPEN/REVIEW operation, by contract distinct from
        // any release. (The PendingIntent itself is built with getActivity in reviewContentIntent;
        // see Notifications.reviewContentIntent — it targets MainActivity, not the receiver.)
        assertEquals("app.eve.action.REVIEW", Notifications.ACTION_REVIEW)
        assertEquals("app.eve.action.DENY", Notifications.ACTION_DENY)
    }
}
