import com.github.yyyolo7a79.neovidecursor.settings.CursorPresets;
import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/** 模拟：0.9.80 用户（slopeTicks=82）升级到 0.9.81（预设=100）会发生什么。 */
public final class ZzMigration {
    public static void main(String[] a) {
        CursorPresets.AnimPreset base = CursorPresets.ANIM_PRESETS[0];
        System.out.println("预设「基础」声明的值：slope=" + base.slopeTicksValue());
        System.out.println();

        // 模拟老状态：所有字段都对得上预设，只有 slopeTicks=82（0.9.80 的默认）
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        s.slopeTicks = 82;
        System.out.println("老用户 slopeTicks = " + s.slopeTicks);
        System.out.println("预设要求          = " + base.slopeTicksValue());
        System.out.println("→ 是否被判定为「已修改」： " + (s.slopeTicks != base.slopeTicksValue()));
        System.out.println();
        if (s.slopeTicks != base.slopeTicksValue()) {
            System.out.println("★ 真问题：老用户一开面板就会被误判成「改了预设」，");
            System.out.println("  从而自动派生「基础 副本」—— 他什么都没做。");
        } else {
            System.out.println("OK：不会误判");
        }
    }
}