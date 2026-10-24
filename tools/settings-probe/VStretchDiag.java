import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

public final class VStretchDiag {
    public static void main(String[] a) {
        System.out.println("=== 三个预设的横向拉伸参数 ===");
        for (int i = 0; i < CursorPresets.ANIM_PRESETS.length; i++) {
            var p = CursorPresets.ANIM_PRESETS[i];
            float[] f = NeovideCursorSettings.deriveTrailFactors(p.stretchRatioK(), 0f);
            System.out.printf("%n预设%d %s%n", i, p.name());
            System.out.printf("  stretchRatioK = %.1f%n", p.stretchRatioK());
            System.out.printf("  trailSize     = %.2f%n", p.trailSize());
            System.out.printf("  animationLength = %.2f%n", p.animationLength());
            System.out.printf("  trailFactors  = %s%n", java.util.Arrays.toString(f));
            // 跨行分支实际用的时长（TrailCorner.jump 的 else 分支）
            double leading = p.animationLength() * Math.max(0, 1 - p.trailSize());
            double trailing = p.animationLength();
            System.out.printf("  跨行时长: rank0=%.3f rank1=%.3f rank2/3=%.3f%n",
                    trailing, (leading+trailing)/2, leading);
        }
    }
}
