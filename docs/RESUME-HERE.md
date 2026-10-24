# 下次开工指令

> 事实核对：**2026-10-24** ｜ git HEAD：`4a2042f`
> **先读 [PROJECT-GUIDE.md](PROJECT-GUIDE.md)** —— 那是权威的工作区地图。

---

## 一句话指令（直接复制）

```
读 D:\deepseekwork\neovim-cursor-pr\docs\PROJECT-GUIDE.md
和 docs\RESUME-HERE.md，然后跑 tools\settings-probe\run-t9-full.ps1 确认门禁，
按下面的待办继续。每步先写探针再写实现。
```

---

## 项目现状（命令核对，不要信文字）

```powershell
cd D:\deepseekwork\neovim-cursor-pr
git log --oneline -1
(Get-ChildItem src\main\java -Recurse -Filter *.java).Count
(Get-ChildItem tools\settings-probe -Filter V*.java).Count
Get-ChildItem dist -Filter 'neovide-cursor-*.jar' | Sort-Object Name | Select-Object -Last 1
```

**核对时的值（2026-10-24）**：

| 项 | 值 |
|---|---|
| git HEAD | `4a2042f` |
| java 文件 | 30 |
| 探针 | 55 |
| 本地最新 jar | `neovide-cursor-0.9.23.jar` |
| 用户已装 | 0.9.22 |
| GitHub 已发布 | v0.9.14（落后 9 版） |

---

## 待办（按优先级）

### P0：验证 0.9.23 的选区跟随

```
1. 单行：行首按 Ctrl+Shift+→ → 拖尾跟选区右端
2. 单行：选中后 Shift+← 缩回 → 拖尾往回跟
3. 多行（块选区）：Alt+拖选 3 行 + Ctrl+Shift+→ → 3 条线都跟
4. 多行：缩回选区 → 应该坍缩
```

**如果仍不对，先分清是：**
- A：跟随点错了（lead vs caret）
- B：块选区没走 followPositionOf
- C：速度不一致（charsMoved 或 rank）

### P1：GitHub 发布追平

本地领先 9 个版本，Release 停在 v0.9.14。
需要：打 tag + 上传 jar + 更新 Release 说明。

### P2：残影模式多光标

`AfterimageTrail` 是「单条历史轨迹」模型，多光标需要每光标一条轨迹 + 各自淘汰。

### P3：IME 探针 6 项破损

`tools/ime-probe/ImeGeomCriterionTest.java` 卡在 mock 基线顺序，
已在 `run-ime-geom-test.ps1` 标为「已知破损 + 警告」。

---

## 开发规则（硬性）

### 1. 先写探针，再写实现

**这不是建议。** 2026-10-24 一天内因跳过它返工 6 次：

| 返工 | 原因 |
|---|---|
| 块选区做成一整块矩形 | 没先确认「6 行退回 1 行应坍缩」这个判据 |
| 选区跟随修反 | 没先测 getVisualPosition vs getLeadSelectionPosition |
| 破坏 P0-5 契约 | 加了 getPrimaryCaret() 中转 |

### 2. 边界

| 规则 | 说明 |
|---|---|
| `core/NeovideConfig.java` | **冻结**，D0 用 git diff 守 |
| 默认值 | D0b 金表比对 24 个字段 |
| 构建 | 只用 `build/offline-build.ps1`，不用 Gradle |
| 安装 | **必须先清空 lib 目录** |

### 3. 常用命令

见 [PROJECT-GUIDE.md 第三节](PROJECT-GUIDE.md)。

---

## 绝对不要做的

| 禁止 | 原因 |
|---|---|
| 把块选区做成整块矩形 | 0.9.19 试过，多光标变一整块、退回不坍缩 |
| 恢复「前缘硬吸附」默认开启 | 与「平滑输入」矛盾，用户明确反对 |
| 改 `shortAnimationLength` 到 0.24 | 实测后缘滞后从 2.8px 恶化到 15.9px |
| 按 `active` 给常驻刷新降频 | 滚动/选择/补全不触发 caret 事件，会卡 |
| 改 `DampedSpring` 的衰减因子 `c` | 会逐帧累积放大 |
| 只换 jar 不清 lib | IDEA 会加载旧版本（10-24 真实事故） |

---

## 用户偏好

1. **只用中文**
2. **命令给可复制的纯文本块，用绝对路径**
3. **先给方案再动手** —— 「能不能做 X」是探询，不是开工令
4. **每完成一个功能就本地 git commit**（不推送）
5. **不要编造** —— 没实测就说没实测