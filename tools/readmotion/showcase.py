"""Readmotion steps for recording keyfx effects in the lab's showcase mode.

The lab (lab/index.html) has a showcase mode, lab/index.html?showcase: the keyboard alone, with a
caption, driven from window.showcase. This module turns short scripts (tap these keys, swipe this
word, hold backspace) into Readmotion web steps, and holds the effect gallery that keyfx, FXboard and
FX for FUTO Keyboard all show in their READMEs. Each repo's tools/readmotion/scenes.py adds its own
hero and writes the recordings into its readmotion.json:

    python tools/readmotion/scenes.py

FXboard and FX for FUTO Keyboard carry keyfx as a subtree in keyfx/, so they import this file from
there, and their recordings serve that copy of the lab.
"""

import json
from pathlib import Path

SIZE = [440, 560]      # the page, in CSS pixels; recorded at twice that
SPACE = (220, 477)     # the spacebar's center at SIZE, for trackpad drags
PAGE = "lab/index.html?showcase"


def key(k: str) -> str:
    """A key's selector: a letter, digit, ',' or '.', or shift, del, enter, space, sym."""
    return f'[data-key="{k}"]'


def setup(cat: str, name: str, only: dict | None = None, text: str | None = None,
          set_: dict | None = None, defaults: bool = False) -> list[dict]:
    """Switch effects and set the caption. `only` turns every other effect off;
    `set_` changes values and leaves the rest as they are."""
    js = []
    if defaults:
        js.append("showcase.defaults()")
    if only is not None:
        js.append(f"showcase.only({json.dumps(only)})")
    if set_:
        js.append(f"showcase.set({json.dumps(set_)})")
    if text is not None:
        js.append(f"showcase.text({json.dumps(text)})")
    js.append(f"showcase.caption({json.dumps(cat)}, {json.dumps(name)})")
    return [{"eval": "; ".join(js)}]


def record(seconds: float) -> list[dict]:
    return [{"record": seconds}]


def mark(name: str) -> list[dict]:
    return [{"mark": name}]


def tap(k: str, move: float = 0.1) -> list[dict]:
    return [{"click": key(k), "move": move}]


def type_text(s: str, move: float = 0.1) -> list[dict]:
    """Taps out the text. Write it in lower case: the keyboard capitalizes sentences itself."""
    names = {" ": "space", "\n": "enter"}
    return [step for ch in s.lower() for step in tap(names.get(ch, ch), move)]


def swipe(word: str, leg: float = 0.16, pause: float = 0.12, move: float = 0.3) -> list[dict]:
    """Swipes a word: presses its first letter and glides through the rest, resting on each. The
    lab is told which word it is, as its own demos do, since its decoder is only a stand-in."""
    keys = [c for i, c in enumerate(word) if i == 0 or c != word[i - 1]]  # 'hello' crosses l once
    return [{"eval": f"showcase.expect({json.dumps(word)})"},
            {"path": [key(c) for c in keys], "over": leg * (len(keys) - 1), "pause": pause, "move": move},
            {"eval": "showcase.expect(null)"}]


def hold(k: str, seconds: float, move: float = 0.3) -> list[dict]:
    return [{"hold": key(k), "for": seconds, "move": move}]


def trackpad(moves: list[tuple[float, float]], over: float, pause: float = 0.25) -> list[dict]:
    """Drags the spacebar: `moves` are offsets from its center, visited in turn."""
    pts = [list(SPACE)] + [[SPACE[0] + dx, SPACE[1] + dy] for dx, dy in moves]
    return [{"path": pts, "over": over, "pause": pause, "move": 0.3}]


def scene(*parts: list[dict]) -> list[dict]:
    return [step for part in parts for step in part]


# ---------------------------------------------------------------- the effect gallery

GALLERY_MARKS = ["typing", "delete", "hold", "enter", "spacebar", "trail", "word"]


def gallery() -> list[dict]:
    """Every category, a few of its effects each, one mark per category."""
    return scene(
        mark("typing"),
        setup("Typing", "Key pop and letter float", only={"pop": {}, "float": {"mode": "rise"}}, text=""),
        type_text("hey there"), record(0.6),
        setup("Typing", "Heat streak: embers", only={"pop": {}, "heat": {"style": "embers", "combo": True, "showAt": 5}}, text=""),
        type_text("type faster", 0.05), record(0.9),
        setup("Typing", "Neighbor wave: firework", only={"pop": {}, "wave": {"style": "firework"}}, text=""),
        type_text("wow nice"), record(0.6),
        setup("Typing", "Sparks and a water drop", only={"pop": {}, "sparks": {}, "drop": {}}, text=""),
        type_text("yes"), record(0.9),

        mark("delete"),
        setup("Backspace tap", "Fire", only={"pop": {}, "del": {"tap": "fire"}}, text="light it up"),
        tap("del", 0.35), record(0.6), tap("del"), record(0.8),
        setup("Backspace tap", "Shatter", only={"pop": {}, "del": {"tap": "shatter"}}, text="smash it"),
        tap("del", 0.2), record(0.6), tap("del"), record(0.8),
        setup("Backspace tap", "Laser", only={"pop": {}, "del": {"tap": "laser"}}, text="zap them"),
        tap("del", 0.2), record(0.6), tap("del"), record(0.8),
        setup("Backspace tap", "Disintegrate", only={"pop": {}, "del": {"tap": "dust"}}, text="dust off"),
        tap("del", 0.2), record(0.6), tap("del"), record(1.0),
        setup("Backspace tap", "Glitch", only={"pop": {}, "del": {"tap": "glitch"}}, text="glitch"),
        tap("del", 0.2), record(0.6), tap("del"), record(0.8),

        mark("hold"),
        setup("Backspace hold", "Black hole, letters swirl in",
              only={"delHold": {"button": "blackhole", "letters": "swirl"}}, text="watch these letters get pulled in"),
        hold("del", 1.9), record(1.1),
        setup("Backspace hold", "Portal, on a tractor beam",
              only={"delHold": {"button": "portal", "letters": "beam"}}, text="beam the whole line away"),
        hold("del", 1.9), record(1.1),
        setup("Backspace hold", "Lava rumble, letters on curves",
              only={"delHold": {"button": "lava", "letters": "vacuum"}}, text="melt every word of this"),
        hold("del", 1.9), record(1.1),

        mark("enter"),
        setup("Enter", "Rocket", only={"pop": {}, "enter": {"style": "rocket"}}, text="launch it"),
        tap("enter", 0.3), record(1.1),
        setup("Enter", "Shockwave", only={"pop": {}, "enter": {"style": "shockwave"}}, text="send it"),
        tap("enter", 0.2), record(1.0),
        setup("Enter", "Check mark", only={"pop": {}, "enter": {"style": "check"}}, text="all done"),
        tap("enter", 0.2), record(1.0),
        setup("Enter", "Row wave", only={"pop": {}, "enter": {"style": "rows"}}, text="next line"),
        tap("enter", 0.2), record(1.1),

        mark("spacebar"),
        setup("Spacebar", "Stars", only={"pop": {}, "space": {"mode": "stars"}}, text="tap"),
        tap("space", 0.3), record(0.8),
        setup("Spacebar", "Puff", only={"pop": {}, "space": {"mode": "puff"}}),
        tap("space", 0.1), record(0.8),
        setup("Spacebar", "Shimmer", only={"pop": {}, "space": {"mode": "shimmer"}}),
        tap("space", 0.1), record(0.8),
        setup("Spacebar swipe", "Trackpad: rails", only={"trackpad": {"look": "rails"}},
              text="drag the spacebar to move the cursor,\nup and down between lines too"),
        trackpad([(-110, 0), (-110, -50), (40, -50), (40, 0)], 2.4), record(0.6),
        setup("Spacebar swipe", "Trackpad: glass", only={"trackpad": {"look": "glass"}}),
        trackpad([(90, 0), (90, -50), (-60, -50)], 1.8), record(0.6),

        mark("trail"),
        setup("Swipe trail", "Comet", only={"comet": {}}, text=""),
        swipe("hello"), record(0.7),
        setup("Swipe trail", "Lightning", only={"lightning": {}}),
        swipe("world"), record(0.7),
        setup("Swipe trail", "Neon tube", only={"neon": {}}),
        swipe("magic"), record(0.7),
        setup("Swipe trail", "Ink brush", only={"ink": {}}),
        swipe("swipe"), record(0.7),
        setup("Swipe trail", "Circuit trail", only={"circuit": {}}),
        swipe("space"), record(0.9),

        mark("word"),
        setup("Swiped word", "Letter hop: join the suggestion",
              only={"hop": {"collect": "arc", "word": "join", "extra": "fall"}, "trace": {}}, text=""),
        swipe("keyboard", 0.14), record(1.7),
        setup("Swiped word", "Letter hop: form the word",
              only={"hop": {"collect": "bounce", "word": "form", "extra": "burn"}, "trace": {}}),
        swipe("magic"), record(1.7),
        setup("Swiped word", "Letter hop: fly into the text",
              only={"hop": {"collect": "zip", "word": "text", "extra": "dust"}, "trace": {}}),
        swipe("effects"), record(1.7),
        setup("Swiped word", "Constellation", only={"constellation": {}}),
        swipe("stars"), record(1.6),
        mark("end"), record(0.1),
    )


def recording(serve: str, steps: list[dict]) -> dict:
    """A web recording of the showcase. `serve` is the keyfx folder, relative to the config."""
    return {"source": "web", "serve": serve, "path": PAGE, "size": SIZE, "scale": 2,
            "settle": 1.2, "steps": steps}


def write(config: Path, recordings: dict) -> None:
    """Puts the recordings into the config, keeping every other section as it is."""
    data = json.loads(config.read_text(encoding="utf-8")) if config.exists() else {}
    data["recordings"] = recordings
    config.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    steps = sum(len(r["steps"]) for r in recordings.values())
    print(f"wrote {len(recordings)} recordings ({steps} steps) to {config}")
