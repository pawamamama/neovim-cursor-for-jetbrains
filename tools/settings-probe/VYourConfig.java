import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 快速验证：你的实际配置算出的 trailFactors。 */
public final class VYourConfig {
    public static void main(String[] a) {
        System.out.println("=== 你当前配置的实际手感 ===");
        System.out.println();

        // 你的设置文件：stretchRatioK = 8.0，其余全默认
        float k = 8.0f;          // ← XML 里唯一持久化的手感参数
        float smooth = 0.0f;     // ← 未设置 = 默认 0

        float[] f = NeovideCursorSettings.deriveTrailFactors(k, smooth);
        System.out.println("  stretchRatioK = " + k + "  typingSmoothness = " + smooth);
        System.out.println("  → trailFactors = " + java.util.Arrays.toString(f));
        System.out.println();

        // 对比几个典型值
        System.out.println("=== 不同 k 的 trailFactors 对比 ===");
        for (float v : new float[]{1.0f, 2.0f, 4.5f, 6.0f, 8.0f}) {
            System.out.printf("  k=%.1f → %s%n", v,
                    java.util.Arrays.toString(NeovideCursorSettings.deriveTrailFactors(v, 0f)));
        }
        System.out.println();
        System.out.println("=== 不同 smooth 的 trailFactors（k 固定 8.0）===");
        for (float v : new float[]{0f, 0.25f, 0.5f, 0.75f, 1.0f}) {
            System.out.printf("  smooth=%.2f → %s%n", v,
                    java.util.Arrays.toString(NeovideCursorSettings.deriveTrailFactors(8.0f, v)));
        }
    }
}
