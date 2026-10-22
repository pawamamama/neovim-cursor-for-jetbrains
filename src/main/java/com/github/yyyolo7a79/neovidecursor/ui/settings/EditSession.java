package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.github.yyyolo7a79.neovidecursor.settings.NeovideCursorSettings;

/**
 * 面板的<b>编辑会话</b>（0.9.9 / Phase 1）。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>重构前，{@code pending} / {@code baseline} / {@code syncingControls} 是
 * {@link NeovideCursorConfigurable} 的三个裸字段，<b>任何方法都能直接给它们赋值</b>。
 * 于是同一套「先拍快照、再比较、再前移」的时序在四个地方各写了一遍，而
 * <b>漏掉任何一步都是静默 bug</b>：
 *
 * <ul>
 *   <li><b>R-01</b>：{@code apply()} 结束时只前移 {@code baseline} 忘了 {@code pending}
 *       → 第二次点「应用」把 Tools 的改动冲掉（用户实测）；</li>
 *   <li><b>V11-3c / U13</b>：{@code reset()} 装载历史配置时没抑制派生
 *       → 凭空多出副本、应用按钮恒亮；</li>
 *   <li><b>B-2</b>：{@code syncingControls} 有一处忘了恢复
 *       → 之后所有控件回灌都被当成用户操作。</li>
 * </ul>
 *
 * <p>本类把这几组时序收成<b>有名字的操作</b>，调用方不再关心「要先做什么、别忘了什么」：
 * <table border="1">
 *   <caption>三个操作与它们替代的手写序列</caption>
 *   <tr><th>操作</th><th>替代的手写序列</th></tr>
 *   <tr><td>{@link #openAt}</td>
 *       <td>{@code pending = deepCopy(state); baseline = deepCopy(state);}
 *           —— 两行必须同源，漏一行就是 R-01</td></tr>
 *   <tr><td>{@link #reloadFrom}</td>
 *       <td>{@code baseline = deepCopy(target); pending = deepCopy(target);}
 *           —— 同上，而且顺序不能反</td></tr>
 *   <tr><td>{@link #close}</td>
 *       <td>{@code baseline = null}</td></tr>
 * </table>
 *
 * <h2>它不做什么</h2>
 *
 * <p><b>不搬走 {@code pending} 字段本身</b>。面板里有 190 处引用 {@code pending}
 * （其中 88 处是 {@code pending.xxx} 成员访问、101 处把它当值传给别的方法）。
 * 把它改成 {@code session.get().xxx} 会让 190 处全部变长，而收益只是「名字更面向对象」——
 * 那不是本阶段要解决的问题。本阶段要的是：<b>让「改变 pending 指向」这件事只有本类能做</b>。
 *
 * <p>所以 {@code pending} / {@code baseline} 仍然是面板的字段，
 * 但它们的<b>每一次重新赋值都必须经由 {@code editSession}</b>。
 * 判据（RF-4）：在面板类里 grep {@code pending\s*=} / {@code baseline\s*=} = 0。
 */
final class EditSession {

    /** 面板正在编辑的副本。{@code null} 表示会话未打开（面板已 dispose）。 */
    private NeovideCursorSettings.State pending;

    /** 打开面板那一刻的快照，只作比较基准，不是真相源。 */
    private NeovideCursorSettings.State baseline;

    EditSession() {
        this.pending = new NeovideCursorSettings.State();
        this.baseline = new NeovideCursorSettings.State();
    }

    // ==================== 读 ====================

    /** 当前编辑副本（永不为 {@code null}：未打开时返回一个空 State，让控件构建不至于 NPE）。 */
    NeovideCursorSettings.State pending() {
        if (pending == null) {
            pending = new NeovideCursorSettings.State();
        }
        return pending;
    }

    /** 比较基准。（可能为 {@code null}：{@code dispose()} 之后就是 null。） */
    NeovideCursorSettings.State baseline() {
        return baseline;
    }

    // ==================== 写（唯一入口）====================

    /**
     * 打开会话：{@code pending} 与 {@code baseline} <b>同时</b>取 {@code source} 的深拷贝。
     *
     * <p><b>两者必须同源</b>：它们一旦不同，{@code apply()} 的脏判据就会把
     * 「没改过的字段」判成脏（或反过来），两个方向都会丢用户数据。
     * 这条时序原来在两个地方各写一遍（{@code createPanel} 与 {@code reset}），
     * 现在只有这里一处。
     */
    void openAt(NeovideCursorSettings.State source) {
        NeovideCursorSettings.State copy = deepCopy(source);
        this.pending = copy;
        this.baseline = deepCopy(copy);
    }

    /**
     * {@code apply()} 成功后把 pending 与 baseline <b>同源前移</b>到 {@code target}。
     *
     * <p><b>这是 R-01 的修法本体</b>：两个字段必须一起前移。
     * 只前移 baseline 会让第二次 apply 把 Tools 的改动冲掉（历史 bug）；
     * 只前移 pending 会让按钮永远亮着。方法签名把两者绑在一起，漏不掉。
     */
    void reloadFrom(NeovideCursorSettings.State target) {
        NeovideCursorSettings.State copy = deepCopy(target);
        this.baseline = copy;
        this.pending = deepCopy(copy);
    }

    /** 关闭会话（{@code disposeUIResources()}）：释放 baseline，pending 归零。 */
    void close() {
        this.baseline = null;
        this.pending = new NeovideCursorSettings.State();
    }

    // ==================== 工具 ====================

    /**
     * 深拷贝。
     *
     * <p>不复用 {@code State.copyFrom} 之外的东西：{@code State} 里全是标量与不可变 String，
     * 所以 {@code copyFrom} 就是完整深拷贝（没有需要逐层复制的引用字段）。
     */
    static NeovideCursorSettings.State deepCopy(NeovideCursorSettings.State from) {
        NeovideCursorSettings.State s = new NeovideCursorSettings.State();
        if (from != null) {
            s.copyFrom(from);
        }
        return s;
    }
}
