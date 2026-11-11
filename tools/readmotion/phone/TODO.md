# Phone recording: still to do

The README media is recorded in keyfx's lab, in a browser. The one thing the lab can't show is
the module's own settings inside FUTO Keyboard, so the hero is meant to end on a real phone recording of them. Until that
recording exists, the hero ends after the message is sent.

## What to record

- **File:** `settings.mp4` in this folder, a screen recording from the phone (Android's own
  screen recorder is fine). About 6 seconds, portrait.
- **Show:** open FX for FUTO Keyboard's settings (from LSPosed or Vector, or the app icon), scroll a little, switch one effect (say,
  Backspace tap → Shatter), go back to a text field and tap backspace so the new effect plays.
- Use the same accent as the hero, `#5EEAD4`, if the phone lets you pick one, and keep
  notifications off so nothing personal shows.

## Adding it

1. In `readmotion.json`, add a recording beside `hero` and `gallery`:

   ```json
   "phone": {"source": "video", "file": "phone/settings.mp4", "width": 880}
   ```

2. Show it as its own clip, or as a still, in the `clips` section (`clips` can be a list, one
   section per recording):

   ```json
   {"recording": "phone", "names": ["settings"], "width": 360, "step": 2, "ms": 66}
   ```

3. Add a caption under `readme.captions`, for example `"settings": "the module's settings,
   on a phone"`, and remove the `TODO(phone)` comment in `scenes.py`.
4. Record and build again (see the README's *Recording the README media*).
