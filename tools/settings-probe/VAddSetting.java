import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * V-ADD-SETTING：设置页对「加一个新参数」的稳健性审计。
 *
 * <p><b>为什么要这个探针</b>：用户要求「设置页真的改不坏再继续」。判断标准不是
 * 读代码觉得整洁，而是主动去找「加了新字段但某处没跟上」的漏点。
 *
 * <p>做法：把 State 里所有 public 实例字段与 SettingsField.FIELDS 的 key 集合做差。
 * 差集里的字段分两类：
 * <ul>
 *   <li><b>OK 漏</b>：确实不需要持久化（内存缓存、派生值）</li>
 *   <li><b>坏 漏</b>：需要持久化但没进表 —— apply() 会静默把它回滚（BUG: diagLogging 就是这样）</li>
 * </ul>
 * 本探针把两者都列出来，由白名单区分。
 */
public final class VAddSetting {
    private static int pass = 0, fail = 0;

    /**
     * 已知「不必进 SettingsField.FIELDS」的 State 字段（白名单）。
     * 每加一条都要写清理由 —— 这是防止「漏了就当设计」的唯一手段。
     */
    private static final Set<String> NOT_PERSISTED = new LinkedHashSet<>(List.of(
            "stateVersion",          // 版本号，由 loadState 单独处理
            "customAnimPresets",     // 自定义预设表，由 CustomPresets 单独管理
            "animCustomName",        // 派生名，同上
            "animPresetBase"         // 进表但标记为「不进快照」，见 presetPointer()
    ));

    public static void main(String[] a) throws Exception {
        System.out.println("=== V-ADD-SETTING：设置页对新增参数的稳健性 ===");

        // ---------- A. State 字段 vs SettingsField.FIELDS ----------
        List<String> stateFields = publicInstanceFields(NeovideCursorSettings.State.class);
        Set<String> tableKeys = new LinkedHashSet<>();
        for (SettingsField f : SettingsField.FIELDS) {
            tableKeys.add(f.key);
        }
        System.out.println("  State 字段数 = " + stateFields.size()
                + "，FIELDS 表项数 = " + tableKeys.size());

        List<String> missing = new ArrayList<>();
        for (String s : stateFields) {
            if (!tableKeys.contains(s)) {
                missing.add(s);
            }
        }
        List<String> unexplained = new ArrayList<>();
        for (String m : missing) {
            if (!NOT_PERSISTED.contains(m)) {
                unexplained.add(m);
            }
        }
        check("A-1 每个 State 字段都在 SettingsField.FIELDS 里（或白名单有理由）",
                unexplained.isEmpty(),
                unexplained.isEmpty() ? "全部覆盖" : "未解释的缺口=" + unexplained);

        // 反查：表里有但 State 里没有的 key（拼错字段名的典型症状）
        List<String> ghost = new ArrayList<>();
        for (String k : tableKeys) {
            if (!stateFields.contains(k)) {
                ghost.add(k);
            }
        }
        check("A-2 FIELDS 里没有指向不存在字段的 key（拼写错误会静默失效）",
                ghost.isEmpty(), ghost.isEmpty() ? "无幽灵 key" : "幽灵=" + ghost);

        // ---------- B. copyFrom 覆盖度 ----------
        // 用反射造一个「所有字段都设成非默认值」的 State，copyFrom 后逐字段比对。
        NeovideCursorSettings.State src = new NeovideCursorSettings.State();
        NeovideCursorSettings.State dst = new NeovideCursorSettings.State();
        setAllTo(src, true);
        dst.copyFrom(src);
        List<String> notCopied = new ArrayList<>();
        for (String f : stateFields) {
            if (NOT_PERSISTED.contains(f)) {
                continue;
            }
            Field fld = NeovideCursorSettings.State.class.getField(f);
            Object sv = fld.get(src);
            Object dv = fld.get(dst);
            if (sv instanceof Float && dv instanceof Float) {
                if (Math.abs((Float) sv - (Float) dv) > 1e-6f) {
                    notCopied.add(f);
                }
            } else if (sv != null && !sv.equals(dv)) {
                notCopied.add(f);
            }
        }
        check("B-1 copyFrom 覆盖全部参与持久化的字段（漏一个 = 面板回显不出来）",
                notCopied.isEmpty(),
                notCopied.isEmpty() ? "全覆盖" : "漏拷=" + notCopied);

        // ---------- C. 面板能建成、控件数合理 ----------
        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi ui = VUi.build(conf);
        check("C-1 createComponent 不抛异常且返回非 null", ui.panel.component != null,
                String.valueOf(ui.panel.component == null ? "null" : ui.panel.component.getClass().getSimpleName()));

        int sliders = ui.sliders.size();
        int checks = ui.checkBoxes.size();
        int combos = ui.combos.size();
        check("C-2 控件数量与已知基线一致（滑块 13 / 复选框 9 / 下拉 3）",
                sliders == 13 && checks == 9 && combos == 3,
                "滑块=" + sliders + " 复选框=" + checks + " 下拉=" + combos
                        + "（若这里变了，说明面板结构被改动，需同步更新探针）");

        // ---------- D. reset / apply / isModified 三步都不炸 ----------
        boolean ok = true;
        String crash = "";
        try {
            conf.reset();
            boolean m1 = conf.isModified();
            conf.apply();
            boolean m2 = conf.isModified();
            if (m1 || m2) {
                ok = false;
                crash = "reset 后 isModified 应为 false，实际 " + m1 + " / apply 后 " + m2;
            }
        } catch (Throwable t) {
            ok = false;
            crash = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        check("D-1 reset → isModified=false → apply → isModified=false（无残留脏状态）",
                ok, ok ? "正常" : crash);

        // ---------- E. 新增参数的四层同步：位置清单 ----------
        // 这不是断言，是把「加一个参数要改哪几处」打印出来，供人工核对。
        System.out.println();
        System.out.println("  ── 加一个新 sliders 参数需要改动的 4 处（现状）──");
        System.out.println("     1) NeovideCursorSettings.State  —— 加字段 + 默认值");
        System.out.println("     2) State.copyFrom()            —— 漏了则面板回显不出来");
        System.out.println("     3) SettingsField.FIELDS        —— 漏了则 apply 静默回滚");
        System.out.println("     4) NeovideCursorConfigurable   —— SPECS[] 加项 + build*Group 加一行");
        System.out.println("  ── 加一个新 checkbox 只需改 3 处（跳过 SPECS）──");
        System.out.println("     1) State 字段  2) copyFrom  3) FIELDS  + build*Group 里 bindCheck 一行");

        System.out.println();
        System.out.printf("=== V-ADD-SETTING 结果：PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("V-ADD-SETTING RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("V-ADD-SETTING RESULT: ALL PASS");
    }

    /** 收集 public 实例字段名（排除 static / final 常量） */
    private static List<String> publicInstanceFields(Class<?> c) {
        List<String> out = new ArrayList<>();
        for (Field f : c.getFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod)) continue;
            out.add(f.getName());
        }
        return out;
    }

    /** 把所有可写字段设成「非默认值」，用于验证 copyFrom 覆盖度 */
    private static void setAllTo(NeovideCursorSettings.State s, boolean marker) throws Exception {
        for (Field f : s.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
            Class<?> t = f.getType();
            try {
                if (t == boolean.class) f.setBoolean(s, !f.getBoolean(s));
                else if (t == int.class) f.setInt(s, f.getInt(s) + 7);
                else if (t == float.class) f.setFloat(s, f.getFloat(s) + 0.137f);
                else if (t == String.class) f.set(s, "probe-" + f.getName());
            } catch (Throwable ignored) {
                // 某些字段有特殊约束，跳过即可
            }
        }
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
