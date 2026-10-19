package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import org.jetbrains.annotations.NotNull;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Point;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IME 组合态几何探针 —— 真机取证用（<b>不是</b>单元测试，不修改任何编辑器状态）。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>{@code BUG-REPORT-CJK-IME.md} §7.3 明确记着「未在运行时抓到 vp 的实际取值」。
 * 而本次拟采用的判据 ——
 *
 * <pre>editor.offsetToXY(offset).x  !=  editor.visualPositionToXY(vp).x  ⇒ 视为中间态</pre>
 *
 * <p>是否成立，<b>完全取决于平台这两个 API 在水杉输入法组合期间是否真的给出不同答案</b>。
 * 读字节码只能提出假说，不能定案 —— 必须在装了水杉的真机上打印出来。
 *
 * <h3>它同时回答两个问题（缺一不可）</h3>
 *
 * <ol>
 *   <li><b>正向</b>：组合期间 {@code p.x != q.x} 是否真的发生？差值是否 ≈ 半个字宽？</li>
 *   <li><b>反向（更关键）</b>：<b>正常场景下是否也会</b> {@code p.x != q.x}？
 *       若会，这个判据就会像批次2 的 F1-b 一样把正常帧也拦掉（F-3 同类缺陷）。
 *       已知的可疑来源：{@code CaretImpl.myVisualColumnAdjustment} —— 平台在
 *       <b>任何</b>「不依附前文的内联 inlay」处都会修正 visual column，
 *       而参数名提示（inlay hints）正是这类 inlay。本探针因此同时记录 inlay 数量。</li>
 * </ol>
 *
 * <h3>输出</h3>
 *
 * <p>{@code %TEMP%\neovide-ime-probe.log}（UTF-8，每行一条，字段 {@code |} 分隔）。
 * 三种行：
 * <ul>
 *   <li>{@code # ...} —— 头部/环境信息</li>
 *   <li>{@code SAMPLE ...} —— 一个"位置状态"被首次观察到的采样</li>
 *   <li>{@code >>> ...} —— 命中可疑条件的告警行（便于 {@code Select-String '>>>'} 一次捞全）</li>
 * </ul>
 *
 * <h3>安全性</h3>
 *
 * <p>只读：不插入/删除文本、不改配色方案、不动任何 {@code Editor} 状态。
 * 唯一的写入是它自己的日志文件。合成 inlay 自检会 <b>创建并立即释放一个独立的临时编辑器</b>，
 * 与用户正在编辑的文件完全无关。
 */
public final class ImeGeomProbe implements CaretListener, EditorFactoryListener {

    private static final String LOG_PATH =
            System.getProperty("java.io.tmpdir") + File.separator + "neovide-ime-probe.log";

    /** 采样间隔（ms）。与生产调度同频（ACTIVE_DELAY_MS=8），足以覆盖组合期间每一帧。 */
    private static final int SAMPLE_MS = 8;

    /** 日志行数上限，防止长时间挂机把磁盘写满（超过后停止追加 SAMPLE，仅保留告警） */
    private static final int MAX_LINES = 400_000;

    /** 心跳间隔（ms）：即使用户不输入也每 2s 记一行，证明探针还活着 */
    private static final long HEARTBEAT_MS = 2_000L;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static volatile ImeGeomProbe listener;

    private static PrintWriter log;

    private static int lineCount;

    private static long lastHeartbeat;

    private static long sampleCount;

    private static long divergeCount;

    private static long offBoundaryCount;

    private static long composeLikeCount;

    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    /** 上一帧观察到的 key（每个编辑器一份），用于只记"变化"的帧 */
    private static final java.util.Map<Integer, String> LAST_KEY = new java.util.concurrent.ConcurrentHashMap<>();

    private ImeGeomProbe() {
    }

    // ==================== 启动 ====================

    /** 由 {@code ImeProbeStartup} 在项目打开后调用一次；重复调用无副作用。 */
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            log = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(LOG_PATH, true), StandardCharsets.UTF_8)), true);
        } catch (IOException e) {
            System.err.println("[ime-probe] 无法打开日志 " + LOG_PATH + " : " + e);
            return;
        }

        header();

        try {
            EditorFactory factory = EditorFactory.getInstance();
            // 已有编辑器先挂上；之后新建的靠 listener
            for (Editor editor : factory.getAllEditors()) {
                attach(editor);
            }
            factory.addEditorFactoryListener(listener(), ApplicationManager.getApplication());
        } catch (Throwable t) {
            emit("# 挂载 editorFactoryListener 失败: " + t);
        }

        Thread sampler = new Thread(ImeGeomProbe::sampleLoop, "neovide-ime-probe");
        sampler.setDaemon(true);
        sampler.start();

        // 自检：在后台跑一次（它会创建临时编辑器，不能占用启动线程）
        Thread selfTest = new Thread(ImeGeomProbe::runSelfTest, "neovide-ime-probe-selftest");
        selfTest.setDaemon(true);
        selfTest.start();

        System.err.println("[ime-probe] 已启动，日志 = " + LOG_PATH);
    }

    private static void header() {
        emit("# ================================================================");
        emit("# Neovide IME 几何探针  启动于 " + new Date());
        emit("# 日志 = " + LOG_PATH);
        try {
            emit("# IDE = " + ApplicationInfo.getInstance().getBuild()
                    + " / " + ApplicationInfo.getInstance().getFullVersion());
        } catch (Throwable ignored) {
            emit("# IDE = <取不到>");
        }
        emit("# Registry editor.input.method.inlay = " + regAsBool("editor.input.method.inlay"));
        emit("#   （true = 平台用 inline inlay 承载组合串；false = 直接写进 document）");
        emit("# Registry editor.input.method.inlay 是否被用户改过 = "
                + regChanged("editor.input.method.inlay"));
        emit("#");
        emit("# 判据说明：");
        emit("#   p = editor.visualPositionToXY(getVisualPosition())  ← 现行代码用的坐标源");
        emit("#   q = editor.offsetToXY(getOffset())                 ← 拟用的几何基准");
        emit("#   dx = p.x - q.x        dx!=0 就是「两个 API 意见不一致」");
        emit("#   onBoundary = p.x 是否落在该行的字形边界上（用 FontMetrics 独立算的第二意见）");
        emit("#   visualColAdj = 反射读 CaretImpl.myVisualColumnAdjustment");
        emit("#                  （平台在「不依附前文的内联 inlay」处修正 visual column 的量，");
        emit("#                    参数名提示/inlay hints 也算——这是本判据最大的误伤风险）");
        emit("#");
        emit("# 用法：只管用中文输入法正常打字，乱打、多打、停在候选上、按方向键都行。");
        emit("#       然后把本文件发回即可。");
        emit("# ================================================================");
        emit("");
    }

    private static ImeGeomProbe listener() {
        ImeGeomProbe local = listener;
        if (local == null) {
            synchronized (ImeGeomProbe.class) {
                if (listener == null) {
                    listener = new ImeGeomProbe();
                }
                local = listener;
            }
        }
        return local;
    }

    private static void attach(Editor editor) {
        try {
            editor.getCaretModel().addCaretListener(listener());
        } catch (Throwable ignored) {
            // 编辑器可能正在销毁，忽略
        }
    }

    // ==================== CaretListener ====================

    @Override
    public void caretPositionChanged(@NotNull CaretEvent event) {
        // 事件路径上不做重活（平台要求它便宜）：只记一行事件，坐标由采样线程读
        try {
            Editor editor = event.getEditor();
            VisualPosition vp = editor.getCaretModel().getVisualPosition();
            LogicalPosition lp = editor.getCaretModel().getLogicalPosition();
            emit("EVENT t=" + TS.format(new Date())
                    + " off=" + editor.getCaretModel().getOffset()
                    + " vp=" + vp.line + ":" + vp.column
                    + " lp=" + lp.line + ":" + lp.column
                    + " ed=" + System.identityHashCode(editor));
        } catch (Throwable ignored) {
            // 事件期间编辑器状态可能非法
        }
    }

    // ==================== EditorFactoryListener ====================

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        attach(event.getEditor());
    }

    // ==================== 高频采样 ====================

    private static void sampleLoop() {
        while (true) {
            try {
                Thread.sleep(SAMPLE_MS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                ApplicationManager.getApplication().invokeLater(ImeGeomProbe::sampleAll);
            } catch (Throwable ignored) {
                // 应用正在关闭
            }
        }
    }

    /** 在 EDT 上采样所有可见编辑器（坐标 API 要求 EDT） */
    private static void sampleAll() {
        long now = System.currentTimeMillis();
        try {
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                try {
                    sampleEditor(editor);
                } catch (Throwable ignored) {
                    // 单个编辑器失败不影响其它
                }
            }
        } catch (Throwable ignored) {
            // 全局失败
        }

        if (now - lastHeartbeat >= HEARTBEAT_MS) {
            lastHeartbeat = now;
            emit("HEARTBEAT t=" + TS.format(new Date())
                    + " samples=" + sampleCount
                    + " dx!=0=" + divergeCount
                    + " offBoundary=" + offBoundaryCount
                    + " composeLike=" + composeLikeCount);
        }
    }

    private static void sampleEditor(Editor editor) {
        if (editor == null || editor.isDisposed()) {
            return;
        }
        if (!editor.getContentComponent().isShowing()) {
            return;
        }

        int off = editor.getCaretModel().getOffset();
        VisualPosition vp = editor.getCaretModel().getVisualPosition();

        // 只记"状态变化"的帧：8ms 同频下绝大多数帧完全相同
        int edId = System.identityHashCode(editor);
        String key = off + "|" + vp.line + "|" + vp.column + "|" + vp.leansRight;
        String prev = LAST_KEY.get(edId);
        boolean inlayAtCaret = hasInlineInlayAt(editor, vp);
        if (key.equals(prev) && !inlayAtCaret) {
            return;
        }
        LAST_KEY.put(edId, key);

        Point p = editor.visualPositionToXY(vp);
        Point q = editor.offsetToXY(off);
        int dx = p.x - q.x;

        sampleCount++;

        // 该行的字形边界（独立于平台 API 的第二意见）
        BoundaryInfo boundary = measureBoundary(editor, off, p.x);

        // 光标附近的 inline inlay（组合串 / inlay hints 都在这）
        int inlayNearCaret = 0;
        StringBuilder inlayOffsets = new StringBuilder();
        try {
            Document doc = editor.getDocument();
            int from = Math.max(0, off - 64);
            int to = Math.min(doc.getTextLength(), off + 64);
            List<Inlay<?>> inlays = editor.getInlayModel().getInlineElementsInRange(from, to);
            inlayNearCaret = inlays.size();
            for (Inlay<?> inlay : inlays) {
                if (inlayOffsets.length() > 0) {
                    inlayOffsets.append(',');
                }
                inlayOffsets.append(inlay.getOffset());
                if (inlay.getOffset() == off) {
                    inlayOffsets.append('*');   // * = 正好在光标 offset 上
                }
            }
        } catch (Throwable ignored) {
            // inlay 模型不可用
        }

        String adjust = reflectVisualColumnAdjustment(editor);

        StringBuilder sb = new StringBuilder(256);
        sb.append("SAMPLE t=").append(TS.format(new Date()))
                .append(" ed=").append(edId)
                .append(" off=").append(off)
                .append(" vp=").append(vp.line).append(':').append(vp.column)
                .append(vp.leansRight ? "R" : "L")
                .append(" p=").append(p.x).append(',').append(p.y)
                .append(" q=").append(q.x).append(',').append(q.y)
                .append(" dx=").append(dx)
                .append(' ').append(boundary.text)
                .append(" inlays=").append(inlayNearCaret)
                .append(" inlayOffsets=[")
                .append(inlayOffsets).append(']')
                .append(' ').append(adjust);
        emit(sb.toString());

        // ---- 分类计数与告警 ----
        if (dx != 0) {
            divergeCount++;
            emit(">>> DX_NONZERO dx=" + dx
                    + " off=" + off + " vp=" + vp.line + ":" + vp.column
                    + " p.x=" + p.x + " q.x=" + q.x
                    + " inlays=" + inlayNearCaret + " " + adjust
                    + " —— 两个 API 意见不一致，判据会判为中间态");
        }
        if (boundary.known && !boundary.onBoundary) {
            offBoundaryCount++;
            emit(">>> OFF_BOUNDARY p.x=" + p.x
                    + " off=" + off + " vp=" + vp.line + ":" + vp.column
                    + " expectedX=" + boundary.expectedX
                    + " charCount=" + boundary.charCount
                    + " —— 「卡在半个字形」的像素级证据");
        }
        // 「像组合态」= 有 inlay 且 dx!=0 或不在边界
        if (inlayNearCaret > 0 && (dx != 0 || (boundary.known && !boundary.onBoundary))) {
            composeLikeCount++;
        }
    }

    // ==================== 合成自检（无需输入法） ====================

    /**
     * 合成自检：在<b>独立临时编辑器</b>里放一个 inline inlay（模拟组合串 / inlay hint），
     * 测量 {@code offsetToXY} 与 {@code visualPositionToXY} 是否分歧。
     *
     * <p>价值：<b>不需要用户装水杉、不需要打字</b>，就能在真机平台上回答
     * 「这个判据会不会误伤正常场景」。若这里 dx 就非 0，说明 inlay hints 等
     * 正常功能会让判据误判 —— 那判据必须加条件，不能直接用。
     *
     * <p>全程 try/catch：拿不到编辑器就如实写「跳过」，不谎报。
     */
    private static void runSelfTest() {
        emit("");
        emit("# ---------------- 合成自检（不依赖输入法） ----------------");

        Editor editor = null;
        Document doc = null;
        try {
            // 等平台完成初始化（启动线程跑得太早会拿不到 EditorFactory 的完整上下文）
            Thread.sleep(3_000);

            ApplicationManager.getApplication().invokeAndWait(() -> { });
            final Document[] docRef = new Document[1];
            final Editor[] edRef = new Editor[1];
            ApplicationManager.getApplication().invokeAndWait(() -> {
                // 含中日韩字符的一行，模拟真实中文行
                docRef[0] = EditorFactory.getInstance().createDocument("中文测试abc中文测试\n第二行");
                edRef[0] = EditorFactory.getInstance().createEditor(docRef[0]);
            });
            doc = docRef[0];
            editor = edRef[0];
            if (editor == null) {
                emit("# 自检：createEditor 返回 null，跳过");
                return;
            }

            final Editor ed = editor;
            ApplicationManager.getApplication().invokeAndWait(() -> {
                try {
                    probeSynthetic(ed, "无 inlay（纯文本基线）", null);
                } catch (Throwable t) {
                    emit("# 自检基线失败: " + t);
                }
            });

            // 在 offset=2（「中」与「文」之间）放一个不依附前文的 inline inlay，
            // 这正是平台 CaretImpl.myVisualColumnAdjustment 会介入的情形。
            for (final int at : new int[]{2, 4}) {
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    Inlay<?> inlay = null;
                    try {
                        inlay = ed.getInlayModel().addInlineElement(
                                at, false, new FixedWidthRenderer(40));
                        probeSynthetic(ed, "在 offset=" + at + " 放 40px 内联 inlay（模拟组合串/hint）", at);
                    } catch (Throwable t) {
                        emit("# 自检(offset=" + at + ")失败: " + t);
                    } finally {
                        // 立刻清掉，避免污染
                        try {
                            if (inlay != null) {
                                inlay.dispose();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }

            emit("# ---------------- 合成自检结束 ----------------");
            emit("");
        } catch (Throwable t) {
            emit("# 自检整体跳过（" + t.getClass().getSimpleName() + ": " + t.getMessage() + "）");
        } finally {
            final Editor ed = editor;
            if (ed != null) {
                try {
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        try {
                            EditorFactory.getInstance().releaseEditor(ed);
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 在给定编辑器上，把光标放到若干 offset 处测量 p/q（只读：只移动我们自己临时编辑器的光标） */
    private static void probeSynthetic(Editor editor, String label, Integer inlayAt) {
        emit("# 自检场景：" + label);
        try {
            Document doc = editor.getDocument();
            int limit = Math.min(doc.getTextLength(), 8);
            for (int off = 0; off <= limit; off++) {
                editor.getCaretModel().moveToOffset(off);
                VisualPosition vp = editor.getCaretModel().getVisualPosition();
                Point p = editor.visualPositionToXY(vp);
                Point q = editor.offsetToXY(off);
                int dx = p.x - q.x;
                LogicalPosition lp = editor.getCaretModel().getLogicalPosition();
                emit(String.format(Locale.ROOT,
                        "#   自检 off=%-2d lp=%d:%-2d vp=%d:%-2d p.x=%-5d q.x=%-5d dx=%-4d %s",
                        off, lp.line, lp.column, vp.line, vp.column, p.x, q.x, dx,
                        dx != 0 ? "<<< 分歧" : ""));
            }
        } catch (Throwable t) {
            emit("#   自检测量失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /** 固定宽度的合成 inlay 渲染器（只是为了让平台量出一段可见宽度） */
    private static final class FixedWidthRenderer
            implements com.intellij.openapi.editor.EditorCustomElementRenderer {
        private final int width;

        FixedWidthRenderer(int width) {
            this.width = width;
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            return width;
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull java.awt.Graphics2D g,
                          @NotNull java.awt.geom.Rectangle2D targetRegion,
                          @NotNull com.intellij.openapi.editor.markup.TextAttributes attrs) {
            // 不需要真的画出来
        }
    }

    // ==================== 测量工具 ====================

    /** 字形边界测量的结果 */
    private static final class BoundaryInfo {
        boolean known;
        boolean onBoundary;
        int expectedX;
        int charCount;
        String text = "";
    }

    /**
     * 用 {@link FontMetrics} 独立算出该行的字形边界，判断 {@code px} 是否落在其中之一。
     *
     * <p>这是<b>独立于平台坐标 API 的第二意见</b>：即使两个 API 都出错，
     * 我们仍能知道「这个 x 到底是不是某个字符的起点」—— 也就是用户说的「半个字形」。
     *
     * <p>行首 x 用 {@code offsetToXY(lineStartOffset)} 取：行首永远在字符边界上，
     * 不会受组合串影响，可安全作基准。
     */
    private static BoundaryInfo measureBoundary(Editor editor, int off, int px) {
        BoundaryInfo info = new BoundaryInfo();
        try {
            Document doc = editor.getDocument();
            int line = doc.getLineNumber(off);
            int lineStart = doc.getLineStartOffset(line);
            int lineEnd = doc.getLineEndOffset(line);
            String text = doc.getText(new TextRange(lineStart, lineEnd));

            int lineStartX = editor.offsetToXY(lineStart).x;

            EditorColorsScheme scheme = editor.getColorsScheme();
            Font font = scheme.getFont(EditorFontType.PLAIN);
            FontMetrics fm = editor.getContentComponent().getFontMetrics(font);

            int acc = 0;
            int expectedRelative = 0;
            int col = off - lineStart;
            List<Integer> boundaries = new ArrayList<>();
            boundaries.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (i == col) {
                    expectedRelative = acc;
                }
                acc += fm.charWidth(text.charAt(i));
                boundaries.add(acc);
            }
            if (col >= text.length()) {
                expectedRelative = acc;
            }

            boolean onBoundary = false;
            for (int b : boundaries) {
                // 容差 1px：平台内部用 float 计算后再截断成 int
                if (Math.abs(lineStartX + b - px) <= 1) {
                    onBoundary = true;
                    break;
                }
            }

            info.known = true;
            info.onBoundary = onBoundary;
            info.expectedX = lineStartX + expectedRelative;
            info.charCount = boundaries.size();

            StringBuilder glyphs = new StringBuilder();
            int limit = Math.min(text.length(), 10);
            for (int i = 0; i < limit; i++) {
                if (i > 0) {
                    glyphs.append(' ');
                }
                glyphs.append(String.format(Locale.ROOT, "U+%04X=%d",
                        (int) text.charAt(i), fm.charWidth(text.charAt(i))));
            }
            info.text = String.format(Locale.ROOT,
                    "col=%d chars=%d onBoundary=%b expectedX=%d glyphs=[%s]",
                    col, boundaries.size(), onBoundary, info.expectedX, glyphs);
        } catch (Throwable t) {
            info.text = "onBoundary=? (err " + t.getClass().getSimpleName() + ")";
        }
        return info;
    }

    private static boolean hasInlineInlayAt(Editor editor, VisualPosition vp) {
        try {
            return editor.getInlayModel().hasInlineElementAt(vp);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 反射读 {@code CaretImpl.myVisualColumnAdjustment}。
     *
     * <p>这是本轮最关键的诊断量：它在 {@code CaretImpl.moveToLogicalPosition} 里被设为
     * {@code inlayAwareOffsetToVisualPosition(...).column - 原始 visual column}，
     * 也就是说 —— <b>只要光标处有不依附前文的内联 inlay，getVisualPosition() 就会偏离
     * offsetToVisualPosition(offset)</b>。这既可能是本 bug 的成因，
     * 也可能是本判据在正常场景下误伤的成因。两种可能都必须用数据区分。
     */
    private static String reflectVisualColumnAdjustment(Editor editor) {
        try {
            Object caret = editor.getCaretModel().getPrimaryCaret();
            java.lang.reflect.Field f = caret.getClass().getDeclaredField("myVisualColumnAdjustment");
            f.setAccessible(true);
            int v = f.getInt(caret);
            return "visualColAdj=" + v + (v != 0 ? " <<<" : "");
        } catch (Throwable t) {
            return "visualColAdj=?";
        }
    }

    // ==================== 日志 ====================

    private static synchronized void emit(String s) {
        if (log == null) {
            return;
        }
        boolean alarm = s.startsWith(">>>") || s.startsWith("#") || s.startsWith("EVENT")
                || s.startsWith("HEARTBEAT") || s.startsWith("SAMPLE");
        if (++lineCount > MAX_LINES && !alarm) {
            return;
        }
        log.println(s);
    }

    private static String regAsBool(String key) {
        try {
            RegistryValue v = Registry.get(key);
            return v == null ? "<null>" : String.valueOf(v.asBoolean());
        } catch (Throwable t) {
            return "<err " + t.getClass().getSimpleName() + ">";
        }
    }

    private static String regChanged(String key) {
        try {
            RegistryValue v = Registry.get(key);
            return v == null ? "<null>" : String.valueOf(v.isChangedFromDefault());
        } catch (Throwable t) {
            return "<err>";
        }
    }
}
