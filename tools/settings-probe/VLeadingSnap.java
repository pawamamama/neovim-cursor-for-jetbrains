import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * V-LEADING-SNAP：前缘贴合（半边拖尾）开关。
 *
 * <p><b>它抓什么 bug</b>：
 * <ol>
 *   <li>开启后前缘斜度必须<b>精确</b>归零 —— 若只是「接近 0」，说明走的不是
 *       {@code DampedSpring.update()} 的 {@code animationLength <= dt} 短路，
 *       而是时长太短，观感会随帧率漂移；</li>
 *   <li>关闭后必须完全回到原来的斜度 —— 前缘角点的 {@code animationLength}
 *       被写成 0 之后，只有下一次 {@code jump()} 才会恢复；若恢复路径断了，
 *       用户关掉开关会得到「半边」的残留；</li>
 *   <li>它必须是设备级字段（不进预设域）—— 切任何动画预设都不能改变它。</li>
 * </ol>
 *
 * <p><b>两套系数分别验什么</b>（审查 M-1 的结论）：
 * <ul>
 *   <li>{@code new NeovideConfig()} 的原始 {@code trailFactors} = {@code {1,.72,.42,.22}}
 *       （等价 slope=100）→ 验「前缘<b>斜度</b>精确归零」，基线 2.11 / 4.06；</li>
 *   <li>生产出厂默认 {@code slopeTicks=0} → {@code {.86,.86,.32,.32}}，同侧两角本就同速、
 *       左右边缘斜度<b>恒为 0</b>。此时不能用斜度判定，改验「前缘角点位移精确为 0、
 *       后缘角点仍滞后」—— 这才是用户默认配置下真正看得见的效果。</li>
 * </ul>
 *
 * <p><b>为什么用反射驱动 {@code SpringTrail}</b>：它是包私有 final 类，与
 * {@code VPreviewRank} / {@code VMulti} / {@code VMCDiff} 同一做法。
 * 本探针<b>只调用生产方法</b>（{@code setTarget} / {@code applyCharsMoved} /
 * {@code update}，{@code assignRanks} 由 update 内部触发），
 * 不自己复刻 rank 算法（纪律四-G 陷阱 3）。
 */
public final class VLeadingSnap {

    /** 2×32 光标，与 CLAUDE.md §六「左半边」实测、BenchSlope 同一组参数。 */
    private static final double W = 2.0, H = 32.0;
    private static final double X0 = 100, Y0 = 100;
    /** 单行右移一个字符的像素量（与 tools/bench/BenchSlope 一致）。 */
    private static final double DX = 12.0;
    private static final double DT = 0.008;
    private static final int FRAMES = 200;

    /** slope=100 形状下的实测基线（CLAUDE.md §六，容差 0.05）。 */
    private static final double BASE_LEFT = 2.11, BASE_RIGHT = 4.06;

    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-LEADING-SNAP：前缘贴合（半边拖尾）===");
        V0Env.install();

        // ---------- A. slope=100 形状：斜度行为（AC-001 / AC-002） ----------
        double[] off = safeMeasure(false, DX, slope100());
        double[] on = safeMeasure(true, DX, slope100());
        System.out.printf("    slope=100 关闭：左 %.2f / 右 %.2f%n", off[0], off[1]);
        System.out.printf("    slope=100 开启：左 %.2f / 右 %.2f%n", on[0], on[1]);

        check("L-2 关闭时斜度与基线一致（左 2.11 / 右 4.06，容差 0.05）",
                near(off[0], BASE_LEFT) && near(off[1], BASE_RIGHT),
                String.format("left=%.4f right=%.4f", off[0], off[1]));
        check("L-1 开启时右边缘（前缘）精确 0.00 且左边缘仍 > 0",
                on[1] == 0.0 && on[0] > 0.01,
                String.format("left=%.4f right=%.6f", on[0], on[1]));
        check("L-1b 开启不改变后缘斜度（左边缘与关闭时相同，容差 0.05）",
                near(on[0], off[0]),
                String.format("on=%.4f off=%.4f", on[0], off[0]));

        // ---------- B. 删除方向镜像（左移 → 前缘是左边缘） ----------
        double[] leftOn = safeMeasure(true, -DX, slope100());
        check("L-12 左移（退格）时左边缘精确 0.00、右边缘 > 0",
                leftOn[0] == 0.0 && leftOn[1] > 0.01,
                String.format("left=%.6f right=%.4f", leftOn[0], leftOn[1]));

        // ---------- C. 可逆（AC-002） ----------
        double rightAfterOff = safeReversible();
        check("L-3 关闭开关后下一次移动恢复斜度（0 残留）",
                near(rightAfterOff, BASE_RIGHT),
                String.format("right=%.4f", rightAfterOff));

        // ---------- D. 出厂默认系数（slope=0）：用位移而不是斜度判定 ----------
        // 这一组才是用户默认配置下的真实观感：前缘瞬间贴住目标，后缘仍滞后。
        double[] onOffsets = firstFrameOffsets(true, DX, factoryDefault());
        double[] offOffsets = firstFrameOffsets(false, DX, factoryDefault());
        System.out.printf("    出厂默认(slope=0) 首帧位移：关闭 前缘 %.3f / 后缘 %.3f；"
                        + "开启 前缘 %.3f / 后缘 %.3f%n",
                offOffsets[0], offOffsets[1], onOffsets[0], onOffsets[1]);
        check("L-13a 出厂默认下：关闭时前缘角点首帧仍有位移（开关确实起作用）",
                offOffsets[0] > 0.01, String.format("lead=%.4f", offOffsets[0]));
        check("L-13b 出厂默认下：开启时前缘角点首帧位移精确 0、后缘仍滞后",
                onOffsets[0] == 0.0 && onOffsets[1] > 0.01,
                String.format("lead=%.6f trail=%.4f", onOffsets[0], onOffsets[1]));

        // ---------- E. 默认值（AC-003） ----------
        check("L-7 默认值为 false（全新安装 / 老配置升级都是关）",
                !new NeovideCursorSettings.State().leadingSnap,
                "default=" + new NeovideCursorSettings.State().leadingSnap);

        // ---------- F. 设备级（AC-004 + 六层②） ----------
        SettingsField f = null;
        for (SettingsField x : SettingsField.FIELDS) {
            if ("leadingSnap".equals(x.key)) {
                f = x;
                break;
            }
        }
        check("L-4a SettingsField.FIELDS 已注册 leadingSnap", f != null,
                f == null ? "缺失 → apply 会丢" : "已注册");
        if (f != null) {
            check("L-4b 设备级：不进 CONFIG（syncsToConfig=false）", !f.syncsToConfig,
                    "syncsToConfig=" + f.syncsToConfig);
            check("L-4c 设备级：不进预设快照（inPreset=false）", !f.inPreset,
                    "inPreset=" + f.inPreset);
            check("L-4d 设备级：runtimeSwitch=true", f.runtimeSwitch,
                    "runtimeSwitch=" + f.runtimeSwitch);
        }

        // ---------- G. 六层③ copyFrom ----------
        NeovideCursorSettings.State from = new NeovideCursorSettings.State();
        from.leadingSnap = true;
        NeovideCursorSettings.State to = new NeovideCursorSettings.State();
        to.copyFrom(from);
        check("L-5 copyFrom 会带上 leadingSnap（六层③）", to.leadingSnap,
                "copy 后 = " + to.leadingSnap);

        // ---------- H. 六层④ loadState ----------
        NeovideCursorSettings svc = new NeovideCursorSettings();
        NeovideCursorSettings.State persisted = new NeovideCursorSettings.State();
        persisted.leadingSnap = true;
        svc.loadState(persisted);
        check("L-6 loadState 会带上 leadingSnap（六层④，重启不丢）",
                svc.getState().leadingSnap,
                "读回 = " + svc.getState().leadingSnap);

        // ---------- I. 切预设不影响（AC-004） ----------
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.leadingSnap = true;
        boolean changed = false;
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            NeovideCursorConfigurable.applyAnimPresetFields(s, CursorPresets.ANIM_PRESETS[i]);
            if (!s.leadingSnap) {
                changed = true;
                System.out.println("    切到预设 " + i + " 后 leadingSnap 变成 false");
                break;
            }
        }
        check("L-8 切任何动画预设都不改 leadingSnap", !changed,
                changed ? "被预设覆盖了"
                        : "全部 " + CursorPresets.ANIM_PRESETS.length + " 个预设都不动它");

        // ---------- J. AnimPreset 不含它（设备级） ----------
        boolean inPreset = false;
        try {
            CursorPresets.AnimPreset.class.getMethod("leadingSnap");
            inPreset = true;
        } catch (NoSuchMethodException expected) {
            // 期望走这里
        }
        check("L-9 AnimPreset 不含该字段（确认不进预设域）", !inPreset,
                inPreset ? "被误加进预设了" : "不在预设里");

        // ---------- K2. 设置页预览也必须跟随（陷阱 6：预览与生产同源） ----------
        int[] previewOn = previewZeroIndices(true);
        int[] previewOff = previewZeroIndices(false);
        check("L-14 预览窗把前缘两角（TR/BR）钉死、后缘两角不钉",
                java.util.Arrays.equals(previewOn, new int[]{1, 2}) && previewOff.length == 0,
                "开启时零时长角=" + java.util.Arrays.toString(previewOn)
                        + "（应为 [1, 2]），关闭时=" + java.util.Arrays.toString(previewOff));

        // L-16：预览与生产「钉的是哪两个角」必须一致（审查 M-2）。
        // 两侧各读自己的行为结果，不在探针里复刻判据。
        int[] prodOn = productionZeroIndices();
        check("L-16 预览与生产被钉住的角集合一致",
                java.util.Arrays.equals(previewOn, prodOn),
                "预览=" + java.util.Arrays.toString(previewOn)
                        + " 生产=" + java.util.Arrays.toString(prodOn));

        // ---------- K3. 真实 UI 路径：勾选 → pending → 预览（不点「应用」也要变） ----------
        check("L-15 pending 变更后预览窗立刻收到开关（refreshPreviews 链路）",
                pushReachesPreview(), "见上");

        // ---------- K. 六层⑤：CaretAnimator 确实读它并推给 trail ----------
        // 【审查 M-3】原来直接对整份源码做子串匹配 —— 注释里留一句同样的文字就能通过，
        // 而且「新建 trail 分支」里的同一字符串会让「遍历已有 trail」那条路径断掉也照样绿。
        // 现在先截出 syncGlobalSettings 方法体、剥掉注释，再断言。
        String anim = stripComments(src("ui/CaretAnimator.java"));
        String syncBody = methodBody(anim, "private void syncGlobalSettings()");
        check("L-10 syncGlobalSettings 从 State 读 leadingSnap",
                syncBody.contains("leadingSnap = s.leadingSnap"),
                syncBody.contains("leadingSnap = s.leadingSnap") ? "已接通" : "缺同步");
        check("L-11 syncGlobalSettings 遍历 trails 把标志推给每个 SpringTrail",
                syncBody.contains("for (SpringTrail trail : trails.values())")
                        && syncBody.contains("trail.setLeadingSnap(leadingSnap)"),
                syncBody.contains("trail.setLeadingSnap(leadingSnap)")
                        ? "已接通" : "缺传播循环");

        System.out.println();
        System.out.printf("=== V-LEADING-SNAP 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-LEADING-SNAP RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-LEADING-SNAP RESULT: ALL PASS");
    }

    // ==================== 系数 ====================

    /** {@code NeovideConfig} 出厂系数（等价 slope=100），与 CLAUDE.md §六基线同源。 */
    private static NeovideConfig slope100() {
        return new NeovideConfig();
    }

    /** 生产出厂默认：{@code slopeTicks=0} → 同侧两角同速，斜度恒为 0。 */
    private static NeovideConfig factoryDefault() {
        NeovideConfig c = new NeovideConfig();
        c.trailFactors = NeovideCursorSettings.deriveTrailFactors(4.5f, 0f, 0f);
        return c;
    }

    // ==================== 物理测量 ====================

    private static double[] safeMeasure(boolean snap, double dx, NeovideConfig cfg) {
        try {
            return measure(snap, dx, cfg);
        } catch (Throwable t) {
            System.out.println("    [测量失败 snap=" + snap + " dx=" + dx + "] " + t);
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    private static double safeReversible() {
        try {
            return reversible();
        } catch (Throwable t) {
            System.out.println("    [可逆测量失败] " + t);
            return Double.NaN;
        }
    }

    /**
     * 驱动生产 {@code SpringTrail} 走一次「单行移动」，返回左右边缘
     * X 差的峰值（与 {@code tools/bench/BenchSlope} 同一口径）。
     *
     * @return {左边缘峰值, 右边缘峰值}；角点顺序 TL / TR / BR / BL
     */
    private static double[] measure(boolean snap, double dx, NeovideConfig cfg) throws Exception {
        Ref r = new Ref(newTrail(cfg));
        r.setTarget(X0, Y0, W, H);          // 首次定位：直接吸附
        r.applyCharsMoved(1);               // 短跳分支
        r.setLeadingSnap(snap);
        r.setTarget(X0 + dx, Y0, W, H);     // 真正的跳动 → 下一帧 assignRanks
        return runFrames(r);
    }

    /** 开启 → 关闭，再移动一次，量右边缘是否恢复。 */
    private static double reversible() throws Exception {
        Ref r = new Ref(newTrail(slope100()));
        r.setTarget(X0, Y0, W, H);
        r.applyCharsMoved(1);
        r.setLeadingSnap(true);
        r.setTarget(X0 + DX, Y0, W, H);
        double[] on = runFrames(r);
        // 硬断言（审查 M-2）：可逆场景的前半段也必须真的生效，不能只打印。
        if (on[1] != 0.0) {
            fail++;
            System.out.printf("  FAIL  L-3a 可逆场景的前半段：开启时右边缘 = %.4f（应为 0）%n", on[1]);
        } else {
            pass++;
            System.out.println("  PASS  L-3a 可逆场景的前半段：开启时右边缘精确 0");
        }

        r.applyCharsMoved(1);
        r.setLeadingSnap(false);
        r.setTarget(X0 + DX * 2, Y0, W, H);
        double[] off = runFrames(r);
        return off[1];
    }

    /**
     * 只推进一帧，返回 {前缘角点位移, 后缘角点位移}（像素，取同侧两角之和）。
     *
     * <p>右移时前缘 = TR(1)/BR(2)，后缘 = TL(0)/BL(3)。这是出厂默认
     * （slope=0）下唯一可观测的量 —— 那种配置下同侧两角同速，斜度恒为 0。
     */
    private static double[] firstFrameOffsets(boolean snap, double dx, NeovideConfig cfg)
            throws Exception {
        Ref r = new Ref(newTrail(cfg));
        r.setTarget(X0, Y0, W, H);
        r.applyCharsMoved(1);
        r.setLeadingSnap(snap);
        r.setTarget(X0 + dx, Y0, W, H);
        r.update(DT);
        TrailCorner[] cs = r.corners();
        double lead = cs[1].getOffsetMagnitude() + cs[2].getOffsetMagnitude();
        double trail = cs[0].getOffsetMagnitude() + cs[3].getOffsetMagnitude();
        return new double[]{lead, trail};
    }

    /** 逐帧推进，取左右边缘 X 差的最大值。 */
    private static double[] runFrames(Ref r) throws Exception {
        double maxLeft = 0, maxRight = 0;
        for (int i = 0; i < FRAMES; i++) {
            if (!r.update(DT)) {
                break;
            }
            TrailCorner[] cs = r.corners();
            maxLeft = Math.max(maxLeft, Math.abs(cs[0].getCurrentX() - cs[3].getCurrentX()));
            maxRight = Math.max(maxRight, Math.abs(cs[1].getCurrentX() - cs[2].getCurrentX()));
        }
        return new double[]{maxLeft, maxRight};
    }

    private static boolean near(double v, double target) {
        return Math.abs(v - target) < 0.05;
    }

    /**
     * 预览窗在 pushTarget 之后<b>哪些角</b>的动画时长恰好为 0。
     *
     * <p>【为什么返回下标而不是个数】审查 M-1 的变异实证：把预览的判据从
     * {@code ranks[i] >= 2} 改成 {@code <= 1}，个数仍是 2 —— 只数个数抓不到
     * 「钉反了方向」。这里断言的是身份（TR/BR），与 L-1 在生产侧的判据对齐。
     *
     * <p>【为什么第一次之后要 snapTo】两次 pushTarget 之间没有 {@code update()}，
     * {@code previousDest} 仍是初值 {@code -1e5} → 走跨行分支；而预览真实打字走的是
     * 短跳分支。把四角摆到上一目标（同时落定 previousDest）后，第二次移动
     * {@code deltaY = 0} → 走短跳，与用户看到的路径一致。
     */
    private static int[] previewZeroIndices(boolean snap) throws Exception {
        AnimationPreview p = AnimationPreview.inline(slope100());
        p.setLeadingSnap(snap);
        setField(p, "cursorW", 2.0);
        setField(p, "cursorH", 32.0);
        setField(p, "centerX", X0);
        setField(p, "centerY", Y0);
        invoke(p, "pushTarget");                 // 首次定位 → A
        TrailCorner[] cs = (TrailCorner[]) getField(p, "corners");
        for (TrailCorner c : cs) {
            c.snapTo(2.0, 32.0, X0, Y0);         // 摆到 A，previousDest 落定
        }
        setField(p, "centerX", X0 + DX);
        invoke(p, "pushTarget");                 // 真正的跳动（短跳分支）
        return zeroIndices(cs);
    }

    /** 生产侧同一场景下被钉住的角（右移一字符、短跳分支、开一帧）。 */
    private static int[] productionZeroIndices() throws Exception {
        Ref r = new Ref(newTrail(slope100()));
        r.setTarget(X0, Y0, W, H);
        r.applyCharsMoved(1);
        r.setLeadingSnap(true);
        r.setTarget(X0 + DX, Y0, W, H);
        r.update(DT);                            // 触发 assignRanks → jump → 钉前缘
        return zeroIndices(r.corners());
    }

    private static int[] zeroIndices(TrailCorner[] cs) {
        int n = 0;
        for (TrailCorner c : cs) {
            if (c.animationLengthForProbe() == 0.0) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < cs.length; i++) {
            if (cs[i].animationLengthForProbe() == 0.0) {
                out[k++] = i;
            }
        }
        return out;
    }

    /**
     * 走真实的预览推送链路：建面板 → 改 pending → refreshPreviews → 读 inlinePreview 的标志。
     *
     * <p>这是用户口径的「实时能看见改变」：勾选框只写 pending，预览要靠
     * {@code refreshPreviews → syncPreviewStyle → pushStyleTo → setLeadingSnap}
     * 这条链把值推过去。链断了这里就红。
     *
     * <p><b>为什么不用 {@code checkBoxByLabel} 点复选框</b>：{@code bindCheck} 建的
     * 复选框文案是空串（标签在行的 label 单元格里），这个方法按控件文本找，必然找不到。
     */
    private static boolean pushReachesPreview() {
        try {
            NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
            VUi.build(conf);
            conf.pendingForProbe().leadingSnap = true;
            conf.refreshPreviewsForProbe();
            Field f = NeovideCursorConfigurable.class.getDeclaredField("inlinePreview");
            f.setAccessible(true);
            AnimationPreview p = (AnimationPreview) f.get(conf);
            if (p == null) {
                System.out.println("    [L-15] inlinePreview 尚未创建");
                return false;
            }
            return p.leadingSnapForProbe();
        } catch (Throwable t) {
            System.out.println("    [L-15 失败] " + t);
            return false;
        }
    }

    private static void setField(Object o, String name, double v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setDouble(o, v);
    }

    private static Object getField(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void invoke(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }

    // ==================== 反射壳 ====================

    private static Object newTrail(NeovideConfig cfg) throws Exception {
        Class<?> st = Class.forName("com.github.yyyolo7a79.neovidecursor.ui.SpringTrail");
        Constructor<?> ctor = st.getDeclaredConstructor(NeovideConfig.class);
        ctor.setAccessible(true);
        return ctor.newInstance(cfg);
    }

    /** SpringTrail 是包私有类，只能反射；方法名写错会红在断言里，而不是编译失败。 */
    private static final class Ref {
        private final Object trail;
        private final Class<?> type;

        Ref(Object trail) {
            this.trail = trail;
            this.type = trail.getClass();
        }

        void setTarget(double cx, double cy, double w, double h) throws Exception {
            call("setTarget", new Class<?>[]{double.class, double.class, double.class, double.class},
                    cx, cy, w, h);
        }

        void applyCharsMoved(int n) throws Exception {
            call("applyCharsMoved", new Class<?>[]{int.class}, n);
        }

        void setLeadingSnap(boolean v) throws Exception {
            call("setLeadingSnap", new Class<?>[]{boolean.class}, v);
        }

        boolean update(double dt) throws Exception {
            Method m = type.getDeclaredMethod("update", double.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(trail, dt);
        }

        TrailCorner[] corners() throws Exception {
            Field f = type.getDeclaredField("corners");
            f.setAccessible(true);
            return (TrailCorner[]) f.get(trail);
        }

        private void call(String name, Class<?>[] sig, Object... args) throws Exception {
            Method m;
            try {
                m = type.getDeclaredMethod(name, sig);
            } catch (NoSuchMethodException e) {
                // 生产代码还没实现 → 让断言红，并给出可读原因
                throw new IllegalStateException("SpringTrail 缺少 " + name + " 方法", e);
            }
            m.setAccessible(true);
            try {
                m.invoke(trail, args);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(name + " 抛出 " + e.getCause(), e.getCause());
            }
        }
    }

    // ==================== 源码读取 ====================

    /** 优先用门禁传入的 {@code neovide.repo}，避免从别处启动时路径落空（审查 LOW-6）。 */
    private static String src(String rel) {
        try {
            Path root = repoRoot();
            return new String(Files.readAllBytes(
                    root.resolve("src/main/java/com/github/yyyolo7a79/neovidecursor/" + rel)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static Path repoRoot() {
        String prop = System.getProperty("neovide.repo");
        if (prop != null && !prop.isEmpty()) {
            Path p = Paths.get(prop);
            if (Files.isRegularFile(p.resolve("CLAUDE.md"))) {
                return p;
            }
        }
        Path p = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("CLAUDE.md"))) {
                return p;
            }
            p = p.getParent();
        }
        return Paths.get(".");
    }

    /** 剥掉 {@code //} 与 {@code /* *}{@code /} 注释，防止「注释里写了就算接通」。 */
    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** 截出某个方法的方法体（从声明到下一个 4 空格缩进的右花括号）。 */
    private static String methodBody(String text, String signature) {
        int start = text.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int end = text.indexOf("\n    }", start);
        return end > start ? text.substring(start, end) : text.substring(start);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }
}
