import java.lang.reflect.Method;

/**
 * 探针：确认 EditorSettings.getLineCursorWidth() 的语义。
 *
 * <p>用户实测：「关闭隐藏原生发现我们绘制出来的还是不和 idea 的一样（2px 5px），
 * 怀疑我设置成了粗光标」。
 *
 * <p>要先弄清 API 到底返回什么，才能决定生产代码怎么用：
 * <ul>
 *   <li>如果它返回「像素」，直接当宽度用是对的</li>
 *   <li>如果它返回「相对倍率」（IDEA 默认 1），那把它当像素用就错了</li>
 * </ul>
 */
public final class VCursorWidth {
    public static void main(String[] a) throws Exception {
        System.out.println("=== VCursorWidth：getLineCursorWidth 语义确认 ===");

        Class<?> settings = Class.forName("com.intellij.openapi.editor.EditorSettings");
        Method m = settings.getMethod("getLineCursorWidth");
        System.out.println("  方法: " + m);
        System.out.println("  返回类型: " + m.getReturnType().getName());

        Method setter = null;
        try {
            setter = settings.getMethod("setLineCursorWidth", int.class);
            System.out.println("  setter: " + setter);
        } catch (NoSuchMethodException e) {
            System.out.println("  setter: 不存在");
        }

        // 搜索 IDEA 源码里的默认值线索
        System.out.println();
        System.out.println("  === 从 IDEA jar 里找默认值 ===");
        // EditorSettings 的实现在 intellij.platform.editor 里
        String[] jars = {
                "intellij.platform.editor.jar",
                "intellij.platform.editor.ex.jar",
                "app.jar",
        };
        for (String jar : jars) {
            System.out.println("    " + jar + ": " + (jarExists(jar) ? "存在" : "不存在"));
        }
    }

    private static boolean jarExists(String name) {
        try {
            return VCursorWidth.class.getClassLoader().getResource(name) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
