import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;

/**
 * V-PREVIEW-SIZE：量出预览窗的真实尺寸与 DPI 缩放。
 *
 * <p><b>用户要求</b>：「窗口高度不用这么大只需要一行 高100的话 只需要140大小吧
 * 应该按照用户显示器的吧 但是求你别把别的改bug了」。
 *
 * <p>先量再改（纪律第 1 条）。
 */
public final class VPreviewSize {
    public static void main(String[] a) throws Exception {
        System.out.println("=== V-PREVIEW-SIZE：当前尺寸与 DPI ===");

        // 1) 屏幕 DPI 缩放
        try {
            java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment();
            java.awt.GraphicsDevice gd = ge.getDefaultScreenDevice();
            java.awt.GraphicsConfiguration gc = gd.getDefaultConfiguration();
            java.awt.geom.AffineTransform t = gc.getDefaultTransform();
            System.out.printf("  屏幕 DPI 缩放 = %.2f x %.2f%n",
                    t.getScaleX(), t.getScaleY());
            java.awt.Rectangle b = gc.getBounds();
            System.out.printf("  屏幕分辨率   = %d x %d%n", b.width, b.height);
        } catch (Throwable ex) {
            System.out.println("  DPI 取不到（headless）: " + ex.getClass().getSimpleName());
        }

        // 2) IDEA 的 JBUIScale（如果能加载）
        try {
            Class<?> jbu = Class.forName("com.intellij.util.ui.JBUIScale");
            java.lang.reflect.Method m = jbu.getMethod("sysScale");
            Object v = m.invoke(null);
            System.out.println("  JBUIScale.sysScale() = " + v);
        } catch (Throwable ex) {
            System.out.println("  JBUIScale 不可用: " + ex.getClass().getSimpleName());
        }

        // 3) 组件实际的 preferredSize
        AnimationPreview inl = AnimationPreview.inline(new NeovideConfig());
        AnimationPreview crs = AnimationPreview.cross(new NeovideConfig());
        java.awt.Dimension di = inl.getPreferredSize();
        java.awt.Dimension dc = crs.getPreferredSize();
        System.out.println();
        System.out.println("  单行窗 preferredSize = " + di.width + " x " + di.height);
        System.out.println("  跨行窗 preferredSize = " + dc.width + " x " + dc.height);
        System.out.println();
        System.out.println("  字号（未测量前）= " + inl.probeFontSizeNow());

        // 4) 设成该尺寸后，字号会变成多少？
        inl.setSize(di.width, di.height);
        inl.probeTick(false);
        System.out.println("  设成 " + di.width + "x" + di.height
                + " 后 → 字号 " + inl.probeFontSizeNow());
        System.out.println("  状态: " + inl.probeState());
    }
}
