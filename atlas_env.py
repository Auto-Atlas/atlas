# Atlas env-var aliasing — the public names are ATLAS_*, the historical names
# (EVE_*, JARVIS_*) keep working forever.
#
# Every entrypoint calls apply_aliases() right after load_dotenv(), before any
# module that reads os.environ at import time. For each ATLAS_<X> present in
# the environment, EVE_<X> and JARVIS_<X> are filled in ONLY where unset — an
# explicitly-set legacy var always wins, so existing installs never change
# behavior by upgrading. The two legacy prefixes share no suffix (verified by
# tests/test_atlas_env.py), so fanning one ATLAS_ value into both is safe.
import os


def apply_aliases(environ=None) -> int:
    """Fan ATLAS_* vars into their EVE_*/JARVIS_* legacy names (setdefault only).

    Returns the number of legacy names that were filled in.
    """
    env = os.environ if environ is None else environ
    filled = 0
    for key in list(env.keys()):
        if not key.startswith("ATLAS_"):
            continue
        suffix = key[len("ATLAS_"):]
        if not suffix:
            continue
        for legacy in (f"EVE_{suffix}", f"JARVIS_{suffix}"):
            if legacy not in env:
                env[legacy] = env[key]
                filled += 1
    return filled
