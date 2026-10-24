import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.github.yyyolo7a79.neovidecursor.ui.settings.AnimationPreview;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 预览窗展示器 —— 三版尺寸对比 + 独立窗口（不启动 IDEA 也能看）。 */
public final class PreviewShowcase {

    /**
     * 展示模式。
     *
     * <p><b>0.9.44 改</b>：不再用固定尺寸传参 —— 那样看不出「面板实际长什么样」。
     * 现在直接用组件自己的 {@code getPreferredSize()}，与设置页里完全一致。
     */
    private static final String[] NAMES = {"单行输入输出", "多行缎带"};

    public static void main(String[] args) throws Exception {
        System.out.println("[1] main 开始 headless="
                + java.awt.GraphicsEnvironment.isHeadless());

        if (args.length > 0 && "--snap".equals(args[0])) { snapshot(); return; }

        // 【必须用 invokeAndWait + 显式打印异常】
        // 之前用 invokeLater，EDT 里的异常被吞掉，表现为「跑了但没窗口」。
        SwingUtilities.invokeAndWait(() -> {
            try {
                showWindow();
                System.out.println("[2] 窗口已 setVisible");
            } catch (Throwable t) {
                System.out.println("[X] showWindow 失败: " + t);
                t.printStackTrace();
            }
        });
        System.out.println("[3] main 结束（EDT 仍在跑）");
    }

    private static void showWindow() {
        JFrame f = new JFrame("Neovide Cursor - 预览窗三版对比");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(new Color(0x2B2D30));
        // 两个独立区块，各自用 preferredSize（与设置页一致）
        root.add(section("单行输入输出（打字 / 退格）",
                AnimationPreview.inline(new NeovideConfig()), 520));
        root.add(section("多行缎带（换行 / 跳转）",
                AnimationPreview.cross(new NeovideConfig()), 300));
        f.setContentPane(root);
        f.pack();
        f.setLocationRelativeTo(null);
        // 【置顶 + 抢焦点】之前窗口开在屏幕中间但被 IDEA 挡住，
        // 用户以为「没出来」。这三行确保一定看得见。
        f.setAlwaysOnTop(true);
        f.setVisible(true);
        f.toFront();
        f.requestFocus();
        f.repaint();
        // 3 秒后取消置顶，不干扰后续操作
        new javax.swing.Timer(3000, e -> f.setAlwaysOnTop(false)) {{ setRepeats(false); }}.start();
        System.out.println("[2a] frame bounds=" + f.getBounds() + " visible=" + f.isVisible());

        // 【诊断】2.5 秒后打印每个预览窗的真实尺寸与状态
        new javax.swing.Timer(2500, e -> {
            System.out.println("--- 诊断（2.5s 后）---");
            dump(f, 0);
        }) {{ setRepeats(false); }}.start();
    }

    private static void dump(java.awt.Container c, int depth) {
        if (c instanceof AnimationPreview) {
            AnimationPreview ap = (AnimationPreview) c;
            System.out.println("    ".repeat(depth) + "Preview " + ap.getWidth() + "x" + ap.getHeight()
                    + "  " + ap.probeState());
        }
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof java.awt.Container) {
                dump((java.awt.Container) k, depth + 1);
            }
        }
    }

    private static JPanel section(String title, AnimationPreview a, int w) {
        java.awt.Dimension ps = a.getPreferredSize();   // ← 用组件自己算的
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(0x2B2D30));
        p.setBorder(BorderFactory.createEmptyBorder(10, 14, 14, 14));
        JLabel lbl = new JLabel(title + "   " + ps.width + "×" + ps.height
                + "   字号自适应");
        lbl.setForeground(new Color(0xD0D0D0));
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        p.add(lbl, BorderLayout.NORTH);

        a.setPreferredSize(ps);
        a.setMinimumSize(ps);
        JPanel holder = new JPanel(null);
        holder.setOpaque(false);
        holder.setPreferredSize(ps);
        a.setBounds(0, 0, ps.width, ps.height);
        holder.add(a);
        p.add(holder, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(ps.width + 28, ps.height + 40));
        return p;
    }
    /** 不开窗，直接渲染两个预览窗（用它们自己的 preferredSize） */
    private static void snapshot() throws Exception {
        File dir = new File("build/preview-snap");
        dir.mkdirs();

        AnimationPreview inl = AnimationPreview.inline(new NeovideConfig());
        java.awt.Dimension di = inl.getPreferredSize();
        inl.setSize(di.width, di.height);
        for (int i = 0; i < 150; i++) {
            inl.probeTick(i % 19 == 0);
        }
        System.out.println("  单行 " + di.width + "x" + di.height + "  " + inl.probeState());
        save(inl, new File(dir, "single.png"));

        AnimationPreview crs = AnimationPreview.cross(new NeovideConfig());
        java.awt.Dimension dc = crs.getPreferredSize();
        crs.setSize(dc.width, dc.height);
        for (int i = 0; i < 60; i++) {
            crs.probeTick(i % 12 == 0);
        }
        System.out.println("  跨行 " + dc.width + "x" + dc.height + "  " + crs.probeState());
        save(crs, new File(dir, "cross.png"));

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
