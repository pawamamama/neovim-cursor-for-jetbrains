import java.awt.*;

/** 马卡龙色系：低饱和 + 高明度的配色，对比度验证。 */
public final class ZzMacaron {

    // 马卡龙常见的 6 色（粉 / 黄 / 绿 / 蓝 / 紫 / 橘）
    static final String[] MACARON = {
        "#FFD1DC", // 樱花粉
        "#FFE5B4", // 奶油黄
        "#C8E6C9", // 抹茶绿
        "#B3E5FC", // 婴儿蓝
        "#E1BEE7", // 薰衣草
        "#FFCCBC", // 蜜桃橘
    };

    public static void main(String[] a) {
        System.out.println("=== 马卡龙 6 色的 HSL 与亮度 ===");
        System.out.printf("%-8s %-10s %-8s %-8s %-8s %s%n", "名称", "HEX", "H", "S", "B", "WCAG亮度");
        String[] names = {"樱花粉", "奶油黄", "抹茶绿", "婴儿蓝", "薰衣草", "蜜桃橘"};
        for (int i = 0; i < MACARON.length; i++) {
            int rgb = Integer.parseInt(MACARON[i].substring(1), 16);
            float[] hsb = Color.RGBtoHSB((rgb>>16)&0xFF, (rgb>>8)&0xFF, rgb&0xFF, null);
            System.out.printf("%-8s %-10s %-8.0f %-8.2f %-8.2f %.3f%n",
                    names[i], MACARON[i], hsb[0]*360, hsb[1], hsb[2], lum(rgb));
        }
        System.out.println();
        System.out.println("=== 与深色背景 (0x2B2D30) 的对比度 ===");
        int bg = 0x2B2D30;
        for (int i = 0; i < MACARON.length; i++) {
            int rgb = Integer.parseInt(MACARON[i].substring(1), 16);
            double c = contrast(rgb, bg);
            String judge = c < 3.0 ? "★ 太暗看不清" : (c > 12 ? "★ 刺眼" : "合适");
            System.out.printf("  %-8s 对比度 %5.2f  %s%n", names[i], c, judge);
        }
        System.out.println();
        System.out.println("注：光标是视觉焦点，需要与背景有明显对比（>= 4.5 才醒目）");
    }

    static double lum(int rgb) {
        double r = lin(((rgb>>16)&0xFF)/255.0), g = lin(((rgb>>8)&0xFF)/255.0), b = lin((rgb&0xFF)/255.0);
        return 0.2126*r + 0.7152*g + 0.0722*b;
    }
    static double lin(double v) { return v <= 0.03928 ? v/12.92 : Math.pow((v+0.055)/1.055, 2.4); }
    static double contrast(int x, int y) {
        double lx = lum(x), ly = lum(y);
        return (Math.max(lx,ly)+0.05)/(Math.min(lx,ly)+0.05);
    }
}