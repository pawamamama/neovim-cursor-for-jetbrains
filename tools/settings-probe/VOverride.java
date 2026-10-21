import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;

/**
 * verifier 自建：reviewer F7 —— Tools 的 applyAnimPreset 必须读覆盖表。
 *
 * <p><b>为什么必须有这条</b>：它描述的是一条会<b>销毁用户工作</b>的路径，
 * 而且分两步、中间看起来一切正常：
 * <pre>
 *   ① 用户在面板把「缎带」的跨行时长改成 0.15 → apply → 写进覆盖表
 *   ② 用户从 Tools 点「缎带」→ 修复前：改回预设常量，用户的改动被冲掉
 *   ③ 用户再在面板点「应用」→ current == constants → removeOverride → 永久删除
 * </pre>
 *
 * <p><b>本探针针对 0.9.8 基线（6 项覆盖域）</b>，不是 0.9.9 的 13 项版。
 * 字段顺序与 {@code CursorPresets.OVERRIDE_FIELDS} 一致：
 * animationLength, shortAnimationLength, trailSize, glowLayers, shadowBlurFactor, glowOpacity。
 */
public final class VOverride {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;

    public static void main(String[] args) {
        System.out.println("=== V-OVERRIDE：reviewer F7 —— Tools 应用预设必须读覆盖表 ===");
        V0Env.install();
        svc = V0Env.svc;

        int idx = 1;                                  // 「缎带」
        CursorPresets.AnimPreset ribbon = CursorPresets.ANIM_PRESETS[idx];

        // 造覆盖：跨行时长 0.15（常量不是 0.15），辉光层数 3（常量不是 3）
        NeovideCursorSettings.State st = svc.getState();
        float[] custom = {
                0.15f,
                ribbon.shortAnimationLength(),
                ribbon.trailSize(),
                3f,
                ribbon.shadowBlurFactor(),
                ribbon.glowOpacity()
        };
        st.animPresetOverrides = CursorPresets.putOverride("", idx, custom);
        st.animPresetBase = idx;
        System.out.println("    覆盖串 = " + st.animPresetOverrides);

        float[] back = CursorPresets.parseOverride(st.animPresetOverrides, idx);
        check("O-0 前置：覆盖值可被 parseOverride 解析回来",
                back != null && back.length == CursorPresets.OVERRIDE_FIELDS,
                back == null ? "null" : ("len=" + back.length));

        // ---- F7 主体 ----
        CursorPresetApplier.applyAnimPreset(ribbon);
        NeovideCursorSettings.State after = svc.getState();

        check("F7-1 有覆盖时跨行时长 = 0.15（不是常量）",
                Math.abs(after.animationLength - 0.15f) < 1e-6f,
                "animationLength=" + after.animationLength
                        + "（常量=" + ribbon.animationLength() + "）");
        check("F7-2 覆盖里的辉光层数 3 被采用",
                after.glowLayers == 3, "glowLayers=" + after.glowLayers);
        check("F7-3 覆盖串应用后仍然存在",
                CursorPresets.parseOverride(after.animPresetOverrides, idx) != null,
                "overrides=\"" + after.animPresetOverrides + "\"");
        check("F7-4 基准下标前移到该预设",
                after.animPresetBase == idx, "base=" + after.animPresetBase);

        // ---- 对照：无覆盖的预设仍按常量 ----
        int idx2 = CursorPresets.ANIM_PRESETS.length - 1;
        CursorPresets.AnimPreset last = CursorPresets.ANIM_PRESETS[idx2];
        CursorPresetApplier.applyAnimPreset(last);
        NeovideCursorSettings.State after2 = svc.getState();
        check("F7-5 无覆盖的预设仍按常量全量覆盖（0.9.8 行为不变）",
                Math.abs(after2.animationLength - last.animationLength()) < 1e-6f
                        && after2.glowLayers == last.glowLayers(),
                "anim=" + after2.animationLength + " layers=" + after2.glowLayers);

        // ---- 关键回归：覆盖保得住 ----
        CursorPresetApplier.applyAnimPreset(ribbon);
        after = svc.getState();
        float[] constants = {
                ribbon.animationLength(), ribbon.shortAnimationLength(), ribbon.trailSize(),
                ribbon.glowLayers(), ribbon.shadowBlurFactor(), ribbon.glowOpacity()
        };
        float[] current = {
                after.animationLength, after.shortAnimationLength, after.trailSize,
                after.glowLayers, after.shadowBlurFactor, after.glowOpacity
        };
        boolean equalsConstants = true;
        for (int i = 0; i < current.length; i++) {
            if (Math.abs(current[i] - constants[i]) >= CursorPresets.EPS) {
                equalsConstants = false;
            }
        }
        check("F7-6 【关键】当前值 != 常量 → 不会触发 removeOverride（覆盖保得住）",
                !equalsConstants,
                "等于常量=" + equalsConstants);

        System.out.println();
        System.out.printf("=== V-OVERRIDE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-OVERRIDE RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-OVERRIDE RESULT: ALL PASS");
        System.exit(0);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name + "   [" + detail + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   [" + detail + "]");
        }
    }
}