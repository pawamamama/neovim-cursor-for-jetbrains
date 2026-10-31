import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * V-FIELD-DOMAIN：预设的三份「字段清单」必须是同一份。
 *
 * <p><b>它守的是什么 bug</b>：项目里同时存在三处关于「哪些字段属于预设」的说法，
 * 而它们曾经互不一致 —— 用户实测到的两个症状：
 * <ul>
 *   <li>点「个人自定义」预设，光标颜色从用户的绿色变成预设里存的粉色（颜色被动画预设改了）；</li>
 *   <li>改「斜度 / 拖尾不透明度 / 淡出」不会派生副本，改「拉伸强度」会（同类操作两种行为）。</li>
 * </ul>
 *
 * <p>三份清单：
 * <ol>
 *   <li>{@code SettingsField.PRESET_FIELDS} —— 自定义预设<b>快照里存什么</b>；</li>
 *   <li>{@code applyAnimPresetFields} —— 内置预设<b>真正写什么</b>；</li>
 *   <li>{@code matchAnimPreset} —— 判断「当前值属于哪个预设」<b>比什么</b>。</li>
 * </ol>
 * 三者只要分家，就会出现「存了不生效」「改了不派生」「匹配撒谎」。
 *
 * <p><b>四条断言</b>：
 * <ul>
 *   <li>D-1 快照存什么 == 内置预设写什么（集合相等）</li>
 *   <li>D-2 快照里的每个 key 恢复时都真的生效（没有「存了不生效」的死数据）</li>
 *   <li>D-3 设备开关 / 颜色 / 输入平滑 / 预设指针都不在快照里</li>
 *   <li>D-4 扰动规则覆盖写入集合里的<b>每一个</b>字段（改任何一项都必须不再匹配该预设）</li>
 * </ul>
 */
public final class VFieldDomain {

    private static int pass = 0;
    private static int fail = 0;

    /** 必须<b>不在</b>动画预设快照里的字段（设备开关 / 颜色域 / 全局项 / 指针）。 */
    private static final String[] MUST_NOT_BE_IN_PRESET = {
            // 设备/功能开关（用户 2026-10-28 明确列出的 9 个）
            "enabled", "hideNativeCaret", "alwaysHighFps", "trailMode",
            "multiCursorNoGlow", "targetFps", "cursorWidthPx", "cursorHeightRatio", "diagLogging",
            // 颜色域（用户决定：动画预设完全不管颜色）
            "tailColor", "shadowColor", "rgbGradient",
            // 全局项（0.9.73 起平滑不参与预设）
            "typingSmoothness",
            // 指针
            "animPresetBase",
    };

    public static void main(String[] args) {
        V0Env.install();
        System.out.println("=== V-FIELD-DOMAIN：预设的三份字段清单必须同源 ===");

        Set<String> presetKeys = keysOf(SettingsField.PRESET_FIELDS);
        Set<String> writtenKeys = fieldsActuallyWritten();

        // ---------- D-1 ----------
        check("D-1 快照存的字段集合 == 内置预设实际写入的字段集合",
                presetKeys.equals(writtenKeys),
                "PRESET_FIELDS=" + presetKeys.size() + " 个，实际写入=" + writtenKeys.size()
                        + " 个；" + diff(presetKeys, writtenKeys));

        // ---------- D-2 ----------
        Set<String> dead = deadSnapshotKeys();
        check("D-2 快照里每个 key 恢复时都生效（没有「存了不生效」的死数据）",
                dead.isEmpty(),
                dead.isEmpty() ? "0 个死 key" : "这些 key 存了却恢复不了：" + dead);

        // ---------- D-3 ----------
        Set<String> wronglyIncluded = new LinkedHashSet<>();
        for (String k : MUST_NOT_BE_IN_PRESET) {
            if (presetKeys.contains(k)) {
                wronglyIncluded.add(k);
            }
        }
        check("D-3 设备开关 / 颜色 / 平滑 / 指针都不在预设快照里",
                wronglyIncluded.isEmpty(),
                wronglyIncluded.isEmpty() ? "0 个越界" : "不该在快照里却出现了：" + wronglyIncluded);

        // ---------- D-4 ----------
        CursorPresets.AnimPreset target = CursorPresets.ANIM_PRESETS[0];
        NeovideCursorSettings.State base = new NeovideCursorSettings.State();
        NeovideCursorConfigurable.applyAnimPresetFields(base, target);
        check("D-4a 刚应用完预设，matchAnimPreset 必须认得它",
                CursorPresetApplier.matchAnimPreset(base) == target,
                "match 结果 = " + nameOf(CursorPresetApplier.matchAnimPreset(base)));

        Set<String> insensitive = new LinkedHashSet<>();
        for (SettingsField f : SettingsField.PRESET_FIELDS) {
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            NeovideCursorConfigurable.applyAnimPresetFields(s, target);
            perturb(f, s);
            if (CursorPresetApplier.matchAnimPreset(s) == target) {
                insensitive.add(f.key);      // 改了它还认成同一个预设 = 判据漏了这个字段
            }
        }
        check("D-4b 改「预设会写的任何一项」之后都不该再匹配同一预设",
                insensitive.isEmpty(),
                insensitive.isEmpty() ? "覆盖 " + SettingsField.PRESET_FIELDS.size() + " 个字段"
                        : "这些字段改了也不影响匹配（判据漏了）：" + insensitive);

        System.out.println();
        System.out.printf("=== V-FIELD-DOMAIN 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("V-FIELD-DOMAIN RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-FIELD-DOMAIN RESULT: ALL PASS");
        System.exit(0);
    }

    // ---------------------------------------------------------------- 断言用的测量

    /**
     * 内置预设<b>实际写入</b>了哪些字段。
     *
     * <p>做法：把全部字段设成远离任何预设取值的哨兵，再调 {@code applyAnimPresetFields}，
     * 看哪些字段的值变了。跑两轮（哨兵取两组值）取并集 —— 这样即使某个字段的预设值
     * 恰好等于其中一组哨兵，也不会漏掉。
     *
     * <p>不读源码、不抄清单：<b>测的是行为</b>（纪律四-G）。
     */
    private static Set<String> fieldsActuallyWritten() {
        CursorPresets.AnimPreset target = CursorPresets.ANIM_PRESETS[0];
        Set<String> written = new LinkedHashSet<>();

        // 逐字段验证：先把预设应用一遍拿到它的值 v0，再把这个字段扰动成别的，
        // 然后重新应用预设 —— 若值又回到 v0，说明 applyAnimPresetFields 真的会写这个字段。
        //
        // 【为什么不用「一组哨兵值跑两轮」】第一版就是那么写的，D-1 当场抓到它漏字段：
        // slopeTicks 的 setter 把越界值 clamp 成 0，而预设值恰好也是 0，
        // 于是「哨兵 → 预设值」没有变化，被判成「预设不写它」。
        // 逐字段法不依赖「哨兵必须远离预设值」这个假设。
        for (SettingsField f : SettingsField.FIELDS) {
            NeovideCursorSettings.State s = new NeovideCursorSettings.State();
            NeovideCursorConfigurable.applyAnimPresetFields(s, target);
            String v0 = f.encode(s);

            perturb(f, s);
            if (f.encode(s).equals(v0)) {
                continue;               // 扰不动（例如取值域只有一个），无法判定，保守跳过
            }
            NeovideCursorConfigurable.applyAnimPresetFields(s, target);
            if (f.encode(s).equals(v0)) {
                written.add(f.key);     // 被写回预设值 ⇒ 属于写入集合
            }
        }
        return written;
    }

    /** 快照里出现了、但恢复时写不进去的 key。 */
    private static Set<String> deadSnapshotKeys() {
        NeovideCursorSettings.State src = new NeovideCursorSettings.State();
        setSentinels(src, true, -999f);
        String snap = SettingsField.snapshot(src);

        NeovideCursorSettings.State dst = new NeovideCursorSettings.State();
        setSentinels(dst, false, 999f);
        SettingsField.restoreSkipping(dst, snap);

        Set<String> dead = new LinkedHashSet<>();
        for (SettingsField f : SettingsField.PRESET_FIELDS) {
            if (!f.encode(src).equals(f.encode(dst))) {
                dead.add(f.key);
            }
        }
        return dead;
    }

    private static void setSentinels(NeovideCursorSettings.State s, boolean boolVal, float numVal) {
        for (SettingsField f : SettingsField.FIELDS) {
            String v;
            switch (f.kind) {
                case BOOL:
                    v = boolVal ? "1" : "0";
                    break;
                case INT:
                    v = String.valueOf((int) numVal);
                    break;
                case FLOAT:
                    v = String.valueOf(numVal);
                    break;
                case HEX:
                    v = boolVal ? "#111111" : "#222222";
                    break;
                case ENUM:
                    v = boolVal ? "SPRING" : "AFTERIMAGE";
                    break;
                default:
                    continue;
            }
            try {
                f.decode(s, v);
            } catch (Throwable ignored) {
                // 某个字段拒收哨兵不影响其余字段（例如带白名单的 setter）
            }
        }
    }

    /**
     * 把某个字段改到一个与当前不同的值。
     *
     * <p><b>2026-10-31 改用 {@link SettingsField#writeRaw} 而不是 {@code decode}</b>：
     * 校验下沉之后 {@code decode} 会按字段区间<b>夹回</b>，于是「当前值正好在端点」的字段
     * （例如内置预设的 {@code tailOpacity=1.0}、{@code trailSize=1.0}）扰动 +0.01 会被夹回原值
     * → 本方法看不出变化 → D-1 误判成「预设不写这个字段」。
     * 夹具的职责是「改成一个不同的值」，不是「走一遍校验」；绕过校验才是它该做的事。
     * <b>断言本身一个字没改。</b>
     */
    private static void perturb(SettingsField f, NeovideCursorSettings.State s) {
        Object cur = f.readState(s);
        switch (f.kind) {
            case BOOL:
                f.writeRaw(s, !Boolean.TRUE.equals(cur));
                break;
            case INT:
                f.writeRaw(s, ((Integer) cur) + 1);
                break;
            case FLOAT:
                f.writeRaw(s, ((Float) cur) + 0.01f);
                break;
            case HEX:
                f.writeRaw(s, "#123456".equalsIgnoreCase((String) cur) ? "#654321" : "#123456");
                break;
            case ENUM:
                f.writeRaw(s, com.github.yyyolo7a79.neovidecursor.core.NeovideConfig.TrailMode.AFTERIMAGE);
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static Set<String> keysOf(java.util.List<SettingsField> fields) {
        Set<String> out = new LinkedHashSet<>();
        for (SettingsField f : fields) {
            out.add(f.key);
        }
        return out;
    }

    private static Map<String, String> encodeAll(NeovideCursorSettings.State s) {
        Map<String, String> m = new LinkedHashMap<>();
        for (SettingsField f : SettingsField.FIELDS) {
            m.put(f.key, f.encode(s));
        }
        return m;
    }

    private static String diff(Set<String> a, Set<String> b) {
        Set<String> onlyA = new LinkedHashSet<>(a);
        onlyA.removeAll(b);
        Set<String> onlyB = new LinkedHashSet<>(b);
        onlyB.removeAll(a);
        return "仅 PRESET_FIELDS=" + onlyA + "，仅实际写入=" + onlyB;
    }

    private static String nameOf(CursorPresets.AnimPreset p) {
        return p == null ? "null" : p.name();
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
