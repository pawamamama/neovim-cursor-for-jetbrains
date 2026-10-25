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


**Language** — [**English**](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja-JP.md) | [한국어](README.ko-KR.md)

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

> **This is the biggest difference from the original** — 14 sliders + 9 checkboxes + 4 dropdowns, all live and persisted.

---

## How it differs from upstream

Built on [yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains) v0.5.x. **Same core trailing physics**, plus a whole usability layer upstream doesn't have:

| Capability | Upstream | **This edition** |
|---|---|---|
| Trail / glow / afterimage | ✅ | ✅ |
| Multi-cursor | ✅ | ✅ **supported since 0.9.32** |
| Keep native caret | ✅ | ⚠️ off by default (issue 2) |
| **Settings panel** | ❌ edit code | ✅ **14 sliders + 9 checkboxes** |
| **Persistence** | ❌ | ✅ saved into IDE config |
| **Animation presets** | ❌ | ✅ 3 |
| **Color presets** | ❌ | ✅ 6 (incl. animated rainbow) |
| **Custom presets** | ❌ | ✅ up to 50 |
| **Tools menu entries** | 4 | ✅ **8** |
| **Slant control (prev/next frame asymmetry)** | ❌ | ✅ **0–100, preset-aware** |
| **Live preview in panel** | ❌ | ✅ |

### Core contribution: character-based input detection

**This is the most fundamental difference from upstream, and the root of the better feel.**

Upstream infers the character count from **pixels**:

```java
jumpX = displacement / caretWidth;
isShortMove = |jumpX| <= threshold;   // threshold = max(16px, charWidth × 2.5)
```

This project uses the editor's **native character offset**:

```java
charsMoved = |currentOffset - previousOffset|;   // exact character count
isShortJump = sameLine && charsMoved <= typingMaxChars(3);
```

| Scenario | Upstream | **This project** |
|---|---|---|
| Single CJK char | Needs `shortMoveMinChars` compensation at large fonts | **Correct by construction** |
| Emoji / surrogate pairs | Must be converted manually | `getOffset()` gets it right |
| Multiple keystrokes in one frame | Misjudged as a long jump | `sameLine` + char-count guard |
| Changing font size | Threshold shifts, feel drifts | **Completely independent** |

**This design took four failed attempts to land** (all documented in source comments):

1. Normalize by caret width → a 2px caret can never exceed the threshold, so the short-jump branch **never fires**
2. Hard-code 16px → a CJK char at font size 20 is judged a long jump: "**English is smooth, deleting Chinese stutters**"
3. `FontMetrics.charWidth('m')` → 'm' is the Latin advance; CJK is twice that
4. Measure "column 0 to column 1 width" → that samples the **first** char of the line; still doubles when the caret sits in a CJK region

### Tuned input-feel parameters

Upstream's `trailFactors` is a **hard-coded array** `{1.0, 0.9, 0.5, 0.3}` — changing it needs a rebuild.

This project splits it into **three continuously adjustable** parameters:

| Parameter | Effect | Range |
|---|---|---|
| **Stretch strength** (`stretchRatioK`) | Derives the four-corner gradient | 1–8, continuous |
| **Typing smoothness** (`typingSmoothness`) | Flattens the gradient toward 1 | 0–100 |
| **Slant** (`slopeTicks`) | Pure rectangle → fully slanted | 0–100 |

Computed by the pure function `deriveTrailFactors(k, smooth, slope)`.
**The number shown in the panel and the value used at runtime share the same formula**
(this fixed a bug where the panel over-reported by 6.4×).

#### Slant (0–100) — the newest parameter

Controls **how much the quad leans**, independent of how far it stretches:

| Slant | Four-corner factors | Stretch | Lean |
|---|---|---|---|
| **0** | `0.86/0.86/0.32/0.32` | 5.84px | **0.00px** ← pure rectangle |
| 50 | `0.93/0.79/0.37/0.27` | 7.10px | 2.02px |
| **100** | `1.00/0.72/0.42/0.22` | 8.26px | 4.06px ← upstream original |

**Slant 0 still stretches** (5.84px) — it only removes the lean, not the deformation.
The math: lean comes from the *same-side* top/bottom corner time difference,
so averaging those pairs removes the lean while the top-vs-bottom gradient
(which produces stretch) survives.

Slant is **preset-scoped**: changing it derives a custom preset copy;
switching presets restores that preset's value.

#### Why single-line typing could never produce a slanted ribbon (fixed in v0.9.82)

The four-corner ranking used a "how many are smaller than me" count:

```java
for (j) if (alignment[j] < alignment[i]) rank++;   // ties are never counted
```

Typing right gives alignments `-0.71 / 0.71 / 0.71 / -0.71` — the left pair and
the right pair are tied, so both corners get the *same* rank:

```
before: TL=0  TR=2  BR=2  BL=0    only 2 distinct ranks
after:  TL=0  TR=2  BR=3  BL=1    4 distinct ranks
```

Same rank → same duration → same speed → both side edges stay vertical →
the quad can only be a rectangle. **Lean is always 0.**

Fixed with a stable sort plus index tiebreak (what all four upstream JS versions do).
Result matches upstream bit-for-bit: typing `0 2 3 1`, deleting `2 0 1 3`.

### Fixes contributed back upstream (merged into v0.5.1 / PR #10)

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
  -Version 0.9.83 `
  -OutName neovide-cursor-0.9.83.jar `
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

### 1. Afterimage mode is still single-cursor

The spring (trail) mode fully supports multiple cursors; **afterimage mode only follows the primary caret**.

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
