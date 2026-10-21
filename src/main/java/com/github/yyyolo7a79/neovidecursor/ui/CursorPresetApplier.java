package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 预设的<b>唯一写入路径</b>（契约 §8.6）。
 *
 * <p><b>为什么单独一个类</b>：{@link CursorPresets} 是纯常量表，契约 §5.8 要求它
 * 既<b>不</b>{@code new NeovideConfig()}、也<b>不</b>出现 {@code NeovideCaretManager} ——
 * 否则预设表就变成第四个真相源。写入职责因此放在这里。
 *
 * <p><b>为什么预设是「全量替换」而面板是「脏字段合并」</b>（契约 §8.6）：
 * 预设是<b>用户明确要求的整套替换</b>（用户知道它会改 6 个字段）；
 * 面板 apply 是<b>用户可能只改了 1 个字段</b>。两者语义不同，不能合并成一个方法。
 *
 * <p><b>正交性是硬判据</b>（方案 §4 与契约 §5.3）：
 * {@link #applyAnimPreset} 只碰 6 个动画字段，<b>绝不</b>碰 {@code tailColor} / {@code shadowColor}；
 * {@link #applyColorPreset} 只碰 2 个 HEX，<b>绝不</b>碰任何动画字段。
 */
public final class CursorPresetApplier {

    private static final Logger LOG = Logger.getInstance(CursorPresetApplier.class);

    private CursorPresetApplier() {
    }

    /**
     * Tools 菜单专用：全量替换 6 个动画字段（与面板的「脏字段合并」语义不同，不可合并）。
     *
     * <p><b>不碰</b>：{@code trailColor} / {@code shadowColor}（颜色正交）、
     * {@code stretchRatioK} / {@code trailFactors}（方案 §4.1：预设一律沿用 4.5×）、
     * {@code trailMode}（独立开关）。
     *
     * <p><b>【reviewer F7】必须先查覆盖表，再退回预设常量。</b>
     *
     * <p>原实现只写 {@code p.xxx()} 常量，完全不看 {@code animPresetOverrides}。
     * 后果是一条会<b>静默销毁用户工作</b>的路径：
     * <ol>
     *   <li>用户在面板里把「缎带」的跨行时长改成 0.15 → apply 后写进覆盖表；</li>
     *   <li>用户从 Tools 菜单点「缎带」→ 本方法把 6 项<b>改回预设常量</b>，
     *       用户刚改的值被冲掉（界面上就是「我的改动没了」）；</li>
     *   <li>用户在面板再点一次「应用」→ {@code apply()} 的三支判据里
     *       {@code current == constants} 成立 → 走第 ① 支 <b>removeOverride</b>
     *       → 覆盖值被删除，改动<b>永久丢失</b>。</li>
     * </ol>
     *
     * <p>修法：应用前先 {@link CursorPresets#parseOverride} 查该预设有没有覆盖值，
     * 有就用覆盖值（与面板 {@code onAnimPresetPicked()} 完全同一条语义），没有才用常量。
     * 这样「Tools 点预设」与「面板选预设」两条入口对同一份数据给出一致结果。
     *
     * @return 实际被应用的预设；{@code null} 输入时不做任何事并返回 {@code null}
     */
    public static CursorPresets.AnimPreset applyAnimPreset(CursorPresets.AnimPreset p) {
        if (p == null) {
            return null;
        }
        NeovideCursorSettings svc = NeovideCursorSettings.getInstance();
        if (svc == null) {
            return null;
        }
        NeovideCursorSettings.State s = svc.getState();

        int idx = indexOf(p);
        float[] fromOverride = (idx >= 0)
                ? CursorPresets.parseOverride(s.animPresetOverrides, idx)
                : null;

        if (fromOverride != null) {
            // 有覆盖：用用户改过的值（6 项，顺序见 CursorPresets.OVERRIDE_FIELDS）
            s.animationLength = fromOverride[0];
            s.shortAnimationLength = fromOverride[1];
            s.trailSize = fromOverride[2];
            s.glowLayers = Math.round(fromOverride[3]);
            s.shadowBlurFactor = fromOverride[4];
            s.glowOpacity = fromOverride[5];
        } else {
            // 无覆盖：用预设常量（0.9.8 原行为，一字未改）
            s.animationLength = p.animationLength();
            s.shortAnimationLength = p.shortAnimationLength();
            s.trailSize = p.trailSize();
            s.glowLayers = p.glowLayers();
            s.shadowBlurFactor = p.shadowBlurFactor();
            s.glowOpacity = p.glowOpacity();
        }

        // 基准也必须前移（与面板 onAnimPresetPicked() 一致）：
        // 否则「Tools 切到有覆盖的预设」后，面板下拉仍指着上一个预设（显示层撒谎），
        // 且下一次面板 apply 会把覆盖写到【错误的 key】上。
        if (idx >= 0) {
            s.animPresetBase = idx;
        }

        svc.persistQuietly();
        NeovideCaretManager.applyConfig();      // ← S1..S8 唯一生效入口（既有）
        LOG.info("neovide-cursor: 已应用动画预设 " + p.name()
                + (fromOverride != null ? "（含用户覆盖值）" : "（预设常量）"));
        return p;
    }

    /** 预设对象在 {@link CursorPresets#ANIM_PRESETS} 里的下标；找不到返回 -1。 */
    static int indexOf(CursorPresets.AnimPreset p) {
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            if (CursorPresets.ANIM_PRESETS[i] == p) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Tools 菜单专用：全量替换 2 个颜色字段。
     *
     * <p><b>不碰</b>任何动画字段（6 个）、{@code stretchRatioK}、{@code trailMode}。
     *
     * @return 实际被应用的颜色预设；{@code null} 输入时不做任何事并返回 {@code null}
     */
    public static CursorPresets.ColorPreset applyColorPreset(CursorPresets.ColorPreset p) {
        if (p == null) {
            return null;
        }
        NeovideCursorSettings svc = NeovideCursorSettings.getInstance();
        if (svc == null) {
            return null;
        }
        NeovideCursorSettings.State s = svc.getState();
        s.tailColor = p.tailColor();
        s.shadowColor = p.shadowColor();
        svc.persistQuietly();
        NeovideCaretManager.applyConfig();
        LOG.info("neovide-cursor: 已应用颜色预设 " + p.name());
        return p;
    }

    // ==================== 匹配判据（界面「当前是哪个预设」用）====================

    /**
     * 逐字段比对找出「当前值属于哪个动画预设」，匹配失败返回 {@code null}（= 界面显示「自定义」）。
     *
     * <p><b>不看历史</b>（契约 §5.3）：任何「记住上次选了哪个」的做法都是第四个真相源。
     * 这也让 Tools 的 {@code ToggleAction} 天然单选 —— 最多只有一个预设能匹配上。
     *
     * <p><b>必须用 {@link CursorPresets#EPS} 而不是 {@code ==}</b>：见该常量的 javadoc。
     */
    public static CursorPresets.AnimPreset matchAnimPreset(NeovideCursorSettings.State s) {
        if (s == null) {
            return null;
        }
        for (CursorPresets.AnimPreset p : CursorPresets.ANIM_PRESETS) {
            if (Math.abs(s.animationLength - p.animationLength()) < CursorPresets.EPS
                    && Math.abs(s.shortAnimationLength - p.shortAnimationLength()) < CursorPresets.EPS
                    && Math.abs(s.trailSize - p.trailSize()) < CursorPresets.EPS
                    && s.glowLayers == p.glowLayers()
                    && Math.abs(s.shadowBlurFactor - p.shadowBlurFactor()) < CursorPresets.EPS
                    && Math.abs(s.glowOpacity - p.glowOpacity()) < CursorPresets.EPS) {
                return p;
            }
        }
        return null;
    }

    /**
     * 逐字段比对找出「当前值属于哪个颜色预设」，匹配失败返回 {@code null}。
     *
     * <p>HEX 用 {@code equalsIgnoreCase}：{@code sanitizeColor} 统一成大写，
     * 但用户手改 XML 可能留小写。
     */
    public static CursorPresets.ColorPreset matchColorPreset(NeovideCursorSettings.State s) {
        if (s == null || s.tailColor == null || s.shadowColor == null) {
            return null;
        }
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.tailColor().equalsIgnoreCase(s.tailColor)
                    && p.shadowColor().equalsIgnoreCase(s.shadowColor)) {
                return p;
            }
        }
        return null;
    }
}
