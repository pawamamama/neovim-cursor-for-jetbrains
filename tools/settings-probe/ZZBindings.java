import com.github.yyyolo7a79.neovidecursor.ui.settings.NeovideCursorConfigurable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** 诊断：为什么只登记了 1 个颜色绑定。 */
public final class ZZBindings {
    public static void main(String[] a) throws Exception {
        V0Env.install();
        NeovideCursorConfigurable conf = new NeovideCursorConfigurable();

        Field f = NeovideCursorConfigurable.class.getDeclaredField("colorBindings");
        f.setAccessible(true);
        Object group = f.get(conf);
        System.out.println("构造后：group = " + group);

        Method size = group.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        System.out.println("构造后 size = " + size.invoke(group));

        // 看 items 内容
        Field items = group.getClass().getDeclaredField("items");
        items.setAccessible(true);
        List<?> list = (List<?>) items.get(group);
        System.out.println("items 内容 = " + list.size() + " 项");
        for (Object o : list) {
            Method name = o.getClass().getDeclaredMethod("name");
            name.setAccessible(true);
            System.out.println("  - " + name.invoke(o));
        }

        // 再手动调一次 init
        Method init = NeovideCursorConfigurable.class.getDeclaredMethod("initColorBindings");
        init.setAccessible(true);
        init.invoke(conf);
        System.out.println("再调 init 后 size = " + size.invoke(group));
        System.out.println("再调 init 后 items = " + ((List<?>) items.get(group)).size());
        System.exit(0);
    }
}
