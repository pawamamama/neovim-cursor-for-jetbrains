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
 * {@link #applyAnimPreset} 只碰 8 个预设字段，<b>绝不</b>碰 {@code tailColor} / {@code shadowColor}；
 * {@link #applyColorPreset} 只碰 2 个 HEX，<b>绝不</b>碰任何动画字段。
 */
public final class CursorPresetApplier {

    private static final Logger LOG = Logger.getInstance(CursorPresetApplier.class);

    private CursorPresetApplier() {
    }

    /**
     * Tools 菜单专用：对<b>内置预设</b>做全量替换（与面板的「脏字段合并」语义不同，不可合并）。
     *
     * <p><b>不碰</b>：{@code tailColor} / {@code shadowColor}（颜色正交）、
     * {@code stretchRatioK} / {@code trailFactors}（预设一律沿用 4.5×）、
     * {@code trailMode}（独立开关）。
     *
     * <p><b>0.9.9 起不再读覆盖表</b>：内置预设<b>永久只读</b>，用户改动一律落在
     * 自定义预设上（见 {@code CustomPresets}）。所以本方法只写那 6 个预设常量，
     * 不存在「冲掉用户改动」的可能 —— 0.9.8 的 F7 那条数据销毁路径随模型一起消失。
     *
     * <p>同时把 {@code animCustomName} 清空：从 Tools 选内置预设 = 明确「我要回到这个内置预设」。
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

        // 写【全部】预设字段（含辉光/淡出开关）。
        //
        // 【为什么不能只写 8 个预设字段（团队审查 F-05 / 用户报的 B3）】
        // 用户在某预设下关掉辉光 → useShadow=false 被持久化；之后从 Tools 切到
        // 任何别的预设，辉光【都不会回来】（用户原话：「切换预设不能让辉光回来」
        // 「缎带没有扩散了，就是持久化了」）。
        //
        // 而「纯平滑输入」这个预设必须能表达「关辉光」—— 6 字段模型写不出来，
        // 所以这不是补丁，是预设字段域本身就该包含这两个开关。
        com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable
                .applyAnimPresetFields(s, p);

        int idx = indexOf(p);
        if (idx >= 0) {
            s.animPresetBase = idx;
        }
        // 内置预设只读：清掉「当前在用自定义预设」的指针
        s.animCustomName = "";

        svc.persistQuietly();
        NeovideCaretManager.applyConfig();
        LOG.info("neovide-cursor: 已应用内置动画预设 " + p.name());
        return p;
    }

    /**
     * Tools 菜单专用：应用一个<b>自定义预设</b>（全参数快照）。
     *
     * <p><b>与 {@link #applyAnimPreset} 的区别</b>：内置预设只定义固定几个字段；
     * 自定义预设是<b>全部 19 个参数的快照</b>，所以整份还原。
     *
     * <p><b>为什么 Tools 必须有这个入口</b>：用户既然能在面板里存自定义预设，
     * 就应该能在 Tools 里一键切换 —— 否则「快捷切换」对自建预设是残废的
     * （用户多次反馈："保存了自定义设置但是 tools 依旧没有"）。
     *
     * @param name 预设名；不存在时什么都不做并返回 {@code false}
     * @return true = 真的应用了
     */
    public static boolean applyCustomAnimPreset(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        NeovideCursorSettings svc = NeovideCursorSettings.getInstance();
        if (svc == null) {
            return false;
        }
        NeovideCursorSettings.State s = svc.getState();
        String snap = com.github.yyyolo7a79.neovidecursor.settings.CustomPresets
                .get(s.customAnimPresets, name);
        if (snap == null) {
            return false;                       // 名字不存在（可能在别处被删了）
        }
        // 【第三轮审查 ②，high：恢复预设不能把插件关掉】
        // 原实现直接 SettingsField.restore(s, snap) 整份 19 字段还原，其中包含
        // enabled / hideNativeCaret / trailMode / alwaysHighFps ——
        // 实测：快照里 enabled=false 时点该预设会把插件整体关掉；
        // 反向会把已经关掉的插件打开；hideNativeCaret 还会触发配色方案的不可逆写入。
        //
        // 内置预设路径（applyAnimPreset）从不碰这四个字段，所以自定义路径也必须一致。
        // 这四个是「运行环境开关」，不是「观感参数」—— 不该被预设快照带走。
        com.github.yyyolo7a79.neovidecursor.settings.SettingsField.restoreSkipping(
                s, snap,
                com.github.yyyolo7a79.neovidecursor.settings.SettingsField.RUNTIME_SWITCH_KEYS);
        s.animCustomName = name;
        svc.persistQuietly();
        NeovideCaretManager.applyConfig();
        LOG.info("neovide-cursor: 已应用自定义预设 " + name);
        return true;
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
        // 【0.9.79 修复：Tools 切颜色时没同步 rgbGradient】
        //
        // 0.9.75 给颜色预设加了 rgbWash 标记（「彩虹（动态）」= true，其余 false），
        // 设置面板的 onColorPresetPicked / restoreColorPreset 都同步写了；
        // **但 Tools 菜单这条路径忘了写** —— 于是：
        //   · 从「彩虹」在 Tools 里切到「冰蓝」→ 两个 HEX 变了，
        //     但 rgbGradient 仍是 true → 绘制层继续走色相循环 → 光标还是彩虹
        //   · 用户看到「切了颜色没变化，只有从设置面板切才管用」
        //
        // 这与「两条路径语义必须一致」是同一类问题（restoreSkipping 那次也是）。
        s.rgbGradient = p.rgbWash();
        svc.persistQuietly();
        NeovideCaretManager.applyConfig();
        LOG.info("neovide-cursor: 已应用颜色预设 " + p.name()
                + " rgbWash=" + p.rgbWash());
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
            // 【第二轮审查 F3，high：判据必须与写入字段域一致】
            // applyAnimPreset 现在写 8 个字段（6 动画 + useShadow + overlayFadeOut），
            // 但 match 只比 6 个 → 三个已实测的撒谎：
            //   ① Tools 勾选态：用户关了辉光，preset 仍被 match 上 → 菜单显示「已选中」，
            //      但实际观感已经不是它了；
            //   ② 点已勾选项的「取消」语义被吃掉（isSelected 为 true 时平台回调 false，
            //      我们忽略它，用户以为取消成功）；
            //   ③ 点「缎带」时如果当时辉光是关的，会静默把辉光打开而下拉没变化。
            // （③ 现在是期望行为 —— 预设就该带辉光，但判据必须能反映它。）
            if (Math.abs(s.animationLength - p.animationLength()) < CursorPresets.EPS
                    && Math.abs(s.shortAnimationLength - p.shortAnimationLength()) < CursorPresets.EPS
                    && Math.abs(s.trailSize - p.trailSize()) < CursorPresets.EPS
                    && s.glowLayers == p.glowLayers()
                    && Math.abs(s.shadowBlurFactor - p.shadowBlurFactor()) < CursorPresets.EPS
                    && Math.abs(s.glowOpacity - p.glowOpacity()) < CursorPresets.EPS
                    && s.useShadow == p.useShadow()
                    && s.overlayFadeOut == p.overlayFadeOut()
                    // 【0.9.47：输入平滑不再参与预设匹配】
                    // 它已改为全局设置（不随预设变），若还比对它，
                    // 用户把平滑拖到 40 后所有预设都会「匹配不上」→ 下拉显示空白。
                    //
                    // 【0.9.46 补充：拉伸强度必须参与匹配】
                    // 上一轮把 stretchRatioK 加进了预设，却漏了这个判据 ——
                    // 会导致「k 不同但其他相同」的两个预设被判为同一个。
                    && Math.abs(s.stretchRatioK - p.stretchRatioK())
                            < CursorPresets.EPS) {
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
        // ==================== 0.9.88 修复：判据失效 ====================
        //
        // 【原判据】
        //     p.rgbWash() == s.rgbGradient
        //
        // 「彩虹」与「原版粉色」两个 HEX 完全相同（都是 #FFC0CB），
        // 0.9.79 加这个判据是为了区分它们 —— 当时 rgbGradient 就表示「走不走色相循环」。
        //
        // 【为什么失效】0.9.85 起 rgbGradient 改了语义：
        //   旧：「走色相循环」
        //   新：「光标渐变（双拼）开关」
        // 于是它不再等于 rgbWash，判据变成掷硬币。实测两条都错：
        //   粉色 + 光标渐变开 → 误匹配成「彩虹」
        //   彩虹 + 光标渐变关 → 误匹配成「原版粉色」
        // 守门探针 VColorMatchBug 复现了这两条。
        //
        // 【新判据：纯 HEX 比对】
        //
        // 0.9.88 给彩虹换成了独有的 HEX（#4FC3F7 / #B388FF），
        // 与任何纯色预设都不再重叠 —— 所以不需要额外维度，比 HEX 就够。
        //
        // 为什么不比 rgbWash / rgbGradient：那个字段的语义已被
        // 「光标渐变开关」占用，拿它做判据就是纪律四-D 说的
        // 「一个字段承担两个职责」。判据必须建立在**不会被复用**的信息上。
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.tailColor().equalsIgnoreCase(s.tailColor)
                    && p.shadowColor().equalsIgnoreCase(s.shadowColor)) {
                return p;
            }
        }
        return null;
    }
}
