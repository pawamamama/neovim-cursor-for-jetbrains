import com.github.yyyolo7a79.neovidecursor.core.GlowIntensity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 守门探针：辉光淡出的两个判据（BUG-B，2026-11-01）。
 *
 * <h2>它抓的是什么 bug</h2>
 *
 * <p>{@code CaretAnimator.tickSpring()} 原来只有一个布尔：
 * <pre>
 *   boolean fading = config.overlayFadeOut &amp;&amp; fade &gt; 0f &amp;&amp; fade &lt; 1f;
 * </pre>
 * 它同时决定「还要不要出帧」和「用最后亮度还是逐组强度」。两个问题在运动期间
 * 答案相反，合成一个布尔必然有一边错：
 * <ul>
 *   <li>对调度：漏掉「淡出前保持期」（fade == 1）→ 落到空闲 120ms，
 *       而默认淡出只有 75ms → 整条曲线被跨过去，用户看到「瞬间熄灭」；</li>
 *   <li>若改成 {@code fade > 0f}：运动期间恒为 true → 所有分组读全局强度，
 *       多光标下静止光标也会发光（CaretTrailPanel 注释里写明已修掉的串扰）。</li>
 * </ul>
 *
 * <p>所以现在是两个纯函数：{@link GlowIntensity#needsMoreFrames}（调度）与
 * {@link GlowIntensity#useLastGlow}（渲染）。本探针对两者逐段断言，
 * 并扫源码防止有人把它们重新合并或退回旧判据。
 */
public final class VFadeTick {

    private static int pass = 0;
    private static int fail = 0;

    private static final long IDLE_MS = 120;   // = CaretAnimator.IDLE_DELAY_MS

    public static void main(String[] args) {
        System.out.println("=== V-FADE-TICK：淡出续帧判据 + 渲染取值判据 ===");

        // ==================== 调度：needsMoreFrames ====================

        // ---------- T-1：开关关闭 → 不续帧 ----------
        check("T-1 淡出开关关闭 → 不需要续帧",
                !GlowIntensity.needsMoreFrames(false, 1f, 1f, 0, IDLE_MS), "");

        // ---------- T-2：从未亮过 → 没有可淡的东西 ----------
        check("T-2 辉光从未亮过（lastGlowStrength=0）→ 不需要续帧",
                !GlowIntensity.needsMoreFrames(true, 0f, 1f, 0, IDLE_MS), "");

        // ---------- T-3：保持期还早 → 不空转（2026-11-01 审查 M-3/L-1） ----------
        check("T-3 保持期还剩 400ms（> 一个空闲帧）→ 不需要全速出帧",
                !GlowIntensity.needsMoreFrames(true, 0.8f, 1f, 400, IDLE_MS), "");

        // ---------- T-3b：保持期即将结束 → 必须开始续帧 ----------
        //
        // 这是 BUG-B 的核心：若这一段不续帧，淡出曲线（默认 75ms）会在一个
        // 120ms 的空闲帧里被整段跨过去。
        check("T-3b 保持期只剩 100ms（≤ 一个空闲帧）→ 必须续帧（否则淡出被跳过）",
                GlowIntensity.needsMoreFrames(true, 0.8f, 1f, 100, IDLE_MS), "");

        // ---------- T-4：淡出中 → 必须逐帧 ----------
        check("T-4 淡出中 fade=0.5 → 必须续帧",
                GlowIntensity.needsMoreFrames(true, 0.8f, 0.5f, 0, IDLE_MS), "");

        // ---------- T-5：淡出结束 → 停止 ----------
        check("T-5 淡出结束 fade=0 → 停止出帧",
                !GlowIntensity.needsMoreFrames(true, 0.8f, 0f, 0, IDLE_MS), "");

        // ---------- T-6：NaN 防御（与 strengthFor 的 M-4 同构） ----------
        check("T-6a lastGlowStrength=NaN → 不续帧（不能永久空转）",
                !GlowIntensity.needsMoreFrames(true, Float.NaN, 1f, 0, IDLE_MS), "");
        check("T-6b fade=NaN → 不续帧",
                !GlowIntensity.needsMoreFrames(true, 0.8f, Float.NaN, 0, IDLE_MS), "");

        // ==================== 渲染：useLastGlow ====================

        // ---------- T-7：运动期间必须用逐组强度（多光标不串扰） ----------
        //
        // 这是 2026-11-01 审查 H-1 的守门：运动期间 fade 恒为 1，
        // 若把「续帧判据」当渲染判据，这里会返回 true → 静止光标也发光。
        check("T-7 本帧在移动（moved=true）→ 用逐组强度（不得用保持亮度）",
                !GlowIntensity.useLastGlow(true, true, 1f), "");

        // ---------- T-8：全部静止、淡出中 → 用最后亮度×fade ----------
        check("T-8 已停止 + 淡出中（fade=0.5）→ 用保持亮度",
                GlowIntensity.useLastGlow(true, false, 0.5f), "");

        // ---------- T-9：全部静止、保持期 → 用最后亮度（满强度） ----------
        check("T-9 已停止 + 保持期（fade=1）→ 用保持亮度",
                GlowIntensity.useLastGlow(true, false, 1f), "");

        // ---------- T-10：开关关闭 / 淡出结束 → 不画 ----------
        check("T-10a 淡出开关关闭 → 不用最后亮度（静止即熄灭）",
                !GlowIntensity.useLastGlow(false, false, 1f), "");
        check("T-10b 淡出结束 fade=0 → 不用最后亮度",
                !GlowIntensity.useLastGlow(true, false, 0f), "");

        // ==================== 源码守卫 ====================

        String animator = readSource("src/main/java/com/github/yyyolo7a79/neovidecursor/ui/CaretAnimator.java");
        if (animator == null) {
            // 【H-3 修复】读不到源码时必须 FAIL，不能因为「扫描结果为空」而假绿。
            check("T-11 能读到 CaretAnimator 源码", false, "读不到源码");
        } else {
            check("T-11 能读到 CaretAnimator 源码", true, "");
            String code = stripComments(animator);
            check("T-12 CaretAnimator 调用 GlowIntensity.needsMoreFrames（不是自己写调度判据）",
                    code.contains("GlowIntensity.needsMoreFrames("), "未找到调用");
            check("T-13 CaretAnimator 调用 GlowIntensity.useLastGlow（不是自己写渲染判据）",
                    code.contains("GlowIntensity.useLastGlow("), "未找到调用");
            check("T-14 CaretAnimator 代码里不再出现旧的 `fade > 0f && fade < 1f` 判据",
                    !code.contains("fade > 0f && fade < 1f"), "旧判据仍在代码里");
        }

        // ==================== 曲线回归（与 VPreviewParity 同一边界） ====================
        double f = 0.1;    // 100ms 淡出
        long t0 = 1_000_000_000L;
        check("T-15a 保持期内 fade=1",
                GlowIntensity.fade(true, t0, t0 + 30_000_000L, 50, f) == 1f, "");
        check("T-15b 淡出中 fade=0.5",
                Math.abs(GlowIntensity.fade(true, t0, t0 + 100_000_000L, 50, f) - 0.5f) < 1e-6f, "");
        check("T-15c 到点 fade=0",
                GlowIntensity.fade(true, t0, t0 + 200_000_000L, 50, f) == 0f, "");

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

    /**
     * 剥掉 Java 的行注释与块注释。
     *
     * <p>源码扫描必须只看代码：本项目的注释习惯大量引用「旧实现长什么样」，
     * 连注释一起扫会让「记录历史」变成「触发假红」。
     *
     * <p>已知限制：不识别文本块（{@code """}）。当前 {@code CaretAnimator} 没有文本块；
     * 若将来加入，需要扩展本状态机。
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
            // 【H-3】不静默吞：扫描类探针读不到源码就是「没测」，必须留下痕迹。
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
