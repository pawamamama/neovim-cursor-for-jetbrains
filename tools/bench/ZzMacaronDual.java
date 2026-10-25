/** 马卡龙动态：双色缓慢过渡。验证配对与过渡效果。 */
public final class ZzMacaronDual {

    // 马卡龙 6 色，按色相环顺序排列（粉 346 -> 橘 14 -> 黄 39 -> 绿 122 -> 蓝 199 -> 紫 291）
    static final String[] HEX = {
        "#FFD1DC", // 0 樱花粉   H=346
        "#FFCCBC", // 1 蜜桃橘   H=14
        "#FFE5B4", // 2 奶油黄   H=39
        "#C8E6C9", // 3 抹茶绿   H=122
        "#B3E5FC", // 4 婴儿蓝   H=199
        "#E1BEE7", // 5 薰衣草   H=291
    };
    static final String[] NAME = {"樱花粉", "蜜桃橘", "奶油黄", "抹茶绿", "婴儿蓝", "薰衣草"};

    public static void main(String[] a) {
        System.out.println("=== 相邻对（色相环顺序）的过渡质量 ===");
        System.out.printf("%-18s %-12s %-12s %-14s %s%n", "配对", "起色", "终色", "ΔE", "判读");
        System.out.println("-".repeat(72));
        for (int i = 0; i < HEX.length; i++) {
            int j = (i + 1) % HEX.length;
            int c1 = Integer.parseInt(HEX[i].substring(1), 16);
            int c2 = Integer.parseInt(HEX[j].substring(1), 16);
            double de = deltaE(c1, c2);
            String judge = de < 8 ? "★ 太接近" : (de > 35 ? "★ 跳变明显" : "柔和过渡");
            System.out.printf("%-18s %-12s %-12s %-14.1f %s%n",
                    NAME[i] + " -> " + NAME[j], HEX[i], HEX[j], de, judge);
        }
        System.out.println();
        System.out.println("=== 跳回（薰衣草 -> 樱花粉，色相环闭合处）===");
        int c1 = Integer.parseInt("#E1BEE7".substring(1), 16);
        int c2 = Integer.parseInt("#FFD1DC".substring(1), 16);
        System.out.printf("  ΔE = %.1f%n", deltaE(c1, c2));
    }

    static double deltaE(int c1, int c2) {
        double[] l1 = lab(c1), l2 = lab(c2);
        return Math.sqrt(Math.pow(l1[0]-l2[0],2) + Math.pow(l1[1]-l2[1],2) + Math.pow(l1[2]-l2[2],2));
    }
    static double[] lab(int rgb) {
        double r = lin(((rgb>>16)&0xFF)/255.0), g = lin(((rgb>>8)&0xFF)/255.0), b = lin((rgb&0xFF)/255.0);
        double x = (0.4124*r + 0.3576*g + 0.1805*b) / 0.95047;
        double y = (0.2126*r + 0.7152*g + 0.0722*b);
        double z = (0.0193*r + 0.1192*g + 0.9505*b) / 1.08883;
        return new double[]{116*f(y)-16, 500*(f(x)-f(y)), 200*(f(y)-f(z))};
    }
    static double f(double t) { return t > 0.008856 ? Math.cbrt(t) : (7.787*t + 16.0/116); }
    static double lin(double v) { return v <= 0.03928 ? v/12.92 : Math.pow((v+0.055)/1.055, 2.4); }
}