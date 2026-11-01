import com.github.yyyolo7a79.neovidecursor.core.TrailCorner;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * V-LEADING-SNAP：前缘贴合（半边拖尾）开关 —— <b>回退后</b>的契约。
 *
 * <p><b>契约变更（功能回退，2026-11-01 用户决定），非缺陷</b>：
 * 实测证明「前缘贴合」的三版实现模型都不可行（见
 * {@code docs/LEADING-SNAP-ISSUES-2026-11-01.md} 与
 * {@code docs/LEADING-SNAP-DECISION-2026-11-01.md}），实现逻辑已全部删除，
 * 只保留开关控件与 State 字段。本探针守的是回退后的五类契约：
 * <ol>
 *   <li><b>开关存在</b>：{@code SettingsField} 里有 {@code leadingSnap}，
 *       且 {@code runtimeSwitch == true}（L-4a/L-4d）；</li>
 *   <li><b>默认值 false</b>：全新安装与老配置升级都是关（L-7）；</li>
 *   <li><b>能持久化</b>：写 true → loadState → 还是 true（L-5/L-6）；</li>
 *   <li><b>不影响任何行为</b>：开关 true 与 false 两种状态下，同一场景的
 *       四角动画时长逐位相同（L-21，走真实 UI 链路）；并有源码级守门
 *       L-22 —— 五个行为层源文件中不得出现任何消费点，防止悄悄复活；</li>
 *   <li><b>不进预设域</b>：{@code PRESET_FIELDS} 不含它、{@code AnimPreset}
 *       不含它、切预设不改它（L-8/L-9/L-20）。</li>
 * </ol>
 *
 * <p><b>为什么 L-22 是结构断言</b>：回退后行为层根本没有这个开关的读取点，
 * 「true/false 行为相同」在物理上恒成立 —— 若没有守门，将来有人把消费逻辑
 * 加回来（哪怕只在一个分支里），L-21 的逐位对比也可能碰巧不红。
 * L-22 直接断言行为层源码零出现，把「复活」堵在编译期之后、运行期之前。
 */
public final class VLeadingSnap {

    /** 单行右移一个字符的像素量（与原基准场景一致）。 */
    private static final double DX = 12.0;
    private static final double X0 = 100, Y0 = 100;

    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-LEADING-SNAP：前缘贴合（半边拖尾）—— 回退后契约 ===");
        V0Env.install();

        // ---------- A. 开关存在（契约 1） ----------
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

        // ---------- B. 默认值 false（契约 2） ----------
        check("L-7 默认值为 false（全新安装 / 老配置升级都是关）",
                !new NeovideCursorSettings.State().leadingSnap,
                "default=" + new NeovideCursorSettings.State().leadingSnap);

        // ---------- C. 能持久化（契约 3） ----------
        NeovideCursorSettings.State from = new NeovideCursorSettings.State();
        from.leadingSnap = true;
        NeovideCursorSettings.State to = new NeovideCursorSettings.State();
        to.copyFrom(from);
        check("L-5 copyFrom 会带上 leadingSnap（六层③）", to.leadingSnap,
                "copy 后 = " + to.leadingSnap);

        NeovideCursorSettings svc = new NeovideCursorSettings();
        NeovideCursorSettings.State persisted = new NeovideCursorSettings.State();
        persisted.leadingSnap = true;
        svc.loadState(persisted);
        check("L-6 写 true → loadState → 还是 true（六层④，重启不丢）",
                svc.getState().leadingSnap,
                "读回 = " + svc.getState().leadingSnap);

        // ---------- D. 不进预设域（契约 5） ----------
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

        boolean inPreset = false;
        try {
            CursorPresets.AnimPreset.class.getMethod("leadingSnap");
            inPreset = true;
        } catch (NoSuchMethodException expected) {
            // 期望走这里
        }
        check("L-9 AnimPreset 不含该字段（确认不进预设域）", !inPreset,
                inPreset ? "被误加进预设了" : "不在预设里");

        boolean inPresetFields = false;
        for (SettingsField x : SettingsField.PRESET_FIELDS) {
            if ("leadingSnap".equals(x.key)) {
                inPresetFields = true;
                break;
            }
        }
        check("L-20 PRESET_FIELDS 不含 leadingSnap（不进自定义预设快照）", !inPresetFields,
                inPresetFields ? "被误加进 PRESET_FIELDS 了" : "不在 PRESET_FIELDS 里");

        // ---------- E. 不影响任何行为（契约 4 = AC-002） ----------
        // 真实 UI 链路：建面板 → pending.leadingSnap = true/false → refreshPreviews
        // → 驱动一次单行移动 → 读四角时长。回退后两种状态必须逐位相同。
        double[] offDur = uiPathDurations(false);
        double[] onDur = uiPathDurations(true);
        check("L-21 开关 true / false 两种状态下，同一场景四角时长逐位相同（AC-002）",
                offDur != null && onDur != null && eqArray(offDur, onDur),
                "false=" + java.util.Arrays.toString(offDur)
                        + " true=" + java.util.Arrays.toString(onDur));

        // 结构守门：五个行为层源文件中不得出现任何消费点（防复活）。
        String[] behaviorFiles = {
                "core/TrailCorner.java",
                "ui/SpringTrail.java",
                "ui/CaretTrailPanel.java",
                "ui/CaretAnimator.java",
                "ui/settings/AnimationPreview.java",
        };
        StringBuilder hits = new StringBuilder();
        for (String rel : behaviorFiles) {
            String body = stripComments(src(rel));
            for (String symbol : new String[]{
                    "leadingSnap", "halfTrail", "setAnimationLengthForTrail", "HALF_TRAIL_CAP"}) {
                if (body.contains(symbol)) {
                    hits.append(rel).append(" 含 ").append(symbol).append("; ");
                }
            }
        }
        check("L-22 行为层五文件零消费点（防复活守门）", hits.length() == 0,
                hits.length() == 0 ? "五个文件剥注释后零出现" : hits.toString());

        System.out.println();
        System.out.printf("=== V-LEADING-SNAP 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-LEADING-SNAP RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-LEADING-SNAP RESULT: ALL PASS");
    }

    // ==================== 真实 UI 链路 ====================

    /**
     * 走真实的设置页链路驱动一次单行移动，返回四角动画时长。
     *
     * <p>链路：建 {@code NeovideCursorConfigurable} → VUi.build → 写
     * {@code pending.leadingSnap} → {@code refreshPreviewsForProbe()} → 反射取
     * {@code inlinePreview} → 驱动「首次定位 + 摆位 + 单行短跳」→ 读四角时长。
     *
     * <p>【为什么要真实链路而不是直接 new AnimationPreview】契约 4 的判定对象是
     * 「开关状态」，开关只在 pending/State 层存在；只有走 refreshPreviews 这条
     * 推送链，true/false 两个状态才真的经过设置页到达预览。
     *
     * <p>【snapTo 的作用】两次 pushTarget 之间没有 {@code update()}，
     * {@code previousDest} 仍是初值 → 第一次会走跨行分支；把四角摆到上一目标
     * （同时落定 previousDest）后，第二次移动走短跳，与用户看到的路径一致。
     */
    private static double[] uiPathDurations(boolean snap) {
        try {
            NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
            VUi.build(conf);
            conf.pendingForProbe().leadingSnap = snap;
            conf.refreshPreviewsForProbe();
            Field f = NeovideCursorConfigurable.class.getDeclaredField("inlinePreview");
            f.setAccessible(true);
            AnimationPreview p = (AnimationPreview) f.get(conf);
            if (p == null) {
                System.out.println("    [L-21] inlinePreview 尚未创建");
                return null;
            }
            setField(p, "cursorW", 2.0);
            setField(p, "cursorH", 32.0);
            setField(p, "centerX", X0);
            setField(p, "centerY", Y0);
            invoke(p, "pushTarget");                 // 首次定位
            TrailCorner[] cs = (TrailCorner[]) getField(p, "corners");
            for (TrailCorner c : cs) {
                c.snapTo(2.0, 32.0, X0, Y0);         // 摆到 A，previousDest 落定
            }
            setField(p, "centerX", X0 + DX);
            invoke(p, "pushTarget");                 // 真正的跳动（短跳分支）
            double[] out = new double[cs.length];
            for (int i = 0; i < cs.length; i++) {
                out[i] = cs[i].animationLengthForProbe();
            }
            return out;
        } catch (Throwable t) {
            System.out.println("    [L-21 失败 snap=" + snap + "] " + t);
            return null;
        }
    }

    private static void setField(Object o, String name, double v) throws Exception {
        Field fld = o.getClass().getDeclaredField(name);
        fld.setAccessible(true);
        fld.setDouble(o, v);
    }

    private static Object getField(Object o, String name) throws Exception {
        Field fld = o.getClass().getDeclaredField(name);
        fld.setAccessible(true);
        return fld.get(o);
    }

    private static void invoke(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }

    private static boolean eq(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static boolean eqArray(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!eq(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    // ==================== 源码读取 ====================

    /** 优先用门禁传入的 {@code neovide.repo}，避免从别处启动时路径落空。 */
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

    /** 剥掉 {@code //} 与 {@code /* *}{@code /} 注释，防止「注释里写了就算违规」。 */
    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
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
