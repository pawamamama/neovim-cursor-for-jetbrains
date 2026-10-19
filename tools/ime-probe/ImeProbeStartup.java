package com.github.yyyolo7a79.neovidecursor.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 探针插件的启动器 —— 唯一的职责是「项目打开后启动一次 {@link ImeGeomProbe}」。
 *
 * <p>用 {@link ProjectActivity} 而不是 {@code StartupActivity}：
 * 后者是 {@code com.intellij.openapi.startup.StartupActivity}（旧 API），
 * 新平台推荐前者，且它保证在 EDT 上执行、能拿到 Project。
 *
 * <p>只做一件事，失败就静默 —— 探针不该影响 IDE 的任何行为。
 */
public final class ImeProbeStartup implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        try {
            ImeGeomProbe.start();
        } catch (Throwable t) {
            System.err.println("[ime-probe] 启动失败：" + t);
        }
        return Unit.INSTANCE;
    }
}
