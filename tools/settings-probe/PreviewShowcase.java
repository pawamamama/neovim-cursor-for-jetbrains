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
        for (int v = 0; v < VARIANTS.length; v++) {
            int w = VARIANTS[v][0], h = VARIANTS[v][1];
            AnimationPreview a = AnimationPreview.inline(new NeovideConfig());
            AnimationPreview b = AnimationPreview.cross(new NeovideConfig());
            root.add(section(NAMES[v] + "  " + w + "x" + h, a, b, w, h));
        }
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

    private static JPanel section(String title, AnimationPreview a, AnimationPreview b, int w, int h) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(0x2B2D30));
        p.setBorder(BorderFactory.createEmptyBorder(10, 14, 14, 14));
        JLabel lbl = new JLabel(title + "   (单行窗 2 倍宽 = 跨行窗)");
        lbl.setForeground(new Color(0xD0D0D0));
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        p.add(lbl, BorderLayout.NORTH);
        // 【修复 3：组件被压成 1x1】
        //
        // GridBagLayout + fill=NONE 时组件不展开，实测每个 Preview 只有 1x1 → 全空。
        // FlowLayout 又会压扁高度。
        //
        // 改用【绝对定位】—— 这是唯一能精确控制「每个组件拿多少像素」的方式。
        // 布局管理器的各种约束在这里全是干扰。
        int rowW = w + w / 2 + 10;
        JPanel row = new JPanel(null);          // null layout = 绝对定位
        row.setOpaque(false);
        row.setPreferredSize(new Dimension(rowW, h));
        row.setMinimumSize(new Dimension(rowW, h));

        a.setBounds(0, 0, w, h);
        b.setBounds(w + 10, 0, w / 2, h);
        row.add(a);
        row.add(b);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(row, BorderLayout.CENTER);
        wrap.setPreferredSize(new Dimension(rowW, h));
        wrap.setMinimumSize(new Dimension(rowW, h));
        p.add(wrap, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(rowW + 28, h + 40));
        return p;
    }

    private static void snapshot() throws Exception {
        File dir = new File("build/preview-snap");
        dir.mkdirs();
        for (int v = 0; v < VARIANTS.length; v++) {
            int w = VARIANTS[v][0], h = VARIANTS[v][1];
            AnimationPreview inl = AnimationPreview.inline(new NeovideConfig());
            inl.setSize(w, h);
            // 打到 12 个字符（打完但还没删的时刻）
            for (int i = 0; i < 150; i++) inl.probeTick(i % 19 == 0);
            System.out.println("  v" + v + " (" + w + "x" + h + ") " + inl.probeState());
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
