# 压缩前状态快照（2026-10-21 夜）

> 用途：上下文压缩后，**这一页就是全部真相**。先读这里，再读 `AUTONOMOUS-TASKS.md`。

---

## 0. 现在在哪个分支、什么状态

| 项 | 值 |
|---|---|
| **当前分支** | `fix/f7-override-read`（从 `f812047` = 0.9.8 基线新建） |
| 已提交 | **无**（F7 改动在工作区，未 commit） |
| 已跑到哪 | 阶段 A（摘 F7）—— **代码完成，探针通过，但有别的红** |
| 用户机器已装 | **0.9.8**（`41F16B3F` / 102,223 B）—— 手感未受影响 |

---

## 1. 我刚做完什么

### F7 已修（6 项版，适配 0.9.8）

文件：`src/main/java/.../ui/CursorPresetApplier.java`

```java
int idx = indexOf(p);
float[] fromOverride = CursorPresets.parseOverride(s.animPresetOverrides, idx);
if (fromOverride != null) { /* 用覆盖值 6 项 */ } else { /* 用常量，原行为 */ }
if (idx >= 0) { s.animPresetBase = idx; }
```

新增 `indexOf(AnimPreset)` 辅助方法。**编译通过。**

新探针 `tools/settings-probe/VOverride.java`（6 项域，非 0.9.9 的 13 项）：
**7 条全绿 PASS=7 FAIL=0**。

---

## 2. 当前门禁状态（重要：红的不全是我的问题）

在 0.9.8 基线上跑 `build-v-probes.ps1` 的结果：

| 探针 | 结果 | 红的原因 |
|---|---|---|
| **VOverride** | **PASS 7 / FAIL 0** | ✅ 我新加的，全绿 |
| VDefects | PASS 37 / FAIL 4 | **0.9.8 自带**：探针写死 10 个滑块，实际 11 个（有 typingSmoothness） |
| VScheme | PASS 13 / FAIL 1 | **0.9.8 自带**：S-1 与 R-18 修法冲突（0.9.9 才修） |
| VPreset | PASS 53 / FAIL 2 | **0.9.8 自带**：V14 断言字段数 19，实际 22 |
| VTools | PASS 11 / FAIL 1 | **0.9.8 自带**：M-11 按 6 字段断言，实际 13 |
| VSnap | PASS 6 / FAIL 1 | **0.9.8 自带**：shadowBlurFactor 量程表旧 |
| VSpring / VHints / VMixed | 全绿 | — |
| VOrder | FAIL 1 | 已知文档缺陷（O-1），informational 不参与门 |

**结论：这些红是 0.9.8 基线的探针缺陷，不是我的改动引入的。**
它们在 0.9.9 分支上已被修（`dcd082b` `a19e402`）。

**这正是 F1/F2 的来源** —— 0.9.9 补 v-probes 进门禁时暴露的那批。

---

## 3. 压缩后接手：三个选择

### 选择 A：把探针修复也摘过来（推荐）

0.9.8 基线要能过门禁，必须先修探针。可直接从 0.9.9 分支拿：

```powershell
git checkout feat/settings-ui-ready -- tools/settings-probe/VUi.java
# 然后逐个人工挑：VDefects / VPreset / VTools / VSnap / VScheme
```

**注意**：不能整批 checkout，因为 0.9.9 的探针假设 13 项预设域，
而 0.9.8 是 6 项。**必须逐个适配**。

### 选择 B：只提交 F7，不管其他红

F7 是独立的数据安全修复，`VOverride` 7 条已证明有效。
其他探针的红是既有的，可以先 commit 并记录，后续再修。

### 选择 C：回 0.9.9 分支继续

`git checkout feat/settings-ui-ready` —— 那里探针全绿，但产品代码含未采用的改动。

---

## 4. 我建议的顺序（压缩后照做）

1. **先 `git status` 确认在 `fix/f7-override-read`，F7 改动还在**
2. 跑 `build-v-probes.ps1` 确认 VOverride 仍 7 条全绿
3. 把 F7 **单独 commit**（只 `CursorPresetApplier` + `VOverride` + `build-v-probes.ps1` 的注册行）
4. 然后决定选择 A / B / C

**绝不做的**（见 `AUTONOMOUS-TASKS.md` 红线）：
- 不装 jar（已装 0.9.8，手感是好的）
- 不动物理/渲染参数
- 不重启 IDEA / DSH

---

## 5. 关键文件索引

| 文件 | 作用 |
|---|---|
| `AUTONOMOUS-TASKS.md` | 今晚任务书 + 红线 R1~R10 |
| `ARCHIVE-0.9.9.md` | 未解决项归档（超卡/迅猛/VSCode 差距） |
| `ASSERTIONS.md` | 断言清单（已校准为 12 段 / 209 条 / 9 变异） |
| `PARITY-BASELINE-0.9.9.md` | parity 判据修订记录 |
| `JITTER-ROOT-CAUSE.md` | 抖动根因（亚像素取整） |
| `RESEARCH-PRIOR-ART.md` | 三个开源实现调研 |
| `PROGRESS.md` | 当前指针 |