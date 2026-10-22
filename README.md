# FUTO Tweaks

An LSPosed / Vector module for [FUTO Keyboard](https://keyboard.futo.org). It changes the
keyboard at runtime, so you keep using the official FUTO app and its updates.

## Features

- **Vertical spacebar cursor:** drag the spacebar up or down to move the cursor between lines.
  Once a drag goes mostly vertical, the module takes it over and moves by line vertically and
  by character sideways. Short or sideways drags stay with FUTO, so its normal cursor drag,
  language swipe and long press keep working.
- **Hide the keyboard navigation bar:** removes the down arrow and keyboard switcher below
  the keys (Android 15 or newer).
- **Effects**, all from the FUTO Tweaks Lab, each with its own settings:
  - Typing: key pop, letter float (rise, grow or ghost key), heat streak, neighbor wave.
  - Special keys: enter sweep; spacebar stars, puff or shimmer; backspace fire, slash or
    shatter, with the deleted letter burning, splitting or shattering too.
  - Backspace hold: the key becomes a black hole, lava, an opening box or a hole, and the
    deleted letters swirl, fly on curves, or get one press each.
  - Swipe trail: key trace, comet, ink brush, stardust.
  - Swipe word: letter hop on the number row (or the top row without one), constellation,
    commit burst, with letter picking by turns, pauses or every key crossed.
  - Long press and spacebar: hold charge (ring or liquid fill) and the spacebar trackpad.
  - Keyboard show: center bloom or row cascade.

Effects use your Material You accent by default, and switch off when Android's
"Remove animations" setting is on.

## Settings

Open the module's settings from LSPosed or Vector, or from the FUTO Tweaks app icon.

- **Changes apply right away**, even while the keyboard is open.
- **Test field:** the box at the top stays in view, so you can type and swipe in it while you
  adjust settings.
- **Import:** paste an export from the FUTO Tweaks Lab to copy its settings over. Section and
  setting names match the lab's, and anything the module doesn't have is skipped.

A few lab settings need changes inside FUTO itself (the bloom accent popup, the hide
animation, lighting up the predicted word in the suggestion bar). Those come with the FUTO
fork and don't show here.

## Install

1. Install the APK and enable **FUTO Tweaks** in LSPosed or Vector.
2. Scope it to FUTO Keyboard (whichever variant you have installed).
3. Force stop FUTO Keyboard in Android settings, then open the keyboard.

## Project layout

- `keyfx/` is the effects library: the settings schema, the engine, and the effect modules.
  It uses plain Android graphics only, with no FUTO or Xposed code, so it can move to its
  own repository and be shared with the FUTO fork.
- `app/` is the Xposed module: it hooks FUTO, turns FUTO's events into keyfx events, and
  generates its settings screen from the keyfx schema.

## Build

- **Android Studio:** open this folder and run *Build > Build APK(s)*.
- **Command line:** `./gradlew assembleRelease` (output in `app/build/outputs/apk/release/`).
- **Without the Android SDK:** `nosdk/build.sh` builds with javac, dx, aapt2 and
  uber-apk-signer, all from GitHub. See the script's header for the jars it needs.
- **GitHub Actions:** push to a repo, and `.github/workflows/build.yml` builds the APK and
  attaches it to a release. Add the `FORK_KEYSTORE_B64`, `FORK_KEYSTORE_PASSWORD`,
  `FORK_KEY_ALIAS` and `FORK_KEY_PASSWORD` secrets to sign with your own key.

## How it hooks

Nav bar hiding and the cursor position use only Android's `InputMethodService`. Touches come
from `MainKeyboardView.onTouchEvent`, typed keys and swipes from `LatinIMELegacy`'s
`KeyboardActionListener` methods, and key positions from the `Keyboard` and `Key` fields.
FUTO builds without name obfuscation, and these are all things R8 can't inline away, so they
stay stable across updates. Each hook installs independently and logs to the LSPosed log if a
FUTO update breaks it, without affecting the others.
