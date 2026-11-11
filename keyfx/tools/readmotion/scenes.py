"""Writes keyfx's README recordings into readmotion.json: a hero montage and the effect gallery.

    python tools/readmotion/scenes.py

Then record and build with Readmotion (see the README's Recording the README media).
"""

from pathlib import Path

import showcase as s

HERE = Path(__file__).resolve().parent


def hero() -> list[dict]:
    """A quick run through keyfx's defaults: typing, a swipe, backspace, a hold and enter."""
    return s.scene(
        s.mark("hero"),
        s.setup("keyfx", "Every key, animated", defaults=True, text=""),
        s.type_text("hello"), s.record(0.3),
        s.setup("keyfx", "Swipe trails and letter hop"),
        s.swipe("world"), s.record(1.4),
        s.setup("keyfx", "Backspace sets letters on fire"),
        s.tap("del", 0.3), s.record(0.5), s.tap("del"), s.record(0.7),
        s.setup("keyfx", "Hold it for a black hole", text="hello world, watch these go"),
        s.hold("del", 1.7), s.record(1.0),
        s.setup("keyfx", "And enter launches a rocket", set_={"enter": {"style": "rocket"}}),
        s.type_text("ship it"), s.tap("enter", 0.2), s.record(1.3),
        s.mark("end"), s.record(0.1),
    )


if __name__ == "__main__":
    s.write(HERE / "readmotion.json", {
        "hero": s.recording("../..", hero()),
        "gallery": s.recording("../..", s.gallery()),
    })
