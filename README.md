# Neovide Cursor for JetBrains — Configurable Edition

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2%2B-000000?logo=intellijidea&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)

> Brings a **Neovide-style trailing cursor** to JetBrains IDEs — the caret leaves a fading, stretched trail wrapped in a soft glow.
>
> **Works with** IntelliJ IDEA / CLion / PyCharm and the rest of the JetBrains family on **2024.2 – 2026.2+**
>
> **What this edition adds**: on top of the original effect, a **full settings panel + persistence + preset system**.
> The original only lets you tweak parameters by editing code — this one lets you adjust, save and switch directly inside the IDE.

---

## Demo

> ### ⚠️ Read this first: the video looks choppier than reality
>
> The clips below are **60fps screen recordings**, but recording drops frames and compression loses detail.
>
> **The author's hands-on feel is noticeably better than what the video shows** — especially the continuity of the trail while typing.
> Don't judge the actual experience by video frame rate; install it and try.

Video files live in [`docs/media/`](docs/media/) in this repo.
**Click the links to download** (GitHub can't embed mp4 playback in a README).

### 1. Long trail (cross-line jumps)

[📹 docs/media/01-large-trail.mp4](docs/media/01-large-trail.mp4) · 11s · 1436×444 · 60fps

### 2. Typing and deleting

[📹 docs/media/02-typing-delete.mp4](docs/media/02-typing-delete.mp4) · 10s · 1040×442 · 60fps

> This one shows the "stretch" best — note how the caret gets pulled back on delete.

### 3. Tools menu preset switching (motion + color)

[📹 docs/media/03-tools-preset.mp4](docs/media/03-tools-preset.mp4) · 45s · 1630×878 · 60fps

### 4. Settings panel (all parameters)

[📹 docs/media/04-settings-panel.mp4](docs/media/04-settings-panel.mp4) · 39s · 1722×1074 · 60fps

> **This is the biggest difference from the original** — 11 sliders + 7 checkboxes, all live and persisted.

---

## How it differs from upstream

Built on [yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains) v0.5.x. **Same core trailing physics**, plus a whole usability layer upstream doesn't have:

| Capability | Upstream | **This edition** |
|---|---|---|
| Trail / glow / afterimage | ✅ | ✅ |
| Multi-cursor | ✅ | ❌ see [Known Issues](#known-issues) |
| Keep native caret | ✅ | ⚠️ off by default (issue 2) |
| **Settings panel** | ❌ edit code | ✅ **11 sliders + 7 checkboxes** |
| **Persistence** | ❌ | ✅ saved into IDE config |
| **Animation presets** | ❌ | ✅ 3 |
| **Color presets** | ❌ | ✅ 5 |
| **Custom presets** | ❌ | ✅ up to 50 |
| **Tools menu entries** | 4 | ✅ **8** |
| **Live preview in panel** | ❌ | ✅ |

### Fixes contributed back upstream

Three fixes were merged into upstream **v0.5.1** ([PR #10](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains/pull/10)):

| Fix | Impact |
|---|---|
| Reset timing only when waking from idle tier | Trail no longer plays the whole animation at 25fps after a pause |
| CJK short-move detection (`shortMoveMinChars`) | A single CJK char at large font sizes is no longer misjudged as a long jump |
| Document modification stamp in the coord cache | No more misalignment after a third-party IME commits |

---

## Installation

### Option 1: Download a Release (recommended)

1. Get `neovide-cursor-*.jar` from [Releases](../../releases)
2. IDE → `Settings → Plugins → ⚙ → Install Plugin from Disk...`
3. Pick the jar, restart

### Option 2: Build it yourself

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 `
  -Version 0.9.14 `
  -OutName neovide-cursor-0.9.14.jar `
  -BaseJar "<path-to-any-idea-plugin-jar>"
```

**No Gradle** — `build\offline-build.ps1` compiles with `javac` and packs with `jar`,
needing only one bundled IDEA jar as a classpath base.

---

## Usage

### Where the settings panel is

`Settings / Preferences → Other Settings → Neovide Cursor`

Four groups:

| Group | Contents |
|---|---|
| **Master switches** | Enable, render mode (spring / afterimage), hide native caret, always-high-fps |
| **Timing** | Cross-line duration, short-move duration, stretch strength, typing smoothness |
| **Appearance** | Trail / glow colors, glow layers, sharpness, opacity, fade-out |
| **Presets** | Animation preset, color preset, new / rename / delete |

**Searchable**: type "trail" or "glow" in the settings search box to jump straight to an item.

### Tools menu

`Tools → Neovide Cursor: Animation Preset ▸` / `Color Preset ▸`

Plus four toggles: master switch, hide native caret, paint diagnostic, afterimage mode.

### Preset semantics

**Built-in presets are read-only.** Change any parameter while a built-in preset is selected
and a "<name> copy" custom preset is derived and selected automatically — **the built-in one never changes.**

Want to keep a configuration? Click "New preset" and give it a name.

> A custom preset stores a **snapshot of all 19 parameters**, not a diff.

---

## Parameters

| Parameter | Default | Notes |
|---|---|---|
| **Stretch strength** (`stretchRatioK`) | `4.5` | Four-corner duration gradient. Higher = more slanted / ribbon-like. Range 1–8 |
| **Typing smoothness** (`typingSmoothness`) | `0` | Flattens the four-corner ratios toward 1. **0 = keep the gradient** |
| **Short-move duration** (`shortAnimationLength`) | `0.11` | Trail length for same-line typing / backspace |
| **Cross-line duration** (`animationLength`) | `0.1` | Trail length for cross-line jumps |
| **Glow sharpness** (`shadowBlurFactor`) | `0.20` | Expansion radius = value × longer caret side |
| **Glow layers** (`glowLayers`) | `8` | **Only affects transition smoothness, not brightness** |
| **Trail density** (`trailSize`) | `1.0` | Duration difference between leading and trailing corners |

> ⚠️ **"Typing smoothness" purely flattens the gradient — it does not make the animation smoother.**
> Measured: `smooth=0` has a *lower* second-order difference than `smooth=0.8` (i.e. smoother),
> while keeping a 3.3× larger gradient. The default 0 is an empirical choice; avoid raising it casually.

---

## Known Issues

> Documented honestly, no sugar-coating.

### 1. No multi-cursor support

Upstream has it (`Map<Caret, SpringTrail>`); this edition only draws the primary caret.
**With Alt+drag column selection, only the primary caret gets a trail.**

### 2. Native caret mode can look like "two carets"

This edition **hides the native caret by default** (`hideNativeCaret = true`) and draws everything itself.

If you choose to keep the native caret:

- IDEA's native caret is about **5px** wide
- The overlay uses `NeovideConfig.cursorWidth` (**2px** default)
- **Mismatched widths → both edges show, looking like two carets**

The fix is identified (read `EditorSettings.getLineCursorWidth()`) but **not implemented yet**.

### 3. Frame rate is limited by the EDT

`invokeLater` tasks rank below native input events in Swing/AWT, so frames drop when the editor is busy.

**Measured** (2609 log samples): median fps **36.4**, median maxGap **51ms**, P90 **279ms**.

**Alternative**: `Tools → Afterimage mode`. It records real historical positions, so dropped frames
only make the ghosts sparser — the trajectory itself never distorts.

### 4. Fast typing can't use a long trail

`TrailCorner.jump()` unconditionally `reset()`s on every keystroke, so any trailing-corner duration
longer than the key interval (80–150ms) **is never reached in practice**.
A declared 240ms trail isn't felt during fast typing.

### 5. No hard snap on the leading edge

Upstream trades "leading corner snaps instantly" for stretch. This edition doesn't —
zero leading-edge duration means the leading edge has no smoothing at all,
which directly contradicts "typing smoothness".

### 6. The "Basic" preset is the choppiest of the three

"Basic" must bit-match the `NeovideConfig` defaults (contract requirement), while the other two
use longer short-move durations and therefore smaller per-frame displacement.

---

## Implementation notes

### Mount point: `contentComponent`

`editor.getContentComponent()` (`EditorComponentImpl`) is the only safe spot:
it's a `JTextComponent` with a `null` layout, so `revalidate()` can't stretch the overlay,
and its coordinate system natively matches `visualPositionToXY()`.

**Disproven mount points**: `JBViewport`, `editor.getComponent()`, ancestor `JLayeredPane`,
window root `LayeredPane` — all cause the overlay to be stretched across the whole editor.

### Trail physics: stretch, not afterimage

1. Four corners of the caret rect, each with X/Y damped springs (`DampedSpring`, closed-form — frame-rate independent)
2. On movement, rank the corners by the angle between motion direction and corner direction (`assignRanks`)
3. Leading corners get large factors (follow closely), trailing corners get small ones (lag)
4. The rectangle is stretched into an irregular quad → visually a trail

### High-precision scheduling

| Mechanism | Measured precision |
|---|---|
| `javax.swing.Timer` (`Object.wait`) | 15.61 ms ❌ pinned at 64fps |
| `Thread.sleep` | **1.56 ms** ✅ |

Hence: "background thread sleeping on a schedule + `invokeLater` back to the EDT to paint".

### Glow: multi-layer concentric expansion

Per-layer alpha is solved from the layer count: `A = 1 - (1 - peak)^(1/N)`
— so "layers" and "intensity" don't interfere. Radius is linear, decaying to 60% of peak at `r ≈ glowWidth/2`.

---

## Acknowledgements

- **[yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains)**
  — where this project started. Core trailing physics, pitfall notes and layout all come from there.
- **[LengineerC/vscode-neovide-cursor](https://github.com/LengineerC/vscode-neovide-cursor)**
  — the basis for matching parameter semantics with the VS Code version.
- **[Neovide](https://github.com/neovide/neovide)** — original source of the trailing effect.

---

## License

MIT, see [LICENSE](LICENSE).

The algorithm derives from the VS Code `neovide-cursor` extension (MIT), see [NOTICE.md](NOTICE.md).
