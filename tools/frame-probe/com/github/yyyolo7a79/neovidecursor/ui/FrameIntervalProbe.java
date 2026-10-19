package com.github.yyyolo7a79.neovidecursor.ui;

import com.github.yyyolo7a79.neovidecursor.core.NeovideConfig;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.editor.event.CaretEvent;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.InvocationEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * verifier 自建探针 F —— 帧<b>间隔</b>分布（p50 / p95 / p99 / maxGap），不是帧数。
 *
 * <p><b>为什么需要它</b>：{@code tools/probe} 的 RegimeProbe 只数「1500ms 里投递了多少帧」。
 * 平均帧率相同完全可能对应两种截然不同的手感：稳定的 8ms，或「一长串 1ms + 一次 200ms 卡顿」。
 * 用户抱怨的"卡"永远是后者。所以本探针捕获每帧的投递时刻，算<b>相邻帧间隔</b>的分位数。
 *
 * <p><b>怎么捕获</b>：往 AWT 事件队列里 push 一个 {@link EventQueue} 子类，在 {@code postEvent}
 * 里对 {@link InvocationEvent} 反射取 {@code runnable}，若该 Runnable 持有本 animator 则记一次时刻
 * （与 RegimeProbe 同一套归因手法，但记录时间戳而不是只计数）。
 *
 * <p>输出每轮一行 {@code ROUND ...}（机器可读），最后每场景一行 {@code SUMMARY ...}。
 */
public final class FrameIntervalProbe {

    static final AtomicInteger DROPPED = new AtomicInteger();
    static Field RUNNABLE_FIELD;
    static final List<long[]> SAMPLES = new ArrayList<>();   // 每项 = {nanos}
    static volatile CaretAnimator TARGET;

    static {
        try {
            RUNNABLE_FIELD = InvocationEvent.class.getDeclaredField("runnable");
            RUNNABLE_FIELD.setAccessible(true);
        } catch (Throwable t) {
            RUNNABLE_FIELD = null;
        }
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    static Editor fakeEditor(final JComponent content, final long tickSleepMs) {
        CaretModel model = (CaretModel) Proxy.newProxyInstance(
                FrameIntervalProbe.class.getClassLoader(),
                new Class<?>[]{CaretModel.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVisualPosition":
                            if (tickSleepMs > 0) {
                                Thread.sleep(tickSleepMs);
                            }
                            return new VisualPosition(0, 0);
                        case "getOffset":
                            return 0;
                        case "addCaretListener":
                        case "removeCaretListener":
                            return null;
                        case "getAllCarets":
                            return new ArrayList<>();
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        return (Editor) Proxy.newProxyInstance(
                FrameIntervalProbe.class.getClassLoader(),
                new Class<?>[]{Editor.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getContentComponent":
                            return content;
                        case "getEditorKind":
                            return EditorKind.MAIN_EDITOR;
                        case "getCaretModel":
                            return model;
                        case "getLineHeight":
                            return 32;
                        case "visualPositionToXY":
                            return new Point(10, 10);
                        case "offsetToXY":
                            return new Point(10, 10);
                        case "isDisposed":
                            return false;
                        case "toString":
                            return "FakeEditor";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    static void fireCaret(CaretAnimator a) {
        final Editor source = fakeEditor(new JPanel(), 0);
        Caret caret = (Caret) Proxy.newProxyInstance(
                FrameIntervalProbe.class.getClassLoader(),
                new Class<?>[]{Caret.class},
                (proxy, method, args) -> "getEditor".equals(method.getName())
                        ? source : defaultValue(method.getReturnType()));
        a.caretPositionChanged(new CaretEvent(caret, new LogicalPosition(0, 0), new LogicalPosition(0, 0)));
    }

    static CaretAnimator ownerOf(Object source) {
        if (!(source instanceof Runnable)) {
            return null;
        }
        for (Field f : source.getClass().getDeclaredFields()) {
            if (CaretAnimator.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(source);
                    if (v instanceof CaretAnimator) {
                        return (CaretAnimator) v;
                    }
                } catch (Throwable ignored) {
                    // 继续
                }
            }
        }
        return null;
    }

    /** 跑一轮，返回该轮的帧间隔样本（毫秒，已排序）。 */
    static double[] round(boolean alwaysHighFps, long tickSleepMs, long caretEveryMs, long windowMs) throws Exception {
        NeovideConfig cfg = new NeovideConfig();
        cfg.alwaysHighFps = alwaysHighFps;
        CaretAnimator a = new CaretAnimator(fakeEditor(new JPanel(), tickSleepMs), cfg);
        TARGET = a;
        SAMPLES.clear();
        SAMPLES.add(new long[]{System.nanoTime()});
        fireCaret(a);
        Thread.sleep(500);                       // 稳定期（不计入窗口）

        // 清空，从窗口起点重新记
        SAMPLES.clear();
        SAMPLES.add(new long[]{System.nanoTime()});

        final AtomicInteger stop = new AtomicInteger();
        Thread pump = null;
        if (caretEveryMs > 0) {
            pump = new Thread(() -> {
                while (stop.get() == 0) {
                    try {
                        Thread.sleep(caretEveryMs);
                    } catch (InterruptedException e) {
                        return;
                    }
                    fireCaret(a);
                }
            });
            pump.setDaemon(true);
            pump.start();
        }

        Thread.sleep(windowMs);
        stop.set(1);
        if (pump != null) {
            pump.interrupt();
        }
        TARGET = null;
        a.dispose();
        Thread.sleep(300);

        List<Long> stamps = new ArrayList<>();
        for (long[] s : new ArrayList<>(SAMPLES)) {
            stamps.add(s[0]);
        }
        stamps.sort(Long::compare);
        double[] gaps = new double[Math.max(0, stamps.size() - 1)];
        for (int i = 1; i < stamps.size(); i++) {
            gaps[i - 1] = (stamps.get(i) - stamps.get(i - 1)) / 1_000_000.0;
        }
        Arrays.sort(gaps);
        return gaps;
    }

    static double pct(double[] sorted, double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double idx = (sorted.length - 1) * p;
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sorted[lo];
        }
        double frac = idx - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    static String f2(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    public static void main(String[] args) throws Exception {
        int rounds = 3;
        long windowMs = 1500;
        if (args.length > 0) {
            rounds = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            windowMs = Long.parseLong(args[1]);
        }

        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
            @Override
            public void postEvent(AWTEvent e) {
                if (e instanceof InvocationEvent && RUNNABLE_FIELD != null && TARGET != null) {
                    try {
                        if (ownerOf(RUNNABLE_FIELD.get(e)) == TARGET) {
                            SAMPLES.add(new long[]{System.nanoTime()});
                        }
                    } catch (Throwable t) {
                        DROPPED.incrementAndGet();
                    }
                }
                super.postEvent(e);
            }
        });

        System.out.println("PROBE attribution=" + (RUNNABLE_FIELD == null ? "UNAVAILABLE" : "runnable-field-ok"));
        System.out.println("PROBE ACTIVE_DELAY_MS=" + CaretAnimator.ACTIVE_DELAY_MS);
        System.out.println("PROBE java=" + System.getProperty("java.version")
                + " rounds=" + rounds + " windowMs=" + windowMs);

        String[] names = {"R1_highfps_tick0", "R2_highfps_tick25", "R3_adaptive_tick0",
                "R4_adaptive_tick25", "R5_highfps_tick25_caret100"};
        boolean[] highFps = {true, true, false, false, true};
        long[] tickSleep = {0, 25, 0, 25, 25};
        long[] caretEvery = {0, 0, 0, 0, 100};

        for (int s = 0; s < names.length; s++) {
            double[][] all = new double[rounds][];
            for (int r = 0; r < rounds; r++) {
                double[] gaps = round(highFps[s], tickSleep[s], caretEvery[s], windowMs);
                all[r] = gaps;
                System.out.println("ROUND " + names[s]
                        + " r" + (r + 1)
                        + " n=" + gaps.length
                        + " p50=" + f2(pct(gaps, 0.50))
                        + " p95=" + f2(pct(gaps, 0.95))
                        + " p99=" + f2(pct(gaps, 0.99))
                        + " max=" + f2(gaps.length == 0 ? Double.NaN : gaps[gaps.length - 1]));
                System.out.flush();
            }
            // 汇总：各分位数跨轮的最小/最大值（噪声只会压低性能 ⇒ 报区间）
            System.out.println("SUMMARY " + names[s]
                    + " p50=" + range(all, 0.50)
                    + " p95=" + range(all, 0.95)
                    + " p99=" + range(all, 0.99)
                    + " maxGap=" + rangeMax(all));
            System.out.flush();
        }
        System.out.println("PROBE DONE dropped=" + DROPPED.get());
        System.out.flush();
        System.exit(0);
    }

    static String range(double[][] all, double p) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double[] g : all) {
            double v = pct(g, p);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return f2(lo) + "~" + f2(hi);
    }

    static String rangeMax(double[][] all) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double[] g : all) {
            double v = g.length == 0 ? Double.NaN : g[g.length - 1];
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return f2(lo) + "~" + f2(hi);
    }
}
