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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「单 animator 帧率矩阵」探针（t4 verifier 自建，基线与本批工作区都能编译）。
 *
 * <p>动机：T1-1 把「各 animator 自建线程 sleep(currentDelayMs)」换成
 * 「共享线程睡到 nextDueNanos」，而 {@code nextDueNanos} 是 EDT 在 tick <b>结束</b>时才写的。
 * 当「单帧 EDT 成本」大于「调度间隔」时，两种写法对帧率的处理可能不同。本探针把这一格量化：
 * 用「让每次 tick 消耗 tickSleepMs」的方式制造长帧，统计每种组合下的**投递帧数**（≈实际帧数）。
 *
 * <p>矩阵（每格 1500ms 测量窗口）：
 * <ul>
 *   <li>R1：alwaysHighFps=true，tick=0ms（常态）</li>
 *   <li>R2：alwaysHighFps=true，tick=25ms（EDT 繁忙）</li>
 *   <li>R3：alwaysHighFps=false，tick=0ms（自适应常态）</li>
 *   <li>R4：alwaysHighFps=false，tick=25ms（自适应 + 繁忙）</li>
 *   <li>R5：alwaysHighFps=true，tick=25ms，+ 每 100ms 一次 caret 事件（模拟持续打字）</li>
 *   <li>R6：alwaysHighFps=false，tick=25ms，+ 每 100ms 一次 caret 事件</li>
 * </ul>
 *
 * <p>输出 {@code RESULT key=value}；帧数 = 被投递且捕获了该 animator 的 InvocationEvent 数。
 */
public class RegimeProbe {

    static final AtomicInteger POSTED = new AtomicInteger();
    static Field RUNNABLE_FIELD;

    static {
        try {
            RUNNABLE_FIELD = InvocationEvent.class.getDeclaredField("runnable");
            RUNNABLE_FIELD.setAccessible(true);
        } catch (Throwable t) {
            RUNNABLE_FIELD = null;
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
                RegimeProbe.class.getClassLoader(),
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
                RegimeProbe.class.getClassLoader(),
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
                RegimeProbe.class.getClassLoader(),
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

    /**
     * 跑一格：返回 1500ms 窗口内的帧数。
     *
     * @param alwaysHighFps 常驻刷新开关（改的是 config 对象，与两棵树无关）
     * @param tickSleepMs   每次 tick 的 EDT 成本
     * @param caretEveryMs  > 0 时每这么久投递一次 caret 事件（模拟打字）；0 = 只在一开始唤醒一次
     */
    static int regime(boolean alwaysHighFps, long tickSleepMs, long caretEveryMs) throws Exception {
        NeovideConfig cfg = new NeovideConfig();
        cfg.alwaysHighFps = alwaysHighFps;
        CaretAnimator a = newAnimator(cfg, tickSleepMs);
        fireCaret(a);                       // 首次唤醒：进入活跃档
        Thread.sleep(400);                  // 稳定期

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

        POSTED.set(0);
        Thread.sleep(1500);
        int frames = POSTED.get();
        stop.set(1);
        if (pump != null) {
            pump.interrupt();
        }
        a.dispose();
        Thread.sleep(300);
        return frames;
    }

    public static void main(String[] args) throws Exception {
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
            @Override
            public void postEvent(AWTEvent e) {
                if (e instanceof InvocationEvent && RUNNABLE_FIELD != null) {
                    try {
                        if (ownerOf(RUNNABLE_FIELD.get(e)) != null) {
                            POSTED.incrementAndGet();
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
                super.postEvent(e);
            }
        });

        out("attribution", RUNNABLE_FIELD == null ? "UNAVAILABLE" : "runnable-field-ok");
        out("ACTIVE_DELAY_MS", CaretAnimator.ACTIVE_DELAY_MS);

        out("R1_highfps_tick0_frames_1500ms", regime(true, 0, 0));
        out("R2_highfps_tick25_frames_1500ms", regime(true, 25, 0));
        out("R3_adaptive_tick0_frames_1500ms", regime(false, 0, 0));
        out("R4_adaptive_tick25_frames_1500ms", regime(false, 25, 0));
        out("R5_highfps_tick25_caret100_frames", regime(true, 25, 100));
        out("R6_adaptive_tick25_caret100_frames", regime(false, 25, 100));

        System.out.println("PROBE DONE");
        System.exit(0);
    }
}
