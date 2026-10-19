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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跨源码树（基线 321b0a0 / 本批工作区）都<b>能编译能运行</b>的调度行为探针（t4 verifier 自建）。
 *
 * <p>只使用两棵树都存在的 API（{@code new CaretAnimator(Editor, NeovideConfig)}、
 * {@code caretPositionChanged()}、{@code dispose()}、{@code CaretAnimator.ACTIVE_DELAY_MS}），
 * 不引用 {@code RenderScheduler}/{@code nextDueNanos} 等本批新增成员，
 * 因此两棵树可以各自编译、各自运行，输出可直接对比。
 *
 * <p><b>计数口径</b>：往系统 EventQueue 前压入一个计数队列，只统计
 * 「被投递到 EDT、且 Runnable 捕获了一个 {@code CaretAnimator} 实例」的 {@link InvocationEvent}
 * —— 也就是插件自己投递的渲染任务；Swing 内部的其它 InvocationEvent 不计。
 * 被捕获的 animator 用反射读 lambda 的捕获字段（形如 {@code arg$1}）确定，
 * 因此可以按 animator 分别计数（这是"每 animator 各自一次投递"的强证据）。
 *
 * <p>输出统一为 {@code RESULT key=value} 行，便于机械比对。
 */
public class DispatchCountProbe {

    static final AtomicInteger POSTED = new AtomicInteger();
    static final Map<Integer, AtomicInteger> BY_ANIMATOR = new ConcurrentHashMap<Integer, AtomicInteger>();
    static volatile boolean forward = false;

    /**
     * {@code InvocationEvent.runnable} 是 protected 字段，必须靠
     * {@code --add-opens java.desktop/java.awt.event=ALL-UNNAMED} 才能读；
     * 拿不到就退化为"只数总数、不做实例归因"（探针会打印 attribution=UNAVAILABLE）。
     */
    static Field RUNNABLE_FIELD;

    static {
        try {
            RUNNABLE_FIELD = InvocationEvent.class.getDeclaredField("runnable");
            RUNNABLE_FIELD.setAccessible(true);
        } catch (Throwable t) {
            RUNNABLE_FIELD = null;
        }
    }

    static Object runnableOf(InvocationEvent e) {
        if (RUNNABLE_FIELD == null) {
            return null;
        }
        try {
            return RUNNABLE_FIELD.get(e);
        } catch (Throwable t) {
            return null;
        }
    }

    static void out(String key, Object value) {
        System.out.println("RESULT " + key + "=" + value);
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    /** 伪造 Editor；tickSleepMs > 0 时每次 tick 都会消耗这么久（模拟 EDT 繁忙的长帧） */
    static Editor fakeEditor(final JComponent content, final long tickSleepMs) {
        CaretModel model = (CaretModel) Proxy.newProxyInstance(
                DispatchCountProbe.class.getClassLoader(),
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
                        case "toString":
                            return "FakeCaretModel";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        return (Editor) Proxy.newProxyInstance(
                DispatchCountProbe.class.getClassLoader(),
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
                        case "toString":
                            return "FakeEditor";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    static CaretAnimator newAnimator(NeovideConfig cfg, long tickSleepMs) {
        return new CaretAnimator(fakeEditor(new JPanel(), tickSleepMs), cfg);
    }

    /** 走真实「击键/移动光标」路径：caretPositionChanged → wakeUp() */
    static void fireCaret(CaretAnimator a) {
        final Editor source = fakeEditor(new JPanel(), 0);
        Caret caret = (Caret) Proxy.newProxyInstance(
                DispatchCountProbe.class.getClassLoader(),
                new Class<?>[]{Caret.class},
                (proxy, method, args) -> "getEditor".equals(method.getName())
                        ? source : defaultValue(method.getReturnType()));
        a.caretPositionChanged(new CaretEvent(caret, new LogicalPosition(0, 0), new LogicalPosition(0, 0)));
    }

    /** 从被投递的 Runnable 里读出被捕获的 CaretAnimator（lambda 捕获字段） */
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
                    // 忽略，继续找下一个字段
                }
            }
        }
        return null;
    }

    static List<Thread> renderThreads() {
        List<Thread> out = new ArrayList<Thread>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("neovide-cursor-render".equals(t.getName()) && t.isAlive()) {
                out.add(t);
            }
        }
        return out;
    }

    static void reset() {
        POSTED.set(0);
        BY_ANIMATOR.clear();
    }

    public static void main(String[] args) throws Exception {
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
            @Override
            public void postEvent(AWTEvent e) {
                if (e instanceof InvocationEvent) {
                    CaretAnimator owner = ownerOf(runnableOf((InvocationEvent) e));
                    if (owner != null) {
                        POSTED.incrementAndGet();
                        BY_ANIMATOR.computeIfAbsent(System.identityHashCode(owner),
                                k -> new AtomicInteger()).incrementAndGet();
                    }
                }
                if (forward) {
                    super.postEvent(e);
                }
            }
        });

        final NeovideConfig cfg = new NeovideConfig();
        out("attribution", RUNNABLE_FIELD == null ? "UNAVAILABLE" : "runnable-field-ok");
        out("alwaysHighFps", cfg.alwaysHighFps);
        out("ACTIVE_DELAY_MS", CaretAnimator.ACTIVE_DELAY_MS);

        // ---------- 阶段 1：3 个 animator、EDT 不执行任务 → 线程数与首轮投递数 ----------
        List<CaretAnimator> three = new ArrayList<CaretAnimator>();
        for (int i = 0; i < 3; i++) {
            three.add(newAnimator(cfg, 0));
        }
        reset();
        Thread.sleep(500);
        out("phase1_threads_3animators", renderThreads().size());
        out("phase1_posts_3animators_noEDT", POSTED.get());
        out("phase1_distinct_animators_posted", BY_ANIMATOR.size());

        Thread.sleep(700);
        out("phase1b_posts_after_700ms_more", POSTED.get());

        // ---------- 阶段 2：全部 dispose → 线程退出 ----------
        for (CaretAnimator a : three) {
            a.dispose();
        }
        Thread.sleep(600);
        out("phase2_threads_after_dispose_all", renderThreads().size());

        // ---------- 阶段 3：EDT 被真实长任务阻塞 → 不得超发；阻塞结束后必须恢复（不漏投）----------
        forward = true;
        CaretAnimator blocked = newAnimator(cfg, 0);
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(() -> {
            started.countDown();
            try {
                Thread.sleep(700);          // 占住 EDT：此时插件投递的任务只能在队列里等
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        if (!started.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
            out("phase3_blocker_started", false);
        }
        reset();
        fireCaret(blocked);            // wakeUp：立即到期
        Thread.sleep(400);
        int blockedPosts = POSTED.get();
        out("phase3_posts_while_EDT_blocked", blockedPosts);
        out("phase3_posts_per_animator_while_blocked", BY_ANIMATOR.get(System.identityHashCode(blocked)));

        Thread.sleep(1200);            // 阻塞结束 + 恢复稳定节奏
        int resumedPosts = POSTED.get();
        out("phase3_posts_after_unblock", resumedPosts);
        out("phase3_resumed_ok", resumedPosts > blockedPosts);
        blocked.dispose();
        Thread.sleep(400);

        // ---------- 阶段 4：快 tick（0ms）、alwaysHighFps → 投递频率 ----------
        CaretAnimator fast = newAnimator(cfg, 0);
        forward = true;
        fireCaret(fast);
        Thread.sleep(300);             // 进入稳定节奏
        reset();
        long t0 = System.nanoTime();
        Thread.sleep(1500);
        long el1 = (System.nanoTime() - t0) / 1_000_000L;
        out("phase4_elapsed_ms", el1);
        out("phase4_posts_fast_tick", POSTED.get());
        out("phase4_expected_elapsed_over_8ms", el1 / CaretAnimator.ACTIVE_DELAY_MS);
        fast.dispose();
        Thread.sleep(400);

        // ---------- 阶段 5：长 tick（25ms > 8ms 派发间隔）→ 是否超发 ----------
        CaretAnimator slow = newAnimator(cfg, 25);
        fireCaret(slow);
        Thread.sleep(300);
        reset();
        t0 = System.nanoTime();
        Thread.sleep(1500);
        long el2 = (System.nanoTime() - t0) / 1_000_000L;
        out("phase5_elapsed_ms", el2);
        out("phase5_posts_slow_tick_25ms", POSTED.get());
        out("phase5_elapsed_over_tick_ms", el2 / 25);
        out("phase5_elapsed_over_8ms", el2 / CaretAnimator.ACTIVE_DELAY_MS);
        slow.dispose();
        Thread.sleep(400);
        out("phase6_threads_at_end", renderThreads().size());
        System.out.println("PROBE DONE");
        System.exit(0);
    }
}
