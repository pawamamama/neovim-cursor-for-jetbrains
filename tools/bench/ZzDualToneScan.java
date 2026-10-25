/** 方案 B 的对比度扫描：找「看得出渐变但不跳」的亮度系数。 */
public final class ZzDualToneScan {
    static final String[] NAMES = {"原版粉色", "冰蓝", "薄荷", "藕荷", "暖沙"};
    static final String[] TAILS = {"#FFC0CB", "#7DD3FC", "#6EE7B7", "#C4B5FD", "#FDE68A"};
    static final String[] GLOWS = {"#FFC0CB", "#38BDF8", "#34D399", "#A78BFA", "#FCD34D"};

    public static void main(String[] a) {
        System.out.println("亮度系数对末端色与对比度的影响（方案 B：辉光色 × 系数）");
        System.out.println();
        System.out.printf("%-10s", "系数");
        for (String n : NAMES) System.out.printf("%-22s", n);
        System.out.println();
        System.out.println("-".repeat(120));
        for (double mul : new double[]{0.85, 0.78, 0.72, 0.65, 0.58, 0.50}) {
            System.out.printf("%-10.2f", mul);
            for (int i = 0; i < NAMES.length; i++) {
                int tail = hex(TAILS[i]);
                int end = glowBased(GLOWS[i], mul);
                double c = contrast(tail, end);
                System.out.printf("%-22s", String.format("#%06X c=%.2f", end, c));
            }
            System.out.println();
        }
        System.out.println();
        System.out.println("目标区间：对比度 1.6 ~ 2.2（看得出渐变，但不像两种颜色）");
    }

    static int glowBased(String glowHex, double mul) {
        int glow = hex(glowHex);
        float[] h = java.awt.Color.RGBtoHSB((glow>>16)&0xFF, (glow>>8)&0xFF, glow&0xFF, null);
        return java.awt.Color.HSBtoRGB(h[0], Math.min(1f, h[1]*1.05f), (float)(h[2]*mul)) & 0xFFFFFF;
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
    static int hex(String s) { return Integer.parseInt(s.substring(1), 16); }
}