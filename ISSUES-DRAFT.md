# ISSUES-DRAFT —— 可直接提交给上游的 issue/PR 正文

> 仓库：`yyyyolo7a79-sketch/neovim-cursor-for-jetbrains`
> 基线：`6bed09d`（v0.5.0）
> 提交人：`pawamamama`（已提交过 issue #1 / #2 / #3、PR #1）
>
> **提交顺序建议**：先 PR-1（一行补丁，最容易接受）→ 再 issue A（CJK，最重要）→ 再 issue C（IME，新发现）→ 最后 issue B（文档矛盾，小事）。
> 每条都可独立提交，互不依赖。

---

# 【PR-1】fix: reset `lastNanos` on wake-up to avoid full-clamp first frame

**标题**：`fix: reset lastNanos in wakeUp() to avoid a full-MAX_DT first frame after idle`

**Body**：

```markdown
## Problem

After the editor has been idle (or unfocused) for a while, the **first** frame after
resuming renders with `dt` clamped to `MAX_DT` (≈33.3 ms) instead of a normal ~8 ms.
That single oversized step makes the damped spring collapse almost instantly:

| `dt` | `c = exp(-(4/animationLength)·dt)` | remaining after one frame |
|---|---|---|
| 0.008 s (normal) | 0.527 | 47 % |
| 0.0333 s (after idle) | 0.070 | **7 %** |

(computed with `animationLength = 0.05`)

So the tail — which should unfold over several frames — **teleports in one frame**.
Visually this is "stutter on the first delete-to-previous-line after a pause".

## Why it is easy to miss

Fast consecutive edits feel **smooth**, which hides the bug. That asymmetry is the
tell-tale sign: while you type continuously, `lastNanos` is refreshed every 8 ms;
only the first frame after a pause sees the whole idle gap.

## Root cause

`lastNanos` is updated only inside `tick()`:

```java
// CaretAnimator.tick()
long now = System.nanoTime();
double dt = Math.min((now - lastNanos) / 1_000_000_000.0, MAX_DT);
lastNanos = now;
```

`wakeUp()` resets `currentDelayMs` and `nextDueNanos`, but **not** `lastNanos`:

```java
// CaretAnimator.wakeUp()
currentDelayMs = ACTIVE_DELAY_MS;
nextDueNanos = System.nanoTime();
RenderScheduler.wakeUp();
```

So the first tick after idle still computes `dt` from the stale `lastNanos`.

## Fix (one line)

```diff
  private void wakeUp() {
      currentDelayMs = ACTIVE_DELAY_MS;
      nextDueNanos = System.nanoTime();
+     lastNanos = System.nanoTime();
      RenderScheduler.wakeUp();
  }
```

If you prefer a single clock read:

```java
long now = System.nanoTime();
currentDelayMs = ACTIVE_DELAY_MS;
nextDueNanos = now;
lastNanos = now;
RenderScheduler.wakeUp();
```

## Scope / risk

- Only affects **the first frame after waking from an idle tier**.
- In the active tier `lastNanos` is already fresh, so normal typing is untouched:
  `framePending` + `nextDueNanos` logic is unchanged, no delay constants change.
- Does not change any animation timing, geometry, or drawing.

## How I verified it

I maintain a fork and reproduced the state machine frame-by-frame. Simulating a wake
from the idle tier (criterion: scheduling interval settles back to ≤12 ms):

| | time to full frame rate |
|---|---|
| before | 26 frames / **715 ms**, running at ~40 ms/frame at the start |
| after | **1 frame / 8 ms** — full rate immediately |

That magnitude matches the "about one second of sluggishness" that users report.

Happy to adjust the approach if you plan to restructure the scheduler — the fix is
independent of how the wake-up is triggered.
```

---

# 【Issue A】CJK short-jump misclassification: one Chinese character falls into the cross-line branch

**标题**：`Short-jump test is pixel-based, so CJK input falls into the cross-line branch (no stretch at all)`

**Body**：

```markdown
## Symptom

With a Chinese IME, typing / deleting a **single Chinese character** looks
"snappy but lifeless" — the quad snaps instead of stretching. English typing in the
same editor is smooth. The difference is systematic, not occasional.

## Root cause

`TrailCorner.jump()` normalises the jump distance by the **cursor width**:

```java
double jumpX = (destX - previousDestX) / width;   // width = config.cursorWidth = 2f
double jumpY = (destY - previousDestY) / height;
double len = Math.hypot(jumpX, jumpY);
boolean isShortMove = len <= config.shortMoveThreshold;   // 8
```

`cursorWidth` is 2 px, but a full-width CJK glyph at font size 20 is ~20 px:

| operation | dx (px) | `jumpX` | `len` | classified as |
|---|---|---|---|---|
| English 1 char | 10 | 5.0 | 5.0 | ✅ short move |
| **CJK 1 char** | **20** | **10.0** | **10.0** | ❌ **cross-line branch** |

And in the cross-line branch with `trailSize = 1`:

```java
double leading = animationLength * (1 - trailSize);   // = 0
```

`leading == 0` means the two leading corners get `animationLength = 0`, and
`DampedSpring.update()` short-circuits on `animationLength <= dt` → the leading edge
**teleports** while the trailing edge slowly follows. That is not "stretch", that is
"snap".

With `shortAnimationLength = 0.11` and the per-rank factors, the short-move branch
would have produced 110 / 79 / 46 / 24 ms — a full gradient. **CJK input never gets it.**

## Suggested fix: use the editor's native character offset

The platform already expresses "how many characters did the caret move" — comparing
offsets gives an exact integer that is independent of font, size, full-width vs
half-width, ligatures, and surrogate pairs:

```java
// CaretAnimator.refreshTarget()
int offset = editor.getCaretModel().getOffset();
int charsMoved = Math.abs(offset - lastCaretOffset);
lastCaretOffset = offset;
```

```java
// TrailCorner.jump()
boolean isShortMove;
if (charsMoved >= 0) {
    boolean sameLine = Math.abs(deltaYpx) <= height * 0.5;
    isShortMove = sameLine && charsMoved <= 3;
} else {
    isShortMove = Math.hypot(dx / width, dy / height) <= config.shortMoveThreshold; // fallback
}
```

`getOffset()` is also cheaper than two `visualPositionToXY()` conversions.

## Three approaches I tried that do **not** work

Sharing these so nobody repeats them:

| approach | failure |
|---|---|
| Hard-coded 16 px threshold | at font size 20 a CJK glyph is ~20 px → one deletion already exceeds it |
| `FontMetrics.charWidth('m') × 2.5` | `'m'` is a Latin advance; CJK is 2× that. Just moves the magic number |
| width of column 0 → column 1 | samples the **first glyph of the line**, so it still doubles when the caret sits in a CJK run |

All three produced "English smooth, Chinese stutter".

## Additional caveat for a frame-rate-adaptive scheduler

`|Δoffset|` is cumulative **since the last frame**. At 125 fps that is 1 per keystroke,
but on an idle-reduced schedule (120–600 ms) one frame may cover 3–5 keystrokes, which
would exceed the threshold and flip into the cross-line branch — the same visual jump.
If your scheduler can idle down, either read the offset from the caret event, or judge
by "same line **and** horizontal displacement within one reasonable jump" rather than by
the raw count.

## Environment

- IntelliJ IDEA 2026.1.3 (`IU-261.25134.95`), JBR 25.0.3, Windows 11
- Font size 20 px, heavy CJK input
- Upstream `6bed09d` (v0.5.0): `TrailCorner` contains no `charsMoved`/`getOffset` usage
```

---

# 【Issue C】IME composition leaves the overlay caret stuck half a glyph off

**标题**：`Caret overlay gets stuck mid-glyph during IME composition and only recovers on the next input`

**Body**：

```markdown
## Symptom

While a Chinese IME is in composition state (pinyin typed but candidate not committed),
the overlay caret sometimes freezes **inside** a glyph — e.g. on the vertical stroke of
「中」, i.e. about **half a glyph width** off. It stays there:

- scrolling the editor: **no** effect
- switching tabs and back: **no** effect
- resizing the window: recovers
- **typing one more character: recovers immediately, and the caret lands at the end of the line**

It is not reliably reproducible (~"after 10+ characters, about half the time"), and it is
purely visual — the real caret and the actual click position are unaffected (the overlay
panel returns `contains() == false`).

## Evidence it is a coordinate-cache problem, not a wrong computation

Four experiments:

| experiment | result | meaning |
|---|---|---|
| same input with an **English** IME | **does not reproduce** | IME-composition specific |
| **scroll** the editor | no effect | the plugin does not observe scrolling at all |
| **switch tab** and back | no effect | content-component size unchanged → no resync |
| **type one more char** | **recovers instantly** | a changed column invalidates the cache |

The last row is the decisive one: it recovers as soon as the visual column changes, which
means the cached value was wrong — the coordinate was computed once and then reused.

## Mechanism

```java
// CaretAnimator.refreshTarget()
if (resync || !trail.isInitialized() || !trail.isAt(vp.line, vp.column)) {
    Point p = editor.visualPositionToXY(vp);   // ← only recomputed when (line, column) changes
    trail.markAt(vp.line, vp.column);
    ...
}
```

During composition, `getVisualPosition().column` can point **inside** the composition
string, so `visualPositionToXY()` yields an x that is a fraction of a glyph away from the
real insertion point. That intermediate `(line, column)` gets cached. When composition
ends, if the resulting column happens to equal the cached one, the fast path concludes
"nothing moved" and **never recomputes**.

`forceResync` is only set when the content component's size changes:

```java
// CaretAnimator.tick()
if (panel.getWidth() != content.getWidth() || panel.getHeight() != content.getHeight()) {
    ...
    forceResync = true;
}
```

There is no scroll/viewport listener anywhere in `src/` (grep for
`Scroll|VisibleArea|Viewport` → 0 hits), which is exactly why scrolling does nothing.

## Suggested fixes (in increasing order of scope)

**1. One-line, lowest risk** — force a resync on any caret event:

```java
@Override
public void caretPositionChanged(@NotNull CaretEvent event) {
    forceResync = true;
    wakeUp();
}
```

This does not fix the frozen-in-composition moment, but it makes scrolling / tab
switching recover, and guarantees the cache is invalidated whenever the platform reports
a caret change.

**2. Most accurate** — cross-check with the caret offset:

`getOffset()` is already read for the short-jump decision. During composition the
**offset usually does not change** while the visual column does. So:

```java
if (offsetUnchangedButVisualPositionChanged) {
    // composition in progress — skip this frame (or snap to the last valid target)
}
```

**3. Full fix** — listen for composition start/end and force a resync (or pause drawing).
This needs a platform API; I have not verified which one is stable.

## Note

This is present in `6bed09d` as well — I verified the same fast-path +
`forceResync` structure exists upstream (L508 / L323). Purely visual, so it may be
acceptable to leave it; but since it is invisible on a non-CJK setup, I thought it worth
reporting.

I have a longer write-up with the full experiment log if useful.
```

---

# 【Issue B】`maxTrailDistanceFactor = 60` contradicts the README pitfall #6

**标题**：`maxTrailDistanceFactor is 60f in code, while README pitfall #6 says it was tightened to 4`

**Body**：

```markdown
## Inconsistency

`NeovideConfig` currently reads:

```java
public float maxTrailDistanceFactor = 60f;
```

But the README pitfall #6 states it was tightened to **4** (~128 px) and that
"normal movement is completely unaffected". The field's own javadoc even mentions
"used to be 4", so the value apparently landed and was later reverted — code and
docs have disagreed for a while.

## What 60 means in practice

The clamp is `maxDistance = max(cursorWidth, lineHeight) × factor`. With a 32 px line
height, 60 → **1920 px**, which exceeds the visible height of most editors. The quad can
be stretched across the whole screen, which:

- covers a lot of text (most visible when deleting a line break)
- blows the repaint bounding box up to nearly full screen (the degradation described in
  pitfall #7)

## Why 4 would not hurt normal movement

The clamp applies to `spring.position` — the pixel offset of one corner, i.e. the size of
the current move. Adjacent columns are ~8 px, one line up/down is ~32 px; both are far
below 128 px, so the clamp never engages. Only deliberate long jumps (delete-line-break,
jump 10+ lines) reach it, and there the tail is simply truncated to ~4 lines.

`clampSpring` only modifies `position`, not `velocity`, so it introduces no jitter or
bounce.

## What I am asking

This is a feel trade-off, so I am **not** proposing a change — just flagging that code
and docs disagree. Either:

1. set it back to 4 (matching the README), or
2. keep 60 and update pitfall #6 to say why 60 was chosen.

Either way the two should agree, otherwise the next reader (or a future refactor) will
follow the wrong one.
```

---

# 【Issue D — 可选，附在 A 或 C 里】踩坑汇总（供重构参考）

若你希望一次把经验都给出去，可以把这段附在 issue A 末尾，或单独开一个
`docs: pitfalls found while profiling the Swing overlay approach` 的 issue：

```markdown
## Directions I ruled out with measurements (so you don't have to)

| hypothesis | measurement | verdict |
|---|---|---|
| drawing is slow | 12-layer glow = **0.05 ms/frame** (0.6 % of an 8 ms budget); full Swing paint 0.09 ms | ❌ |
| repaint area too large | area **67× larger was 15× faster** (1.06 M px → 66.6 fps vs 3.5 K px → 15.7 fps) | ❌ |
| GC | longest pause 15 ms, while stalls were 900 ms–31 s | ❌ |
| GPU | JBR 25 loads no D3D/OpenGL pipeline; cursor is 64 px; Java2D does not accelerate `fill(Path2D)` anyway | ❌ |
| the plugin hogs the EDT | 45-min JFR: plugin = **3.26 %** of samples | ❌ |
| **IDE's own painting** | `ScaledBlit.Scale` = **12.6 %** of EDT | ⚠️ the real head |

The plugin's own logs show `delay=8ms` (scheduler is fine) with `maxGap=358ms` /
`399ms` — tasks were **queued 120–400 ms** in the EDT. So the bottleneck is EDT
contention, not our drawing.

## Two pitfalls worth knowing before you restructure

**1. Sub-stepping the physics is a no-op.**

```
one update(0.12)        = 6.828984590243152
15 × update(0.008)      = 6.828984590243153
difference = 8.88e-16   ← identical
```

Because `updateCorners()` / `submitRepaint()` live *outside* the step loop, only one
frame is ever rendered per tick. "15 steps inside one frame" == "1 step".

**2. Clamping per-frame displacement breaks the spring.**

An early attempt capped single-frame displacement at 50 % and zeroed velocity when it
triggered. At 20 fps that fires every frame, so the motion stops being a critically
damped curve — it felt "mushy". Do not clamp `position` delta; if you must bound a
jump, clamp `spring.position` itself (which is what `maxTrailDistanceFactor` already does).

## Biggest remaining wins (if useful)

| priority | what | why |
|---|---|---|
| P0 | skip dirty-region submission when the editor is unfocused **and** fully static | at 125 fps × N editors that is ~1000 repaints/s redrawing an already-correct picture |
| P1 | idle the scheduler for hidden tabs | currently `!content.isShowing()` only clears the corners but keeps ticking at 8 ms, calling `visualPositionToXY` every frame |
```
