/**
 * 双拼渐变末端色的候选算法 —— 纯计算验证，暂不改生产代码。
 *
 * 依据：
 *  · WCAG 2.1 相对亮度公式（https://www.w3.org/TR/WCAG21/#dfn-relative-luminance）
 *    0.2126 R + 0.7152 G + 0.0722 B（线性化后）
 *  · W3C CSS Color 4 的 HSL/Oklch 色相插值建议
 */
public final class ZzDualTone {

    public static void main(String[] a) {
        String[] names = {"原版粉色", "冰蓝", "薄荷", "藕荷", "暖沙"};
        String[] taILS = {"#FFC0CB", "#7DD3FC", "#6EE7B7", "#C4B5FD", "#FDE68A"};
        String[] glows = {"#FFC0CB", "#38BDF8", "#34D399", "#A78BFA", "#FCD34D"};

        System.out.println("=== 方案 A：色相偏移（HSL，±30°）===");
        System.out.printf("%-10s %-10s %-22s %-8s %s%n", "预设", "主色", "末端色(+30° 降饱和)", "对比度", "评");
        for (int i = 0; i < names.length; i++) {
            int rgb = hex(taILS[i]);
            double[] hsb = rgbToHsb(rgb);
            // 色相 +30°，饱和度 ×0.75，亮度 ×0.92
            double h = (hsb[0] + 30.0 / 360) % 1.0;
            int end = hsbToRgb(h, hsb[1] * 0.75, hsb[2] * 0.92);
            System.out.printf("%-10s %-10s %-22s %-8.2f %s%n", names[i], taILS[i],
                    toHex(end), contrast(rgb, end), judge(rgb, end));
        }

        System.out.println();
        System.out.println("=== 方案 B：辉光色参与（主色 → 辉光色 × 0.85 亮度）===");
        for (int i = 0; i < names.length; i++) {
            int rgb = hex(taILS[i]);
            int glow = hex(glows[i]);
            double[] gh = rgbToHsb(glow);
            int end = hsbToRgb(gh[0], Math.min(1, gh[1] * 1.05), gh[2] * 0.85);
            System.out.printf("%-10s %-10s %-22s %-8.2f %s%n", names[i], taILS[i],
                    toHex(end), contrast(rgb, end), judge(rgb, end));
        }

        System.out.println();
        System.out.println("=== 方案 C：同色相明度梯度（最保守，只改 L）===");
        for (int i = 0; i < names.length; i++) {
            int rgb = hex(taILS[i]);
            double[] hsb = rgbToHsb(rgb);
            int end = hsbToRgb(hsb[0], hsb[1], Math.max(0.15, hsb[2] * 0.62));
            System.out.printf("%-10s %-10s %-22s %-8.2f %s%n", names[i], taILS[i],
                    toHex(end), contrast(rgb, end), judge(rgb, end));
        }
    }

    static String judge(int a, int b) {
        double c = contrast(a, b);
        if (c < 1.3) return "★太接近，看不出渐变";
        if (c > 6.0) return "★太跳，像两种颜色";
        return "合适";
    }

    // ===== WCAG 相对亮度 =====
    static double lum(int rgb) {
        double r = lin(((rgb >> 16) & 0xFF) / 255.0);
        double g = lin(((rgb >> 8) & 0xFF) / 255.0);
        double b = lin((rgb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }
    static double lin(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
    static double contrast(int a, int b) {
        double la = lum(a), lb = lum(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    static int hex(String s) { return Integer.parseInt(s.substring(1), 16); }
    static String toHex(int rgb) { return String.format("#%06X", rgb); }

    static double[] rgbToHsb(int rgb) {
        float[] h = java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        return new double[]{h[0], h[1], h[2]};
    }
    static int hsbToRgb(double h, double s, double b) {
        return java.awt.Color.HSBtoRGB((float) h, (float) s, (float) b) & 0xFFFFFF;
    }
}