"""EchoGuard — the text-level backstop against EVE answering her own speakerphone
echo (observed 2026-07-07: "While Hermes gathers the details..." self-reply loop).
Pure-logic tests on record/is_echo; the pipeline wiring is a pass-through seam."""

from speech_factory import EchoGuard


def test_exact_echo_is_dropped():
    g = EchoGuard()
    g.record_bot_text("While Hermes gathers the details, I'll keep an eye on it.")
    assert g.is_echo("While Hermes gathers the details, I'll keep an eye on it.")


def test_partial_trailing_echo_is_dropped():
    # Whisper often catches only the tail end after the mic gate reopens.
    g = EchoGuard()
    g.record_bot_text("While Hermes gathers the details, I'll keep an eye on it.")
    assert g.is_echo("while Hermes gathers the details")


def test_fuzzy_echo_with_stt_noise_is_dropped():
    g = EchoGuard()
    g.record_bot_text("Your calendar is free today, Owner.")
    assert g.is_echo("your calendar is free today owner")


def test_real_user_speech_passes():
    g = EchoGuard()
    g.record_bot_text("Your calendar is free today, Owner.")
    assert not g.is_echo("add a meeting with Alex tomorrow at nine")


def test_short_confirmations_never_guarded():
    # "yes" must survive even if EVE just said the word — eating confirmations
    # would break the approve flows.
    g = EchoGuard()
    g.record_bot_text("Say yes and I will send it.")
    assert not g.is_echo("yes")
    assert not g.is_echo("yes send it")  # < MIN_WORDS


def test_old_bot_lines_age_out():
    g = EchoGuard(window_s=0.0)
    g.record_bot_text("While Hermes gathers the details, I'll keep an eye on it.")
    assert not g.is_echo("While Hermes gathers the details, I'll keep an eye on it.")
