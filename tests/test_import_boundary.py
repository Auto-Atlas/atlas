"""Repo must import clean on non-Jetson boxes: heavy ARM64-only deps
(riva.client, depthai, dynamixel_sdk) must NOT be pulled in at import time.
Every Jetson body module guards those behind function-local imports."""
import importlib
import sys

import pytest

HEAVY = ("riva.client", "depthai", "dynamixel_sdk")
MODULES = ["riva_stt", "riva_tts", "speech_factory", "jetson_bot",
           "oakd_vision", "jetson_tools", "hand_tool"]


@pytest.mark.parametrize("modname", MODULES)
def test_module_imports_without_heavy_deps(modname):
    for h in HEAVY:
        sys.modules.pop(h, None)
    importlib.import_module(modname)
    leaked = [h for h in HEAVY if h in sys.modules]
    assert not leaked, f"{modname} imported heavy deps at module load: {leaked}"
