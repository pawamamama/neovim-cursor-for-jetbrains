# leadingSnap 实机验收失败复盘与决策材料（2026-11-01 下午）

> 用途：交给**决策会话**决定下一步方案。本文件不预设结论。
> 当前工作区：路线 C（前缘 0ms / 后缘 20ms）已全绿（109 探针 / 1264 断言，`VLeadingSnap` 33/33），**未提交**。
> 本轮尝试的「前缘 45ms + 后缘 120ms + 尾巴封顶 6px」实验**已完整回退**，工作区无半成品。

---

## 一、用户实机反馈（原话）

安装 v0.9.117（路线 C）后：

> 「没有效果出来 像是 idea 原生光标那样卡卡的，连续删除输入也没有效果」

追问后：

> 「根本没有尾巴」「我说了要平滑，现在卡卡的甚至不如之前那两版」

光标颜色确认：**浅蓝色 #7DD3FC** —— 说明插件在绘制，光标是我们的，不是 IDEA 原生。

---

## 二、日志证据（不是推测）

`%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.1\log\idea.log`：

```
10:56:23 INFO  neovide-cursor: 已从设置服务同步 CONFIG；alwaysHighFps=true,
               overlayFadeOut=false, enabled=true, hideNativeCaret=true
10:58:00–10:58:42 WARN  RenderScheduler: 调度到期但投递不出去（连续 10 轮），
               EDT 可能被阻塞；本轮退避 1 ms   × 数十条
10:58:24 INFO  neovide-cursor: applyConfig 完成（animators=10, trailMode=SPRING,
               hideNativeCaret=true, enabled=true）
```

- 插件正常加载、配置正常、`leadingSnap=true` 已在 XML 中（安装时补写）。
- 10:58 的 EDT 阻塞持续约 40 秒，与「IDEA 刚打开项目、索引占满 EDT」吻合；它是**加剧**卡顿的因素，但不是「没有尾巴」的原因。
- 无任何异常 / ClassNotFound / read-action 错误。

---

## 三、根因：三版模型各错在哪

| 版本 | 模型 | 用户反馈 | 机制上的原因 |
|---|---|---|---|
| 30fe004 | 前缘 0ms + 后缘 `shortAnimationLength×trailFactors` | 「拉伸过长、盖住字符」 | 整块矩形瞬间横跨；后缘最长 110ms |
| c2ae1e9 | 四角同长 110ms + 绘制层固定尾巴 | 「只有一个残影，是淡一点的光标」「连续跟不上」 | 本体永远落后一次移动；尾巴是分离的平移矩形 |
| 当前（路线 C） | 前缘 0ms + rank1 13.3ms + rank0 20ms | 「像原生光标卡卡的」「没有尾巴」 | **前缘 0ms = 每键瞬移一格**；**尾巴只存在 24ms、6px**，连打看不见 |

### 关键矛盾（这决定了不能再只调一个数）

在隐藏原生光标的前提下，「本体」就是用户看到的唯一光标，因此它**必须平滑移动**。而路线 C 为了「不落后」把前缘设成 0ms，物理上就是瞬移 —— 这正是「像 IDEA 原生光标」的来源。

同时，纯临界阻尼弹簧里，后缘的**尾巴长度**与**停留时间**由同一个参数 `animationLength` 决定：

```
首帧剩余位移 = 位移 × (1 + 4·dt/L) × exp(-4·dt/L)
dt = 8ms、位移 12px：
  L=0.02s → 6.30px（停留约 24ms）
  L=0.04s → 9.71px
  L=0.06s → 10.79px（停留约 60ms）
  L=0.12s → 11.58px（停留约 160ms）
```

**要尾巴短（≤6px）就必然很快消失；要停留久就必然长到接近一个字符宽。** 用户既要「短」又要「看得见」，纯弹簧无解。

---

## 四、候选方案（供决策会话拍板）

### A. 短而久：后缘时长 120ms + 位移封顶 6px（本轮试到一半，已回退）

- 前缘 45ms（平滑、约 5–6 帧，非瞬移）；rank1 80ms；rank0 120ms。
- 后缘弹簧位置每帧 `clamp` 到 6px：尾巴长度固定半个字符，但用 120ms 平滑收回。
- 已写好的代码（**已回退**，可据本文件重做）：
  `TrailCorner.HALF_TRAIL_MAX_PX` + `trailOffsetCap` 字段 + `update()` 的 clamp 分支；
  `SpringTrail.assignRanks()` / `AnimationPreview.pushTarget()` 对 rank≤1 设置 cap。
- 风险：`clampSpring` 只截位置、不改速度；连续输入时后缘每帧被截到 6px，视觉应稳定，但**尚未实机验证**。停止时从 6px 平滑收回。
- 与用户要求的匹配度：**平滑 ✅ / 有尾巴 ✅（6px 固定、停留久）/ 不跨格 ✅**。

### B. 长而快：纯弹簧，后缘 60–80ms

- 前缘 40–45ms，后缘 60–80ms，不封顶。
- 尾巴首帧约 10–11px，可见且停留 60–80ms。
- 风险：尾巴覆盖刚走过字符格的大部分（但仍 < 12px，不跨格）。用户之前明确说过「别把字符吞掉」，可能再次否决。

### C. 绘制层梯形尾巴（回到第二套绘制，但改形状）

- 本体四角平滑（约 45ms）；尾巴由绘制层画成**从本体底边延伸出去的梯形**（不是平移矩形），长度 6–8px、透明度渐变。
- 优点：长度与时长彻底解耦，可同时「短 + 久 + 有渐变」。
- 风险：这正是 c2ae1e9 被否决的路线；必须解决「像第二个光标」的根因（梯形连接、透明度渐变、与本体同色），否则重蹈覆辙。

### D. 回滚 0.9.116（c2ae1e9）

- 用户原话「甚至不如之前那两版」，回滚不是他要的结果，仅作为兜底。

---

## 五、决策会话需要回答的问题

1. 选 A / B / C / D 哪条？（推荐先试 A，因为它最接近「平滑 + 有尾巴 + 不跨格」，且改动已有一版可复用。）
2. 若选 A：前缘 45ms 是否足够平滑？后缘 120ms + 6px cap 是否够「看得见」？这两个数可各自独立调。
3. 若选 C：尾巴长度、透明度、形状（梯形/三角/圆角）具体怎么定？
4. 是否要把「前缘时长 / 尾巴时长 / 尾巴长度」暴露为设置项？（用户此前说「参数有点多」，倾向不暴露。）

---

## 六、当前工作区状态（接手前必看）

- 分支 `main`，HEAD `c2ae1e9`；**所有本批改动未提交**。
- 生产：`TrailCorner / SpringTrail / CaretTrailPanel / CaretAnimator / AnimationPreview / NeovideCursorConfigurable` 已按路线 C 改完，全绿。
- 探针：`VLeadingSnap` 33 条全绿；全量 109 探针 / 1264 断言 ALL PASS（`VRegr2` 两条 informational 不变）。
- 已回退：本轮 cap 实验（`HALF_TRAIL_MAX_PX / trailOffsetCap / update clamp` 及探针期望）。
- 已安装：v0.9.117（路线 C），配置 `leadingSnap=true` 已写入 XML。
- 报告与计划：`docs/archive/reports/LEADING-SNAP-ISSUES-2026-11-01.md`、`plans/leading-snap-v2.md`、本文件。

接手命令：

```powershell
git status --porcelain
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/settings-probe/build-v-probes.ps1
```
