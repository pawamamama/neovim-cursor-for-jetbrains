# Neovide Cursor for JetBrains

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2%20–%202026.1-000000?logo=intellijidea&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)

> A Neovide-style trailing caret for JetBrains IDEs. Four independent damped
> springs pull the caret rectangle into a fading ribbon, wrapped in a soft
> multi-layer glow.

**Current release: `v0.9.138-a1`** (2026-11-06).
The A1 visual-line branch unification is implemented and has been verified
hands-on by the maintainer on **IntelliJ IDEA 2026.1.3** (Windows 11).

**Language:** English | [简体中文](README.zh-CN.md)

---

## What's new in 0.9.138-a1 (A1)

Before A1 the plugin chose its animation branch with **two different
predicates**:

- single caret: `|offset change| <= typingMaxChars` (character count);
- multiple carets: a hard-coded **16 px** pixel fallback (`charsMoved = -1`).

Real-device logs showed that this split misclassified same-line jumps
(`Tab`, `End`, `Ctrl+Arrow`, word-wise movement, same-line mouse jumps) as
cross-line jumps: 93 of 100 multi-caret CROSS decisions in a 320-sample log
were on the same visual line, 49 of them only 17 px away from the previous
position.

A1 replaces both with **one rule**:

```text
same screen visual line  -> SHORT branch  (shortAnimationLength)
visual line changed      -> CROSS branch  (animationLength)
```

What changed:

- Single-caret and multi-caret paths call the same
  `SpringTrail.semanticCharsMoved(initialized, previousVisualLine, currentVisualLine)`.
- The settings preview sends the same two semantic values:
  `INLINE -> 0`, `CROSS -> Integer.MAX_VALUE`.
- The old character-count predicate (`lastCaretOffset` /
  `updateCharsMoved`) and the multi-caret pixel fallback
  (`updateBlockCharsMoved`) are **removed from the production path**.
- `TrailCorner`'s legacy pixel fallback (`charsMoved < 0`) is kept only for
  callers that supply no semantic value; the production path no longer uses it.
- Behaviour change: same-line large jumps now use the **short** branch instead
  of being misclassified as cross-line. A real line change still uses the
  **CROSS** branch and keeps the existing A-like ribbon shape.
- Each caret keeps its own previous visual line, so in one frame a caret that
  really changes line goes CROSS while another caret on the same line goes
  SHORT.

**Verification for this release** (commands and full logs are in
[the release report](docs/sessions/2026-11-06-release-0.9.138/REPORT.md)):

| Check | Result |
|---|---|
| Full probe gate | `V-PROBES RESULT: ALL PASS` (117 registered probes) |
| D0 gate | `core/NeovideConfig.java` zero diff (`EXIT=0`) |
| A1-specific probes | `VMultiBranchDiag` 35/35, `VPreviewBranch` 8/8, `VTypingSpan` 9/9 |
| Hands-on acceptance | **PASS** on IDEA 2026.1.3, 2026-11-06 |

---

## Features

- **Stretch, not afterimage.** Each corner of the caret rectangle runs its own
  X/Y damped spring; ranks are assigned from the movement direction, so the
  leading corners follow quickly and the trailing corners lag. The rectangle is
  deformed into a quad instead of leaving ghost copies.
- **Visual-line branch rule (A1).** Short typing/backspace animations and
  cross-line ribbons are selected by the caret's screen visual line, for single
  and multiple carets alike.
- **Seven-tab settings panel.** Every visual parameter is adjustable in the IDE
  and persisted; no source edits or rebuilds.
- **Presets.** 3 built-in animation presets, 6 built-in color presets, and up to
  50 custom presets. Built-in presets are read-only: editing one derives a
  "<name> copy" custom preset.
- **Live preview.** The cursor-line, multi-line and glow tabs each contain an
  animation preview driven by the same production logic.
- **Tools menu shortcuts.** Switch animation/color presets and the main toggles
  without opening Settings.
- **Glow fade.** Glow brightness follows the trail intensity and may only fall
  at `dt / fadeOutDuration` per frame, so the fade parameters control how slowly
  it dims rather than when it starts.
- **Multi-caret support** in spring mode, with per-trail glow state.
- **i18n.** 168 message keys, English root plus Simplified Chinese.

---

## Settings panel (7 tabs)

`Settings / Preferences -> Other Settings -> Neovide Cursor`

| Tab | Contents |
|---|---|
| **1 Master** | Enable, hide native caret, always high FPS, target FPS (125 / 144), multi-caret no glow |
| **2 Color** | Trail color, trail opacity, glow color, cursor gradient (dual-tone) |
| **3 Cursor line** | Short-move duration, typing smoothness, stretch strength, slant, leading-edge snap (placeholder), cursor width/height |
| **4 Multi-line** | Cross-line duration, trail density, cross-line shape experiment (default off) |
| **5 Glow** | Live preview, glow layers, sharpness, opacity, fade hold/duration, glow fade toggle |
| **6 Special** | Render mode (spring / afterimage), experimental options |
| **7 Debug** | Diagnostic logging, paint diagnostics |

Control count: **14 sliders + 11 checkboxes + 4 dropdowns**. The settings model
has **29 fields**, of which **13** are preset-scoped.

### Key parameters

| Parameter | Default | Notes |
|---|---|---|
| Stretch strength (`stretchRatioK`) | `4.5` | Four-corner duration gradient, range 1–8 |
| Typing smoothness (`typingSmoothness`) | `0` | Flattens the gradient toward 1; `0` keeps it |
| Slant (`slopeTicks`) | `0` | 0 = rectangle, 100 = upstream four-tier lean |
| Short-move duration (`shortAnimationLength`) | `0.11 s` | Same-visual-line typing / backspace |
| Cross-line duration (`animationLength`) | `0.10 s` | Visual-line changes; max 0.35 s |
| Trail density (`trailSize`) | `1.0` | Duration spread between leading and trailing corners. **1.0 is the intended value**; lowering it shortens the ribbon |
| Glow layers (`glowLayers`) | `8` | Affects transition smoothness, not overall brightness |
| Glow sharpness (`shadowBlurFactor`) | `0.20` | Expansion radius = value × longer caret side |
| Glow opacity (`glowOpacity`) | `0.55` | Peak opacity of the glow |
| Fade hold (`cursorDisappearDelay`) | `50 ms` | Hold before the glow starts to fall |
| Fade duration (`cursorFadeOutDuration`) | `0.075 s` | Maximum fall rate per frame |
| Cursor width / height | `2 px` / `1.0` | Height 1.0 = full line height |

> **`trailSize = 1.0` is deliberate.** It produces the longest visible ribbon.
> Lowering it makes the trail shorter and less noticeable; it is not a fix for
> any known issue.

---

## Experimental features — not recommended for daily use

### Cross-line shape experiment (`crossShapeExperiment`)

- **Device-level check box, default OFF**, in the **Special** tab.
- When ON, it takes over only the **cross-line / mouse-drag / large keyboard
  jump** path. Single-line typing, slant, stretch, smoothing, glow, colors and
  fade are unchanged.
- Current formula (A-like, VS Code 1.0.1 style): corners with movement
  alignment `> 0.5` get a fixed **20 ms** duration; the others get
  `animationLength × {1.0, 0.72, 0.42, 0.22}`.
- **Status: experimental and unfinished.** The A-like shape has not passed a
  visual acceptance. Do **not** use it for everyday work.
- When ON, **Trail density is greyed out** and no longer affects that path; its
  value is kept, not cleared. Cross-line duration remains the time base.
- Combining it with the **Ribbon** preset can look bad; this is a known
  combination limit.

### Leading-edge snap (`leadingSnap`)

A placeholder check box. It can be toggled and persisted, but **does not change
any behaviour**; the UI label says the feature is not implemented.

### Afterimage mode

Experimental render mode. It records real past positions, so dropped frames
only make the afterimages sparser; however it **follows only the main caret**
and the glow fade does not apply to it. Use the default **Spring (stretch)**
mode unless you explicitly need it.

---

## Installation

### From a release (recommended)

1. Download `neovide-cursor-0.9.138-a1.jar` from
   [Releases](https://github.com/pawamamama/neovide-cursor-for-jetbrains/releases).
2. IDE: `Settings -> Plugins -> gear -> Install Plugin from Disk...`
3. Select the jar and restart the IDE.

### With the repository script (Windows, development convenience)

```powershell
# Preview the backup/drop list only; touches nothing
pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.138-a1 -DryRun

# Install without starting the IDE
pwsh -NoProfile -ExecutionPolicy Bypass -File build\install.ps1 -Version 0.9.138-a1 -NoStart
```

The script closes IDEA, clears the plugin `lib` directory, the settings XML and
the diagnostic log, copies the new jar, then writes the backed-up personal
settings back. It targets the **IDEA 2026.1** configuration path on Windows and
does not install into PyCharm.

> The installed file name is always `jetbrains-neovide-cursor-0.9.8.jar` (a
> loading convention). **Do not infer the version from the file name**; read
> `<version>` inside `META-INF/plugin.xml`.

### Build from source

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build\offline-build.ps1 `
  -Version 0.9.138-a1 `
  -OutName neovide-cursor-0.9.138-a1.jar `
  -BaseJar "<path-to-an-idea-plugin-jar>"
```

No Gradle is required: `offline-build.ps1` compiles with `javac` and packages
with `jar`, using one bundled IDE jar as the classpath base.

---

## Compatibility

| Item | Value |
|---|---|
| IDE build range | `since-build="242"` / `until-build="261.*"` -> IntelliJ Platform **2024.2 through 2026.1** |
| Java | 21 (class file version 65) |
| Verified hands-on | **IntelliJ IDEA 2026.1.3** (build 261), Windows 11 |
| Verified build | `0.9.138-a1`, jar SHA-256 `b81ec276b403eb81d2caaf68393daac492f0245c6542ef5bef99ede0252688ff` |
| Not verified | 2024.2 runtime; other JetBrains products (WebStorm, GoLand, CLion, Rider, PhpStorm, ...); Linux / macOS |
| Not supported | Fleet |

PyCharm 2025.2 on the maintainer's machine runs **upstream 0.6.0** for
comparison; it is not this build.

---

## Known limitations

1. **Afterimage mode is single-caret.** Spring mode supports multiple carets;
   afterimage mode only follows the main caret.
2. **Keeping the native caret can look like two carets.** The native caret is
   about 5 px wide, while the overlay defaults to 2 px. Keep
   `hideNativeCaret = true` (the default) to avoid it.
3. **Frame rate is limited by the EDT.** Rendering runs through `invokeLater`,
   below native input events. Measured on 2609 log samples: median 36.4 fps,
   median max gap 51 ms, P90 279 ms.
4. **Fast typing cannot use a long trail.** `TrailCorner.jump()` resets on every
   keystroke, so a trailing-corner duration longer than the key interval
   (80–150 ms) is not reached in practice.
5. **The "Basic" preset is the choppiest of the three.** It must match the
   `NeovideConfig` defaults bit-for-bit; the other two presets use longer
   short-move durations.
6. **Glow can breathe at about 10 keys/second.** With the default 50 ms hold /
   75 ms fade the brightness may move between roughly 0.57 and 1.0; at 25/50 it
   can dip to about 0.32. Raise "Hold before fade" to 100 ms or increase
   "Fade duration" if it bothers you.
7. **`crossShapeExperiment` is unfinished.** See the warning above; keep it off
   for daily use.
8. **`leadingSnap` is a placeholder.** Toggling it changes nothing.
9. **Terminals are not animated.** Terminal carets are not controlled by
   `CARET_COLOR`; this is a known platform limitation.
10. **A1 changes same-line large jumps.** `Tab`, `End`, `Ctrl+Arrow`,
    word-wise movement and same-line mouse jumps now use the short branch. This
    is the intended A1 behaviour and was accepted hands-on; it is not a bug. A
    real line change still uses the CROSS ribbon.

---

## How it works

1. The caret rectangle has four corners; each corner has X/Y damped springs
   (`DampedSpring`, closed-form, frame-rate independent).
2. On movement, corners are ranked by the alignment between the movement
   direction and the corner direction (`assignRanks`, stable sort + index
   tiebreak).
3. The rank maps to a duration factor; leading corners get large factors
   (follow closely), trailing corners get small ones (lag).
4. A1 selects SHORT vs CROSS from the caret's previous/current **screen visual
   line**, for single and multiple carets.
5. Glow is drawn as concentric layers; per-layer alpha is solved from the layer
   count, so "layers" and "intensity" do not interfere. Fade is rate-limited by
   `dt / fadeOutDuration`.
6. Frames are scheduled by a background thread sleeping on `nanoTime` and
   posting to the EDT via `invokeLater`.

Further reading:

- [docs/CURRENT-STATE-2026-11-04.md](docs/CURRENT-STATE-2026-11-04.md) — current version, trailSize, A–E status
- [docs/WORKFLOW.md](docs/WORKFLOW.md) — five-step project workflow
- [docs/WORK-DISCIPLINE.md](docs/WORK-DISCIPLINE.md) — the 14 project rules
- [docs/PARAMETER-MAP.md](docs/PARAMETER-MAP.md) — which parameters follow presets
- [docs/INDEX.md](docs/INDEX.md) — full documentation map
- [CLAUDE.md](CLAUDE.md) — hand-off guide for a new session

---

## Contributors

- **pawamamama** ([@pawamamama](https://github.com/pawamamama)) — fork
  maintainer: settings/preset layer, i18n, platform-252 read-action fix, glow
  fade model, and the A1 visual-line branch unification.
- **yyyyolo7a79-sketch** — original project and core trailing physics.

---

## Acknowledgements

- **[yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains)**
  — the starting point: core trailing physics, pitfall notes and layout.
- **[LengineerC/vscode-neovide-cursor](https://github.com/LengineerC/vscode-neovide-cursor)**
  — basis for matching parameter semantics with the VS Code version.
- **[Neovide](https://github.com/neovide/neovide)** — the original source of the
  trailing effect.

---

## License

MIT, see [LICENSE](LICENSE).

The algorithm derives from the VS Code `neovide-cursor` extension (MIT); see
[NOTICE.md](NOTICE.md).

---

<sub>**For contributors:** the probe gate is the project's test suite — run
`tools/settings-probe/build-v-probes.ps1` and check the final
`V-PROBES RESULT` line. `core/NeovideConfig.java` is D0-locked: any diff there
fails the release gate.</sub>
