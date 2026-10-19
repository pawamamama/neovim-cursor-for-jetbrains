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
 * T1-1 共享调度器运行期探针（t4 verifier 自建，只对本批工作区源码编译）—— 针对
 * {@code CaretAnimator#lastDispatchedDueNanos} 这条新增护栏做「不超投 / 不漏投」压力测试。
 *
 * <p>与前一位实现者的探针相互独立：本探针
 * <ul>
 *   <li>用<b>反射读 lambda 捕获字段</b>把每个被投递的 EDT 任务归属到具体 animator 实例，
 *       因此"3 个同时到期 ⇒ 3 个独立任务"是按<b>实例</b>验证的，而不是只数总数；</li>
 *   <li>直接操纵 {@code nextDueNanos}（包内可见）构造"到期值陈旧/全部过期/只有 1 个到期"
 *       等边界序列；</li>
 *   <li>覆盖：3 个同时到期、只有 1 个到期（另两个 10s 后）、到期值长期不变、
 *       全部过期、线程"建→退→再建→再退"两轮、长 tick 下的投递频率。</li>
 * </ul>
 *
 * <p>输出统一为 {@code RESULT key=value}。
 */
public class T1SchedulerProbe {

    static final AtomicInteger POSTED = new AtomicInteger();
    static final Map<Integer, AtomicInteger> BY_ANIMATOR = new ConcurrentHashMap<Integer, AtomicInteger>();
    static volatile boolean forward = false;

    /**
     * {@code InvocationEvent.runnable} 是 protected 字段，必须靠
     * {@code --add-opens java.desktop/java.awt.event=ALL-UNNAMED} 才能读；
     * 拿不到就退化为"只数总数、不做实例归因"。
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

    static Editor fakeEditor(final JComponent content, final long tickSleepMs) {
        CaretModel model = (CaretModel) Proxy.newProxyInstance(
                T1SchedulerProbe.class.getClassLoader(),
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
                T1SchedulerProbe.class.getClassLoader(),
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

    static void fireCaret(CaretAnimator a) {
        final Editor source = fakeEditor(new JPanel(), 0);
        Caret caret = (Caret) Proxy.newProxyInstance(
                T1SchedulerProbe.class.getClassLoader(),
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

    static int postsOf(CaretAnimator a) {
        AtomicInteger v = BY_ANIMATOR.get(System.identityHashCode(a));
        return v == null ? 0 : v.get();
    }

    /**
     * 把 animator 摆到「没有在途任务、没有历史投递记录、到期时刻 = dueNanos」的干净状态。
     *
     * <p>必须有这一步：本探针在 forward=false 下运行，被投递的任务永远不会执行，
     * 因此 framePending 会一直停在 true（这是"防堆积"的正常表现）。要构造下一个
     * 独立场景，就得手工模拟"上一帧已经被执行完"。{@code lastDispatchedDueNanos}
     * 复位到字段初值 {@code Long.MIN_VALUE} 同理。
     */
    static void arm(CaretAnimator a, long dueNanos) {
        a.framePending.set(false);
        a.lastDispatchedDueNanos = Long.MIN_VALUE;
        a.nextDueNanos = dueNanos;
    }

    /** 反射读 RenderScheduler.thread（private static），确认"退出了就置回 null" */
    static Object schedulerThreadField() {
        try {
            Field f = RenderScheduler.class.getDeclaredField("thread");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return "ERR:" + t;
        }
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
        out("IDLE_DELAY_MS_init_due_offset", 120);

        // ================= 场景 A：3 个同时到期 ⇒ 3 个独立 EDT 任务（按实例归因）=================
        CaretAnimator a1 = newAnimator(cfg, 0);
        CaretAnimator a2 = newAnimator(cfg, 0);
        CaretAnimator a3 = newAnimator(cfg, 0);
        forward = false;                       // EDT 不执行：只观测"投递"次数
        reset();
        long dueNow = System.nanoTime();
        arm(a1, dueNow);
        arm(a2, dueNow);
        arm(a3, dueNow);
        Thread.sleep(400);
        out("A_posts_3simultaneous", POSTED.get());
        out("A_distinct_animators", BY_ANIMATOR.size());
        out("A_posts_a1", postsOf(a1));
        out("A_posts_a2", postsOf(a2));
        out("A_posts_a3", postsOf(a3));
        out("A_threads", renderThreads().size());

        sleep(700);
        out("A_posts_after_700ms_more", POSTED.get());

        // ================= 场景 B：只有 a1 到期（a2/a3 在 10s 后）⇒ 只应投递 1 个 =================
        reset();
        long far = System.nanoTime() + 10_000_000_000L;
        arm(a1, System.nanoTime());
        arm(a2, far);
        arm(a3, far);
        Thread.sleep(400);
        out("B_posts_only_a1_due", POSTED.get());
        out("B_posts_a1", postsOf(a1));
        out("B_posts_a2", postsOf(a2));
        out("B_posts_a3", postsOf(a3));

        // ================= 场景 C：到期值长期不变（过期但任务没执行）⇒ 恰好 1 次，不得反复投递 =================
        reset();
        long stale = System.nanoTime() - 1_000_000_000L;   // 1s 前就已过期
        arm(a1, stale);
        Thread.sleep(800);
        out("C_posts_stale_due_800ms", POSTED.get());
        out("C_posts_a1", postsOf(a1));
        out("C_lastDispatchedDueNanos_equals_stale", a1.lastDispatchedDueNanos == stale);

        // ================= 场景 D：全部过期（三个都远超期）⇒ 各 1 个，且不再重复 =================
        reset();
        long stale2 = System.nanoTime() - 5_000_000_000L;
        arm(a1, stale2);
        arm(a2, stale2);
        arm(a3, stale2);
        Thread.sleep(400);
        out("D_posts_all_overdue", POSTED.get());
        out("D_distinct_animators", BY_ANIMATOR.size());
        sleep(600);
        out("D_posts_after_600ms_more", POSTED.get());

        // ================= 场景 E：线程生命周期：建 → 退 → 再建 → 再退 =================
        out("E_threads_3animators", renderThreads().size());
        a1.dispose();
        a2.dispose();
        a3.dispose();
        Thread.sleep(700);
        out("E_threads_after_dispose_all", renderThreads().size());
        out("E_thread_field_after_exit", schedulerThreadField());

        CaretAnimator b1 = newAnimator(cfg, 0);
        Thread.sleep(300);
        out("E_threads_after_register_again", renderThreads().size());
        out("E_thread_field_after_rebuild", schedulerThreadField() != null);
        b1.dispose();
        Thread.sleep(700);
        out("E_threads_after_second_dispose", renderThreads().size());
        out("E_thread_field_after_second_exit", schedulerThreadField());

        // ================= 场景 F：长 tick（25ms > 8ms 间隔）下的投递频率 =================
        forward = true;
        CaretAnimator slow = newAnimator(cfg, 25);
        fireCaret(slow);
        Thread.sleep(300);
        reset();
        long t0 = System.nanoTime();
        Thread.sleep(1500);
        long el = (System.nanoTime() - t0) / 1_000_000L;
        out("F_elapsed_ms", el);
        out("F_posts_slow_tick", POSTED.get());
        out("F_elapsed_over_tick25", el / 25);
        out("F_elapsed_over_8", el / 8);
        slow.dispose();
        Thread.sleep(500);
        out("F_threads_at_end", renderThreads().size());

        System.out.println("PROBE DONE");
        System.exit(0);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
