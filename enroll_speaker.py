# enroll_speaker.py
#
# Offline enrollment: record/load ~30s of a person, embed it, upsert into
# profiles.json. Run from a terminal, once per person. Re-runnable.
#
#   python enroll_speaker.py --name Alex --tier known --wav alex.wav
#   python enroll_speaker.py --name Owner --tier owner --wav owner.wav
#   python enroll_speaker.py --calibrate
#
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import numpy as np

import speaker_id

VALID_TIERS = {"owner", "known", "kid"}


def valid_tier(tier: str) -> bool:
    return tier in VALID_TIERS


def _default_path() -> Path:
    return Path(os.getenv("EVE_VOICEPRINTS", str(Path.home() / "eve-voiceprints" / "profiles.json")))


def _load_raw(path: Path) -> dict:
    if Path(path).is_file():
        try:
            return json.loads(Path(path).read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"encoder_version": speaker_id.ENCODER_VERSION,
            "preprocessing_version": speaker_id.PREPROCESSING_VERSION, "profiles": []}


def upsert_profile(profiles_path, name, tier, embedding) -> None:
    path = Path(profiles_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = _load_raw(path)
    # always (re)stamp the current versions
    data["encoder_version"] = speaker_id.ENCODER_VERSION
    data["preprocessing_version"] = speaker_id.PREPROCESSING_VERSION
    data["profiles"] = [r for r in data.get("profiles", []) if r.get("name") != name]
    data["profiles"].append({"name": name, "tier": tier, "embedding": list(embedding)})
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def calibrate(profiles_path) -> None:
    profs = speaker_id.load_profiles(Path(profiles_path))
    if len(profs) < 2:
        print("Need >=2 enrolled profiles to calibrate.")
        return
    print("Inter-speaker cosine similarity:")
    for i, a in enumerate(profs):
        for b in profs[i + 1:]:
            print(f"  {a.name:>10} vs {b.name:<10} {float(np.dot(a.embedding, b.embedding)):.3f}")
    print("Set EVE_SPEAKER_THRESHOLD above the highest cross-speaker score and "
          "below your enrollment self-similarity.")


def _read_wav_bytes(args) -> bytes:
    if args.wav:
        return Path(args.wav).read_bytes()
    raise SystemExit("record a WAV with your tool of choice and pass it via --wav")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name")
    ap.add_argument("--tier")
    ap.add_argument("--wav")
    ap.add_argument("--wav-dir", dest="wav_dir",
                    help="folder of live-captured utterance WAVs (EVE_ENROLL_CAPTURE_DIR) "
                         "— averages them so the profile matches EVE's live audio domain")
    ap.add_argument("--calibrate", action="store_true")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--profiles", default=str(_default_path()))
    args = ap.parse_args()

    if args.calibrate:
        calibrate(args.profiles)
        return
    if not args.name or not valid_tier(args.tier or ""):
        raise SystemExit("--name and --tier (owner|known|kid) required")
    existing = speaker_id.load_profiles(Path(args.profiles))
    if args.tier == "owner" and any(p.tier == "owner" for p in existing) and not args.force:
        raise SystemExit("an owner already exists — pass --force to add another")
    if args.wav_dir:
        # BEST: average EVE's own live-captured utterances — same audio domain as
        # matching, so the real speaker isn't false-rejected.
        files = sorted(Path(args.wav_dir).glob("*.wav"))
        if not files:
            raise SystemExit(f"no .wav files in {args.wav_dir}")
        emb = speaker_id.embed_profile_files([f.read_bytes() for f in files])
        print(f"Averaged {len(files)} captured utterances from {args.wav_dir}")
    else:
        # Fallback: a single offline clip, windowed+averaged. Works, but an
        # offline-recorded file can land in a slightly different audio domain
        # than EVE's live capture — prefer --wav-dir if matching scores low.
        emb = speaker_id.embed_profile(_read_wav_bytes(args))
    upsert_profile(args.profiles, args.name, args.tier, emb.tolist())
    print(f"Enrolled {args.name} as {args.tier}. Profiles: {args.profiles}")


if __name__ == "__main__":
    main()
