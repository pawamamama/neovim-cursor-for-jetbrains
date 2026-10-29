import com.github.yyyolo7a79.neovidecursor.core.NeovideColors;
import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;
import com.github.yyyolo7a79.neovidecursor.ui.CaretTrailPanel;
import com.github.yyyolo7a79.neovidecursor.ui.CursorPresetApplier;

import java.awt.Color;
import java.lang.reflect.Field;

/**
 * V-GLOW-FOG —— 彩虹模式下辉光雾色的守门探针（BUG-011，0.9.105）。
 *
 * <p><b>它抓的是什么 bug：</b>
 * 颜色预设里的 {@code shadowColor} 曾被当成「彩虹模式下不参与绘制的摆设」，
 * 0.9.88 据此把彩虹的 shadowColor 随手定成紫罗兰 {@code #B388FF}。
 * 实际上它整条喂给辉光 —— 彩虹光标旁的雾因此从粉雾变成淡紫，
 * 用户看到的是「光标和辉光不搭」。
 *
 * <p><b>为什么这条必须有探针：</b>
 * 在此之前没有任何探针盯「预设 shadowColor → 面板 glowColor」这条链路。
 * {@code VColorMatchBug} 只验匹配，{@code VRainbowSep} 只验本体填充路径，
 * 两者都绕开了辉光色 —— 这正是这个 bug 能活到用户发现的原因。
 *
 * <p><b>断言分层（纪律四-G：断言太弱 = 假通过）：</b>
 * <ul>
 *   <li>第 2 条锁<b>本次决策</b>（雾色 = #FFFFFF）；</li>
 *   <li>第 3、4 条锁<b>链路事实</b>（预设的 shadowColor 原样喂给辉光）；</li>
 *   <li>第 5 条做<b>反证</b>（换一个 HEX，辉光必须跟着变）——
 *       没有它，第 3 条可能只是恒真的死数据；</li>
 *   <li>第 6、7 条防<b>连带损伤</b>（改 HEX 后匹配唯一性没被破坏）；</li>
 *   <li>第 8 条锁<b>雾色的定性要求</b>（低饱和，不与色相环抢戏）。</li>
 * </ul>
 */
public final class VGlowFog {
    private static int pass = 0, fail = 0;

    public static void main(String[] a) throws Exception {
        V0Env.install();
        System.out.println("=== V-GLOW-FOG（彩虹辉光雾色）===");

        CursorPresets.ColorPreset rainbow = null;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p.rgbWash()) {
                rainbow = p;
                break;
            }
        }
        check("1 找到彩虹预设（rgbWash == true）",
                rainbow != null, rainbow == null ? "无" : rainbow.name());
        if (rainbow == null) {
            finish();
            return;
        }

        // ---------- 断言 A：本次决策锁定 ----------
        check("2 彩虹预设的 shadowColor = #FFFFFF（中性白雾，BUG-011 的修法）",
                "#FFFFFF".equalsIgnoreCase(rainbow.shadowColor()),
                "实际=" + rainbow.shadowColor());

        // ---------- 断言 B：它真的流到辉光绘制 ----------
        Color glow = glowOf(rainbow.shadowColor());
        Color expect = NeovideColors.parse(rainbow.shadowColor(), 1.0f);
        check("3 彩虹预设的 shadowColor 原样流到 CaretTrailPanel.glowColor",
                sameRgb(glow, expect), "glow=" + hex(glow) + " 期望=" + hex(expect));
        check("4 辉光 alpha 恒 255（refreshStyles 固定 1.0f，浓淡只由 glowOpacity 给）",
                glow != null && glow.getAlpha() == 255,
                "alpha=" + (glow == null ? "null" : glow.getAlpha()));

        // ---------- 断言 C：反证 —— 链路是活的，不是死数据 ----------
        Color probeGlow = glowOf("#123456");
        check("5 反证：shadowColor 换成 #123456，辉光必须跟着变",
                sameRgb(probeGlow, NeovideColors.parse("#123456", 1.0f)),
                "glow=" + hex(probeGlow));

        // ---------- 断言 D：改 HEX 没破坏匹配唯一性 ----------
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.tailColor = rainbow.tailColor();
        s.shadowColor = rainbow.shadowColor();
        CursorPresets.ColorPreset m = CursorPresetApplier.matchColorPreset(s);
        check("6 彩虹仍能被唯一匹配（matchColorPreset 比的是两个 HEX）",
                m == rainbow, "匹配=" + (m == null ? "null" : m.name()));

        int dup = 0;
        for (CursorPresets.ColorPreset p : CursorPresets.COLOR_PRESETS) {
            if (p == rainbow) {
                continue;
            }
            if (p.tailColor().equalsIgnoreCase(rainbow.tailColor())
                    && p.shadowColor().equalsIgnoreCase(rainbow.shadowColor())) {
                dup++;
            }
        }
        check("7 彩虹的 (tail, shadow) 组合与其它预设无重复", dup == 0, "重复数=" + dup);

        // ---------- 断言 E：雾色的定性要求 ----------
        float[] hsb = Color.RGBtoHSB(expect.getRed(), expect.getGreen(), expect.getBlue(), null);
        check("8 雾色低饱和（S <= 0.10），不与色相环抢戏",
                hsb[1] <= 0.10f,
                String.format("H=%.2f S=%.2f V=%.2f", hsb[0], hsb[1], hsb[2]));

        finish();
    }

    /**
     * 用给定 shadowColor 造一个面板，读它真正拿去画辉光的那个 Color。
     *
     * <p>走的是生产构造路径（{@code CaretTrailPanel(config)} → {@code refreshStyles()}），
     * 不是在这里重算一遍 {@code parse(shadowColor)} —— 否则探针测的就成了自己的复刻
     * （纪律四-G 的「假通过①」）。
     */
    static Color glowOf(String shadowHex) throws Exception {
        NeovideConfig cfg = new NeovideConfig();
        cfg.shadowColor = shadowHex;
        cfg.tailColor = "#4FC3F7";
        cfg.useShadow = false;              // 只验字段，不画像素
        CaretTrailPanel p = new CaretTrailPanel(cfg);
        Field f = CaretTrailPanel.class.getDeclaredField("glowColor");
        f.setAccessible(true);
        return (Color) f.get(p);
    }

    static boolean sameRgb(Color a, Color b) {
        return a != null && b != null
                && a.getRed() == b.getRed()
                && a.getGreen() == b.getGreen()
                && a.getBlue() == b.getBlue();
    }

    static String hex(Color c) {
        return c == null ? "null"
                : String.format("#%02X%02X%02X alpha=%d",
                        c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    static void check(String n, boolean ok, String d) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + n + "   [" + d + "]");
        } else {
            fail++;
            System.out.println("  FAIL  " + n + "   [" + d + "]");
        }
    }

    static void finish() {
        System.out.println();
        System.out.printf("=== PASS %d，FAIL %d ===%n", pass, fail);
        if (fail > 0) {
            System.out.println("RESULT: FAILED");
            System.exit(1);
        }
        System.out.println("RESULT: ALL PASS");
    }
}
