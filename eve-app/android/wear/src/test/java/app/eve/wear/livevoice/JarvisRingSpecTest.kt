package app.eve.wear.livevoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the PURE state -> ring-behaviour contract of JarvisRing (the arc-reactor wrist visual): the
 * mood grouping and each mood's exact visual recipe. Rendering itself is visual (canvas draw) and is
 * NOT asserted here — only the deterministic math that decides colour/motion per state, so the state
 * language can never silently drift. Hues are pinned to NeuralBrain's PALETTES / the screen's danger.
 */
class JarvisRingSpecTest {

    // ---- mood grouping: every VoiceState folds to the documented mood --------------------------

    @Test fun idle_states_are_idle_mood() {
        assertEquals(RingMood.IDLE, ringMoodOf(VoiceState.Idle))
        assertEquals(RingMood.IDLE, ringMoodOf(VoiceState.NotConfigured))
    }

    @Test fun connect_states_are_connecting_mood() {
        assertEquals(RingMood.CONNECTING, ringMoodOf(VoiceState.Connecting))
        assertEquals(RingMood.CONNECTING, ringMoodOf(VoiceState.Reconnecting))
    }

    @Test fun listen_states_are_listening_mood() {
        assertEquals(RingMood.LISTENING, ringMoodOf(VoiceState.YourTurn))
        assertEquals(RingMood.LISTENING, ringMoodOf(VoiceState.Hearing(0f)))
        assertEquals(RingMood.LISTENING, ringMoodOf(VoiceState.Hearing(0.9f)))
    }

    @Test fun thinking_is_thinking_mood() {
        assertEquals(RingMood.THINKING, ringMoodOf(VoiceState.Thinking))
    }

    @Test fun speaking_is_speaking_mood() {
        assertEquals(RingMood.SPEAKING, ringMoodOf(VoiceState.Speaking))
    }

    @Test fun no_audio_and_error_are_dead_mood() {
        assertEquals(RingMood.DEAD, ringMoodOf(VoiceState.NoAudio))
        assertEquals(RingMood.DEAD, ringMoodOf(VoiceState.Error("boom")))
    }

    // ---- muted mic: the orb goes RED (owner's cue 2026-07-11: red = mic muted, blue = live) ----

    @Test fun a_muted_mic_turns_the_orb_red_at_full_brightness() {
        val muted = mutedSpecOf(specForMood(RingMood.LISTENING))
        assertTrue(muted.core.contentEquals(intArrayOf(248, 113, 113)), "muted core is the danger red")
        assertTrue(muted.ring.contentEquals(intArrayOf(239, 68, 68)), "muted ring is the danger red")
        assertTrue(muted.glow.contentEquals(intArrayOf(255, 160, 160)), "muted glow is the danger red")
        // COLOR is the cue, not dimming: brightness stays the state's own so red reads clearly
        // on the black face (the earlier dim cue is gone — owner's call 2026-07-11).
        assertEquals(1.0f, muted.dim)
    }

    @Test fun muting_recolors_but_never_changes_the_motion() {
        // Mute is a color-only overlay: the ring keeps moving exactly as its state demands, so the
        // listening pulse / speaking amplitude rendering keep working while red.
        for (mood in RingMood.entries) {
            val base = specForMood(mood)
            val muted = mutedSpecOf(base)
            assertEquals(base.baseRotDegPerSec, muted.baseRotDegPerSec, "spin changed for $mood")
            assertEquals(base.counterRotate, muted.counterRotate, "counterRotate changed for $mood")
            assertEquals(base.radarSweep, muted.radarSweep, "radarSweep changed for $mood")
            assertEquals(base.pulseWithMic, muted.pulseWithMic, "pulseWithMic changed for $mood")
            assertEquals(base.speakPulse, muted.speakPulse, "speakPulse changed for $mood")
            assertEquals(base.breathe, muted.breathe, "breathe changed for $mood")
            assertEquals(base.frozen, muted.frozen, "frozen changed for $mood")
            assertEquals(base.dim, muted.dim, "dim changed for $mood — mute must not dim")
        }
    }

    // ---- long-press END: the ring eases down over the hold window ------------------------------

    @Test fun hold_progress_contracts_and_dims_the_ring_monotonically() {
        // No hold → untouched; full hold → visibly contracted AND dimmed (the hold never feels
        // dead). Monotonic so the ease reads as continuous feedback across the whole window.
        assertEquals(1f, holdRadiusScale(0f))
        assertEquals(1f, holdBrightnessScale(0f))
        assertTrue(holdRadiusScale(1f) < 1f, "a full hold must contract the ring")
        assertTrue(holdBrightnessScale(1f) < 1f, "a full hold must dim the ring")
        assertTrue(holdRadiusScale(0.5f) > holdRadiusScale(1f))
        assertTrue(holdBrightnessScale(0.5f) > holdBrightnessScale(1f))
    }

    @Test fun hold_progress_is_clamped_to_the_unit_range() {
        assertEquals(holdRadiusScale(1f), holdRadiusScale(5f))
        assertEquals(holdBrightnessScale(1f), holdBrightnessScale(5f))
        assertEquals(1f, holdRadiusScale(-1f))
        assertEquals(1f, holdBrightnessScale(-1f))
    }

    // ---- exact recipe per mood: colours (NeuralBrain hues) + motion flags ----------------------

    @Test fun idle_spec_is_teal_calm_breathing() {
        val s = specForMood(RingMood.IDLE)
        assertTrue(s.core.contentEquals(intArrayOf(56, 195, 215)))
        assertTrue(s.ring.contentEquals(intArrayOf(45, 165, 190)))
        assertTrue(s.glow.contentEquals(intArrayOf(80, 200, 220)))
        assertEquals(12f, s.baseRotDegPerSec)
        assertTrue(s.breathe)
        assertFalse(s.counterRotate)
        assertFalse(s.radarSweep)
        assertFalse(s.pulseWithMic)
        assertFalse(s.speakPulse)
        assertFalse(s.frozen)
        assertEquals(1.0f, s.dim)
    }

    @Test fun connecting_spec_is_slate_dim_radar() {
        val s = specForMood(RingMood.CONNECTING)
        assertTrue(s.ring.contentEquals(intArrayOf(100, 108, 122)))
        assertTrue(s.radarSweep)
        assertEquals(0f, s.baseRotDegPerSec)
        assertFalse(s.frozen)
        assertEquals(0.6f, s.dim)
    }

    @Test fun listening_spec_is_sky_mic_reactive() {
        val s = specForMood(RingMood.LISTENING)
        assertTrue(s.core.contentEquals(intArrayOf(90, 210, 255)))
        assertTrue(s.ring.contentEquals(intArrayOf(56, 189, 248)))
        assertTrue(s.pulseWithMic)
        assertTrue(s.breathe)
        assertFalse(s.speakPulse)
        assertEquals(18f, s.baseRotDegPerSec)
        assertEquals(1.0f, s.dim)
    }

    @Test fun thinking_spec_is_purple_counter_rotating() {
        val s = specForMood(RingMood.THINKING)
        assertTrue(s.ring.contentEquals(intArrayOf(167, 139, 250)))
        assertTrue(s.counterRotate)
        assertEquals(28f, s.baseRotDegPerSec)
        assertFalse(s.frozen)
    }

    @Test fun speaking_spec_is_amber_synthetic_pulse() {
        val s = specForMood(RingMood.SPEAKING)
        assertTrue(s.core.contentEquals(intArrayOf(251, 191, 36)))
        assertTrue(s.ring.contentEquals(intArrayOf(245, 178, 64)))
        assertTrue(s.speakPulse)
        assertFalse(s.pulseWithMic) // no real TTS RMS on the wire — the pulse is synthetic
        assertEquals(16f, s.baseRotDegPerSec)
    }

    @Test fun dead_spec_is_danger_frozen_dim() {
        val s = specForMood(RingMood.DEAD)
        assertTrue(s.core.contentEquals(intArrayOf(248, 113, 113))) // WearEveColors.danger 0xFFF87171
        assertTrue(s.frozen)
        assertEquals(0f, s.baseRotDegPerSec)
        assertEquals(0.45f, s.dim)
        assertFalse(s.breathe)
        assertFalse(s.pulseWithMic)
        assertFalse(s.speakPulse)
    }

    // ---- the convenience straight-from-state path lines up with the two pure steps -------------

    @Test fun ringSpecOf_matches_specForMood_of_ringMoodOf() {
        val states = listOf(
            VoiceState.Idle, VoiceState.NotConfigured, VoiceState.Connecting, VoiceState.Reconnecting,
            VoiceState.YourTurn, VoiceState.Hearing(0.5f), VoiceState.Thinking, VoiceState.Speaking,
            VoiceState.NoAudio, VoiceState.Error("x"),
        )
        for (st in states) {
            val a = ringSpecOf(st)
            val b = specForMood(ringMoodOf(st))
            assertTrue(a.ring.contentEquals(b.ring), "ring hue mismatch for $st")
            assertEquals(b.baseRotDegPerSec, a.baseRotDegPerSec, "spin mismatch for $st")
            assertEquals(b.frozen, a.frozen, "frozen mismatch for $st")
            assertEquals(b.dim, a.dim, "dim mismatch for $st")
        }
    }
}
