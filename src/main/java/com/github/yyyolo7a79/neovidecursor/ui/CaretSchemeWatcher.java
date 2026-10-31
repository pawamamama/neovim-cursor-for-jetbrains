package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;

/**
 * 配色方案变更监听器 —— <b>缺陷 4（切主题后原生光标复活）的修法</b>。
 *
 * <p><b>缺陷现象</b>：勾选「隐藏原生光标」后切换配色方案（Settings | Editor | Color Scheme），
 * 原生光标又出现了，与覆盖层叠成两套绘制。
 *
 * <p><b>根因</b>：{@code NeovideCaretManager.applyNativeCaretHidden()} 开头有一条
 * <pre>
 *   if (savedCaretColor != null) return;      // ← 以为「已经隐藏过」
 * </pre>
 * 切主题后 {@code EditorColorsManager.getGlobalScheme()} 返回的是<b>另一个对象</b>，
 * 它的 {@code CARET_COLOR} 是可见的；但 {@code savedCaretColor} 仍非 null（它记的是旧 scheme 的原色），
 * 于是方法早退 —— 新 scheme 上的透明色根本没被写过。插件「以为还隐藏着」，
 * 而 {@code setColor} 只改了旧 scheme 对象，新的不受影响。
 *
 * <p><b>修法</b>：用平台的消息总线订阅 {@link EditorColorsManager#TOPIC}
 * （{@code Topic<EditorColorsListener>}，广播方向 {@code TO_DIRECT_CHILDREN}），
 * 回调里调 {@code applyNativeCaretHidden(true)} 强制按<b>当前</b> scheme 重新隐藏。
 *
 * <p><b>【U-M-20 修正】原注释说「{@code immediateDelivery=false} → 回调在 EDT 上跑」，
 * 这是错的</b>：javap 实测 {@code MessageBusImpl} 的 {@code immediateDelivery}
 * 只决定「锁冲突时是否延后投递」，<b>不改投递线程</b>。所以本类在回调入口显式判断
 * 线程并在非 EDT 时 {@code invokeLater} 转投（见 {@code handleSchemeChange}）。
 *
 * <p><b>为什么【禁止】用 {@code getSchemeModificationCounter()} 轮询</b>：它只是个
 * {@code abstract long}，没有变更通知语义；轮询必然引入延迟与无谓开销。
 *
 * <p><b>生命周期</b>：{@code app.getMessageBus().connect(this)} —— 平台在 {@code dispose()}
 * 时自动断开连接，不会泄漏。注册是幂等的，重复调用只连一次。
 */
public final class CaretSchemeWatcher implements Disposable {

    /** 单例（插件内只需要一个监听器）。 */
    private static final CaretSchemeWatcher INSTANCE = new CaretSchemeWatcher();

    /** 消息总线连接；{@code null} = 尚未注册（或平台不可用）。 */
    private MessageBusConnection connection;

    /** 已捕获到的监听器实例（{@code subscribe} 之后回填），供 {@link #isInstalled()} 与探针使用。 */
    private EditorColorsListener listener;

    private CaretSchemeWatcher() {
    }

    public static CaretSchemeWatcher getInstance() {
        return INSTANCE;
    }

    /**
     * 幂等安装：已经注册过、或平台不可用（离线探针 / 无 Application）时都不做事。
     *
     * @return true = 本次真正建立了订阅
     */
    public boolean install() {
        if (connection != null) {
            return false;
        }
        try {
            Application app = ApplicationManager.getApplication();
            if (app == null) {
                return false;
            }
            MessageBus bus = app.getMessageBus();
            if (bus == null) {
                return false;
            }
            MessageBusConnection conn = bus.connect(this);   // ← 绑到 this，dispose 时自动断开
            if (conn == null) {
                return false;
            }
            // 【复查 L-5】先登记再 subscribe：subscribe 抛时 dispose() 才断得开，
            // 否则 MessageBusConnection 泄漏（旧缺陷，本次顺手修）。
            this.connection = conn;
            EditorColorsListener l = scheme -> handleSchemeChange(scheme);
            conn.subscribe(EditorColorsManager.TOPIC, l);
            this.listener = l;
            return true;
        } catch (Throwable t) {
            // 消息总线不可用时不能让插件在启动阶段崩溃 —— 但也不能一个字都不留（U-M-16）：
            // 调用点（NeovideCaretManager.ensureSchemeWatcher）会根据返回值决定是否提示。
            CursorDiag.swallowed("installSchemeWatcher", t);
            return false;
        }
    }

    /** 是否已经建立订阅。 */
    public boolean isInstalled() {
        return connection != null;
    }

    /** 探针用：已订阅的监听器实例（未注册时为 null）。 */
    public EditorColorsListener listenerForProbe() {
        return listener;
    }

    /**
     * 配色方案变更的回调体（从 lambda 里抽出来，便于离线探针直接驱动）。
     *
     * <p>两条早退都是【必须】的：
     * <ul>
     *   <li>{@code enabled=false} —— 插件已关闭时不该再改全局配色；</li>
     *   <li>{@code hideNativeCaret=false} —— 用户本来就要原生光标，别去动它。</li>
     * </ul>
     *
     * @param scheme 新方案（平台传入；本方法不依赖它，一律读 {@code getGlobalScheme()}，
     *               因为 {@code applyNativeCaretHidden(force)} 语义就是「按当前的重新来」）
     */
    public void handleSchemeChange(EditorColorsScheme scheme) {
        // 【U-M-20】**不假定自己在哪个线程**。
        //
        // 原类注释断言「TOPIC 无 immediateDelivery → 回调在 EDT 上跑」，javap 已否证：
        // immediateDelivery 只决定「锁冲突时是否延后投递」，**不改投递线程**；
        // 非 EDT 的发布者会让 setColor 写到全局 scheme 的中途态。
        //
        // 防御方式是「判断 + 转发」，不是 assertIsDispatchThread() —— 后者会把
        // 「可能并发」变成「必定崩溃」。
        // 【复查 LOW】取 app / 判线程 / 转投 这一段也要有兜底：平台在 dispose 之后
        // 仍可能投递一次主题事件，此时 invokeLater 会抛 AlreadyDisposedException，
        // 而异常会从消息总线订阅者里穿出去。转投失败就降级为当场执行。
        try {
            Application app = ApplicationManager.getApplication();
            if (app != null && !app.isDispatchThread()) {
                app.invokeLater(() -> handleSchemeChangeOnEdt(scheme));
                return;
            }
        } catch (Throwable ignored) {
            // 落到下面的直接执行
        }
        handleSchemeChangeOnEdt(scheme);
    }

    /**
     * 真正干活的那一半（原 {@code handleSchemeChange} 的方法体）。
     *
     * <p><b>绝不再判断线程</b>：探针桩里的 {@code invokeLater} 是立即执行的，
     * 这里若再判一次就会无限递归（StackOverflow）。
     *
     * <p>{@code restricted} 包级可见：既是拆方法的必然结果，也让探针能单独驱动。
     */
    void handleSchemeChangeOnEdt(EditorColorsScheme scheme) {
        try {
            if (!NeovideCaretManager.isEnabled()) {
                return;
            }
            if (!NeovideCaretManager.isHideNativeCaret()) {
                return;
            }
            NeovideCaretManager.applyNativeCaretHidden(true);   // ← force：忽略 savedCaretColor 早退
        } catch (Throwable t) {
            // 【U-M-18】单次主题切换失败不该影响 IDE，但必须留痕：这是「缺陷 4」唯一的兜底路径。
            CursorDiag.swallowed("schemeChange", t);
        }
    }

    @Override
    public void dispose() {
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
                // 平台已 dispose 时 disconnect 可能抛，忽略
            }
            connection = null;
        }
        listener = null;
    }
}
