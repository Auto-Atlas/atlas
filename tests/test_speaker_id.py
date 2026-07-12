import numpy as np
import pytest
import speaker_id
from speaker_id import Profile, identify


def _unit(v):
    v = np.asarray(v, dtype=np.float32)
    return v / np.linalg.norm(v)


def test_identify_returns_best_match_above_threshold():
    will = Profile("Owner", "owner", _unit([1, 0, 0]))
    ash = Profile("Alex", "known", _unit([0, 1, 0]))
    m = identify(_unit([0.95, 0.05, 0]), [will, ash], threshold=0.75)
    assert m.name == "Owner" and m.tier == "owner"
    assert m.score > 0.75


def test_below_threshold_is_unknown():
    will = Profile("Owner", "owner", _unit([1, 0, 0]))
    m = identify(_unit([0, 0, 1]), [will], threshold=0.75)
    assert m.name is None and m.tier == "unknown"


def test_no_profiles_is_unknown():
    m = identify(_unit([1, 0, 0]), [], threshold=0.75)
    assert m.name is None and m.tier == "unknown"


def test_load_profiles_missing_file_returns_empty(tmp_path):
    assert speaker_id.load_profiles(tmp_path / "nope.json") == []


def test_load_profiles_version_mismatch_returns_empty(tmp_path):
    import json
    p = tmp_path / "profiles.json"
    p.write_text(json.dumps({
        "encoder_version": "WRONG", "preprocessing_version": "WRONG",
        "profiles": [{"name": "X", "tier": "owner", "embedding": [1, 0, 0]}],
    }))
    assert speaker_id.load_profiles(p) == []


def _silent_wav(seconds, sr=16000, ch=1):
    import io, wave
    b = io.BytesIO()
    with wave.open(b, "wb") as o:
        o.setnchannels(ch)
        o.setsampwidth(2)
        o.setframerate(sr)
        o.writeframes(np.zeros(int(sr * ch * seconds), dtype=np.int16).tobytes())
    return b.getvalue()


def test_embed_profile_averages_short_windows():
    # 12s clip @ 4s windows -> 3 windows; stub embed avoids needing resemblyzer.
    calls = {"n": 0}

    def stub(_b):
        calls["n"] += 1
        return _unit([1.0, 0.0, 0.0])

    out = speaker_id.embed_profile(_silent_wav(12), window_s=4.0, _embed=stub)
    assert calls["n"] == 3                                  # averaged over 3 windows
    assert abs(float(np.linalg.norm(out)) - 1.0) < 1e-5     # renormalized


def test_embed_profile_files_averages_each_clip():
    seen = {"n": 0}

    def stub(_b):
        seen["n"] += 1
        return _unit([1.0, 0.0, 0.0])

    out = speaker_id.embed_profile_files([b"a", b"b", b"c"], _embed=stub)
    assert seen["n"] == 3
    assert abs(float(np.linalg.norm(out)) - 1.0) < 1e-5


def test_embed_profile_files_empty_raises():
    with pytest.raises(ValueError):
        speaker_id.embed_profile_files([], _embed=lambda b: _unit([1, 0, 0]))


def test_embed_profile_short_clip_falls_back_to_single():
    calls = {"n": 0}

    def stub(_b):
        calls["n"] += 1
        return _unit([0.0, 1.0, 0.0])

    speaker_id.embed_profile(_silent_wav(1), window_s=4.0, _embed=stub)
    assert calls["n"] == 1                                  # too short to window


def test_load_profiles_roundtrip(tmp_path):
    import json
    emb = _unit([1, 2, 3]).tolist()
    p = tmp_path / "profiles.json"
    p.write_text(json.dumps({
        "encoder_version": speaker_id.ENCODER_VERSION,
        "preprocessing_version": speaker_id.PREPROCESSING_VERSION,
        "profiles": [{"name": "Will", "tier": "owner", "embedding": emb}],
    }))
    loaded = speaker_id.load_profiles(p)
    assert len(loaded) == 1 and loaded[0].name == "Will" and loaded[0].tier == "owner"
