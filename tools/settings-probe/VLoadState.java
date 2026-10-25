import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * V-LOAD-STATE：State 的「第四处拷贝」必须覆盖全部字段。
 *
 * <p><b>这是审查抓到的 blocker</b>：项目里「同一个 State 字段要同步几处」已经有四处：
 * <ol>
 *   <li>字段声明</li>
 *   <li>{@code SettingsField.FIELDS}（apply 用）</li>
 *   <li>{@code State.copyFrom()}（面板回显用）</li>
 *   <li>{@code loadState()}（<b>启动读盘用</b>）</li>
 * </ol>
 * 前三处都有探针（VAddSetting / VDefaultFreeze），<b>第四处从来没被守过</b>。
 *
 * <p>后果：{@code loadState} 手写 23 条赋值而 State 有 28 个字段，漏的 5 个
 * （diagLogging / multiCursorNoGlow / cursorWidthPx / cursorHeightRatio / rgbGradient）
 * 每次启动被静默丢弃 —— 用户设的光标宽度、RGB 重启即失效，零提示。
 *
 * <p>本探针用「写满 → 序列化 → 反序列化 → 逐字段比对」验证往返完整性。
 */
public final class VLoadState {
    private static int pass = 0, fail = 0;

    /** 不需要经 loadState 往返的字段（平台内部/指针/派生） */
    private static final List<String> EXEMPT = List.of("stateVersion");

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-LOAD-STATE：loadState 字段覆盖度（第四处拷贝）===");

        V0Env.install();

        // L-1：每个 State 字段都必须在 loadState 里被赋值
        List<String> all = fields();
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/github/yyyolo7a79/"
                        + "neovidecursor/settings/NeovideCursorSettings.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        int lsStart = src.indexOf("public void loadState");
        int lsEnd = src.indexOf("state = target;", lsStart);
        String body = (lsStart >= 0 && lsEnd > lsStart) ? src.substring(lsStart, lsEnd) : "";
        List<String> notAssigned = new ArrayList<>();
        for (String f : all) {
            if (EXEMPT.contains(f)) continue;
            if (!body.contains("target." + f)) {
                notAssigned.add(f);
            }
        }
        check("L-1 loadState 覆盖全部 State 字段（漏一个 = 重启即失效）",
                notAssigned.isEmpty(),
                notAssigned.isEmpty() ? all.size() + " 个字段全覆盖"
                        : "漏拷=" + notAssigned + "  ← 加上 target.xxx = from.xxx");

        // L-2：真实往返 —— 写满所有字段，序列化再反序列化，逐字段比对
        NeovideCursorSettings svc = new NeovideCursorSettings();
        NeovideCursorSettings.State src2 = new NeovideCursorSettings.State();
        setAllDistinct(src2);
        svc.loadState(src2);
        NeovideCursorSettings.State back = svc.getState();
        List<String> lost = new ArrayList<>();
        for (String f : all) {
            if (EXEMPT.contains(f)) continue;
            String want = str(src2, f);
            String got = str(back, f);
            if (!want.equals(got)) {
                lost.add(f + "(" + want + "→" + got + ")");
            }
        }
        check("L-2 loadState 往返不丢任何字段（实测 28 个字段写入后读回）",
                lost.isEmpty(),
                lost.isEmpty() ? "全部保留" : "丢失=" + lost);

        // L-3：animPresetBase 的合法值 2（纯平滑输入）不能被重置为 0
        NeovideCursorSettings svc2 = new NeovideCursorSettings();
        NeovideCursorSettings.State s3 = new NeovideCursorSettings.State();
        s3.animPresetBase = 2;
        svc2.loadState(s3);
        check("L-3 animPresetBase=2（纯平滑输入）重启后不被踢回 0",
                svc2.getState().animPresetBase == 2,
                "读回 = " + svc2.getState().animPresetBase
                        + "（0=基础 1=缎带 2=纯平滑输入，数组共 "
                        + CursorPresets.ANIM_PRESETS.length + " 项）");

        // L-4：越界下标仍必须回落默认（不能因为放宽上界就不 clamp）
        NeovideCursorSettings svc3 = new NeovideCursorSettings();
        NeovideCursorSettings.State s4 = new NeovideCursorSettings.State();
        s4.animPresetBase = 99;
        svc3.loadState(s4);
        check("L-4 animPresetBase 越界（99）仍回落默认预设",
                svc3.getState().animPresetBase == CursorPresets.ANIM_PRESET_DEFAULT_INDEX,
                "读回 = " + svc3.getState().animPresetBase);

        // L-5：运行期开关跳跃清单必须存在且非空（供两条 restore 路径共用）
        check("L-5 SettingsField.RUNTIME_SWITCH_KEYS 已定义（两条 restore 路径共用）",
                SettingsField.RUNTIME_SWITCH_KEYS != null
                        && SettingsField.RUNTIME_SWITCH_KEYS.length >= 4,
                "清单 = " + java.util.Arrays.toString(SettingsField.RUNTIME_SWITCH_KEYS));

        System.out.println();
        System.out.printf("=== V-LOAD-STATE 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-LOAD-STATE RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-LOAD-STATE RESULT: ALL PASS");
    }

    private static List<String> fields() {
        List<String> out = new ArrayList<>();
        for (Field f : NeovideCursorSettings.State.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            out.add(f.getName());
        }
        return out;
    }

    /** 把每个字段设成与默认值不同的值 */
    private static void setAllDistinct(NeovideCursorSettings.State s) throws Exception {
        for (Field f : s.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            try {
                if (t == boolean.class) f.setBoolean(s, !f.getBoolean(s));
                else if (t == int.class) f.setInt(s, f.getInt(s) == 0 ? 3 : 9);
                else if (t == float.class) f.setFloat(s, 0.42f);
                else if (t == String.class) f.set(s, "probe");
            } catch (Throwable ignored) {
            }
        }
        // 【合法区间内的值 —— 否则会被 clamp 后误判成"丢失"】
        //
        // 第一版探针在这一点上误报过 5 个字段，记下来：
        //   tailColor="probe"       → sanitizeColor 丢弃非法 HEX，回落默认 #FFC0CB
        //   stretchRatioK=0.42      → clamp 到 [1.0, 8.0]，变成 1.0
        //   animationLength=0.42    → clamp 到 [0.05, 0.35]，变成 0.35
        //   shortAnimationLength=0.42 → clamp 到 [0.02, 0.25]，变成 0.25
        // 这些是【设计要求的输入校验】，不是丢失。探针数据必须落在合法区间内。
        s.cursorWidthPx = 4;              // [1, 5]
        s.cursorHeightRatio = 0.5f;       // [0.3, 1.0]
        s.animPresetBase = 1;             // [0, 2]
        s.tailColor = "#123456";          // 合法 HEX
        s.shadowColor = "#ABCDEF";
        s.stretchRatioK = 6.0f;           // [1.0, 8.0]
        s.animationLength = 0.2f;         // [0.05, 0.35]
        s.shortAnimationLength = 0.15f;   // [0.02, 0.25]
        s.shadowBlurFactor = 0.4f;        // [0.05, 0.60]
        s.glowLayers = 6;                 // [1, 12]
    }

    private static String str(Object o, String name) throws Exception {
        Field f = o.getClass().getField(name);
        Object v = f.get(o);
        if (v instanceof Float) {
            return String.format(java.util.Locale.ROOT, "%.4f", (Float) v);
        }
        if (v instanceof Enum) {
            return ((Enum<?>) v).name();
        }
        return String.valueOf(v);
    }

    private static void check(String n, boolean ok, String d) {
        if (ok) { pass++; System.out.println("  PASS  " + n + "   [" + d + "]"); }
        else { fail++; System.out.println("  FAIL  " + n + "   [" + d + "]"); }
    }
}