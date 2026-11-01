package com.github.yyyolo7a79.neovidecursor.ui.settings;

import com.intellij.AbstractBundle;
import com.intellij.l10n.LocalizationStateService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * 界面文案的唯一来源（英文为 root，中文见 {@code _zh_CN}）。
 *
 * <p><b>为什么不用 {@link com.intellij.DynamicBundle}</b>（2026-11-01 实测）：
 * 它在没有语言包的 headless JVM 里<b>永远解析成英文</b>，
 * {@code -Duser.language=zh_CN} 与显式传入 locale 都无效
 * （{@code getResourceBundle(loader, path, locale)} 也走平台解析，不看参数）。
 * 那样「两套 locale 各跑一次门禁」只会跑两遍同样的英文，<b>验证不到中文</b>。
 *
 * <p>本类仍然复用平台的格式化语义：取值与格式化走
 * {@link AbstractBundle#messageOrDefault(ResourceBundle, String, String, Object...)}，
 * 与 IntelliJ 自己的 bundle 是同一套 MessageFormat 规则。
 *
 * <p><b>为什么必须用 {@code getNoFallbackControl}</b>：JDK 默认的
 * {@link ResourceBundle.Control} 会在 root bundle 之前插入
 * {@link Locale#getDefault()} 作为 fallback。中文 Windows 上即使显式请求
 * {@link Locale#ENGLISH}，候选链也会先命中 {@code _zh_CN} —— 英文界面会变成中文。
 */
public final class NeovideCursorBundle {

    /** 资源包路径（也是 {@link PropertyKey#resourceBundle()} 的契约值）。 */
    public static final String BUNDLE = "messages.NeovideCursorBundle";

    /**
     * 关掉「默认 locale 回退」：找不到请求的 locale 时直接用 root（英文），
     * 不会退到 JVM 默认 locale。{@code FORMAT_PROPERTIES} 在 Java 9+ 按 UTF-8 读。
     */
    private static final ResourceBundle.Control CONTROL =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private NeovideCursorBundle() {
    }

    /**
     * 取一条界面文案。
     *
     * @param key    资源键；缺失时返回 key 本身（界面可见，不抛异常）
     * @param params {@link java.text.MessageFormat} 参数
     */
    public static @NotNull String message(
            @PropertyKey(resourceBundle = BUNDLE) @NotNull String key, Object... params) {
        try {
            return AbstractBundle.messageOrDefault(resourceBundle(), key, key, params);
        } catch (Throwable t) {
            // 两种情况都兜住，绝不让设置页整体打不开：
            //   ① 整个资源包缺失（打包漏了 properties）→ MissingResourceException；
            //   ② 平台类不可用（AbstractBundle 被移走/改名）→ NoClassDefFoundError。
            // 返回 key 本身：界面会显示 settings.xxx，问题可见但不崩。
            // 注意：NeovideCursorConfigurable 的 static final 常量在类初始化时调本方法，
            // 这里若抛出去会变成 ExceptionInInitializerError → 之后永久 NoClassDefFoundError。
            warnOnce(t);
            return key;
        }
    }

    /** 键是否存在（用于「新增预设但还没登记文案」时回退到数据层名字）。 */
    public static boolean containsKey(@PropertyKey(resourceBundle = BUNDLE) @NotNull String key) {
        try {
            return resourceBundle().containsKey(key);
        } catch (Throwable t) {
            warnOnce(t);
            return false;
        }
    }

    /** 资源包不可用时只记一次日志（不静默吞异常，也不刷屏）。 */
    private static void warnOnce(Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        try {
            // 用全限定名、且放在 try 里：平台连 Logger 都不可用时也不能让类初始化失败。
            com.intellij.openapi.diagnostic.Logger
                    .getInstance(NeovideCursorBundle.class)
                    .warn("neovide-cursor: i18n bundle unavailable; showing raw keys", t);
        } catch (Throwable ignored) {
            // 日志子系统也不可用：界面显示 settings.xxx 已经让问题可见。
        }
    }

    private static volatile boolean warned;

    /**
     * 当前界面语言。
     *
     * <p>平台已初始化 → 跟随 IDE 的语言设置（与语言包一致）；
     * 探针 / 极早期调用 → {@link Locale#getDefault()}，这样两套 locale 可被测试。
     */
    static @NotNull Locale currentLocale() {
        try {
            LocalizationStateService service = LocalizationStateService.getInstance();
            if (service != null) {
                // 与平台 LocalizationUtil.getLocale(boolean) 同语义：
                // 语言已切换但尚未重启时，运行中的 IDE 仍是旧语言，必须读
                // lastSelectedLocale；否则设置页会在重启窗口里混两种语言。
                String tag = Boolean.TRUE.equals(service.isRestartRequired())
                        ? service.getLastSelectedLocale() : service.getSelectedLocale();
                if (tag != null && !tag.isEmpty()) {
                    Locale locale = Locale.forLanguageTag(tag);
                    if (!locale.getLanguage().isEmpty()) {
                        return normalize(locale);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 平台未初始化（headless 探针 / 启动早期）：回退到 JVM 默认 locale。
        }
        return normalize(Locale.getDefault());
    }

    /**
     * 只提供简体中文包：所有 {@code zh*}（含 zh-TW / zh-Hant）一律落到 zh_CN。
     * 只有 {@code zh} 而无国家码时也要补国家码，否则 no-fallback 链找不到 {@code _zh}，
     * 会直接落到 root（英文）。这是刻意的产品选择：繁体用户看简体比看英文更可读。
     */
    private static @NotNull Locale normalize(@NotNull Locale locale) {
        return "zh".equals(locale.getLanguage()) ? Locale.SIMPLIFIED_CHINESE : locale;
    }

    /** 按当前 locale 取包。{@link ResourceBundle} 自带按 (name, locale, loader) 的缓存。 */
    static @NotNull ResourceBundle resourceBundle() {
        return ResourceBundle.getBundle(BUNDLE, currentLocale(),
                NeovideCursorBundle.class.getClassLoader(), CONTROL);
    }
}
