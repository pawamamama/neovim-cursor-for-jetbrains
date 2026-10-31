import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.ui.CaretAnimator;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * V-AUDIT-BATCH2-R5 —— 审计第 2 批「第 5 轮」（G 热路径微分配 / H 死代码与注释）的守门探针。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>这一批的共同点：<b>改了但画面一模一样</b>。没有一条会改变观感，所以
 * 「跑一遍功能」永远测不出来 —— 必须把「分配发生在哪一行」「注释说的是不是真的」
 * 变成可断言的事实。
 *
 * <h2>判据分两类，标签写在断言名里</h2>
 *
 * <ul>
 *   <li><b>【结构】</b>：源码文本 / 反射。分配与注释没有行为面，只能这样守；
 *       按陷阱 4，每条都做过变异测试（改回坏写法 → 必红）。</li>
 *   <li><b>【行为】</b>：调用生产方法断言返回值。优先，凡是能行为化的都行为化了
 *       （G1 的填充值、G3/H6 的真值表、G6 的辉光半径）。</li>
 * </ul>
 *
 * <h2>为什么读源码用仓库根绝对路径</h2>
 *
 * <p>门禁脚本可能从任意 cwd 被调用（见 build-v-probes.ps1 的 -Dneovide.repo），
 * 相对路径在 VDeadField 上踩过坑，这里统一走 {@link #SRC_ROOT} 常量。
 */
public final class VAuditBatch2Round5 {

    private static int pass = 0;
    private static int fail = 0;

    /** 生产源码根（由 build-v-probes.ps1 注入 -Dneovide.repo）。 */
    private static final Path SRC_ROOT = Paths.get(
            System.getProperty("neovide.repo",
                    "D:\\deepseekwork\\neovim-cursor-pr"),
            "src", "main", "java", "com", "github", "yyyolo7a79", "neovidecursor");

    public static void main(String[] args) throws Exception {
        System.out.println("=== V-AUDIT-BATCH2-R5：第 5 轮（G 热路径 / H 死代码与注释）===");

        g1();
        g2();
        g3();
        g4();
        g5();
        g6();
        g7();
        g8();
        h1();
        h2();
        h3();
        h4();
        h5();
        h6();

        finish();
    }

    // ==================== 源码读取辅助 ====================

    private static String src(String rel) {
        try {
            return new String(Files.readAllBytes(SRC_ROOT.resolve(rel)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 取一个方法体：从 signature 到「下一个同缩进的方法/字段声明」。找不到返回 ""。 */
    private static String bodyOf(String source, String signature, String nextMarker) {
        int i = source.indexOf(signature);
        if (i < 0) {
            return "";
        }
        int j = source.indexOf(nextMarker, i + signature.length());
        return j > i ? source.substring(i, j) : source.substring(i);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    // ==================== G 批 ====================

    /**
     * G1 / U-M-2：{@code tickSpring}、{@code tickStaticCaret} 每帧不再 {@code new double[4]}。
     *
     * <p>【结构】+【行为】两条：
     * 结构守「没有分配」，行为守「改用复用数组后值仍然对」——
     * 后者才是 AC-005（观感零变化）的直接证据：四角坐标逐位相等。
     */
    private static void g1() throws Exception {
        String animator = src("ui/CaretAnimator.java");

        String spring = bodyOf(animator, "private boolean tickSpring(", "private float glowFade(");
        String staticCaret = bodyOf(animator, "private boolean tickStaticCaret()", "private boolean tickSpring(");

        // 用 `new double[` 而不是字面 `new double[]{`：回归不一定写成数组字面量
        // （`double[] a = new double[4]; a[0]=...` 同样每帧分配）。
        // tickSpring 允许恰好 1 处 —— 那是 groupMaxScratch 的**扩容分支**（组数变大时才走）；
        // 每帧路径上出现第二处就是回归。tickStaticCaret 则必须 0 处。
        check("G1-a【结构】tickSpring 内 new double[ 至多 1 处（仅 groupMax 扩容分支）",
                !spring.isEmpty() && count(spring, "new double[") <= 1,
                spring.isEmpty() ? "★ 找不到方法体（源码解析失败）" : "命中 " + count(spring, "new double[") + " 处");
        check("G1-b【结构】tickStaticCaret 内无 new double[",
                !staticCaret.isEmpty() && count(staticCaret, "new double[") == 0,
                staticCaret.isEmpty() ? "★ 找不到方法体" : "命中 " + count(staticCaret, "new double[") + " 处");
        check("G1-c【结构】tickSpring 内 groupMaxOffset 不再每帧 new",
                !spring.contains("new double[maxGroup + 1]"),
                spring.contains("new double[maxGroup + 1]") ? "★ 仍在每帧分配" : "已改为成员数组复用");

        // 行为：fillQuadXs/fillQuadYs 写出的值必须与四角当前坐标逐位相等
        Field fx = null;
        Field fy = null;
        try {
            fx = CaretAnimator.class.getDeclaredField("quadScratchX");
            fy = CaretAnimator.class.getDeclaredField("quadScratchY");
        } catch (NoSuchFieldException ignored) {
            // 未实现：下面一条直接红
        }
        check("G1-d【结构】存在复用数组 quadScratchX / quadScratchY（double[]）",
                fx != null && fy != null
                        && fx.getType() == double[].class && fy.getType() == double[].class,
                (fx == null || fy == null) ? "★ 字段不存在" : fx.getType().getSimpleName());

        boolean valuesOk = false;
        String valuesDetail = "★ fillQuadXs/fillQuadYs 不存在";
        try {
            Method mx = CaretAnimator.class.getDeclaredMethod("fillQuadXs", double[].class, TrailCorner[].class);
            Method my = CaretAnimator.class.getDeclaredMethod("fillQuadYs", double[].class, TrailCorner[].class);
            mx.setAccessible(true);
            my.setAccessible(true);

            NeovideConfig cfg = new NeovideConfig();
            TrailCorner[] cs = new TrailCorner[]{
                    new TrailCorner(-0.5, -0.5, cfg),
                    new TrailCorner(0.5, -0.5, cfg),
                    new TrailCorner(0.5, 0.5, cfg),
                    new TrailCorner(-0.5, 0.5, cfg),
            };
            for (TrailCorner c : cs) {
                c.jump(2, 32, 100.25, 200.75, 0);
            }
            double[] xs = new double[4];
            double[] ys = new double[4];
            mx.invoke(null, xs, cs);
            my.invoke(null, ys, cs);
            boolean eq = true;
            for (int i = 0; i < 4; i++) {
                eq &= xs[i] == cs[i].getCurrentX() && ys[i] == cs[i].getCurrentY();
            }
            valuesOk = eq;
            valuesDetail = String.format("首角=(%.3f,%.3f)", xs[0], ys[0]);
        } catch (NoSuchMethodException e) {
            // 保持 valuesOk=false
        }
        check("G1-e【行为】fillQuadXs/fillQuadYs 写入值与四角 getCurrentX/Y 逐位相等",
                valuesOk, valuesDetail);
    }

    /**
     * G2 / U-M-10：IME 判据里 {@code offsetToXY} 只算一次，诊断日志只在诊断开启时求值。
     *
     * <p>【结构】理由：这条路径必须在真实 Editor 上才走得到，而它的失败形态是
     * 「日志实参求值抛异常 → 整条慢路径 return false」。离线构造该状态要伪造
     * 半个平台（read action + VisualPosition + 虚拟空间判据），成本远超收益；
     * 变异测试证明结构判据抓得住回退。
     */
    private static void g2() {
        String animator = src("ui/CaretAnimator.java");
        String region = bodyOf(animator, "boolean midComposition = false;", "imeSkipFrames = 0;");

        // 必须「恰好 1 次」而不是「至多 1 次」：0 次意味着判据自己没算，
        // midComposition 恒 false、IME 中间态保护静默失效（2026-10-31 审查指出）。
        check("G2-a【结构】IME 判据区间内 offsetToXY 恰好 1 次（不再为日志重算）",
                !region.isEmpty() && count(region, "editor.offsetToXY") == 1,
                region.isEmpty() ? "★ 找不到判据区间" : "命中 " + count(region, "editor.offsetToXY") + " 次");
        check("G2-b【结构】imeSkipPicked 的求值被 CursorDiag.enabled() 包住",
                !region.isEmpty() && region.contains("CursorDiag.enabled()")
                        && region.indexOf("CursorDiag.enabled()") < region.indexOf("imeSkipPicked"),
                region.isEmpty() ? "★ 找不到判据区间" : "enabled()=" + region.contains("CursorDiag.enabled()"));
    }

    /**
     * G3 / U-M-1：{@code CONFIG} 引用 volatile；EDT 契约判据是纯函数。
     *
     * <p>行为断言用真值表：{@code (有平台, 在 EDT)} 四种组合里只有「有平台且不在 EDT」
     * 算违规 —— 离线探针没有 Application，必须放行，否则本门禁自己就跑不起来。
     */
    private static void g3() throws Exception {
        Field config = NeovideCaretManager.class.getDeclaredField("CONFIG");
        check("G3-a【结构】CONFIG 引用声明为 volatile",
                Modifier.isVolatile(config.getModifiers()),
                Modifier.toString(config.getModifiers()));

        Method f = null;
        try {
            f = NeovideCaretManager.class.getDeclaredMethod(
                    "isEdtContractViolated", boolean.class, boolean.class);
            f.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            // 未实现：下面一条红
        }
        boolean truth = false;
        String detail = "★ isEdtContractViolated 不存在";
        if (f != null) {
            boolean a = (Boolean) f.invoke(null, true, false);
            boolean b = (Boolean) f.invoke(null, true, true);
            boolean c = (Boolean) f.invoke(null, false, false);
            truth = a && !b && !c;
            detail = "有平台/非EDT=" + a + "，有平台/EDT=" + b + "，无平台/非EDT=" + c;
        }
        check("G3-b【行为】EDT 契约真值表：仅「有平台且不在 EDT」判违规", truth, detail);
    }

    /**
     * G4 / U-L-1：{@code assignRanks} 的 alignment 数组复用。
     *
     * <p>【结构】分配面没有行为可测；反射确认字段类型，源码确认函数体内不再 new。
     */
    private static void g4() throws Exception {
        String trail = src("ui/SpringTrail.java");
        String ranks = bodyOf(trail, "private void assignRanks()", "private Integer[] idxScratch;");

        check("G4-a【结构】assignRanks 内不再 new double[",
                !ranks.isEmpty() && !ranks.contains("new double["),
                ranks.isEmpty() ? "★ 找不到方法体" : (ranks.contains("new double[") ? "★ 仍每次新建" : "已复用"));

        // SpringTrail 是包私有类（final class），只能用反射拿 Class —— 与 VMCDiff 同一做法。
        Field f = null;
        try {
            Class<?> st = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.SpringTrail");
            f = st.getDeclaredField("alignmentScratch");
        } catch (Exception ignored) {
            // 未实现
        }
        check("G4-b【结构】存在 alignmentScratch（double[]）",
                f != null && f.getType() == double[].class,
                f == null ? "★ 字段不存在" : f.getType().getSimpleName());
    }

    /**
     * G5 / U-L-3：{@code identityHashCode(this)} 只允许出现在 traceId 初始化那一处。
     *
     * <p>该项在轮次 2~4 已修（traceId 已接管三处键）。本断言是<b>回归守卫</b>：
     * 任何人再写一处，三个诊断表的键就会分叉 —— 而那种分叉不会报错，
     * 只会让 dispose 清不掉、日志串台。
     */
    private static void g5() {
        String animator = src("ui/CaretAnimator.java");
        int n = count(animator, "System.identityHashCode(this)");
        check("G5【结构】System.identityHashCode(this) 全文件仅 1 处（traceId 初始化）",
                n == 1, "命中 " + n + " 处");
    }

    /**
     * G6 / U-L-5：辉光半径的小步长改动不再被 0.5px 迟滞吞掉。
     *
     * <p>【行为】这是本批少数能纯离线复现的：同尺寸下把系数从 0.20 调到 0.21，
     * 半径应 6.4 → 6.72。旧实现以「已经写进去的值」为基准，差值 0.32 &lt; 0.5，
     * 于是**这一次拖动完全没有效果** —— 正是「拖了滑块画面没反应」的一个候选解释。
     */
    private static void g6() {
        NeovideConfig cfg = new NeovideConfig();
        cfg.shadowBlurFactor = 0.20f;
        CaretTrailPanel p = new CaretTrailPanel(cfg);

        p.updateGlowRadius(2, 32);
        float first = p.getGlowWidth();

        cfg.shadowBlurFactor = 0.21f;
        p.updateGlowRadius(2, 32);
        float second = p.getGlowWidth();

        check("G6-a【行为】首次半径按系数算（0.20×32 = 6.4）",
                Math.abs(first - 6.4f) < 0.001f,
                String.format("实际 %.3f", first));
        check("G6-b【行为】同尺寸改系数 0.20→0.21 必须生效（6.4 → 6.72）",
                Math.abs(second - 6.72f) < 0.001f,
                String.format("实际 %.3f（差 %.3f）", second, second - 6.4f));
    }

    /**
     * G7 / U-L-6：{@code drawQuadGlow} 对非有限值有显式守卫。
     *
     * <p>【结构】批 1 已堵住主要 NaN 源头，该分支从生产不可达 ——
     * 没有行为可测，只能守守卫本身（与 buildRgbWashPaint 同构）。
     */
    private static void g7() {
        String panel = src("ui/CaretTrailPanel.java");
        // 结束标记用 isFiniteQuad 而不是 computeOffsetDirections：辅助方法本身在两者之间，
        // 若用后者的名字，区间会把 isFiniteQuad 的实现也包含进来 —— 于是「守卫被删掉」
        // 仍能匹配到 Float.isFinite（变异测试实测踩到过这个假通过）。
        String glow = bodyOf(panel, "private void drawQuadGlow(", "private boolean isFiniteQuad()");
        check("G7【结构】drawQuadGlow 顶部有 Float.isFinite 守卫",
                glow.contains("Float.isFinite(quadGlow)") && glow.contains("isFiniteQuad()"),
                glow.isEmpty() ? "★ 找不到方法体" : "isFinite=" + glow.contains("Float.isFinite(quadGlow)"));
    }

    /**
     * G8 / U-L-10：{@code currentGlowLayers()} 在 drawQuadGlow 里只取一次。
     *
     * <p>【结构】两条读取之间没有副作用，重复调用只是每帧多两次虚调用；
     * 真正要守的是「辉光强度由哪些量算出来」只有一处说明。
     */
    private static void g8() {
        String panel = src("ui/CaretTrailPanel.java");
        String glow = bodyOf(panel, "private void drawQuadGlow(", "private void computeOffsetDirections(");
        int n = count(glow, "currentGlowLayers()");
        check("G8【结构】drawQuadGlow 内 currentGlowLayers() 恰调用 1 次",
                n == 1, "命中 " + n + " 次");
    }

    // ==================== H 批 ====================

    /**
     * H1 / U-M-23：每帧不再写无人读的 {@code glowStrength}；面板 javadoc 不再撒谎。
     *
     * <p>【结构】写完仍要保证预览窗可用 —— 预览走的是它自己那个面板实例，
     * 与生产每帧链无关（见 AnimationPreview.step()）。
     */
    private static void h1() {
        String animator = src("ui/CaretAnimator.java");
        String spring = bodyOf(animator, "private boolean tickSpring(", "private float glowFade(");
        check("H1-a【结构】tickSpring 内不再调用 setGlowStrength",
                !spring.contains("setGlowStrength"),
                spring.isEmpty() ? "★ 找不到方法体" : (spring.contains("setGlowStrength") ? "★ 仍在每帧写" : "已摘除"));

        String panel = src("ui/CaretTrailPanel.java");
        check("H1-b【结构】面板不再声称 glowStrength「为 0 时完全不绘制光晕」",
                !panel.contains("为 0 时完全不绘制光晕"),
                panel.contains("为 0 时完全不绘制光晕") ? "★ 谎言仍在" : "已改");
        check("H1-c【结构】glowStrength 被标注为预览与探针专用",
                panel.contains("预览与探针专用"),
                panel.contains("预览与探针专用") ? "已标注" : "★ 未标注");
    }

    /**
     * H2 / U-L-2：死表删除，活表仍在。
     *
     * <p>角点顺序是「错了就镜像」的不变量，只能靠一个权威来源。
     * 三份表里 CaretAnimator 那份零读者 —— 删它不改变任何数值。
     */
    private static void h2() throws Exception {
        boolean animatorHasIt = true;
        try {
            CaretAnimator.class.getDeclaredField("CORNER_RELATIVE");
        } catch (NoSuchFieldException e) {
            animatorHasIt = false;
        }
        check("H2-a【结构】CaretAnimator 的 CORNER_RELATIVE 死表已删除",
                !animatorHasIt, animatorHasIt ? "★ 仍然存在" : "已删除");

        Class<?> st = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.SpringTrail");
        Field live = st.getDeclaredField("CORNER_RELATIVE");
        live.setAccessible(true);
        Object v = live.get(null);
        int len = v == null ? -1 : ((Object[]) v).length;
        check("H2-b【结构】SpringTrail 的 CORNER_RELATIVE 仍是唯一活表（4 个角）",
                len == 4, "长度=" + len);
    }

    /**
     * H3 / U-L-4：no-trail 分支不再绕过「不可见降频」。
     *
     * <p>【结构】0.9.100 修的是 not-showing 分支；no-trail 分支是同一形态的第二扇门。
     * 行为面需要一个不可见且无光标的编辑器桩，离线成本过高 —— 结构判据 + 变异测试。
     */
    private static void h3() {
        String animator = src("ui/CaretAnimator.java");
        // 结束标记必须用「编辑器不可见」那段注释，不能用 `if (!content.isShowing()) {`
        // —— 后者现在也出现在 no-trail 分支内部（本项修法），会让区间截断成空。
        String branch = bodyOf(animator, "if (activeTrails.isEmpty()) {",
                "// 编辑器不可见（后台标签页");
        check("H3-a【结构】no-trail 分支更新 currentDelayNs",
                branch.contains("currentDelayNs"),
                branch.isEmpty() ? "★ 找不到分支" : "命中=" + branch.contains("currentDelayNs"));
        check("H3-b【结构】no-trail 分支维护 wasHidden",
                branch.contains("wasHidden"),
                branch.isEmpty() ? "★ 找不到分支" : "命中=" + branch.contains("wasHidden"));
    }

    /**
     * H4 / U-L-11：注释与文档的过期引用清零。
     *
     * <p>【结构】全部是注释类条目，没有行为面。逐条对应审查报告点名的形态：
     * 幽灵代码（newCenterX）、过期行号（:1018-1019 / :1007）、
     * 双 javadoc 互斥（IdentityHashMap vs HashMap）、javadoc 飘位（refreshGlowPadding）。
     */
    private static void h4() {
        String animator = src("ui/CaretAnimator.java");
        String manager = src("ui/NeovideCaretManager.java");

        check("H4-a【结构】注释里不再出现幽灵代码 newCenterX",
                !animator.contains("newCenterX"),
                animator.contains("newCenterX") ? "★ 仍引用不存在的语句" : "已清");

        check("H4-b【结构】过期行号引用 :1018-1019 / :1007 已清除",
                !animator.contains(":1018-1019") && !manager.contains("CaretAnimator:1007")
                        && !manager.contains("refreshTarget 在 :1007"),
                "animator=" + animator.contains(":1018-1019")
                        + " manager=" + manager.contains(":1007"));

        check("H4-c【结构】trails 字段只保留一份 javadoc（HashMap 那份）",
                animator.contains("必须用 HashMap 而不是 IdentityHashMap")
                        && !animator.contains("为什么用 {@link IdentityHashMap}"),
                "HashMap说明=" + animator.contains("必须用 HashMap 而不是 IdentityHashMap")
                        + "，矛盾块=" + animator.contains("为什么用 {@link IdentityHashMap}"));

        // javadoc 必须紧贴方法：中间只允许空白
        int idx = animator.indexOf("void refreshGlowPadding()");
        boolean adjacent = false;
        String detail = "★ 找不到方法";
        if (idx > 0) {
            int close = animator.lastIndexOf("*/", idx);
            int open = animator.lastIndexOf("/**", close);
            if (close > 0 && open > 0 && open < close) {
                String between = animator.substring(close + 2, idx);
                String doc = animator.substring(open, close);
                adjacent = between.trim().isEmpty() && doc.contains("shadowBlurFactor");
                detail = "紧贴=" + between.trim().isEmpty() + "，doc含系数=" + doc.contains("shadowBlurFactor");
            }
        }
        check("H4-d【结构】refreshGlowPadding 的 javadoc 紧贴方法本身（不再飘到 allCorners）",
                adjacent, detail);

        check("H4-e【结构】不再声称「静态光标只能靠 refreshGlowPadding」而代码跳过它",
                !animator.contains("它连慢路径都进不去，只能靠这里"),
                animator.contains("它连慢路径都进不去，只能靠这里") ? "★ 过期断言仍在" : "已改");
    }

    /**
     * H5 / U-L-12：{@code frameSeq} 的口径写在声明处。
     *
     * <p>它的值是「慢路径采样序号」，不是显示帧号 —— 光标静止时根本不递增。
     * 读日志的人按「帧号」理解会算错采样率（陷阱：文档撒谎）。
     */
    private static void h5() {
        String animator = src("ui/CaretAnimator.java");
        int decl = animator.indexOf("private long frameSeq");
        boolean ok = false;
        String detail = "★ 找不到字段声明";
        if (decl > 0) {
            int start = Math.max(0, decl - 700);
            String around = animator.substring(start, decl);
            ok = around.contains("慢路径");
            detail = "声明前注释含「慢路径」=" + ok;
        }
        check("H5【结构】frameSeq 声明处写明「每 15 次慢路径采样」，不再暗示显示帧号", ok, detail);
    }

    /**
     * H6 / U-L-13：EDT 契约有守卫，且只在平台存在时生效。
     *
     * <p>【行为】与 G3-b 共用同一个纯函数；这里补一条「守卫真的接在入口上」的结构判据
     * —— 纯函数写对了但没人调，是本项目反复出现的形态（targetFps 事故）。
     */
    private static void h6() {
        String manager = src("ui/NeovideCaretManager.java");
        String edt = bodyOf(manager, "private static void applyConfigOnEdt(", "// ---------- S2..S6");

        // 必须匹配「调用」而不是「词」：上面那条注释里也写着 assertEdtContract 四个字，
        // 只搜词会让「把调用删掉」的变异照样绿（实测踩到过）。
        check("H6-a【结构】applyConfigOnEdt 入口调用 EDT 契约守卫",
                edt.contains("assertEdtContract(\""),
                edt.isEmpty() ? "★ 找不到方法体" : "已接");
        check("H6-b【结构】NeovideCaretManager 不靠 assertIsDispatchThread 硬抛（离线探针要能跑）",
                count(manager, "assertIsDispatchThread") == 0,
                "命中 " + count(manager, "assertIsDispatchThread") + " 次");
    }

    // ==================== 辅助 ====================

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        }
    }

    private static void finish() {
        System.out.println();
        System.out.printf("=== VAUDIT-BATCH2-R5 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("VAUDIT-BATCH2-R5 RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("VAUDIT-BATCH2-R5 RESULT: ALL PASS");
        System.exit(0);
    }
}
