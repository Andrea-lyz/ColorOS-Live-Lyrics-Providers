package io.github.andrealtb.coloroslyrics.provider.readify;

import android.content.Context;
import android.os.Handler;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;

/** Runs real module gate/accept methods with a fake host context, without MediaService. */
public final class VersionGateRegressionTest {
    static final List<String> logs = new ArrayList<>();
    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    static ReadifyModule module() throws Exception {
        ReadifyModule module = new ReadifyModule();
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
            XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
            (p, method, args) -> {
                if (method.getName().equals("log")) { logs.add((String) args[2]); return null; }
                if (method.getReturnType() == int.class) return 102;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
        module.attachFramework(framework, () -> {});
        Field main = module.getClass().getDeclaredField("main"); main.setAccessible(true); main.set(module, new Handler());
        return module;
    }
    static void accept(ReadifyModule m) throws Exception {
        Method a = m.getClass().getDeclaredMethod("accept", Object.class, String.class, String.class);
        a.setAccessible(true); a.invoke(m, m, "unit-private-id", "PRIVATE_BOOK_SENTENCE");
    }
    static void version(ReadifyModule m, String v) throws Exception {
        Method c = m.getClass().getDeclaredMethod("checkVersion", Context.class, String.class);
        c.setAccessible(true); c.invoke(m, new Context(v), "test-context-before-service");
    }
    public static void main(String[] args) throws Exception {
        ReadifyModule pending = module();
        accept(pending);
        if (!field(pending, "sentence").equals("")) throw new AssertionError("unknown version published");
        if (logs.stream().noneMatch(s -> s.contains("first sentence callback") && s.contains("supported=false")))
            throw new AssertionError("blocked callback silent again");
        version(pending, "3.1.0"); accept(pending);
        if (!field(pending, "sentence").equals("PRIVATE_BOOK_SENTENCE")) throw new AssertionError("still depends on MediaService");
        ReadifyModule unsupported = module(); version(unsupported, "3.2.0"); accept(unsupported);
        if (!field(unsupported, "sentence").equals("")) throw new AssertionError("unsupported version published");
        if (logs.stream().anyMatch(s -> s.contains("PRIVATE_BOOK_SENTENCE") || s.contains("unit-private-id")))
            throw new AssertionError("content leaked to logs");
        System.out.println("Version gate regression passed: pre-service acceptance, pending diagnostics, wrong-version rejection, private logs");
    }
}
