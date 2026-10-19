package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.NeovideCaretManager;
import com.intellij.openapi.options.BoundConfigurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.observable.properties.GraphProperty;
import com.intellij.openapi.observable.properties.PropertyGraph;
import com.intellij.openapi.ui.DialogPanel;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.dsl.builder.BuilderKt;
import com.intellij.ui.dsl.builder.ButtonKt;
import com.intellij.ui.dsl.builder.Cell;
import com.intellij.ui.dsl.builder.ComboBoxKt;
import com.intellij.ui.dsl.builder.HyperlinkEventAction;
import com.intellij.ui.dsl.builder.MutableProperty;
import com.intellij.ui.dsl.builder.Panel;
import com.intellij.ui.dsl.builder.Row;
import com.intellij.ui.dsl.builder.SliderKt;
import kotlin.jvm.functions.Function0;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Neovide 拖尾光标的设置面板（第一期 16 项）。
 *
 * <p><b>为什么继承 {@link BoundConfigurable} 而不是裸实现 {@code SearchableConfigurable}：
 * </b>父类已经实现了 {@code createComponent / isModified / reset / apply / disposeUIResources}，
 * 子类只需要给出 {@link #createPanel()}。手写那几个方法等于把"什么时候把控件值刷回模型"
 * 的时序自己重新发明一遍，而 {@code DialogPanel} 已经做对了。
 *
 * <p><b>第一期是「OK 生效」，不做实时预览。</b>三条硬约束：
 * <ol>
 *   <li>{@code apply()} 之前<b>不碰任何运行期状态</b>——全部编辑只发生在 {@link #pending}；</li>
 *   <li>不用 DSL 的「控件变化回调」那套 API（其中一个带 {@code @ApiStatus.NonExtendable}，
 *       另一个官方文档无背书）去实时写配置；</li>
 * </ol>
 * 拖动滑块时右侧数值会跟着变——那只是<b>改一个 JLabel 的文字</b>，
 * 不碰 {@code CONFIG}、不调 {@code applyConfig()}，与"实时预览"是两件事。
 *
 * <p><b>数据流</b>（{@code SETTINGS-CONTRACT.md} §3.4）：
 * <pre>
 *   reset()   service.getState() --深拷贝--&gt; pending --DialogPanel--&gt; 控件
 *   apply()   控件 --DialogPanel--&gt; pending --+--&gt; service.getState() --&gt; XML
 *                                             +--&gt; NeovideCaretManager.applyConfig()
 * </pre>
 * {@link #pending} 是预览副本：面板编辑期间只改它，用户点 Cancel 时没有任何东西需要回滚。
 */
public final class NeovideCursorConfigurable extends BoundConfigurable implements SearchableConfigurable {

    /** 配置页 id —— 必须是插件 id 前缀（官方原话 "The FQN should be based on the plugin id"）。 */
    public static final String ID = "com.github.yyyolo7a79.neovidecursor.settings";

    /** 页面名称。**同时**写在 plugin.xml 的 displayName 里（平台取名字时不必实例化本类）。 */
    public static final String DISPLAY_NAME = "Neovide Cursor";

    // ==================== 警告文案（契约 §2.2，逐字，不得改动）====================

    private static final String W_ALWAYS_HIGH_FPS =
            "常驻刷新 = 调度线程每秒向 EDT 投递约 125 次任务，且永不停歇。标签页很多或机器较慢时，"
            + "它可能让 IDE 反而更卡。关掉后空闲会降到 120ms（失焦 600ms），"
            + "代价是「停手后第一次操作」可能等一个空闲周期才动。";

    private static final String W_HIDE_NATIVE =
            "隐藏原生光标 = 把全局配色方案的 CARET_COLOR 改成全透明。IDE 会把它落盘写进 colors/*.icls，"
            + "卸载插件后光标可能依然是隐形的。取消勾选即可恢复原生光标（也建议从此处恢复）。";

    private static final String W_COUPLING =
            "「隐藏原生光标」勾选时覆盖层就是唯一的「光标」，跟随完全依赖调度，建议同时保持常驻刷新；"
            + "取消勾选后原生光标零延迟跟随，常驻刷新可以关掉以省电。";

    private static final String W_GLOW_LAYERS =
            "层数越多渐变越平滑，但每层都是一次 CPU 抗锯齿 + alpha 混合（fill(Path2D) 不享受 Java2D 的 GPU 加速）。"
            + "层数不影响亮度：内部按层数反解每层 alpha，8 层与 12 层的峰值亮度相同。";

    private static final String W_SHADOW_BLUR =
            "0.6 是原版 JS 的夸张光雾（约 40px）。它同时决定重绘 padding：每个编辑器每帧的重绘包围盒会随之变大，"
            + "慢机器上可能掉帧。默认 0.2（约 14px）是本插件按「光晕总宽与残影模式一致」反推的值。";

    private static final String W_STRETCH =
            "只调整后缘与前缘的时长比（拉伸倍率）。默认 4.5×＝后缘 110ms / 前缘 24ms，是实测调出来的观感；"
            + "调低会让拖尾变平，调高会把后缘拉长。rank0 最拖、rank3 最跟手，方向固定，不可对调。";

    private static final String W_FADE_OFF =
            "辉光淡出关闭时不使用这两个值，辉光瞬时归零（行为与 0.9.1 一致）。";

    // ==================== 下拉项文案（契约 §2.4：不得只显示枚举名）====================

    private static final String MODE_SPRING_TEXT = "弹簧拉伸（Neovide 原版效果）";
    private static final String MODE_AFTERIMAGE_TEXT = "残影轨迹（低帧率更稳）";
    private static final List<String> MODE_ITEMS = Arrays.asList(MODE_SPRING_TEXT, MODE_AFTERIMAGE_TEXT);

    /**
     * 第一期固定不可调，只用来把 {@code trailFactors} 换算成界面上的毫秒数（契约 §2.5 联动显示）。
     * <b>它是 NeovideConfig 现值的一个只读镜像，不是新的默认值来源。</b>
     */
    private static final float SHORT_ANIMATION_LENGTH = 0.11f;

    private static final int COMMENT = -1;

    private static final HyperlinkEventAction NO_LINK = HyperlinkEventAction.HTML_HYPERLINK_INSTANCE;

    // ==================== 预览副本 + 控件引用 ====================

    /**
     * 面板正在编辑的副本。<b>绝不在 apply() 之前写进 service</b>——那是「OK 生效」的语义边界。
     */
    private NeovideCursorSettings.State pending = new NeovideCursorSettings.State();

    // 非 DSL 绑定控件：DialogPanel 不追踪它们，必须在 reset/apply/isModified 里手工同步
    private ColorPanel tailColorPanel;
    private ColorPanel shadowColorPanel;

    // 数值标签（拖动时只改文字，纯 UI）
    private JLabel disappearDelayLabel;
    private JLabel fadeOutDurationLabel;
    private JLabel tailOpacityLabel;
    private JLabel glowOpacityLabel;
    private JLabel glowLayersLabel;
    private JLabel shadowBlurLabel;
    private JLabel trailSizeLabel;
    private JLabel stretchLabel;

    // 联动置灰用的三个实时属性（由控件自身的监听器驱动）
    private final PropertyGraph graph = new PropertyGraph();
    private final GraphProperty<Boolean> springMode = graph.property(Boolean.TRUE);
    private final GraphProperty<Boolean> shadowOn = graph.property(Boolean.TRUE);
    private final GraphProperty<Boolean> fadeOn = graph.property(Boolean.TRUE);

    /**
     * 构造器<b>不得创建任何 Swing 组件</b>：application 级 Configurable
     * "may be instantiated on a background thread"，且 {@code buildSearchableOptions}
     * 会为取关键字而实例化它。所有 UI 都在 {@link #createPanel()} 里建。
     *
     * <p>父类构造器是<b>两个 String</b>：{@code BoundConfigurable(String displayName, String helpTopic)}。
     * 官方 Kotlin 示例是单参数（带默认值），照抄到 Java 会编译失败。
     */
    public NeovideCursorConfigurable() {
        super(DISPLAY_NAME, null);
    }

    @Override
    public String getId() {
        return ID;
    }

    // ==================== 面板构建 ====================

    /**
     * 纯 DSL 构建，<b>不依赖 Project / Editor</b>——
     * {@code buildSearchableOptions}、{@code TraverseUIStarter} 会在无项目环境下调用它。
     */
    @Override
    public DialogPanel createPanel() {
        pending = deepCopy(currentState());
        graphReset(pending);

        return BuilderKt.panel(panel -> {

            panel.row("", row -> {
                row.comment("本页所有改动点「应用 / OK」后才生效；面板内不做实时预览。", COMMENT, NO_LINK);
                return kotlin.Unit.INSTANCE;
            });

            // ---------- A. 总开关 ----------
            panel.group("总开关", true, g -> {

                g.row("启用拖尾光标（总开关）", row -> {
                    bindCheck(row, () -> pending.enabled, v -> pending.enabled = v);
                    return kotlin.Unit.INSTANCE;
                });

                g.row("拖尾渲染模式", row -> {
                    Cell<com.intellij.openapi.ui.ComboBox<String>> c = ComboBoxKt.bindItem(
                            row.comboBox(MODE_ITEMS, null),
                            new MutableProperty<String>() {
                                @Override public String get() { return modeText(pending.trailMode); }
                                @Override public void set(String v) { pending.trailMode = textToMode(v); }
                            });
                    com.intellij.openapi.ui.ComboBox<String> box = c.getComponent();
                    // 下拉一改就刷新置灰；只改 enabled 状态，不碰 CONFIG
                    box.addActionListener(e -> springMode.set(isSpringText(box.getSelectedItem())));
                    return kotlin.Unit.INSTANCE;
                });

                g.row("隐藏原生光标（覆盖层充当光标）", row -> {
                    bindCheck(row, () -> pending.hideNativeCaret, v -> pending.hideNativeCaret = v)
                            .comment(W_HIDE_NATIVE, COMMENT, NO_LINK);
                    return kotlin.Unit.INSTANCE;
                });

                g.row("常驻刷新（永远按 8ms 调度）", row -> {
                    bindCheck(row, () -> pending.alwaysHighFps, v -> pending.alwaysHighFps = v)
                            .comment(W_ALWAYS_HIGH_FPS, COMMENT, NO_LINK);
                    return kotlin.Unit.INSTANCE;
                });

                // 契约 §2.2：W_COUPLING 挂在 #3/#4 下方的 rowComment
                g.row("", row -> {
                    row.rowComment(W_COUPLING, COMMENT, NO_LINK);
                    return kotlin.Unit.INSTANCE;
                });
                return kotlin.Unit.INSTANCE;
            });

            // ---------- B. 淡出 ----------
            panel.group("淡出", true, g -> {

                g.row("辉光淡出（停止后柔和熄灭）", row -> {
                    bindCheck(row, () -> pending.overlayFadeOut, v -> {
                        pending.overlayFadeOut = v;
                        fadeOn.set(v);
                    });
                    return kotlin.Unit.INSTANCE;
                });

                g.row("淡出前保持时长", row -> {
                    SliderKt.bindValue(row.slider(0, 500, 50, 10),
                            () -> pending.cursorDisappearDelay,
                            v -> {
                                pending.cursorDisappearDelay = v;
                                if (disappearDelayLabel != null) {
                                    disappearDelayLabel.setText(v + " ms");
                                }
                                return kotlin.Unit.INSTANCE;
                            }).enabledIf(fadeOn);
                    disappearDelayLabel = new JLabel();
                    row.cell(disappearDelayLabel).enabledIf(fadeOn);
                    row.rowComment(W_FADE_OFF, COMMENT, NO_LINK).enabledIf(fadeOn);
                    return kotlin.Unit.INSTANCE;
                });

                g.row("淡出时长", row -> {
                    SliderKt.bindValue(row.slider(0, 1000, 75, 5),
                            () -> Math.round(pending.cursorFadeOutDuration * 1000f),
                            v -> {
                                pending.cursorFadeOutDuration = v / 1000f;
                                if (fadeOutDurationLabel != null) {
                                    fadeOutDurationLabel.setText(v + " ms");
                                }
                                return kotlin.Unit.INSTANCE;
                            }).enabledIf(fadeOn);
                    fadeOutDurationLabel = new JLabel();
                    row.cell(fadeOutDurationLabel).enabledIf(fadeOn);
                    return kotlin.Unit.INSTANCE;
                });
                return kotlin.Unit.INSTANCE;
            });

            // ---------- C. 颜色与辉光 ----------
            panel.group("颜色与辉光", true, g -> {

                g.row("拖尾颜色", row -> {
                    tailColorPanel = new ColorPanel();
                    tailColorPanel.setSupportTransparency(false);
                    row.cell(tailColorPanel);
                    return kotlin.Unit.INSTANCE;
                });

                g.row("拖尾不透明度", row -> {
                    SliderKt.bindValue(row.slider(0, 100, 100, 5),
                            () -> Math.round(pending.tailOpacity * 100f),
                            v -> {
                                pending.tailOpacity = v / 100f;
                                if (tailOpacityLabel != null) {
                                    tailOpacityLabel.setText(fmt2(pending.tailOpacity));
                                }
                                return kotlin.Unit.INSTANCE;
                            });
                    tailOpacityLabel = new JLabel();
                    row.cell(tailOpacityLabel);
                    return kotlin.Unit.INSTANCE;
                });

                g.row("启用辉光", row -> {
                    bindCheck(row, () -> pending.useShadow, v -> {
                        pending.useShadow = v;
                        shadowOn.set(v);
                    });
                    return kotlin.Unit.INSTANCE;
                });

                g.row("辉光颜色", row -> {
                    shadowColorPanel = new ColorPanel();
                    shadowColorPanel.setSupportTransparency(false);
                    row.cell(shadowColorPanel).enabledIf(shadowOn);
                    return kotlin.Unit.INSTANCE;
                });

                g.row("辉光峰值不透明度", row -> {
                    SliderKt.bindValue(row.slider(0, 100, 55, 1),
                            () -> Math.round(pending.glowOpacity * 100f),
                            v -> {
                                pending.glowOpacity = v / 100f;
                                if (glowOpacityLabel != null) {
                                    glowOpacityLabel.setText(fmt2(pending.glowOpacity));
                                }
                                return kotlin.Unit.INSTANCE;
                            }).enabledIf(shadowOn);
                    glowOpacityLabel = new JLabel();
                    row.cell(glowOpacityLabel).enabledIf(shadowOn);
                    return kotlin.Unit.INSTANCE;
                });

                g.row("辉光层数", row -> {
                    SliderKt.bindValue(row.slider(1, 12, 8, 1),
                            () -> pending.glowLayers,
                            v -> {
                                pending.glowLayers = v;
                                if (glowLayersLabel != null) {
                                    glowLayersLabel.setText(v + " 层");
                                }
                                return kotlin.Unit.INSTANCE;
                            }).enabledIf(shadowOn);
                    glowLayersLabel = new JLabel();
                    row.cell(glowLayersLabel).enabledIf(shadowOn);
                    row.rowComment(W_GLOW_LAYERS, COMMENT, NO_LINK).enabledIf(shadowOn);
                    return kotlin.Unit.INSTANCE;
                });

                g.row("辉光外扩幅度", row -> {
                    SliderKt.bindValue(row.slider(5, 60, 20, 1),
                            () -> Math.round(pending.shadowBlurFactor * 100f),
                            v -> {
                                pending.shadowBlurFactor = v / 100f;
                                if (shadowBlurLabel != null) {
                                    shadowBlurLabel.setText(fmt2(pending.shadowBlurFactor));
                                }
                                return kotlin.Unit.INSTANCE;
                            }).enabledIf(shadowOn);
                    shadowBlurLabel = new JLabel();
                    row.cell(shadowBlurLabel).enabledIf(shadowOn);
                    row.rowComment(W_SHADOW_BLUR, COMMENT, NO_LINK).enabledIf(shadowOn);
                    return kotlin.Unit.INSTANCE;
                });
                return kotlin.Unit.INSTANCE;
            });

            // ---------- D. 拖尾手感 ----------
            panel.group("拖尾手感", true, g -> {

                g.row("拖尾密度", row -> {
                    SliderKt.bindValue(row.slider(0, 100, 100, 1),
                            () -> Math.round(pending.trailSize * 100f),
                            v -> {
                                pending.trailSize = v / 100f;
                                if (trailSizeLabel != null) {
                                    trailSizeLabel.setText(fmt2(pending.trailSize));
                                }
                                return kotlin.Unit.INSTANCE;
                            }).enabledIf(springMode);
                    trailSizeLabel = new JLabel();
                    row.cell(trailSizeLabel).enabledIf(springMode);
                    return kotlin.Unit.INSTANCE;
                });

                g.row("拖尾拉伸强度", row -> {
                    SliderKt.bindValue(row.slider(10, 80, 45, 1),
                            () -> Math.round(pending.stretchRatioK * 10f),
                            v -> {
                                pending.stretchRatioK = v / 10f;
                                if (stretchLabel != null) {
                                    stretchLabel.setText(stretchText(pending.stretchRatioK));
                                }
                                return kotlin.Unit.INSTANCE;
                            }).enabledIf(springMode);
                    stretchLabel = new JLabel();
                    row.cell(stretchLabel).enabledIf(springMode);
                    row.rowComment(W_STRETCH, COMMENT, NO_LINK).enabledIf(springMode);
                    return kotlin.Unit.INSTANCE;
                });
                return kotlin.Unit.INSTANCE;
            });

            return kotlin.Unit.INSTANCE;
        });
    }

    // ==================== reset / apply / isModified ====================

    /**
     * 从 service 取当前值灌进控件（契约 §3.4 的 R1–R4）。
     *
     * <p><b>顺序：先换 pending，再 super.reset()。</b>{@code DialogPanel.reset()} 是把
     * {@code get()} 的结果写进控件——pending 还是旧的，控件就会被灌上一轮的值。
     */
    @Override
    public void reset() {
        pending = deepCopy(currentState());
        graphReset(pending);

        super.reset();          // DialogPanel 按 pending 刷所有绑定控件

        // R3：非绑定控件（ColorPanel）与派生标签要自己刷
        if (tailColorPanel != null) {
            tailColorPanel.setSelectedColor(parseHex(pending.tailColor));
        }
        if (shadowColorPanel != null) {
            shadowColorPanel.setSelectedColor(parseHex(pending.shadowColor));
        }
        refreshAllLabels();
    }

    /**
     * 把控件值写回 service 并生效（契约 §3.4 的 A1–A5）。
     *
     * <p><b>唯一的生效入口是 {@link NeovideCaretManager#applyConfig()}</b>：直接改
     * {@code CONFIG} 字段会让颜色 / 辉光 / 淡出这些被<b>缓存</b>的值停在旧状态
     * （证据见 {@code NeovideCaretManager.applyConfig()} 的 S7 与 {@code CaretAnimator}
     * 的 {@code refreshGlowPadding()}）。
     */
    @Override
    public void apply() {
        // A1：非绑定控件回读（DialogPanel 追踪不到 ColorPanel）
        if (tailColorPanel != null && tailColorPanel.getSelectedColor() != null) {
            pending.tailColor = toHex(tailColorPanel.getSelectedColor());
        }
        if (shadowColorPanel != null && shadowColorPanel.getSelectedColor() != null) {
            pending.shadowColor = toHex(shadowColorPanel.getSelectedColor());
        }

        // A2：DialogPanel 把绑定控件的值写进 pending
        super.apply();

        // A3：pending → service 的 state（15 个字段 + stretchRatioK）
        NeovideCursorSettings svc = serviceOrNull();
        if (svc == null) {
            return;
        }
        NeovideCursorSettings.State target = svc.getState();
        target.enabled = pending.enabled;
        target.trailMode = pending.trailMode;
        target.hideNativeCaret = pending.hideNativeCaret;
        target.alwaysHighFps = pending.alwaysHighFps;
        target.overlayFadeOut = pending.overlayFadeOut;
        target.cursorDisappearDelay = pending.cursorDisappearDelay;
        target.cursorFadeOutDuration = pending.cursorFadeOutDuration;
        target.tailColor = pending.tailColor;
        target.tailOpacity = pending.tailOpacity;
        target.useShadow = pending.useShadow;
        target.shadowColor = pending.shadowColor;
        target.glowOpacity = pending.glowOpacity;
        target.glowLayers = pending.glowLayers;
        target.shadowBlurFactor = pending.shadowBlurFactor;
        target.trailSize = pending.trailSize;
        // 标量真相：只有它会派生 trailFactors（NeovideCursorSettings#deriveTrailFactors）
        target.stretchRatioK = pending.stretchRatioK;

        // A4：落盘
        svc.persistQuietly();

        // A5：唯一生效入口（写字段 → 调 setter → 刷新三类缓存）
        NeovideCaretManager.applyConfig();
    }

    /**
     * 是否有未保存的改动。
     *
     * <p><b>必须额外比较 ColorPanel</b>：它不是 {@code DialogPanel} 的绑定控件，
     * 平台自己的 {@code isModified} 完全看不到它——只比 {@code super.isModified()}
     * 会导致「只改了颜色 → 应用按钮是灰的 → 改动静默丢失」。
     */
    @Override
    public boolean isModified() {
        if (super.isModified()) {
            return true;
        }
        NeovideCursorSettings svc = serviceOrNull();
        if (svc == null) {
            return false;
        }
        NeovideCursorSettings.State cur = svc.getState();
        if (tailColorPanel != null && tailColorPanel.getSelectedColor() != null
                && !toHex(tailColorPanel.getSelectedColor()).equalsIgnoreCase(cur.tailColor)) {
            return true;
        }
        return shadowColorPanel != null && shadowColorPanel.getSelectedColor() != null
                && !toHex(shadowColorPanel.getSelectedColor()).equalsIgnoreCase(cur.shadowColor);
    }

    @Override
    public void disposeUIResources() {
        super.disposeUIResources();
        tailColorPanel = null;
        shadowColorPanel = null;
        disappearDelayLabel = null;
        fadeOutDurationLabel = null;
        tailOpacityLabel = null;
        glowOpacityLabel = null;
        glowLayersLabel = null;
        shadowBlurLabel = null;
        trailSizeLabel = null;
        stretchLabel = null;
    }

    // ==================== 搜索定位 ====================

    /**
     * 设置页搜索：按关键字把焦点与滚动定位到对应字段。
     *
     * <p>第一期只做「滚动到 + 请求焦点」，不做像素级高亮——平台自己的
     * {@code TraverseUIStarter} 才是搜索高亮的权威实现，手工再写一套只会与它打架。
     */
    @Override
    public Runnable enableSearch(String option) {
        if (option == null || option.trim().isEmpty()) {
            return null;
        }
        final JLabel target = targetFor(option.trim().toLowerCase(Locale.ROOT));
        if (target == null) {
            return null;
        }
        return () -> {
            target.requestFocusInWindow();
            JComponent parent = (JComponent) target.getParent();
            if (parent != null) {
                Rectangle r = target.getBounds();
                parent.scrollRectToVisible(r);
            }
        };
    }

    /** 关键字 → 面板上最能代表它的那个数值标签。 */
    private JLabel targetFor(String key) {
        if (has(key, "常驻", "高帧", "highfps", "fps", "刷新")) {
            return glowLayersLabel;
        }
        if (has(key, "淡出", "fade")) {
            return disappearDelayLabel;
        }
        if (has(key, "层", "layer", "辉光", "glow")) {
            return glowLayersLabel;
        }
        if (has(key, "外扩", "模糊", "blur", "光晕")) {
            return shadowBlurLabel;
        }
        if (has(key, "不透明", "透明", "opacity")) {
            return glowOpacityLabel;
        }
        if (has(key, "拉伸", "stretch", "拖尾", "trail")) {
            return stretchLabel;
        }
        if (has(key, "密度", "size")) {
            return trailSizeLabel;
        }
        return null;
    }

    private static boolean has(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 标签刷新（纯 UI）====================

    private void refreshAllLabels() {
        if (disappearDelayLabel != null) {
            disappearDelayLabel.setText(pending.cursorDisappearDelay + " ms");
        }
        if (fadeOutDurationLabel != null) {
            fadeOutDurationLabel.setText(Math.round(pending.cursorFadeOutDuration * 1000f) + " ms");
        }
        if (tailOpacityLabel != null) {
            tailOpacityLabel.setText(fmt2(pending.tailOpacity));
        }
        if (glowOpacityLabel != null) {
            glowOpacityLabel.setText(fmt2(pending.glowOpacity));
        }
        if (glowLayersLabel != null) {
            glowLayersLabel.setText(pending.glowLayers + " 层");
        }
        if (shadowBlurLabel != null) {
            shadowBlurLabel.setText(fmt2(pending.shadowBlurFactor));
        }
        if (trailSizeLabel != null) {
            trailSizeLabel.setText(fmt2(pending.trailSize));
        }
        if (stretchLabel != null) {
            stretchLabel.setText(stretchText(pending.stretchRatioK));
        }
    }

    /**
     * 「拖尾拉伸强度」的派生显示：标称倍率 + 后缘/前缘绝对时长 + rank 方向。
     *
     * <p>时长 = 第一期固定的 110ms 基准 × {@code trailFactors[rank]}
     * （{@code TrailCorner.java:220}），而 {@code trailFactors} 由
     * {@link NeovideCursorSettings#deriveTrailFactors(float)} 从标量派生——
     * 面板只<b>显示</b>派生结果，绝不做第二套换算。
     */
    static String stretchText(float k) {
        float[] f = NeovideCursorSettings.deriveTrailFactors(k);
        long trailingMs = Math.round(SHORT_ANIMATION_LENGTH * 1000f * f[0]);
        long leadingMs = Math.round(SHORT_ANIMATION_LENGTH * 1000f * f[3]);
        float ratio = f[3] > 0f ? f[0] / f[3] : 0f;
        // 比值用三位小数：契约 §2.5 的自检表就是按 4.545 / 7.692 这种精度写的，
        // 面板显示必须与它逐位对得上，否则"显示"和"文档"又是两套口径。
        return String.format(Locale.ROOT,
                "%.1f×   后缘 %d ms / 前缘 %d ms   (rank0 最拖 → rank3 最跟手，实际比值 %.3f×)",
                k, trailingMs, leadingMs, ratio);
    }

    // ==================== 小工具 ====================

    /**
     * 复选框（不带行内文字：DSL 的行首文字与行内控件是两列，
     * 契约 §2.4 要求「左侧是字段名、右侧是控件」）。
     */
    private static Cell<JBCheckBox> bindCheck(Row row,
                                              Function0<Boolean> getter,
                                              final java.util.function.Consumer<Boolean> setter) {
        return ButtonKt.bindSelected(row.checkBox(""), new MutableProperty<Boolean>() {
            @Override public Boolean get() { return getter.invoke(); }
            @Override public void set(Boolean v) {
                setter.accept(v);
                return;
            }
        });
    }

    private static String modeText(NeovideConfig.TrailMode mode) {
        return mode == NeovideConfig.TrailMode.AFTERIMAGE ? MODE_AFTERIMAGE_TEXT : MODE_SPRING_TEXT;
    }

    private static NeovideConfig.TrailMode textToMode(String text) {
        return MODE_AFTERIMAGE_TEXT.equals(text)
                ? NeovideConfig.TrailMode.AFTERIMAGE
                : NeovideConfig.TrailMode.SPRING;
    }

    private static boolean isSpringText(Object selected) {
        return !MODE_AFTERIMAGE_TEXT.equals(selected);
    }

    private static String fmt2(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private void graphReset(NeovideCursorSettings.State s) {
        springMode.set(s.trailMode == NeovideConfig.TrailMode.SPRING);
        shadowOn.set(s.useShadow);
        fadeOn.set(s.overlayFadeOut);
    }

    private static NeovideCursorSettings serviceOrNull() {
        try {
            return NeovideCursorSettings.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取当前持久化状态；服务不可用（无 Application 的离线探针）时退回默认值。 */
    private static NeovideCursorSettings.State currentState() {
        NeovideCursorSettings svc = serviceOrNull();
        if (svc != null) {
            return svc.getState();
        }
        return new NeovideCursorSettings.State();
    }

    private static NeovideCursorSettings.State deepCopy(NeovideCursorSettings.State from) {
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        if (from != null) {
            s.copyFrom(from);
        }
        return s;
    }

    /** 供探针复用：{@code "#RRGGBB"} → Color。 */
    public static Color parseHex(String hex) {
        try {
            if (hex == null) {
                return Color.BLACK;
            }
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return new Color(Integer.parseInt(h, 16));
        } catch (Throwable t) {
            return Color.BLACK;
        }
    }

    /** 供探针复用：Color → {@code "#RRGGBB"}。 */
    public static String toHex(Color c) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ==================== 供探针读取的观测量 ====================

    /** 探针/调试用：当前预览副本（只读语义，不要改它）。 */
    public NeovideCursorSettings.State pendingForProbe() {
        return pending;
    }

    /** 探针/调试用：控件引用（可能为 null，视面板是否已创建）。 */
    public JLabel stretchLabelForProbe() {
        return stretchLabel;
    }

    public JLabel glowLayersLabelForProbe() {
        return glowLayersLabel;
    }

    public ColorPanel tailColorPanelForProbe() {
        return tailColorPanel;
    }

    public ColorPanel shadowColorPanelForProbe() {
        return shadowColorPanel;
    }

    /** 探针/调试用：复选框被绑定到哪个 predicate（验证联动置灰来源）。 */
    public JLabel[] allLabelsForProbe() {
        return new JLabel[]{disappearDelayLabel, fadeOutDurationLabel, tailOpacityLabel,
                glowOpacityLabel, glowLayersLabel, shadowBlurLabel, trailSizeLabel, stretchLabel};
    }
}
