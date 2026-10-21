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
- **Typing effects:** key pop, letter float, a heat streak that warms as you type faster, and
  special keys (backspace crumbles, enter sweeps its row, space shimmers).
- **Swipe typing effects:** key trace, and letter hop, where crossed letters hop into the
  suggestion bar and snap together into the recognized word.
- **Long press and spacebar:** a charge ring that fills during a hold, and a trackpad look
  (dimmed keys and a grid) while the spacebar moves the cursor.
- **Keyboard appearing:** rows cascade into place, bottom first.
- **Classic effects:** ripple, key glow, particles, and a spacebar drag trail.

Every effect has its own toggle and uses your Material You accent. Effects switch off when
Android's "Remove animations" setting is on.

## Install

1. Install the APK and enable **FUTO Tweaks** in LSPosed or Vector.
2. Scope it to FUTO Keyboard (whichever variant you have installed).
3. Force stop FUTO Keyboard in Android settings, then open the keyboard.
4. Adjust options from the module's settings button, or the FUTO Tweaks app icon.

## Build

- **Android Studio:** open this folder and run *Build > Build APK(s)*.
- **Command line:** `./gradlew assembleRelease` (output in `app/build/outputs/apk/release/`).
- **GitHub Actions:** push to a repo, and `.github/workflows/build.yml` builds the APK and
  attaches it to a release. Add the `FORK_KEYSTORE_B64`, `FORK_KEYSTORE_PASSWORD`,
  `FORK_KEY_ALIAS` and `FORK_KEY_PASSWORD` secrets to sign with your own key.

## How it hooks

Nav bar hiding hooks only Android's `InputMethodService`. The spacebar and effects hooks
use `MainKeyboardView.onTouchEvent`, `onKeyPressed`, the `mSpaceKey` field and `Key`'s
`x/y/width/height` fields. FUTO builds without name obfuscation, so these stay stable across
updates. Each hook installs independently and logs to the LSPosed log if a FUTO update
breaks it, without affecting the others.
