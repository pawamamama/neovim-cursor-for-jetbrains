import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 预览窗展示器 —— 三版尺寸对比 + 独立窗口（不启动 IDEA 也能看）。 */
public final class PreviewShowcase {

    /** 三版候选：宽 × 高 */
    private static final int[][] VARIANTS = {{440, 120}, {520, 200}, {640, 260}};
    private static final String[] NAMES = {"A-紧凑", "B-中等", "C-大"};

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--snap".equals(args[0])) { snapshot(); return; }
        SwingUtilities.invokeLater(PreviewShowcase::showWindow);
    }

    private static void showWindow() {
        JFrame f = new JFrame("Neovide Cursor - 预览窗三版对比");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(new Color(0x2B2D30));
        for (int v = 0; v < VARIANTS.length; v++) {
            int w = VARIANTS[v][0], h = VARIANTS[v][1];
            AnimationPreview a = AnimationPreview.inline(new NeovideConfig());
            AnimationPreview b = AnimationPreview.cross(new NeovideConfig());
            root.add(section(NAMES[v] + "  " + w + "x" + h, a, b, w, h));
        }
        f.setContentPane(root);
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    private static JPanel section(String title, AnimationPreview a, AnimationPreview b, int w, int h) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(0x2B2D30));
        p.setBorder(BorderFactory.createEmptyBorder(10, 14, 14, 14));
        JLabel lbl = new JLabel(title);
        lbl.setForeground(new Color(0xD0D0D0));
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        p.add(lbl, BorderLayout.NORTH);
        JPanel row = new JPanel(new GridLayout(1, 2, 10, 0));
        row.setOpaque(false);
        a.setPreferredSize(new Dimension(w / 2 - 5, h));
        b.setPreferredSize(new Dimension(w / 2 - 5, h));
        row.add(a);
        row.add(b);
        p.add(row, BorderLayout.CENTER);
        return p;
    }

    private static void snapshot() throws Exception {
        File dir = new File("build/preview-snap");
        dir.mkdirs();
        for (int v = 0; v < VARIANTS.length; v++) {
            int w = VARIANTS[v][0], h = VARIANTS[v][1];
            AnimationPreview inl = AnimationPreview.inline(new NeovideConfig());
            inl.setSize(w, h);
            for (int i = 0; i < 40; i++) inl.probeTick(i % 12 == 0);
            System.out.println("  v" + v + " inline: " + inl.probeState());
            save(inl, new File(dir, "v" + v + "-inline.png"));
            AnimationPreview crs = AnimationPreview.cross(new NeovideConfig());
            crs.setSize(w, h);
            for (int i = 0; i < 30; i++) crs.probeTick(i % 12 == 0);
            save(crs, new File(dir, "v" + v + "-cross.png"));
        }
        System.out.println("snapshots -> " + dir.getAbsolutePath());
    }

    private static void save(AnimationPreview comp, File out) throws Exception {
        BufferedImage img = new BufferedImage(comp.getWidth(), comp.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        comp.paint(g);
        g.dispose();
        ImageIO.write(img, "png", out);
    }
}
