import json
import enroll_speaker
import speaker_id


def test_valid_tier():
    assert enroll_speaker.valid_tier("owner")
    assert enroll_speaker.valid_tier("kid")
    assert not enroll_speaker.valid_tier("admin")


def test_upsert_writes_versioned_shape(tmp_path):
    p = tmp_path / "profiles.json"
    enroll_speaker.upsert_profile(p, "Owner", "owner", [1.0, 0.0, 0.0])
    data = json.loads(p.read_text())
    assert data["encoder_version"] == speaker_id.ENCODER_VERSION
    assert data["preprocessing_version"] == speaker_id.PREPROCESSING_VERSION
    assert data["profiles"][0]["name"] == "Owner"


def test_upsert_replaces_same_name(tmp_path):
    p = tmp_path / "profiles.json"
    enroll_speaker.upsert_profile(p, "Alex", "known", [1.0, 0.0])
    enroll_speaker.upsert_profile(p, "Alex", "known", [0.0, 1.0])
    data = json.loads(p.read_text())
    names = [r["name"] for r in data["profiles"]]
    assert names.count("Alex") == 1
    assert data["profiles"][0]["embedding"] == [0.0, 1.0]
