import com.github.yyyolo7a79.neovidecursor.core.GlowIntensity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 守门探针：辉光的「目标曲线 + 限速下降」（B 方案，0.9.126）。
 *
 * <h2>它抓的是什么 bug</h2>
 *
 * <p>0.9.125 的淡出是「保持满亮 → 到点乘 0」：{@code CaretAnimator} 在拖尾还有偏移时
 * 每帧把计时起点重置、并强制 {@code fade = 1}，于是只能等拖尾归零、再等保持期结束
 * 才开始淡。用户感知为「停下后才熄灭」，而且淡出作用在已经缩到最小的光晕上，很突兀。
 *
 * <p>0.9.126 改成限速下降：目标仍是 {@link GlowIntensity#strengthFor(double)}，
 * 但每帧最多下降 {@code dt / fadeOutSeconds}，保持期从「最后一次移动」起算。
 * 本探针对纯函数逐段断言，并扫源码防止有人退回「全局 fade 乘子」。
 *
 * <h2>【0.9.126 契约变更】旧断言逐条去向</h2>
 *
 * <p>旧版 T-1~T-19 断言的是 {@code fade()} / {@code useLastGlowForGroup()} /
 * {@code glowForGroup()} 三个函数。它们表达的是「到点乘 0」模型，本次按用户决定
 * 整体替换为限速模型，因此旧断言不再成立。这是<b>契约变更，不是为了让测试通过而改测试</b>：
 *  <ul>
 *   <li>旧 T-1~T-6（fade 曲线 / needsMoreFrames 旧签名）→ 新 C-1~C-7；</li>
 *   <li>旧 T-7~T-10、T-16（保持亮度 / 逐组判据）→ 新 A-5、A-11、新源码守卫 D-2~D-5；</li>
 *   <li>旧 T-15（线性 fade 0.5 / 0 / 1）→ 新 A-2~A-4、A-12（限速速率与 75/300/500 差异）；</li>
 *   <li>旧 T-18（计时不被 moved 包住）→ 新 A-11（无新移动时必须单调不增）。</li>
 *  </ul>
 *
 * <p>为什么保留源码守卫思路：纯函数测试无法发现「生产代码根本没用它」。
 * 0.9.125 的 BUG-B 就是判据写对了、调用点仍然把两个语义合成一个布尔。
 */
public final class VFadeTick {

    private static int pass = 0;
    private static int fail = 0;

    private static final long IDLE_MS = 120;   // = CaretAnimator.IDLE_DELAY_MS

    public static void main(String[] args) {
        System.out.println("=== V-FADE-TICK：限速下降（B 方案，0.9.126）===");

        // ==================== A. advanceGlow 纯函数 ====================

        // ---------- A-1：目标高于当前 → 立即上升（AC-2 按键回满） ----------
        check("A-1 目标高于当前 → 立即到目标（按键立刻回满）",
                eq(GlowIntensity.advanceGlow(true, 0.2f, 1f, 0.008, 0.075, false), 1f),
                "got=" + GlowIntensity.advanceGlow(true, 0.2f, 1f, 0.008, 0.075, false));

        // ---------- A-2：单帧降幅 = dt / 淡出时长 ----------
        float exp75 = 1f - (float) (0.008 / 0.075);
        check("A-2 fade=75ms：单帧降幅 = 8/75",
                eq(GlowIntensity.advanceGlow(true, 1f, 0f, 0.008, 0.075, false), exp75),
                "got=" + GlowIntensity.advanceGlow(true, 1f, 0f, 0.008, 0.075, false)
                        + " exp=" + exp75);

        // ---------- A-3 / A-4：300ms、500ms 的单帧降幅更小 ----------
        float exp300 = 1f - (float) (0.008 / 0.300);
        float exp500 = 1f - (float) (0.008 / 0.500);
        check("A-3 fade=300ms：单帧降幅 = 8/300",
                eq(GlowIntensity.advanceGlow(true, 1f, 0f, 0.008, 0.300, false), exp300), "");
        check("A-4 fade=500ms：单帧降幅 = 8/500",
                eq(GlowIntensity.advanceGlow(true, 1f, 0f, 0.008, 0.500, false), exp500), "");

        // ---------- A-5：保持期内不下降 ----------
        check("A-5 保持期 holdActive=true → 亮度不变",
                eq(GlowIntensity.advanceGlow(true, 0.5f, 0f, 0.008, 0.075, true), 0.5f), "");

        // ---------- A-6：目标地板，不允许降到目标以下 ----------
        check("A-6 目标 0.1、单帧本应降到 0.093 → 被目标托住",
                eq(GlowIntensity.advanceGlow(true, 0.2f, 0.1f, 0.008, 0.075, false), 0.1f),
                "got=" + GlowIntensity.advanceGlow(true, 0.2f, 0.1f, 0.008, 0.075, false));

        // ---------- A-7：开关关闭 → 直接走曲线（旧行为） ----------
        check("A-7 overlayFadeOut=false → 直接返回目标曲线",
                eq(GlowIntensity.advanceGlow(false, 0.5f, 0.2f, 0.008, 0.075, false), 0.2f), "");

        // ---------- A-8：淡出时长 0 → 立即到位（不是归零） ----------
        check("A-8 fadeOutSeconds=0 → 立即到位（目标 0.4 时返回 0.4）",
                eq(GlowIntensity.advanceGlow(true, 1f, 0.4f, 0.008, 0.0, false), 0.4f),
                "got=" + GlowIntensity.advanceGlow(true, 1f, 0.4f, 0.008, 0.0, false));

        // ---------- A-9：NaN 守卫（不能静默画出「没有辉光」） ----------
        check("A-9a current=NaN → 0",
                eq(GlowIntensity.advanceGlow(true, Float.NaN, 0f, 0.008, 0.075, false), 0f), "");
        check("A-9b target=NaN → 0",
                eq(GlowIntensity.advanceGlow(true, 1f, Float.NaN, 0.008, 0.075, false), 0f), "");

        // ---------- A-10：非法时长不能冻结亮度（会永久空转） ----------
        check("A-10a fadeOutSeconds=NaN → 立即到目标（不能冻结亮度）",
                eq(GlowIntensity.advanceGlow(true, 0.6f, 0f, 0.008, Double.NaN, false), 0f), "");
        check("A-10b fadeOutSeconds=+Infinity → 立即到目标",
                eq(GlowIntensity.advanceGlow(true, 0.6f, 0f, 0.008,
                        Double.POSITIVE_INFINITY, false), 0f), "");
        check("A-10c dt=0 → 保持 current（零步长不动）",
                eq(GlowIntensity.advanceGlow(true, 0.6f, 0f, 0.0, 0.075, false), 0.6f), "");
        check("A-10d 非法淡出时长不会让调度永久空转",
                !GlowIntensity.needsMoreFrames(true, 0f, 0f, false, 0, IDLE_MS), "");

        // ---------- A-11：20 帧单调不增、始终不低于目标 ----------
        float b75 = 1f;
        boolean monotonic = true;
        for (int i = 0; i < 20; i++) {
            float next = GlowIntensity.advanceGlow(true, b75, 0f, 0.008, 0.075, false);
            if (next > b75 + 1e-6f || next < 0f) {
                monotonic = false;
            }
            b75 = next;
        }
        check("A-11 20 帧单调下降、不反弹、不越界", monotonic, "b75=" + b75);

        // ---------- A-12：75 / 300 / 500 在 20 帧后必须可区分 ----------
        float a = 1f, b = 1f, c = 1f;
        for (int i = 0; i < 20; i++) {
            a = GlowIntensity.advanceGlow(true, a, 0f, 0.008, 0.075, false);
            b = GlowIntensity.advanceGlow(true, b, 0f, 0.008, 0.300, false);
            c = GlowIntensity.advanceGlow(true, c, 0f, 0.008, 0.500, false);
        }
        check("A-12 20 帧后 75 < 300 < 500（参数真实有效）",
                a < b && b < c, "75=" + a + " 300=" + b + " 500=" + c);

        // ---------- A-13~A-15：单 trail 入口（生产/预览共用） ----------
        long now = 2_000_000_000L;
        check("A-13 从未移动的 trail（lastMove=0, offset=0）→ 0（拆组不继承）",
                eq(GlowIntensity.advanceTrailGlow(true, 0f, 0.0, 0L, now,
                        0.008, 50, 0.075), 0f), "");
        check("A-14 正在移动的 trail（offset=8, lastMove=now）→ 立即满强度",
                eq(GlowIntensity.advanceTrailGlow(true, 0f, 8.0, now, now,
                        0.008, 50, 0.075), 1f), "");
        check("A-15 保持期内不降、随后按 fade 下降",
                eq(GlowIntensity.advanceTrailGlow(true, 1f, 0.0, now,
                        now + 40_000_000L, 0.008, 50, 0.075), 1f)
                        && GlowIntensity.advanceTrailGlow(true, 1f, 0.0, now,
                        now + 100_000_000L, 0.008, 50, 0.075) < 1f,
                "");

        // ==================== B. holdRemainingMs ====================

        long t0 = 1_000_000_000L;
        check("B-1 从未移动（lastMove=0）→ 0",
                GlowIntensity.holdRemainingMs(0L, t0, 50) == 0L, "");
        check("B-2 刚移动 → 剩余 = delay",
                GlowIntensity.holdRemainingMs(t0, t0, 50) == 50L, "");
        check("B-3 已过 30ms（delay=50）→ 剩 20ms",
                GlowIntensity.holdRemainingMs(t0, t0 + 30_000_000L, 50) == 20L, "");
        check("B-4 已过 50ms → 0",
                GlowIntensity.holdRemainingMs(t0, t0 + 50_000_000L, 50) == 0L, "");
        check("B-5 now < lastMove（时钟回拨）→ 按满保持处理",
                GlowIntensity.holdRemainingMs(t0, t0 - 1_000_000L, 50) == 50L, "");

        // ==================== C. needsMoreFrames ====================

        check("C-1 开关关闭 → 不续帧",
                !GlowIntensity.needsMoreFrames(false, 1f, 1f, false, 0, IDLE_MS), "");
        check("C-2 亮度为 0 → 不续帧",
                !GlowIntensity.needsMoreFrames(true, 0f, 0f, false, 0, IDLE_MS), "");
        check("C-3 目标 > 0（拖尾还在）→ 续帧",
                GlowIntensity.needsMoreFrames(true, 0.8f, 1f, false, 0, IDLE_MS), "");
        check("C-4 保持期还剩 400ms → 不空转",
                !GlowIntensity.needsMoreFrames(true, 1f, 0f, true, 400, IDLE_MS), "");
        check("C-5 保持期只剩 100ms（≤ 空闲帧）→ 必须续帧",
                GlowIntensity.needsMoreFrames(true, 1f, 0f, true, 100, IDLE_MS), "");
        check("C-6 正在下降（hold=false，target=0）→ 必须续帧",
                GlowIntensity.needsMoreFrames(true, 0.5f, 0f, false, 0, IDLE_MS), "");
        check("C-7 target=NaN → 不续帧（不能永久空转）",
                !GlowIntensity.needsMoreFrames(true, 0.5f, Float.NaN, false, 0, IDLE_MS), "");

        // ==================== D. 源码守卫 ====================

        String animator = readSource(
                "src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java");
        if (animator == null) {
            check("D-0 能读到 CaretAnimator 源码", false, "读不到源码");
        } else {
            check("D-0 能读到 CaretAnimator 源码", true, "");
            String code = stripComments(animator);
            check("D-1 CaretAnimator 调用 GlowIntensity.advanceTrailGlow（生产真的用了新模型）",
                    code.contains("GlowIntensity.advanceTrailGlow("), "未找到调用");
            check("D-2 CaretAnimator 不再调用旧的 glowForGroup",
                    !code.contains("GlowIntensity.glowForGroup("), "旧调用仍在");
            check("D-3 CaretAnimator 不再保留 lastGlowStrength 保持路径",
                    !code.contains("lastGlowStrength"), "旧字段仍在");
            check("D-4 CaretAnimator 不再保留 lastAnimatingNanos 计时",
                    !code.contains("lastAnimatingNanos"), "旧字段仍在");
            check("D-5 CaretAnimator 不再出现旧的 `fade > 0f && fade < 1f` 判据",
                    !code.contains("fade > 0f && fade < 1f"), "旧判据仍在");
            check("D-6 CaretAnimator 使用逐 trail 的亮度状态（glowStates）",
                    code.contains("glowStates"), "未找到状态容器");
            // 0.9.126 的关键契约：保持期必须从「最后一次移动」起算。
            // 若改回收拢时刻，观感就退回 0.9.125「停下才暗」。
            int iSetTarget = code.indexOf("if (trail.setTarget(cx, cy, w, h)) {");
            int iSetTargetEnd = iSetTarget < 0 ? -1 : code.indexOf("}", iSetTarget);
            int iLastMove = iSetTarget < 0 ? -1
                    : code.indexOf("lastMoveNanos = now;", iSetTarget);
            check("D-9 lastMoveNanos 在 setTarget 分支内更新（保持期从移动起算）",
                    iSetTarget >= 0 && iLastMove > iSetTarget
                            && (iSetTargetEnd < 0 || iLastMove < iSetTargetEnd),
                    "setTarget=" + iSetTarget + " end=" + iSetTargetEnd
                            + " lastMove=" + iLastMove);
            check("D-10 组亮度只用于本帧显示，不写回 trail 状态（拆组不继承）",
                    !java.util.regex.Pattern.compile("brightness\\s*=\\s*groupBrightness")
                            .matcher(code).find(),
                    "发现把组亮度写回 trail");
            check("D-11 渲染使用组内最大亮度（不是重新算曲线）",
                    code.contains("float trailGlow = groupBrightness[trail.groupId];"),
                    "未找到 trailGlow = groupBrightness");
            check("D-12 每个 trail 用 advanceTrailGlow 推进",
                    code.contains("st.brightness = GlowIntensity.advanceTrailGlow("),
                    "未找到 per-trail 推进");
            // 【审查 M-1】D-10 只匹配 `brightness = groupBrightness` 紧邻形式；
            // 两步写回（先取局部变量再赋值）会绕过。这里数 tickSpring 内
            // `.brightness =` 的出现次数：只允许 1 处，且右值必须是 advanceTrailGlow。
            String springBody = bodyOf(code,
                    "private boolean tickSpring(", "private boolean tickAfterimage(");
            int brightnessAssigns = count(springBody, ".brightness =");
            check("D-13 tickSpring 内 .brightness 只赋值一次且来自 advanceTrailGlow",
                    !springBody.isEmpty() && brightnessAssigns == 1
                            && springBody.contains(".brightness = GlowIntensity.advanceTrailGlow("),
                    "assigns=" + brightnessAssigns + " bodyLen=" + springBody.length());
        }

        String glowSrc = readSource(
                "src/main/java/com/github/yyyolo7a79/neovidecursor/core/GlowIntensity.java");
        String glowCode = glowSrc == null ? "" : stripComments(glowSrc);
        check("D-7 GlowIntensity 提供 advanceGlow",
                glowCode.contains("public static float advanceGlow("), "");
        check("D-8 GlowIntensity 不再保留旧的 glowForGroup",
                !glowCode.contains("public static float glowForGroup("), "");

        System.out.println();
        System.out.printf("=== V-FADE-TICK 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-FADE-TICK RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-FADE-TICK RESULT: ALL PASS");
        System.exit(0);
    }

    private static boolean eq(float got, float exp) {
        return Math.abs(got - exp) <= 1e-5f;
    }

    /** 取两个方法签名之间的源码片段；找不到起始标记返回空串。 */
    private static String bodyOf(String src, String startMarker, String endMarker) {
        int i = src.indexOf(startMarker);
        if (i < 0) {
            return "";
        }
        int j = src.indexOf(endMarker, i + startMarker.length());
        return j < 0 ? src.substring(i) : src.substring(i, j);
    }

    /** 统计子串出现次数。 */
    private static int count(String s, String needle) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * 剥掉 Java 的行注释与块注释。
     *
     * <p>源码扫描必须只看代码：本项目的注释习惯大量引用「旧实现长什么样」，
     * 连注释一起扫会让「记录历史」变成「触发假红」。
     *
     * <p>已知限制：不识别文本块（{@code """}）。当前 {@code CaretAnimator} 没有
     * 文本块；若将来加入，需要扩展本状态机，否则 D 段源码守卫可能静默失准。
     */
    private static String stripComments(String src) {
        if (src == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        boolean inLine = false, inBlock = false, inStr = false, inChar = false, esc = false;
        while (i < n) {
            char c = src.charAt(i);
            char d = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') { inLine = false; out.append(c); }
            } else if (inBlock) {
                if (c == '*' && d == '/') { inBlock = false; i++; }
                else if (c == '\n') { out.append(c); }
            } else if (inStr) {
                out.append(c);
                if (esc) { esc = false; }
                else if (c == '\\') { esc = true; }
                else if (c == '"') { inStr = false; }
            } else if (inChar) {
                out.append(c);
                if (esc) { esc = false; }
                else if (c == '\\') { esc = true; }
                else if (c == '\'') { inChar = false; }
            } else if (c == '/' && d == '/') {
                inLine = true; i++;
            } else if (c == '/' && d == '*') {
                inBlock = true; i++;
            } else if (c == '"') {
                inStr = true; out.append(c);
            } else if (c == '\'') {
                inChar = true; out.append(c);
            } else {
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    private static String readSource(String rel) {
        try {
            String repo = System.getProperty("neovide.repo", ".");
            Path p = Paths.get(repo).resolve(rel);
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("  读取源码失败：" + rel + " —— " + e);
            return null;
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "   " + detail);
        }
    }
}
