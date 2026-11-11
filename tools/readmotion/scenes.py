"""Writes FX for FUTO Keyboard's README recordings into readmotion.json: a hero and keyfx's
effect gallery.

    python tools/readmotion/scenes.py

The recordings play keyfx's lab in showcase mode, from the keyfx/ subtree: the lab's keyboard is
a mock of FUTO Keyboard, and the effects and settings are the ones this module adds to the real
one. See keyfx/tools/readmotion/showcase.py.
"""

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "keyfx" / "tools" / "readmotion"))
import showcase as s  # noqa: E402

ACCENT = "#5EEAD4"
# The module draws over FUTO's keys but can't move them (no CAP_KEY_TRANSFORM), so the recordings
# leave out what only FXboard can do: key pops that scale the real key, and the lava's key shake.
HOST = {"global": {"accent": ACCENT}, "pop": {"scaleKey": False}, "delHold": {"shake": False}}


def hero() -> list[dict]:
    """A reply in a chat: effects while typing and swiping, whole-word backspace, the vertical
    spacebar cursor."""
    return s.scene(
        s.mark("hero"),
        s.setup("FX for FUTO Keyboard", "Effects while you type", defaults=True, text="Dinner at seven?\n",
                set_={**HOST, "enter": {"style": "shockwave"}}),
        s.type_text("sounds good,"), s.record(0.3),
        s.setup("FX for FUTO Keyboard", "Swipe a word, watch it hop"),
        s.swipe("see"), s.record(0.7), s.swipe("you"), s.record(0.7), s.swipe("there"), s.record(1.2),
        s.setup("FX for FUTO Keyboard", "Hold backspace for whole words",
                set_={"delHold": {"shake": False, "words": True, "wordsAfter": 1, "wordPace": 380}}),
        s.hold("del", 1.5), s.record(0.8),
        s.setup("FX for FUTO Keyboard", "Drag the spacebar between lines"),
        s.trackpad([(0, -52), (-70, -52), (-70, 0), (130, 0)], 2.2), s.record(0.4),
        s.setup("FX for FUTO Keyboard", "Then send it"),
        s.swipe("see"), s.record(0.5), s.swipe("you"), s.record(0.5), s.swipe("then"), s.record(0.7),
        s.tap("enter", 0.3), s.record(1.3),
        # TODO(phone): end on the module's settings in the real FUTO Keyboard, recorded on a phone.
        # See tools/readmotion/phone/TODO.md for what to record and how to add it here.
        s.mark("end"), s.record(0.1),
    )


if __name__ == "__main__":
    gallery = s.gallery()
    gallery.insert(0, {"eval": f"showcase.set({json.dumps(HOST)})"})
    s.write(HERE / "readmotion.json", {
        "hero": s.recording("../../keyfx", hero()),
        "gallery": s.recording("../../keyfx", gallery),
    })
