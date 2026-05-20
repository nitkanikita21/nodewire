package dev.nitka.nodewire;

import java.lang.reflect.Method;

/**
 * Runs the actual {@code Module.implAddReads} reflection from an UNNAMED module.
 *
 * <p>Loaded indirectly by {@link JpmsBridge} via a fresh anonymous ClassLoader so
 * that the JVM places this class in that loader's unnamed module. The
 * {@code --add-opens=java.base/java.lang=ALL-UNNAMED} JVM arg then covers the
 * caller during {@code setAccessible(true)} — which is the only piece that can't
 * be done from the mod's named module on the PLUGIN layer.
 *
 * <p>The class must not be reached by normal classloading; if mod code references
 * it by name, the SecureModuleClassLoader would load it into the {@code nodewire}
 * named module and {@code setAccessible} would fail again. Access is strictly via
 * {@code getResourceAsStream + defineClass} from {@link JpmsBridge}.
 */
public final class JpmsBridgeHelper {
    private JpmsBridgeHelper() {}

    public static void bridge() throws ReflectiveOperationException {
        Class<?> dpk = Class.forName("kotlin.coroutines.jvm.internal.DebugProbesKt");
        Class<?> dpi = Class.forName("kotlinx.coroutines.debug.internal.DebugProbesImpl");
        Module stdlib = dpk.getModule();
        Module coroutines = dpi.getModule();

        Method m = Module.class.getDeclaredMethod("implAddReads", Module.class);
        m.setAccessible(true);
        m.invoke(stdlib, coroutines);
        // Inverse direction is cheap and defensive; some debug paths reach back.
        m.invoke(coroutines, stdlib);
    }
}
