package com.github.yyyolo7a79.neovidecursor.core;

import java.awt.Color;

/**
 * 颜色解析工具 —— 对应原 JS 版的 {@code cursorHexToRgba}。
 *
 * <p>支持 {@code #RGB}、{@code #RRGGBB}、{@code #RRGGBBAA} 三种 HEX 写法（{@code #} 可省略），
 * 解析结果再乘以外部传入的不透明度系数。
 */
public final class NeovideColors {

    /**
     * 最多逐条记录这么多<b>不同的</b>非法串；超过只补一条汇总。
     *
     * <p>为什么必须有界：{@code parse} 在每次配置变更（{@code refreshStyles}）时都会跑，
     * 一个坏颜色会<b>每改一次设置就报一次</b>。不设上限就是把 idea.log 刷成一片，
     * 等于从「没有日志」变成「日志没用」（CursorDiag 的预算表是同一套思路）。
     */
    private static final int MAX_REPORTED = 16;

    /** 日志里原串的最大展示长度。 */
    private static final int HEX_LOG_MAX = 24;

    /**
     * 已经报过的非法串 —— 同一串只报一次。
     *
     * <p>用并发集合：绘制路径可能来自不同线程（探针里也会并发跑）。
     * 只在<b>失败分支</b>查它，成功路径零额外开销 —— 这是每帧都会走的代码。
     */
    private static final java.util.Set<String> REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 逐条记录达到上限后只补一条汇总，用这个标志保证只补一次。 */
    private static volatile boolean capLogged;

    /** 日志子系统故障只降级一次，别把 stderr 也刷爆。 */
    private static volatile boolean logBroken;

    private NeovideColors() {
    }

    /**
     * 记录一次非法颜色串（同一串只报一次，总数有界）。
     *
     * <p><b>为什么 core 里要引平台 Logger</b>：L-4 要求「失败必须留痕」，
     * 而 core 原本零平台依赖。备选方案是新建一个 core 日志门面，但那超出本批白名单，
     * 且「谁负责注入实现」本身是新风险（漏注入 = 全部日志静默消失，正是本批要消灭的病）。
     * 所以这里惰性取 Logger 并兜住 {@code Throwable}：没有平台 jar 时（手工
     * {@code java -cp classes}）只打一条 stderr，不会让解析中断。
     *
     * <p>与 {@code TrailCorner.safeWarn} 是同一份实现的两份拷贝 —— 白名单所致，
     * 批次 2 抽 {@code CoreLog} 后合并。
     */
    private static void warnInvalid(String hex) {
        String key = hex == null ? "(null)" : hex;      // ConcurrentHashMap 不接受 null 键
        try {
            if (REPORTED.contains(key)) {
                return;                                 // 同一串只报一次
            }
            if (REPORTED.size() >= MAX_REPORTED) {
                if (!capLogged) {
                    capLogged = true;
                    safeWarn("非法颜色串的逐条记录已达记录上限 " + MAX_REPORTED + " 条，后续不再逐条记录");
                }
                return;
            }
            // 【入集合的必须是截断后的键】否则手改 XML 塞进几 MB 的颜色串时，
            // 这 16 个元素会各自驻留整份原文（进程内永不释放）。
            // 键里带长度：两个长度不同的超长串不会被截断后误判成同一个。
            String stored = key.length() <= HEX_LOG_MAX ? key
                    : key.substring(0, HEX_LOG_MAX) + "#" + key.length();
            if (REPORTED.add(stored)) {
                String shown = key.length() <= HEX_LOG_MAX ? key
                        : key.substring(0, HEX_LOG_MAX) + "…(共 " + key.length() + " 字符)";
                safeWarn("非法颜色串，已退回黑色: [" + shown + "]");
            }
        } catch (Throwable t) {
            // parse 的契约是「解析失败不应导致动画中断」，日志失败更不能中断它
        }
    }

    /** 打一条 warn；日志子系统不可用时降级到 stderr（只报一次）。 */
    private static void safeWarn(String msg) {
        try {
            com.intellij.openapi.diagnostic.Logger.getInstance(NeovideColors.class)
                    .warn("neovide-cursor: " + msg);
        } catch (Throwable t) {
            if (!logBroken) {
                logBroken = true;
                System.err.println("[neovide-cursor] 日志不可用（只报一次）: " + t);
            }
        }
    }

    /**
     * 解析 HEX 颜色字符串。
     *
     * @param hex     颜色文本，如 {@code #FFC0CB}
     * @param opacity 额外的不透明度系数（0~1），与颜色自带的 alpha 相乘
     * @return 解析后的 Color；解析失败时返回不透明黑色，保证不影响绘制流程
     */
    public static Color parse(String hex, float opacity) {
        try {
            if (hex == null) {
                warnInvalid(null);
                return Color.BLACK;
            }

            String h = hex.startsWith("#") ? hex.substring(1) : hex;

            // #RGB 缩写展开为 #RRGGBB
            if (h.length() == 3) {
                StringBuilder sb = new StringBuilder();
                for (char c : h.toCharArray()) {
                    sb.append(c).append(c);
                }
                h = sb.toString();
            }

            // 无 alpha 通道时补全为 FF
            if (h.length() == 6) {
                h = h + "FF";
            }
            if (h.length() != 8) {
                warnInvalid(hex);
                return Color.BLACK;
            }

            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            int a = Integer.parseInt(h.substring(6, 8), 16);

            float alpha = (a / 255.0f) * clamp01(opacity);
            return new Color(clamp255(r), clamp255(g), clamp255(b), Math.round(alpha * 255));

        } catch (Exception e) {
            // 颜色解析失败不应导致动画中断，退回黑色。
            // 但必须留痕（L-4）：非法颜色会让拖尾与辉光同时变黑，
            // 否则用户只能报「光标黑了」，而日志里一个字都没有。
            warnInvalid(hex);
            return Color.BLACK;
        }
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
