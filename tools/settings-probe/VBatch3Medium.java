import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.CustomPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.settings.SettingsField;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * V-BATCH3-MEDIUM —— 第 3 批审查收尾「第一批 5 条 MEDIUM」的守门探针（2026-10-31）。
 *
 * <p>覆盖四条（P-M-8 由 {@code VPreviewRank} 单独守门，因为它要驱动 {@code SpringTrail}）：
 * <ul>
 *   <li><b>D-1（P-M-11）</b>：{@code disposeUIResources()} 之后，
 *       3 个预览 + previewConfig + 2 个非 DSL 控件 + 2 个恢复按钮共 <b>8 个引用</b>
 *       必须全部为 null；并且先给一条「dispose 之前它们非 null」的对照 ——
 *       否则「一直是 null」也能通过，那是一条恒真断言。</li>
 *   <li><b>N-1（P-M-5）</b>：自定义预设快照里的越界数值必须被夹回合法区间，
 *       且夹回的结果<b>必须等于 {@code loadState} 对同一份脏数据的处理结果</b>
 *       （「同一个字段两条入口，一条设防一条不设防」正是这条缺陷的原文）；
 *       夹过之后要留一条 warn。</li>
 *   <li><b>N-2（P-M-5 的反面）</b>：合法值必须<b>逐位不变</b> ——
 *       这是「观感零变化」的可测形式，也是防止「为了修越界顺手把所有值都改写」。</li>
 *   <li><b>N-3（P-M-6）</b>：颜色预设快照里的非法 HEX 不得写进 pending，且必须有提示。</li>
 *   <li><b>N-4（P-M-3）</b>：整条损坏的颜色快照同样不许静默 —— 不写半套颜色、
 *       必须有提示、下拉回到真实状态、且不得顺手派生「X 副本」。</li>
 *   <li><b>N-5（结构守门）</b>：{@code PRESET_FIELDS} 里每个「非运行环境开关」的
 *       FLOAT/INT 字段都必须在面板的 {@code SPECS} 里有对应项 —— 否则新加的数值字段
 *       会绕过 N-1 这套校验，重新回到「越界值进 State」。</li>
 * </ul>
 *
 * <p><b>期望值一律由生产代码算出</b>：合法区间取自 {@code NeovideCursorSettings.loadState()}
 * 对同一份脏数据的处理结果（不是抄 {@code SPECS} 的数字，也不是抄 State 的默认值）——
 * 这样「两条入口同源」才是真的被断言，而不是拿实现验实现。
 */
public final class VBatch3Medium {

    private static int pass;
    private static int fail;

    /** 捕获到的 warn / error（按消息内容断言）。 */
    private static final List<String> WARNS = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        // 【必须第一句】生产类的 static final Logger 在类首次被触达时就创建。
        Logger.setFactory(new CapturingFactory());

        System.out.println("=== V-BATCH3-MEDIUM：第 3 批收尾（P-M-11 / P-M-5 / P-M-6 / P-M-3）===");

        a0();
        disposeReleasesReferences();
        presetNumbersClamped();
        legalNumbersUntouched();
        badHexRejected();
        brokenColorSnapshotNotSilent();
        specsCoverEveryNumericPresetField();

        finish();
    }

    // ==================== A-0 机制自检 ====================

    private static void a0() {
        int m = mark();
        Logger.getInstance("vbatch3medium-selftest").warn("selftest-marker");
        check("A-0 日志捕获机制自检（它红 = 其余「必须有日志」的断言都不算数）",
                anyWarnSince(m, "selftest-marker"), "");
    }

    // ==================== D-1（P-M-11）：dispose 释放 8 个引用 ====================

    private static void disposeReleasesReferences() {
        System.out.println();
        System.out.println("--- D-1（P-M-11）：disposeUIResources 必须释放 3 个预览 + 5 个组件引用 ---");

        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        boolean[] before = conf.disposeNullFlagsForProbe();
        check("D-1a 对照：面板建好后 8 个引用都非 null（否则 D-1b 是恒真断言）",
                before.length == 8 && allFalse(before), describe(before));

        conf.disposeUIResources();
        boolean[] after = conf.disposeNullFlagsForProbe();
        check("D-1b dispose 之后 8 个引用全为 null（含 previewConfig —— 它是 refreshPreviews 的唯一门槛）",
                allTrue(after), describe(after));

        // dispose 之后 refreshPreviews 必须彻底变成空操作：
        // 门槛 previewConfig == null 一挡，就不再对三棵已脱离组件树的预览写参数 / repaint。
        //
        // 【为什么不走 refreshPresetDisplayForProbe()】它第一句就判两个下拉是否为 null，
        // dispose 之后直接早退，refreshPreviews() 一次都不会被调到 ——
        // 那条断言会名不副实（审查 2026-10-31 指出）。这里直接调那一层。
        boolean noCrash = true;
        try {
            conf.refreshPreviewsForProbe();
        } catch (Throwable t) {
            noCrash = false;
        }
        check("D-1c dispose 之后 refreshPreviews() 本身不得抛异常（previewConfig 守卫生效）",
                noCrash, "noCrash=" + noCrash);
    }

    // ==================== N-1（P-M-5）：越界数值必须被夹回 ====================

    private static void presetNumbersClamped() {
        System.out.println();
        System.out.println("--- N-1（P-M-5）：越界数值快照夹回量程，且与 loadState 同源 ---");

        // ---- oracle：把同一份脏数据交给 loadState，它给出的才是「另一条入口的答案」 ----
        NeovideCursorSettings.State dirty = new NeovideCursorSettings.State();
        dirty.glowLayers = 99;
        dirty.animationLength = 0.9f;
        dirty.tailOpacity = 5.0f;
        NeovideCursorSettings oracle = new NeovideCursorSettings();
        oracle.loadState(dirty);
        int expectLayers = oracle.getState().glowLayers;
        float expectAnim = oracle.getState().animationLength;
        float expectOpacity = oracle.getState().tailOpacity;

        V0Env.install();
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.animPresetBase = 0;
        s.animCustomName = "";
        s.glowLayers = 8;
        s.animationLength = 0.1f;
        s.tailOpacity = 1.0f;
        s.customAnimPresets = CustomPresets.put("", "越界预设", "b0",
                "glowLayers=99;animationLength=0.9;tailOpacity=5");

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        int noticesBefore = conf.userNoticeCountForProbe();
        int m = mark();
        conf.animPresetComboForProbe().setSelectedIndex(CursorPresets.ANIM_PRESETS.length);

        NeovideCursorSettings.State p = conf.pendingForProbe();
        check("N-1a 越界 glowLayers 被夹回（结果必须 == loadState 对同一脏值的处理）",
                p.glowLayers == expectLayers,
                "pending=" + p.glowLayers + " loadState=" + expectLayers);
        check("N-1b 越界 animationLength 被夹回（== loadState）",
                Math.abs(p.animationLength - expectAnim) < 1e-6f,
                "pending=" + p.animationLength + " loadState=" + expectAnim);
        check("N-1c 越界 tailOpacity 被夹回（== loadState）",
                Math.abs(p.tailOpacity - expectOpacity) < 1e-6f,
                "pending=" + p.tailOpacity + " loadState=" + expectOpacity);
        check("N-1d 夹回时留一条 warn，且写清是哪个字段",
                anyWarnSince(m, "glowLayers"),
                "warn=" + brief(m));
        check("N-1e 夹回时给用户一行提示（不静默改值）",
                conf.userNoticeCountForProbe() > noticesBefore,
                "提示=" + conf.lastUserNoticeForProbe());

        // ---- N-1f：夹回要「自愈」—— 同一条坏预设再选一次不得再弹一次 ----
        //
        // 审查 2026-10-31 实测：不回写快照时，用户在这个预设上每点一次下拉就弹一次模态框。
        // 「编辑即保存」本来就是这条路径的语义（站在自定义预设上编辑 → 快照实时更新），
        // 夹回也是一次编辑，所以第一次夹回就该把快照写干净。
        int noticesAfterFirst = conf.userNoticeCountForProbe();
        conf.pickAnimPresetForProbe(0);                         // 先离开这个预设
        conf.animPresetComboForProbe().setSelectedIndex(CursorPresets.ANIM_PRESETS.length);
        check("N-1f 夹回后自愈：再选同一条坏预设不再重复提示",
                conf.userNoticeCountForProbe() == noticesAfterFirst,
                "第一次之后=" + noticesAfterFirst + " 第二次之后="
                        + conf.userNoticeCountForProbe()
                        + " 快照仍含99="
                        + conf.pendingForProbe().customAnimPresets.contains("glowLayers=99"));
    }

    // ==================== N-2（P-M-5 反面）：合法值一位不变 ====================

    private static void legalNumbersUntouched() {
        System.out.println();
        System.out.println("--- N-2（P-M-5 反面）：合法数值必须逐位不变（观感零变化） ---");

        V0Env.install();
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.animPresetBase = 0;
        s.animCustomName = "";
        s.glowLayers = 3;
        s.animationLength = 0.2f;
        s.tailOpacity = 0.47f;          // 不在 20% 网格上：说明「只夹越界、不吸附网格」
        s.stretchRatioK = 3.3f;
        s.customAnimPresets = CustomPresets.put("", "合法预设", "b0",
                "glowLayers=5;animationLength=0.12;tailOpacity=0.47;stretchRatioK=3.3");

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        conf.animPresetComboForProbe().setSelectedIndex(CursorPresets.ANIM_PRESETS.length);

        NeovideCursorSettings.State p = conf.pendingForProbe();
        check("N-2a 合法值一位不变（glowLayers=5）", p.glowLayers == 5, "=" + p.glowLayers);
        check("N-2b 合法值一位不变（animationLength=0.12）",
                Float.compare(p.animationLength, 0.12f) == 0, "=" + p.animationLength);
        check("N-2c 合法的非网格值不被吸到网格上（tailOpacity=0.47）",
                Float.compare(p.tailOpacity, 0.47f) == 0, "=" + p.tailOpacity);
        check("N-2d 合法的非网格值不被吸到网格上（stretchRatioK=3.3）",
                Float.compare(p.stretchRatioK, 3.3f) == 0, "=" + p.stretchRatioK);
    }

    // ==================== N-3（P-M-6）：非法 HEX 不得进 State ====================

    private static void badHexRejected() {
        System.out.println();
        System.out.println("--- N-3（P-M-6）：颜色预设快照里的非法 HEX 不得写进 pending ---");

        V0Env.install();
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.colorPresetBase = 0;
        s.colorCustomName = "";
        s.tailColor = "#123456";
        s.shadowColor = "#654321";
        s.customColorPresets = CustomPresets.put("", "坏颜色", "b0",
                "tailColor=not-a-color;shadowColor=#FFFFFF");

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        int noticesBefore = conf.userNoticeCountForProbe();
        conf.colorPresetComboForProbe().setSelectedIndex(CursorPresets.COLOR_PRESETS.length);

        NeovideCursorSettings.State p = conf.pendingForProbe();
        check("N-3a 非法 HEX 不得写进 pending（tailColor 保持原值）",
                "#123456".equals(p.tailColor), "tailColor=" + p.tailColor);
        check("N-3b 非法 HEX 不得写进 pending（shadowColor 保持原值，不写半套）",
                "#654321".equals(p.shadowColor), "shadowColor=" + p.shadowColor);
        check("N-3c 非法 HEX 必须给用户一行提示",
                conf.userNoticeCountForProbe() > noticesBefore,
                "提示=" + conf.lastUserNoticeForProbe());

        // 对照：合法的 9 位 RGBA 仍是合法颜色（本轮不收紧口径）
        //
        // 【已知不一致，不是本探针在替它背书】`SettingsField.isValidHexColor` 收 7/9 位，
        // 而 `NeovideCursorSettings.sanitizeColor`（loadState 用）只收 7 位 —— 于是 9 位
        // 颜色**应用后能生效、重启后回默认**（第 1 批 M-1「颜色合法性三套规则」）。
        // 那是 loadState 侧的既有缺陷，本轮白名单不动 settings/，故只断言「面板不收紧」，
        // 不把「重启后一致」写成期望。修它需要同时改 sanitizeColor 与本条。
        V0Env.install();
        NeovideCursorSettings.State s2 = V0Env.svc.getState();
        s2.colorPresetBase = 0;
        s2.colorCustomName = "";
        s2.tailColor = "#123456";
        s2.customColorPresets = CustomPresets.put("", "RGBA", "b0",
                "tailColor=#80FFC0CB;shadowColor=#FFFFFF");
        NeovideCursorConfigurable conf2 = new NeovideCursorConfigurable();
        VUi.build(conf2);
        conf2.reset();
        conf2.colorPresetComboForProbe().setSelectedIndex(CursorPresets.COLOR_PRESETS.length);
        check("N-3d 合法的 9 位 #AARRGGBB 仍被接受（口径与 isValidHexColor 一致）",
                "#80FFC0CB".equals(conf2.pendingForProbe().tailColor),
                "tailColor=" + conf2.pendingForProbe().tailColor);

        // ---- N-3e：校验会 trim，写入也必须 trim ----
        //
        // `isValidHexColor` 内部 trim 后判定合法，而绘制层的 `NeovideColors.parse`
        // **不 trim**。若把原串（带前导空格）写进 State，就是「校验通过、绘制认不出、
        // 光标变黑」—— 正是 0.9.74 加 isValidHexColor 要挡的那类。动画快照那条路径
        // （SettingsField.decode 的 HEX 分支）本来就是 trim 后再赋值。
        V0Env.install();
        NeovideCursorSettings.State s3 = V0Env.svc.getState();
        s3.colorPresetBase = 0;
        s3.colorCustomName = "";
        s3.tailColor = "#123456";
        s3.customColorPresets = CustomPresets.put("", "带空格", "b0",
                "tailColor= #80FFC0CB;shadowColor=#FFFFFF");
        NeovideCursorConfigurable conf3 = new NeovideCursorConfigurable();
        VUi.build(conf3);
        conf3.reset();
        conf3.colorPresetComboForProbe().setSelectedIndex(CursorPresets.COLOR_PRESETS.length);
        check("N-3e 写入前必须 trim（绘制层不 trim，带空格的串会变成黑色）",
                "#80FFC0CB".equals(conf3.pendingForProbe().tailColor),
                "tailColor=「" + conf3.pendingForProbe().tailColor + "」");
    }

    // ==================== N-4（P-M-3）：坏颜色快照不得静默 ====================

    private static void brokenColorSnapshotNotSilent() {
        System.out.println();
        System.out.println("--- N-4（P-M-3）：整条损坏的颜色快照不得静默无反应 ---");

        V0Env.install();
        NeovideCursorSettings.State s = V0Env.svc.getState();
        s.colorPresetBase = 0;
        s.colorCustomName = "";
        s.tailColor = "#123456";
        s.shadowColor = "#654321";
        s.customColorPresets = CustomPresets.put("", "坏快照", "b0", "garbage");

        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();

        int noticesBefore = conf.userNoticeCountForProbe();
        int entriesBefore = CustomPresets.parse(conf.pendingForProbe().customColorPresets).size();
        int selBefore = conf.colorPresetComboForProbe().getSelectedIndex();

        conf.colorPresetComboForProbe().setSelectedIndex(CursorPresets.COLOR_PRESETS.length);

        NeovideCursorSettings.State p = conf.pendingForProbe();
        check("N-4a 坏快照不写半套颜色", "#123456".equals(p.tailColor) && "#654321".equals(p.shadowColor),
                "tail=" + p.tailColor + " shadow=" + p.shadowColor);
        check("N-4b 坏快照必须给用户一行提示（不许点了没反应）",
                conf.userNoticeCountForProbe() > noticesBefore,
                "提示=" + conf.lastUserNoticeForProbe());
        check("N-4c 坏快照不得写 colorCustomName（下拉不得声称已切换）",
                p.colorCustomName.isEmpty(), "name=「" + p.colorCustomName + "」");
        check("N-4d 下拉回到真实选中项",
                conf.colorPresetComboForProbe().getSelectedIndex() == selBefore,
                "之前=" + selBefore + " 之后=" + conf.colorPresetComboForProbe().getSelectedIndex());
        check("N-4e 兜底刷新不得顺手派生「X 副本」",
                CustomPresets.parse(p.customColorPresets).size() == entriesBefore
                        && p.colorCustomName.isEmpty(),
                "条目 " + entriesBefore + "→"
                        + CustomPresets.parse(p.customColorPresets).size());
    }

    // ==================== N-5：SPECS 覆盖所有数值预设字段 ====================

    private static void specsCoverEveryNumericPresetField() {
        System.out.println();
        System.out.println("--- N-5（结构）：每个非 runtimeSwitch 的数值预设字段都必须有滑块规格 ---");

        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        VUi.build(conf);
        conf.reset();
        List<String> sliderKeys = conf.sliderKeysForProbe();

        List<String> missing = new ArrayList<>();
        List<String> covered = new ArrayList<>();
        for (SettingsField f : SettingsField.PRESET_FIELDS) {
            if (f.runtimeSwitch) {
                continue;                       // 运行环境开关：预设还原根本不会写它
            }
            if (f.kind != SettingsField.Kind.FLOAT && f.kind != SettingsField.Kind.INT) {
                continue;                       // 布尔 / 枚举 / 颜色：不参与数值量程
            }
            if (sliderKeys.contains(f.key)) {
                covered.add(f.key);
            } else {
                missing.add(f.key);
            }
        }
        check("N-5 非 runtimeSwitch 的数值预设字段全部有滑块规格（否则会绕过量程校验）",
                missing.isEmpty(),
                "缺=" + missing + " 覆盖=" + covered.size() + " 项");
    }

    // ==================== helpers ====================

    private static boolean allFalse(boolean[] a) {
        for (boolean b : a) {
            if (b) {
                return false;
            }
        }
        return true;
    }

    private static boolean allTrue(boolean[] a) {
        if (a == null || a.length == 0) {
            return false;
        }
        for (boolean b : a) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    private static final String[] NAMES = {
            "inlinePreview", "crossPreview", "glowPreview", "previewConfig",
            "trailModeCombo", "paintDiagCheck", "animRestoreBtn", "colorRestoreBtn"
    };

    private static String describe(boolean[] flags) {
        StringBuilder sb = new StringBuilder();
        if (flags == null) {
            return "null";
        }
        for (int i = 0; i < flags.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(i < NAMES.length ? NAMES[i] : "?").append('=').append(flags[i] ? "null" : "非null");
        }
        return sb.toString();
    }

    private static int mark() {
        return WARNS.size();
    }

    private static boolean anyWarnSince(int m, String needle) {
        for (int i = Math.min(m, WARNS.size()); i < WARNS.size(); i++) {
            if (WARNS.get(i).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String brief(int m) {
        if (WARNS.size() <= m) {
            return "无";
        }
        String w = WARNS.get(WARNS.size() - 1);
        return w.length() > 100 ? w.substring(0, 100) + "…" : w;
    }

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
        System.out.printf("=== VBATCH3-MEDIUM 结果：PASS %d，FAIL %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("VBATCH3-MEDIUM RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("VBATCH3-MEDIUM RESULT: ALL PASS");
        System.exit(0);
    }

    // ==================== 日志捕获 ====================

    private static final class CapturingFactory implements Logger.Factory {
        @Override
        public Logger getLoggerInstance(String category) {
            return new Capturing(category);
        }
    }

    private static final class Capturing extends Logger {
        private final String category;

        Capturing(String category) {
            this.category = category;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public void debug(String message, Throwable t) {
        }

        @Override
        public void info(String message) {
            // 本探针只断言 warn/error，info 不收集
        }

        @Override
        public void info(String message, Throwable t) {
        }

        @Override
        public void warn(String message) {
            WARNS.add(category + ": " + message);
        }

        @Override
        public void warn(String message, Throwable t) {
            WARNS.add(category + ": " + message);
        }

        @Override
        public void error(String message) {
            WARNS.add(category + ": " + message);
        }

        @Override
        public void error(String message, Throwable t, String... details) {
            WARNS.add(category + ": " + message);
        }
    }
}
