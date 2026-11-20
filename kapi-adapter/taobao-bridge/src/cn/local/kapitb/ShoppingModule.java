package cn.local.kapitb;

import android.app.Application;
import android.app.Instrumentation;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;

public final class ShoppingModule extends XposedModule {
    private static final String KAPI = "com.kapinote.ai";
    private static final String TAOBAO = "com.taobao.taobao";
    private static final long KAPI_VERSION_CODE = 367; // 1.30.01
    private static final String TAG = "KapiShopping";

    private String process;
    private HookHandle startupHook;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName();
        log(Log.INFO, TAG, "onModuleLoaded " + process);
    }

    @Override public void onPackageLoaded(PackageLoadedParam param) {
        log(Log.INFO, TAG, "onPackageLoaded " + param.getPackageName() + " first=" + param.isFirstPackage());
        if (!param.isFirstPackage() || !KAPI.equals(param.getPackageName())) return;
        try {
            KapiBootstrap.install(this, param.getApplicationInfo(), param.getDefaultClassLoader());
        } catch (IOException | GeneralSecurityException | ReflectiveOperationException error) {
            log(Log.ERROR, TAG, "Cannot initialize Kapi business classes", error);
            throw new IllegalStateException("Cannot initialize Kapi business classes", error);
        }
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        log(Log.INFO, TAG, "onPackageReady " + pkg + " first=" + param.isFirstPackage()
                + " application=" + param.getApplicationInfo().className
                + " factory=" + param.getAppComponentFactory().getClass().getName());
        if (!param.isFirstPackage() || !pkg.equals(process)) return;
        if (!KAPI.equals(pkg) && !TAOBAO.equals(pkg)) return;
        try {
            Method startup = Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            startupHook = hook(startup).intercept(chain -> {
                // Install the order hooks before Application.onCreate.
                Application app = (Application) chain.getArg(0);
                if (!pkg.equals(app.getPackageName())) return chain.proceed();
                log(Log.INFO, TAG, "callApplicationOnCreate " + app.getClass().getName());
                startupHook.unhook();
                if (KAPI.equals(pkg)) {
                    if (app.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode() == KAPI_VERSION_CODE) {
                        KapiHooks.install(this, app.getClassLoader());
                    } else {
                        Log.w(TAG, "咔皮版本不受支持，需要 1.30.01");
                    }
                } else {
                    TaobaoBridge.install(this, app.getClassLoader());
                }
                return chain.proceed();
            });
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException("Cannot find Android application startup hook", error);
        }
    }
}
