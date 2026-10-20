import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.TimerListener;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.components.JBTextField;

import javax.swing.JSlider;
import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * t7 探针 —— t5 审查的三条 medium findings 的回归护栏。
 *
 * <p>这三条 t2/t3/t4 的既有探针<b>都覆盖不到</b>，因此必须单独立项：
 * <ul>
 *   <li><b>T-01（R-01）</b>：连续两次 apply 不得冲掉 Tools 的改动。
 *       既有 B3-5 只测「连续两次 apply 不丢<b>用户自己</b>的值」，没测 Tools 的值；</li>
 *   <li><b>T-02（R-02）</b>：用户拖滑块后，两个预设下拉必须实时变成末项「自定义」。
 *       既有 P 系列 17 条全部从 {@code applyAnimPreset} 入口驱动，没有一条从「用户拖滑块」入口驱动下拉显示；</li>
 *   <li><b>T-03（R-03）</b>：切主题后旧 scheme 也必须能被恢复。
 *       既有 S 系列 14 条没有一条测「旧 scheme 的 CARET_COLOR」。</li>
 * </ul>
 *
 * <p>每条都必须在「修复前形态」上失败 —— 见 {@code run-t7-probe.ps1} 的变异段。
 *
 * <p>退出码：0 = 全通过；1 = 有失败项。
 */
public final class ProbeT7 {

    private static int pass = 0;
    private static int fail = 0;

    private static NeovideCursorSettings svc;
    private static FakeColorsManager colors;
    private static EditorFactory factory;

    public static void main(String[] args) throws Exception {
        System.out.println("=== t7 探针：R-01 / R-02 / R-03 ===");
        System.out.println();
        svc = new NeovideCursorSettings();
        installFakeApp();

        testR01();
        testR02();
        testR03();

        System.out.println();
        System.out.printf("=== 结果：通过 %d，失败 %d ===%n", pass, fail);
        System.out.flush();
        if (fail > 0) {
            System.out.println("HAS FAILURES");
            System.exit(1);
        }
        System.out.println("ALL OK");
        System.exit(0);
    }

    // ================================================================ R-01
    /**
     * T-01：Tools 改 X → apply → apply → X 仍是 Tools 的值。
     *
     * <p>修复前（只前移 baseline、不前移 pending）的失败路径：
     * 第 1 次 apply 保住 Tools 的 X；但 pending.X 仍是面板打开时的旧值，
     * 于是 {@code isModified()} 保持 true、按钮继续亮着；第 2 次 apply 时
     * baseline 已被前移成 Tools 的新值，{@code pending.X != baseline.X} 成立
     * → 把旧值写回去 → Tools 被冲掉。
     */
    private static void testR01() {
        System.out.println("--- T-01 / R-01：连续两次 apply 不冲掉 Tools 的改动 ---");
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        conf.createComponent();

        resetState();
        svc.getState().glowLayers = 8;              // 面板打开时的基准
        conf.reset();                                // 拍 baseline

        svc.getState().glowLayers = 3;               // ← Tools 菜单把它改成 3（用户没碰滑块）
        conf.apply();                                // ← 第 1 次点「应用」
        check("T-01a 第 1 次 apply：Tools 的 glowLayers=3 保住",
                svc.getState().glowLayers == 3, "glowLayers=" + svc.getState().glowLayers);

        boolean stillModified = conf.isModified();
        conf.apply();                                // ← 第 2 次点「应用」（用户手滑/连点）
        check("T-01b 第 2 次 apply：Tools 的 glowLayers=3 【仍】保住（修复前会被冲回 8）",
                svc.getState().glowLayers == 3, "glowLayers=" + svc.getState().glowLayers);
        check("T-01c apply 之后 isModified() 必须为 false（pending 与 baseline 同源前移）",
                !conf.isModified(), "第 1 次 apply 后 isModified=" + stillModified
                        + "，第 2 次 apply 后 isModified=" + conf.isModified());

        // T-01d：连续两次 apply 也不得丢掉用户自己的改动（回归保护）
        resetState();
        conf.reset();
        conf.slidersForProbe().get(4).setValue(6);   // 用户把 glowLayers 拖到 6
        conf.apply();
        conf.apply();
        check("T-01d 连续两次 apply 不丢【用户】的改动（glowLayers 仍 6）",
                svc.getState().glowLayers == 6, "glowLayers=" + svc.getState().glowLayers);

        // T-01e：Tools 改 X + 用户改 Y → 两次 apply 后两者都对
        resetState();
        svc.getState().glowLayers = 8;
        svc.getState().overlayFadeOut = true;
        conf.reset();
        conf.slidersForProbe().get(4).setValue(5);   // 用户改 glowLayers → 5
        svc.getState().overlayFadeOut = false;       // Tools 改 overlayFadeOut → false
        conf.apply();
        conf.apply();
        check("T-01e 两次 apply 后：用户的 glowLayers=5 与 Tools 的 overlayFadeOut=false 同时成立",
                svc.getState().glowLayers == 5 && !svc.getState().overlayFadeOut,
                "glowLayers=" + svc.getState().glowLayers
                        + " overlayFadeOut=" + svc.getState().overlayFadeOut);
        System.out.println();
    }

    // ================================================================ R-02
    /**
     * T-02：用户拖滑块改掉动画参数后，两个下拉都必须变成末项「自定义」。
     *
     * <p>修复前：滑块 ChangeListener 只调 {@code refreshDerivedHints()}，不重算预设显示，
     * 于是下拉继续显示「原版粉色」，而实际值早已不是它 —— <b>显示层撒谎</b>。
     */
    private static void testR02() {
        System.out.println("--- T-02 / R-02：拖滑块后预设下拉实时变成「自定义」 ---");
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();
        conf.createComponent();

        int customIdxAnim = conf.animPresetComboForProbe().getItemCount() - 1;
        int customIdxColor = conf.colorPresetComboForProbe().getItemCount() - 1;

        // 起点：先应用「原版粉色」动画预设，让下拉显示预设 1
        resetState();
        conf.reset();
        conf.pickAnimPresetForProbe(0);
        check("T-02a 起点：应用预设 1 后动画下拉显示下标 0（原版粉色）",
                conf.animPresetComboForProbe().getSelectedIndex() == 0,
                "下拉下标=" + conf.animPresetComboForProbe().getSelectedIndex());

        // 用户把「辉光层数」拖到 3 —— 不在任何动画预设里
        int glowIdx = conf.sliderKeysForProbe().indexOf("glowLayers");
        conf.slidersForProbe().get(glowIdx).setValue(3);

        check("T-02b 拖 glowLayers 8→3 后，动画下拉变成末项「自定义」",
                conf.animPresetComboForProbe().getSelectedIndex() == customIdxAnim,
                "下拉下标=" + conf.animPresetComboForProbe().getSelectedIndex()
                        + "（期望 " + customIdxAnim + "）");
        check("T-02c 末项文案确实是「" + CursorPresets.CUSTOM_LABEL + "」",
                CursorPresets.CUSTOM_LABEL.equals(
                        conf.animPresetComboForProbe().getItemAt(customIdxAnim)),
                String.valueOf(conf.animPresetComboForProbe().getItemAt(customIdxAnim)));

        // 颜色下拉此时仍应是「原版粉色」（颜色没被碰）—— 正交性 + 只刷该刷的
        check("T-02d 颜色下拉不受影响，仍是预设 1（动画改动不污染颜色显示）",
                conf.colorPresetComboForProbe().getSelectedIndex() == 0,
                "颜色下拉下标=" + conf.colorPresetComboForProbe().getSelectedIndex());

        // 再拖回 8 —— 应重新匹配上预设 1（不能单向卡在「自定义」）
        conf.slidersForProbe().get(glowIdx).setValue(8);
        check("T-02e 把 glowLayers 拖回 8 后，动画下拉重新显示预设 1（不是单向卡死）",
                conf.animPresetComboForProbe().getSelectedIndex() == 0,
                "下拉下标=" + conf.animPresetComboForProbe().getSelectedIndex());

        // 键入路径（onTextEdited）也必须刷新
        resetState();
        conf.reset();
        conf.pickAnimPresetForProbe(0);
        int layersIdx = conf.sliderKeysForProbe().indexOf("glowLayers");
        conf.textFieldsForProbe().get(layersIdx).setText("3");
        check("T-02f 在输入框键入 3（onTextEdited 路径）同样让下拉变「自定义」",
                conf.animPresetComboForProbe().getSelectedIndex() == customIdxAnim,
                "下拉下标=" + conf.animPresetComboForProbe().getSelectedIndex());

        // ColorPanel 路径（R-02 第 4 处入口）
        resetState();
        conf.reset();
        conf.pickColorPresetForProbe(0);
        ColorPanel tail = conf.tailColorPanelForProbe();
        check("T-02g 找到拖尾颜色 ColorPanel", tail != null, tail == null ? "null" : "ok");
        if (tail != null) {
            tail.setSelectedColor(new Color(0x12, 0x34, 0x56));
            // ColorPanel.setSelectedColor 不派发 ActionEvent，这里直接驱动回调体
            // （平台上用户选色会派发；探针里等价调用同一个入口）
            conf.onColorPanelChangedForProbe();
            check("T-02h 改 ColorPanel（onColorPanelChanged 路径）后颜色下拉变「自定义」",
                    conf.colorPresetComboForProbe().getSelectedIndex() == customIdxColor,
                    "颜色下拉下标=" + conf.colorPresetComboForProbe().getSelectedIndex());
        }
        System.out.println();
    }

    // ================================================================ R-03
    /**
     * T-03：A 隐藏 → 切 B（force）→ 取消勾选 → <b>A 也必须恢复</b>。
     *
     * <p>修复前：{@code savedCaretColor} 只有一个槽位，切到 B 时被 B 的原色覆盖，
     * A 的透明色从此无人恢复；{@code restoreNativeCaret()} 只写当前 scheme。
     */
    private static void testR03() {
        System.out.println("--- T-03 / R-03：切主题后旧 scheme 也必须恢复 ---");

        NeovideCursorSettings.State st = svc.getState();
        st.enabled = true;
        st.hideNativeCaret = true;
        NeovideCaretManager.syncStateToConfig();
        NeovideCaretManager.clearSavedCaretColorForProbe();
        NeovideCaretManager.clearHiddenSchemesForProbe();

        Color aCaret = new Color(0xA9, 0xB7, 0xC6);
        Color bCaret = new Color(0xFF, 0x00, 0x00);

        EditorColorsScheme schemeA = newScheme(aCaret);
        EditorColorsScheme schemeB = newScheme(bCaret);

        // 1) 在 A 主题下隐藏
        colors.scheme = schemeA;
        NeovideCaretManager.applyNativeCaretHidden();
        check("T-03a A 主题下隐藏：A 的 CARET_COLOR 变透明",
                isTransparent(schemeA.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(schemeA.getColor(EditorColors.CARET_COLOR)));

        // 2) 切到 B 主题（模拟 CaretSchemeWatcher 收到 TOPIC 后 force）
        colors.scheme = schemeB;
        NeovideCaretManager.applyNativeCaretHidden(true);
        check("T-03b 切到 B 后 force 隐藏：B 的 CARET_COLOR 变透明",
                isTransparent(schemeB.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(schemeB.getColor(EditorColors.CARET_COLOR)));
        check("T-03c 记录里应有两个被改过的 scheme（A 与 B）",
                NeovideCaretManager.hiddenSchemeCountForProbe() == 2,
                "记录数=" + NeovideCaretManager.hiddenSchemeCountForProbe());

        // 3) 用户取消勾选
        NeovideCaretManager.restoreNativeCaret();

        check("T-03d 取消勾选后：B（当前 scheme）恢复为可见色",
                bCaret.equals(schemeB.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(schemeB.getColor(EditorColors.CARET_COLOR)));
        check("T-03e 【R-03 核心】取消勾选后：A（旧 scheme）也恢复为可见色"
                        + "（修复前 A 会永久停在透明）",
                aCaret.equals(schemeA.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(schemeA.getColor(EditorColors.CARET_COLOR)));
        check("T-03f 恢复后记录被清空（不泄漏）",
                NeovideCaretManager.hiddenSchemeCountForProbe() == 0,
                "记录数=" + NeovideCaretManager.hiddenSchemeCountForProbe());

        // 4) 三个主题的链式切换：三个都隐藏，restore 后三个都可见
        NeovideCaretManager.clearSavedCaretColorForProbe();
        NeovideCaretManager.clearHiddenSchemesForProbe();
        Color c1 = new Color(0x11, 0x22, 0x33);
        Color c2 = new Color(0x44, 0x55, 0x66);
        Color c3 = new Color(0x77, 0x88, 0x99);
        EditorColorsScheme s1 = newScheme(c1);
        EditorColorsScheme s2 = newScheme(c2);
        EditorColorsScheme s3 = newScheme(c3);

        colors.scheme = s1;
        NeovideCaretManager.applyNativeCaretHidden();
        colors.scheme = s2;
        NeovideCaretManager.applyNativeCaretHidden(true);
        colors.scheme = s3;
        NeovideCaretManager.applyNativeCaretHidden(true);
        check("T-03g 三次切主题后记录数 = 3",
                NeovideCaretManager.hiddenSchemeCountForProbe() == 3,
                "记录数=" + NeovideCaretManager.hiddenSchemeCountForProbe());

        NeovideCaretManager.restoreNativeCaret();
        Set<String> restored = new HashSet<>();
        if (c1.equals(s1.getColor(EditorColors.CARET_COLOR))) {
            restored.add("s1");
        }
        if (c2.equals(s2.getColor(EditorColors.CARET_COLOR))) {
            restored.add("s2");
        }
        if (c3.equals(s3.getColor(EditorColors.CARET_COLOR))) {
            restored.add("s3");
        }
        check("T-03h 链式切换 3 个主题后，restore 让【三个都】恢复可见",
                restored.size() == 3, "已恢复=" + restored);

        // ===== T-03i / t9 / t8-R09：「切回已隐藏过的 A」这条路径 =====
        //
        // 这是 t7 修 R-03 时引入的新缺陷：
        //   A 隐藏（记录 A 原色）→ 切 B（force）→ 【切回 A】
        //   切回 A 时 A 已是透明 → sanitizeCaretColor 只能返回【兜底前景色】
        //   → HIDDEN_SCHEMES.put 把 A 的正确原色【覆盖】成兜底色
        //   → 且 restoreNativeCaret 的兜底段又用兜底色盖一次
        //   → 用户取消勾选后 A 变成兜底色，自己设的光标色永久丢失。
        //
        // 修法：(a) putIfAbsent 保住第一次记录；(b) 兜底段跳过「已由记录表处理」的 scheme。
        NeovideCaretManager.clearSavedCaretColorForProbe();
        NeovideCaretManager.clearHiddenSchemesForProbe();

        Color aReal = new Color(0xA9, 0xB7, 0xC6);       // A 的真实原色
        Color aFallback = new Color(0x00, 0xFF, 0x00);  // A 的「兜底前景色」——故意设成与真实色不同
        Color bReal = new Color(0xFF, 0x00, 0x00);

        EditorColorsScheme reA = newSchemeWithFallback(aReal, aFallback);
        EditorColorsScheme reB = newSchemeWithFallback(bReal, new Color(0x00, 0x00, 0xFF));

        // 1) A 隐藏
        colors.scheme = reA;
        NeovideCaretManager.applyNativeCaretHidden();
        check("T-03i-1 A 隐藏后 A 变透明",
                isTransparent(reA.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(reA.getColor(EditorColors.CARET_COLOR)));

        // 2) 切到 B，force 隐藏
        colors.scheme = reB;
        NeovideCaretManager.applyNativeCaretHidden(true);
        check("T-03i-2 切到 B 后 B 变透明",
                isTransparent(reB.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(reB.getColor(EditorColors.CARET_COLOR)));

        // 3) 【关键】切回 A —— 此时 A 仍是透明，sanitize 只能返回兜底色
        colors.scheme = reA;
        NeovideCaretManager.applyNativeCaretHidden(true);
        check("T-03i-3 切回 A 后 A 仍透明",
                isTransparent(reA.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(reA.getColor(EditorColors.CARET_COLOR)));

        // 4) 取消勾选
        NeovideCaretManager.restoreNativeCaret();
        Color aFinal = reA.getColor(EditorColors.CARET_COLOR);
        check("T-03i-4 【t8-R09 核心】切回 A 后取消勾选：A 必须是【真实原色 #A9B7C6】"
                        + "（修复前被兜底前景色 #00FF00 覆盖）",
                aReal.equals(aFinal),
                "实际=" + aFinal + "  期望真原色=" + aReal + "  兜底色=" + aFallback);
        Color bFinal = reB.getColor(EditorColors.CARET_COLOR);
        check("T-03i-5 同时 B 也恢复为可见色",
                bReal.equals(bFinal), "实际=" + bFinal);

        NeovideCaretManager.clearSavedCaretColorForProbe();
        NeovideCaretManager.clearHiddenSchemesForProbe();

        testR17();
        testR18();
        System.out.println();
    }

    // ================================================================ R-17
    /**
     * T-17 / t10-(R-17)：<b>恢复失败的 scheme 必须留在记录表里</b>，以便下次重试。
     *
     * <p>修复前：循环里 setColor，循环外 {@code clear()} 无条件全清 ——
     * 某个 scheme 抛一次异常，它的记录也被删掉，那个 scheme 永久停在透明态
     * 且<b>再调多少次 restore 都救不回来</b>。
     *
     * <p>测法：造一个「第一次恢复必抛异常、之后正常」的 scheme，断言：
     * 第一次 restore 后它仍在记录表里，第二次 restore 能把它恢复成可见色。
     */
    private static void testR17() {
        System.out.println("--- T-17 / R-17：恢复失败的 scheme 必须保留记录、可重试 ---");
        NeovideCaretManager.clearSavedCaretColorForProbe();
        NeovideCaretManager.clearHiddenSchemesForProbe();

        Color real = new Color(0x11, 0x22, 0x33);
        Color fallback = new Color(0x44, 0x55, 0x66);
        FlakyScheme flaky = new FlakyScheme(real, fallback);

        colors.scheme = flaky;
        NeovideCaretManager.applyNativeCaretHidden();
        check("T-17a 隐藏后 flaky scheme 变透明",
                isTransparent(flaky.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(flaky.getColor(EditorColors.CARET_COLOR)));

        flaky.failNextSet = true;
        NeovideCaretManager.restoreNativeCaret();

        check("T-17b 第一次 restore（该 scheme 抛异常）后，记录【仍保留】1 条",
                NeovideCaretManager.hiddenSchemeCountForProbe() == 1,
                "记录数=" + NeovideCaretManager.hiddenSchemeCountForProbe()
                        + "（修复前会被无条件 clear() 成 0）");
        check("T-17c 该 scheme 此刻仍是透明（恢复确实失败了）",
                isTransparent(flaky.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(flaky.getColor(EditorColors.CARET_COLOR)));

        NeovideCaretManager.restoreNativeCaret();
        check("T-17d 第二次 restore 能把失败过的 scheme 恢复成【真实原色】（可自愈）",
                real.equals(flaky.getColor(EditorColors.CARET_COLOR)),
                "实际=" + flaky.getColor(EditorColors.CARET_COLOR) + " 期望=" + real);
        check("T-17e 恢复成功后记录被清空",
                NeovideCaretManager.hiddenSchemeCountForProbe() == 0,
                "记录数=" + NeovideCaretManager.hiddenSchemeCountForProbe());

        NeovideCaretManager.clearSavedCaretColorForProbe();
        NeovideCaretManager.clearHiddenSchemesForProbe();
    }

    // ================================================================ R-18
    /**
     * T-18 / t10-(R-18)：无参 {@code applyNativeCaretHidden()} 的早退必须<b>按 scheme</b> 判定，
     * 不能沿用全局 {@code savedCaretColor != null}。
     *
     * <p>修复前：「同内容、不同对象」的 scheme（切主题时平台常给克隆体）会因为
     * savedCaretColor 非 null 而被早退 → 该对象上的 CARET_COLOR 从未被写过、仍然可见 →
     * 原生光标与覆盖层叠成双套绘制。
     */
    private static void testR18() {
        System.out.println("--- T-18 / R-18：同内容不同对象的 scheme 也必须被隐藏 ---");
        NeovideCaretManager.clearSavedCaretColorForProbe();
        NeovideCaretManager.clearHiddenSchemesForProbe();

        Color same = new Color(0xA9, 0xB7, 0xC6);
        EditorColorsScheme a = newSchemeWithFallback(same, new Color(0x00, 0xFF, 0x00));
        EditorColorsScheme b = newSchemeWithFallback(same, new Color(0x00, 0xFF, 0x00));

        colors.scheme = a;
        NeovideCaretManager.applyNativeCaretHidden();
        check("T-18a A 被隐藏（透明）",
                isTransparent(a.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(a.getColor(EditorColors.CARET_COLOR)));

        colors.scheme = b;
        NeovideCaretManager.applyNativeCaretHidden();
        check("T-18b 【R-18 核心】内容相同的另一个对象 B 也必须被隐藏"
                        + "（修复前会被 savedCaretColor != null 早退，B 仍可见 → 双套绘制）",
                isTransparent(b.getColor(EditorColors.CARET_COLOR)),
                String.valueOf(b.getColor(EditorColors.CARET_COLOR)));

        check("T-18c 记录表里有 A 与 B 两条（per-object 记录）",
                NeovideCaretManager.hiddenSchemeCountForProbe() == 2,
                "记录数=" + NeovideCaretManager.hiddenSchemeCountForProbe());

        colors.scheme = b;
        NeovideCaretManager.applyNativeCaretHidden();
        check("T-18d 同一个对象重复调无参版本：记录数不变（早退仍有效，只是改成按 scheme）",
                NeovideCaretManager.hiddenSchemeCountForProbe() == 2,
                "记录数=" + NeovideCaretManager.hiddenSchemeCountForProbe());

        NeovideCaretManager.restoreNativeCaret();
        check("T-18e restore 后 A 与 B 都恢复为可见色",
                same.equals(a.getColor(EditorColors.CARET_COLOR))
                        && same.equals(b.getColor(EditorColors.CARET_COLOR)),
                "A=" + a.getColor(EditorColors.CARET_COLOR)
                        + " B=" + b.getColor(EditorColors.CARET_COLOR));

        NeovideCaretManager.clearSavedCaretColorForProbe();
        NeovideCaretManager.clearHiddenSchemesForProbe();
    }

    /** 「第一次恢复会抛异常、之后正常」的 scheme（模拟 scheme 被回收 / 只读时机）。 */
    private static final class FlakyScheme extends DefaultColorsScheme {
        private final Map<Object, Color> values = new HashMap<>();
        private final Color real;
        private final Color fallback;
        boolean failNextSet = false;

        FlakyScheme(Color real, Color fallback) {
            this.real = real;
            this.fallback = fallback;
        }

        @Override
        public Color getColor(com.intellij.openapi.editor.colors.ColorKey key) {
            if (EditorColors.CARET_COLOR.equals(key)) {
                Color v = values.get(key);
                return v != null ? v : real;
            }
            return values.get(key);
        }

        @Override
        public void setColor(com.intellij.openapi.editor.colors.ColorKey key, Color value) {
            // 只在「恢复成非透明」时可能失败；隐藏（写透明）永远成功
            if (failNextSet && value != null && value.getAlpha() != 0) {
                failNextSet = false;
                throw new IllegalStateException("模拟 scheme 不可写（探针用）");
            }
            values.put(key, value);
        }

        @Override
        public Color getDefaultForeground() {
            return fallback;
        }
    }

    /**
     * 造一个 scheme：CARET_COLOR 默认值为 {@code real}，{@code getDefaultForeground()} 返回
     * <b>不同的</b> {@code fallback}。
     *
     * <p><b>为什么必须让两者不同</b>：{@code sanitizeCaretColor} 在光标色为透明时会退回
     * {@code getDefaultForeground()}。若 real == fallback，T-03i-4 就无法区分
     * 「恢复成真实原色」与「被兜底色覆盖」—— 断言会假绿。
     */
    private static EditorColorsScheme newSchemeWithFallback(Color real, Color fallback) {
        return new DefaultColorsScheme() {
            private final Map<Object, Color> values = new HashMap<>();

            @Override
            public Color getColor(com.intellij.openapi.editor.colors.ColorKey key) {
                if (EditorColors.CARET_COLOR.equals(key)) {
                    Color v = values.get(key);
                    return v != null ? v : real;
                }
                return values.get(key);
            }

            @Override
            public void setColor(com.intellij.openapi.editor.colors.ColorKey key, Color value) {
                values.put(key, value);
            }

            @Override
            public Color getDefaultForeground() {
                return fallback;
            }
        };
    }

    // ================================================================ 工具

    private static boolean isTransparent(Color c) {
        return c != null && c.getAlpha() == 0;
    }

    /** 造一个只认 CARET_COLOR 的最小 scheme（默认 Foreground 也设上，供 sanitize 兜底用）。 */
    private static EditorColorsScheme newScheme(Color caret) {
        return new DefaultColorsScheme() {
            private final Map<Object, Color> values = new HashMap<>();

            @Override
            public Color getColor(com.intellij.openapi.editor.colors.ColorKey key) {
                if (EditorColors.CARET_COLOR.equals(key)) {
                    Color v = values.get(key);
                    return v != null ? v : caret;
                }
                return values.get(key);
            }

            @Override
            public void setColor(com.intellij.openapi.editor.colors.ColorKey key, Color value) {
                values.put(key, value);
            }

            @Override
            public Color getDefaultForeground() {
                return caret;
            }
        };
    }

    private static void resetState() {
        NeovideCursorSettings.State s = svc.getState();
        NeovideConfig d = new NeovideConfig();
        s.stateVersion = 1;
        s.enabled = d.enabled;
        s.trailMode = d.trailMode;
        s.hideNativeCaret = d.hideNativeCaret;
        s.alwaysHighFps = d.alwaysHighFps;
        s.overlayFadeOut = d.overlayFadeOut;
        s.cursorDisappearDelay = d.cursorDisappearDelay;
        s.cursorFadeOutDuration = d.cursorFadeOutDuration;
        s.tailColor = d.tailColor;
        s.tailOpacity = d.tailOpacity;
        s.useShadow = d.useShadow;
        s.shadowColor = d.shadowColor;
        s.glowOpacity = d.glowOpacity;
        s.glowLayers = d.glowLayers;
        s.shadowBlurFactor = d.shadowBlurFactor;
        s.trailSize = d.trailSize;
        s.stretchRatioK = 4.5f;   // NeovideConfig 无此字段，派生自 trailFactors 默认 4.5×
        s.animationLength = d.animationLength;
        s.shortAnimationLength = d.shortAnimationLength;
        svc.syncStateToConfig();
    }

    private static final com.intellij.ui.ExperimentalUI EXPERIMENTAL_UI =
            new com.intellij.ui.ExperimentalUI() {
                @Override
                public void saveCurrentValueAndReapplyDefaultLaf() {
                    // 探针里没有 LaF 可保存
                }
            };

    private static void installFakeApp() {
        colors = new FakeColorsManager();
        factory = new FakeEditorFactory();

        Application proxy = (Application) Proxy.newProxyInstance(
                ProbeT7.class.getClassLoader(),
                new Class<?>[]{Application.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getService":
                            if (a != null && a.length == 1) {
                                Class<?> c = (Class<?>) a[0];
                                if (c == NeovideCursorSettings.class) {
                                    return svc;
                                }
                                if (c == PropertiesComponent.class) {
                                    return new FakeProps();
                                }
                                if (c == com.intellij.ui.ExperimentalUI.class) {
                                    return EXPERIMENTAL_UI;
                                }
                                if (c == ActionManager.class) {
                                    return FakeActionManager.INSTANCE;
                                }
                                if (c == com.intellij.openapi.ui.popup.JBPopupFactory.class) {
                                    return FakePopupFactory.INSTANCE;
                                }
                                if (c == EditorFactory.class) {
                                    return factory;
                                }
                                if (c == EditorColorsManager.class) {
                                    return colors;
                                }
                                if ("SingleAlarmSharedCoroutineScopeHolder".equals(c.getSimpleName())) {
                                    return newAlarmHolder(c);
                                }
                            }
                            return null;
                        case "getMessageBus":
                            return null;
                        case "isDispatchThread":
                            return true;
                        case "saveSettings":
                            return null;
                        case "invokeLater":
                            ((Runnable) a[0]).run();
                            return null;
                        case "toString":
                            return "FakeApp";
                        case "hashCode":
                            return 1;
                        case "equals":
                            return p == a[0];
                        default:
                            return defaultValue(m.getReturnType());
                    }
                });
        ApplicationManager.setApplication(proxy, () -> { });
    }

    /** headless 探针用的 ActionManager 桩（collapsibleGroup 构造时要 CollapsiblePanel-toggle）。 */
    static final class FakeActionManager extends ActionManager {
        static final FakeActionManager INSTANCE = new FakeActionManager();
        @Override public ActionPopupMenu createActionPopupMenu(String pl, ActionGroup g) { return null; }
        @Override public ActionToolbar createActionToolbar(String pl, ActionGroup g, boolean h) { return null; }
        @Override public AnAction getAction(String id) { return null; }
        @Override public String getId(AnAction a) { return null; }
        @Override public void registerAction(String id, AnAction a) { }
        @Override public void registerAction(String id, AnAction a,
                                            com.intellij.openapi.extensions.PluginId p) { }
        @Override public void unregisterAction(String id) { }
        @Override public void replaceAction(String id, AnAction a) { }
        @Override public String[] getActionIds(String prefix) { return new String[0]; }
        @Override public List<String> getActionIdList(String prefix) { return new ArrayList<>(); }
        @Override public boolean isGroup(String id) { return false; }
        @Override public AnAction getActionOrStub(String id) { return null; }
        @Override public void addTimerListener(TimerListener l) { }
        @Override public void removeTimerListener(TimerListener l) { }
        @Override public com.intellij.openapi.util.ActionCallback tryToExecute(
                AnAction a, java.awt.event.InputEvent e, java.awt.Component c, String pl, boolean n) {
            return null;
        }
        @Override public KeyboardShortcut getKeyboardShortcut(String id) { return null; }
    }

    /** headless 探针用的 JBPopupFactory 桩（intTextField 校验器要建气泡）。 */
    static final class FakePopupFactory extends com.intellij.openapi.ui.popup.JBPopupFactory {
        static final FakePopupFactory INSTANCE = new FakePopupFactory();
        @Override public <T> com.intellij.openapi.ui.popup.IPopupChooserBuilder<T> createPopupChooserBuilder(
                List<? extends T> l) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(
                String t, Runnable r, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(
                String t, String y, String n, Runnable yr, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createConfirmation(
                String t, String y, String n, Runnable yr, Runnable nr, int d) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopupStep createActionsStep(
                ActionGroup g, com.intellij.openapi.actionSystem.DataContext c, String t,
                boolean a, boolean b, String s, java.awt.Component comp, boolean x, int n, boolean z) {
            return null;
        }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(
                javax.swing.JComponent c) { return null; }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(
                com.intellij.openapi.actionSystem.DataContext c) { return null; }
        @Override public com.intellij.ui.awt.RelativePoint guessBestPopupLocation(Editor e) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createActionGroupPopup(
                String t, ActionGroup g, com.intellij.openapi.actionSystem.DataContext c,
                boolean a, boolean b, boolean d, Runnable r, int n,
                com.intellij.openapi.util.Condition<? super AnAction> cond) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createActionGroupPopup(
                String t, ActionGroup g, com.intellij.openapi.actionSystem.DataContext c,
                com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid aid,
                boolean cancel, Runnable r, int n,
                com.intellij.openapi.util.Condition<? super AnAction> cond, String pl) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(
                com.intellij.openapi.ui.popup.ListPopupStep s) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(
                com.intellij.openapi.ui.popup.ListPopupStep s, int w) { return null; }
        @Override public com.intellij.openapi.ui.popup.ListPopup createListPopup(
                Project p, com.intellij.openapi.ui.popup.ListPopupStep s,
                java.util.function.Function<? super javax.swing.ListCellRenderer,
                        ? extends javax.swing.ListCellRenderer> f) { return null; }
        @Override public com.intellij.openapi.ui.popup.TreePopup createTree(
                com.intellij.openapi.ui.popup.JBPopup p,
                com.intellij.openapi.ui.popup.TreePopupStep s, Object o) { return null; }
        @Override public com.intellij.openapi.ui.popup.TreePopup createTree(
                com.intellij.openapi.ui.popup.TreePopupStep s) { return null; }
        @Override public com.intellij.openapi.ui.popup.ComponentPopupBuilder createComponentPopupBuilder(
                javax.swing.JComponent a, javax.swing.JComponent b) { return popupBuilder(); }
        @Override public boolean isBestPopupLocationVisible(Editor e) { return false; }
        @Override public java.awt.Point getCenterOf(javax.swing.JComponent a, javax.swing.JComponent b) {
            return null;
        }
        @Override public List<com.intellij.openapi.ui.popup.JBPopup> getChildPopups(
                java.awt.Component c) { return new ArrayList<>(); }
        @Override public boolean isPopupActive() { return false; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createBalloonBuilder(
                javax.swing.JComponent c) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createDialogBalloonBuilder(
                javax.swing.JComponent c, String t) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(
                String h, javax.swing.Icon i, Color f, Color b,
                javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(
                com.intellij.util.ui.Html h, javax.swing.Icon i, Color f, Color b,
                javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.BalloonBuilder createHtmlTextBalloonBuilder(
                String h, com.intellij.openapi.ui.MessageType t,
                javax.swing.event.HyperlinkListener l) { return null; }
        @Override public com.intellij.openapi.ui.popup.JBPopup createMessage(String m) { return null; }
        @Override public com.intellij.openapi.ui.popup.Balloon getParentBalloonFor(
                java.awt.Component c) { return null; }
        @Override protected <T> com.intellij.openapi.ui.popup.PopupChooserBuilder.PopupComponentAdapter<T>
                createPopupComponentAdapter(
                        com.intellij.openapi.ui.popup.PopupChooserBuilder<T> b,
                        javax.swing.JList<T> l) { return null; }
        @Override protected <T> com.intellij.openapi.ui.popup.PopupChooserBuilder.PopupComponentAdapter<T>
                createPopupComponentAdapter(
                        com.intellij.openapi.ui.popup.PopupChooserBuilder<T> b,
                        javax.swing.JTree t) { return null; }
        @Override protected <T> com.intellij.openapi.ui.popup.PopupChooserBuilder.PopupComponentAdapter<T>
                createPopupComponentAdapter(
                        com.intellij.openapi.ui.popup.PopupChooserBuilder<T> b,
                        javax.swing.JTable t) { return null; }
    }

    private static com.intellij.openapi.ui.popup.ComponentPopupBuilder popupBuilder() {
        return (com.intellij.openapi.ui.popup.ComponentPopupBuilder) Proxy.newProxyInstance(
                ProbeT7.class.getClassLoader(),
                new Class<?>[]{com.intellij.openapi.ui.popup.ComponentPopupBuilder.class},
                (pr, m, a) -> m.getReturnType().isInstance(pr) ? pr : null);
    }

    /** SingleAlarmSharedCoroutineScopeHolder 替身（包私有类，反射建）。 */
    private static Object newAlarmHolder(Class<?> holderClass) {
        try {
            Class<?> scopeClass = Class.forName("kotlinx.coroutines.CoroutineScope");
            Object globalScope = Class.forName("kotlinx.coroutines.GlobalScope")
                    .getField("INSTANCE").get(null);
            java.lang.reflect.Constructor<?> ctor = holderClass.getConstructor(scopeClass);
            ctor.setAccessible(true);
            return ctor.newInstance(globalScope);
        } catch (Throwable t) {
            return null;
        }
    }

    static final class FakeColorsManager extends EditorColorsManager {
        EditorColorsScheme scheme;
        @Override public EditorColorsScheme getGlobalScheme() { return scheme; }
        @Override public void addColorScheme(EditorColorsScheme s) { }
        @Override public EditorColorsScheme[] getAllSchemes() {
            return new EditorColorsScheme[]{scheme};
        }
        @Override public void setGlobalScheme(EditorColorsScheme s) { scheme = s; }
        @Override public void setCurrentSchemeOnLafChange(EditorColorsScheme s) { scheme = s; }
        @Override public EditorColorsScheme getActiveVisibleScheme() { return scheme; }
        @Override public EditorColorsScheme getScheme(String n) { return scheme; }
        @Override public boolean isDefaultScheme(EditorColorsScheme s) { return false; }
        @Override public boolean isUseOnlyMonospacedFonts() { return true; }
        @Override public void setUseOnlyMonospacedFonts(boolean b) { }
        @Override public long getSchemeModificationCounter() { return 0L; }
    }

    static final class FakeEditorFactory extends EditorFactory {
        @Override public Document createDocument(CharSequence s) { return null; }
        @Override public Document createDocument(char[] s) { return null; }
        @Override public Document createDocument(boolean b) { return null; }
        @Override public Document createDocument(CharSequence s, boolean a, boolean b) { return null; }
        @Override public Editor createEditor(Document d) { return null; }
        @Override public Editor createViewer(Document d) { return null; }
        @Override public Editor createEditor(Document d, Project p) { return null; }
        @Override public Editor createEditor(Document d, Project p, EditorKind k) { return null; }
        @Override public Editor createEditor(Document d, Project p, FileType f, boolean b) { return null; }
        @Override public Editor createEditor(Document d, Project p, VirtualFile f, boolean b) { return null; }
        @Override public Editor createEditor(Document d, Project p, VirtualFile f, boolean b, EditorKind k) {
            return null;
        }
        @Override public Editor createViewer(Document d, Project p) { return null; }
        @Override public Editor createViewer(Document d, Project p, EditorKind k) { return null; }
        @Override public void releaseEditor(Editor e) { }
        @Override public Stream<Editor> editors(Document d, Project p) { return Stream.empty(); }
        @Override public Editor[] getAllEditors() { return new Editor[0]; }
        @Override public List<Editor> getEditorList() { return new ArrayList<>(); }
        @Override public void addEditorFactoryListener(EditorFactoryListener l) { }
        @Override public void addEditorFactoryListener(EditorFactoryListener l,
                                                       com.intellij.openapi.Disposable d) { }
        @Override public void removeEditorFactoryListener(EditorFactoryListener l) { }
        @Override public EditorEventMulticaster getEventMulticaster() { return null; }
        @Override public void refreshAllEditors() { }
    }

    static final class FakeProps extends PropertiesComponent {
        final Map<String, String> map = new HashMap<>();
        @Override public void unsetValue(String name) { map.remove(name); }
        @Override public boolean isValueSet(String name) { return map.containsKey(name); }
        @Override public String getValue(String name) { return map.get(name); }
        @Override public void setValue(String name, String v) { map.put(name, v); }
        @Override public void setValue(String name, String v, String d) { map.put(name, v); }
        @Override public void setValue(String name, float v, float d) { }
        @Override public void setValue(String name, int v, int d) { }
        @Override public void setValue(String name, boolean v, boolean d) { }
        @Override public String[] getValues(String name) { return new String[0]; }
        @Override public void setValues(String name, String[] v) { }
        @Override public List<String> getList(String name) { return new ArrayList<>(); }
        @Override public void setList(String name, Collection<String> v) { }
        @Override public boolean updateValue(String name, boolean v) { return true; }
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name + (detail == null || detail.isEmpty() ? "" : "   " + detail));
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   " + detail);
        }
    }
}
