/** 找「0.72 到底够不够」的答案：扫描系数，量两端色差（感知指标）。 */
public final class ZzDualTonePerceptual {
    static final String[] NAMES = {"原版粉色", "冰蓝", "薄荷", "藕荷", "暖沙"};
    static final String[] TAIL = {"#FFC0CB", "#7DD3FC", "#6EE7B7", "#C4B5FD", "#FDE68A"};
    static final String[] GLOW = {"#FFC0CB", "#38BDF8", "#34D399", "#A78BFA", "#FCD34D"};

    public static void main(String[] a) {
        System.out.println("系数对「感知色差」的影响");
        System.out.println("  ΔE 是 CIE76 色差，人眼可辨阈值约 2.3；> 10 是明显不同色");
        System.out.println();
        System.out.printf("%-8s", "系数");
        for (String n : NAMES) System.out.printf("%-14s", n);
        System.out.println();
        System.out.println("-".repeat(80));
        for (double mul : new double[]{0.85, 0.72, 0.65, 0.58, 0.50, 0.42}) {
            System.out.printf("%-8.2f", mul);
            for (int i = 0; i < NAMES.length; i++) {
                int tail = hex(TAIL[i]);
                int end = glowBased(GLOW[i], mul);
                System.out.printf("%-14s", String.format("ΔE=%.1f", deltaE(tail, end)));
            }
            System.out.println();
        }
        System.out.println();
        System.out.println("判据：ΔE 3~8 = 柔和渐变（马卡龙感）；8~15 = 明显；>15 = 像两种颜色");
    }

    /** CIE76 ΔE（简化为 sRGB->Lab） */
    static double deltaE(int c1, int c2) {
        double[] l1 = lab(c1), l2 = lab(c2);
        return Math.sqrt(Math.pow(l1[0]-l2[0],2) + Math.pow(l1[1]-l2[1],2) + Math.pow(l1[2]-l2[2],2));
    }

    static double[] lab(int rgb) {
        double r = lin(((rgb>>16)&0xFF)/255.0), g = lin(((rgb>>8)&0xFF)/255.0), b = lin((rgb&0xFF)/255.0);
        double x = (0.4124*r + 0.3576*g + 0.1805*b) / 0.95047;
        double y = (0.2126*r + 0.7152*g + 0.0722*b) / 1.00000;
        double z = (0.0193*r + 0.1192*g + 0.9505*b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116*fy - 16, 500*(fx-fy), 200*(fy-fz)};
    }
    static double f(double t) { return t > 0.008856 ? Math.cbrt(t) : (7.787*t + 16.0/116); }
    static double lin(double v) { return v <= 0.03928 ? v/12.92 : Math.pow((v+0.055)/1.055, 2.4); }

    static int glowBased(String glowHex, double mul) {
        int glow = hex(glowHex);
        float[] h = java.awt.Color.RGBtoHSB((glow>>16)&0xFF, (glow>>8)&0xFF, glow&0xFF, null);
        return java.awt.Color.HSBtoRGB(h[0], Math.min(1f, h[1]*1.05f), (float)(h[2]*mul)) & 0xFFFFFF;
    }
    static int hex(String s) { return Integer.parseInt(s.substring(1), 16); }
}