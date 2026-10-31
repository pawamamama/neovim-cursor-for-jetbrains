package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置页顶行右侧的插件信息区 —— 简介 + GitHub 跳转 + 检查更新。
 *
 * <p><b>用户要求原文</b>：「顶行右边有点空 建议这边打个广告 简单介绍一下插件
 * 然后弄个github跳转 检查更新」。
 *
 * <h2>为什么「检查更新」要自己实现</h2>
 *
 * <p>插件是<b>通过 jar 手动安装</b>的（不走 JetBrains Marketplace），
 * 所以 IDEA 自带的插件更新机制对我们无效 —— 它只查市场。
 *
 * <p>做法：请求 GitHub Releases API 的 {@code /releases/latest}，
 * 比对 {@code tag_name} 与当前 {@link #currentVersion()}，有新版就把
 * 「检查更新」按钮变成可点击的「有新版本 vX.Y.Z」链接。
 *
 * <p><b>完全异步</b>：网络请求在后台线程，绝不阻塞 EDT。
 *
 * <p><b>失败不静默（P-M-4，2026-10-31）</b>：原来失败分支把按钮文字复位成「检查更新」，
 * 与点击前完全一致 —— 用户分不清「没网 / 超时 / 仓库没 Release」和「按钮坏了」。
 * 现在按钮变成「检查失败，点击重试」，tooltip 写完整原因，另记一条 warn。
 * <b>不弹模态框</b>：这是常驻控件，每次断网都弹框会打断设置流程；
 * 按钮文字本身就是一行可见提示（探针可断言 {@link #resolveOutcome}）。
 */
final class PluginInfoBar {

    private static final Logger LOG = Logger.getInstance(PluginInfoBar.class);

    /** 仓库（唯一真值来源） */
    private static final String REPO = "pawamamama/neovim-cursor-for-jetbrains";
    private static final String RELEASES_URL = "https://github.com/" + REPO + "/releases";
    private static final String API_LATEST =
            "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final Color LINK = new Color(0x589DF6);
    private static final Color DIM = new Color(0x8A8A8A);

    private PluginInfoBar() {
    }

    /**
     * 构建整块信息区（右侧对齐）。
     *
     * @param currentVersion 当前插件版本（由调用方从 plugin.xml 读）
     */
    static @NotNull JComponent create(@NotNull String currentVersion) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        p.setOpaque(false);

        // ---- 简介（一行，简短） ----
        JLabel intro = new JLabel(
                "Neovide 风格平滑光标 · 弹簧拖尾 · 多光标 · 块选区");
        intro.setForeground(DIM);
        intro.setFont(intro.getFont().deriveFont(Font.PLAIN, 11f));
        p.add(intro);

        // ---- GitHub ----
        JLabel gh = link("GitHub", RELEASES_URL, "在浏览器中打开项目主页");
        p.add(gh);

        // ---- 检查更新 ----
        JButton check = new JButton("检查更新");
        check.setBorderPainted(false);
        check.setContentAreaFilled(false);
        check.setFocusPainted(false);
        check.setForeground(LINK);
        check.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        check.setToolTipText("当前版本 v" + currentVersion + "，点击检查 GitHub 上是否有新版");
        p.add(check);

        check.addActionListener(e -> {
            check.setEnabled(false);
            check.setText("检查中…");
            // 【必须异步】网络请求不能跑在 EDT 上，否则设置页会卡住
            ApplicationManager.getApplication().executeOnPooledThread(() ->
                    doCheck(currentVersion, check));
        });

        return p;
    }

    /** 后台线程：请求 GitHub API，回来后切回 EDT 更新按钮 */
    private static void doCheck(String currentVersion, JButton check) {
        String latest = null;
        String error = null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_LATEST).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "neovide-cursor-plugin");

            int code = conn.getResponseCode();
            if (code == 200) {
                // 【自检发现】原来 getInputStream().readAllBytes() 没关闭流，
                // 每点一次「检查更新」泄漏一个连接。用 try-with-resources 兜住。
                byte[] buf;
                try (java.io.InputStream in = conn.getInputStream()) {
                    buf = in.readAllBytes();
                }
                String body = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                if (m.find()) {
                    latest = m.group(1);
                } else {
                    // 【自检发现】原来这里不设 error，
                    // 导致 tooltip 显示「检查失败：null」。给个明确原因。
                    error = "响应里没有 tag_name（API 格式可能变了）";
                }
            } else if (code == 404) {
                error = "仓库暂无 Release";
            } else {
                error = "HTTP " + code;
            }
        } catch (java.net.SocketTimeoutException e) {
            // 【P-M-4】超时必须与「没网」「版本相同」区分开：
            // 本机代理经常对 GitHub 失效（CLAUDE.md 已记录），超时是最常见的失败形态。
            error = "网络超时（4 秒无响应）";
        } catch (java.net.UnknownHostException | java.net.ConnectException e) {
            error = "无法连接 GitHub（网络或代理不可用）";
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        final String latestTag = latest;
        final String err = error;
        if (latestTag == null) {
            // 【P-M-4】失败路径原来零日志；补一条后台线程日志，便于用户抓 idea.log。
            try {
                LOG.warn("neovide-cursor: 检查更新失败：" + err);
            } catch (Throwable ignored) {
                // 日志子系统不可用：不影响下面的可见提示
            }
        }
        SwingUtilities.invokeLater(() -> {
            check.setEnabled(true);
            Outcome o = resolveOutcome(latestTag, currentVersion, err);
            check.setText(o.text);
            check.setToolTipText(o.tooltip);
            if (o.downloadable) {
                for (java.awt.event.ActionListener l : check.getActionListeners()) {
                    check.removeActionListener(l);
                }
                check.addActionListener(e ->
                        openBrowser(RELEASES_URL + "/tag/" + latestTag));
            }
        });
    }

    /**
     * 「检查结果 → 按钮文案 / tooltip / 是否换成下载链接」的<b>唯一</b>映射（P-M-4）。
     *
     * <p><b>为什么抽成纯函数</b>：失败文案要能被探针离线断言，而不是只能靠实机看。
     * 探针调用本方法，生产也调用本方法 —— 不存在「测的是一份、跑的是另一份」（陷阱 3）。
     */
    static Outcome resolveOutcome(String latestTag, String currentVersion, String error) {
        if (latestTag == null) {
            // 【P-M-4 的核心修复】原来这里把文字复位成「检查更新」，
            // 与点击前完全一致 —— 用户看到的是「点了、转了一下、什么都没变」，
            // 分不清「没网」「超时」「没 Release」和「按钮坏了」。
            String reason = (error == null || error.isEmpty()) ? "未知原因" : error;
            return new Outcome("检查失败，点击重试",
                    "检查失败：" + reason + "（可点 GitHub 手动查看）", false);
        }
        if (compareVersion(latestTag, currentVersion) > 0) {
            return new Outcome("有新版本 " + latestTag, "点击前往下载", true);
        }
        return new Outcome("已是最新 (v" + currentVersion + ")", "GitHub 最新：" + latestTag, false);
    }

    /** {@link #resolveOutcome} 的结果。字段包级可见，探针直接读。 */
    static final class Outcome {
        final String text;
        final String tooltip;
        final boolean downloadable;

        Outcome(String text, String tooltip, boolean downloadable) {
            this.text = text;
            this.tooltip = tooltip;
            this.downloadable = downloadable;
        }
    }

    /**
     * 版本比较：0.9.43 vs v0.9.33 → 正数表示前者新。
     *
     * <p>只比较数字段，忽略前缀 v、后缀 -diag 等。
     */
    static int compareVersion(String a, String b) {
        int[] pa = parseVersion(a);
        int[] pb = parseVersion(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) {
                return pa[i] - pb[i];
            }
        }
        return 0;
    }

    private static int[] parseVersion(String v) {
        int[] out = new int[3];
        if (v == null) {
            return out;
        }
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(v);
        if (m.find()) {
            for (int i = 0; i < 3; i++) {
                try {
                    out[i] = Integer.parseInt(m.group(i + 1));
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** 可点击的链接式 JLabel */
    private static JLabel link(String text, String url, String tip) {
        JLabel l = new JLabel(text);
        l.setForeground(LINK);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setToolTipText(tip);
        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openBrowser(url);
            }
        });
        return l;
    }

    /** 打开浏览器；IDEA 里用 BrowserUtil，独立环境退回 Desktop */
    private static void openBrowser(String url) {
        try {
            BrowserUtil.browse(url);
        } catch (Throwable t) {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI.create(url));
                }
            } catch (Throwable ignored) {
                LOG.info("neovide-cursor: 无法打开浏览器 " + url);
            }
        }
    }
}
