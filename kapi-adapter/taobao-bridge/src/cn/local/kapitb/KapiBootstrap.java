package cn.local.kapitb;

import android.content.pm.ApplicationInfo;
import android.util.Log;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.InMemoryDexClassLoader;
import io.github.libxposed.api.XposedModule;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.DigestInputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class KapiBootstrap {
    private static final String TAG = "KapiShopping";
    private static final String STOCK_SHA256 =
            "85a39277816e84e9c0d755144354a4479ab73e84b91fa823d56b3542efb9b39f";
    private static final int[] BUSINESS_OFFSETS = {
        49152, 10027008, 10043392, 18743296, 27279360,
        35061760, 43991040, 53035008, 61374464, 66600960
    };
    private static final String SHELL_APPLICATION = "com.Proxy.ShellApplication";
    private static final String MAIN_APPLICATION = "com.kapinote.ai.MainApp";

    static void install(XposedModule module, ApplicationInfo app, ClassLoader loader)
            throws IOException, GeneralSecurityException, ReflectiveOperationException {
        if (!matchesStockApk(app.sourceDir)) {
            module.log(Log.WARN, TAG, "咔皮 APK 与验证过的原版 1.30.01 不匹配");
            return;
        }
        module.log(Log.INFO, TAG, "Verified stock Kapi APK hash");

        ByteBuffer[] dexFiles = readBusinessDexFiles(app.sourceDir);
        InMemoryDexClassLoader dexLoader = new InMemoryDexClassLoader(dexFiles, loader.getParent());
        Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
        pathListField.setAccessible(true);
        Object appPathList = pathListField.get(loader);
        Field elementsField = appPathList.getClass().getDeclaredField("dexElements");
        elementsField.setAccessible(true);
        Object[] original = (Object[]) elementsField.get(appPathList);
        Object[] business = (Object[]) elementsField.get(pathListField.get(dexLoader));
        Object[] combined = Arrays.copyOf(business, business.length + original.length);
        System.arraycopy(original, 0, combined, business.length, original.length);
        elementsField.set(appPathList, combined);
        module.log(Log.INFO, TAG, "Inserted " + business.length + " Kapi business DEX files");

        Class<?> factory = loader.loadClass("androidx.core.app.CoreComponentFactory");
        Method instantiate = factory.getDeclaredMethod(
                "instantiateApplication", ClassLoader.class, String.class);
        module.hook(instantiate).intercept(chain -> {
            module.log(Log.INFO, TAG, "CoreComponentFactory.instantiateApplication " + chain.getArg(1));
            Object result = SHELL_APPLICATION.equals(chain.getArg(1))
                    ? chain.proceed(new Object[] {chain.getArg(0), MAIN_APPLICATION})
                    : chain.proceed();
            module.log(Log.INFO, TAG, "CoreComponentFactory created " + result.getClass().getName());
            return result;
        });
        app.className = MAIN_APPLICATION;
        module.log(Log.INFO, TAG, "Installed Kapi factory hook for " + app.className);
    }

    private static boolean matchesStockApk(String sourceDir) throws IOException, GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream input = new DigestInputStream(new FileInputStream(sourceDir), digest)) {
            byte[] buffer = new byte[65536];
            while (input.read(buffer) != -1) {
                // DigestInputStream hashes each APK chunk as it is read.
            }
        }
        return new BigInteger(1, digest.digest()).equals(new BigInteger(STOCK_SHA256, 16));
    }

    private static ByteBuffer[] readBusinessDexFiles(String sourceDir) throws IOException {
        byte[] container;
        try (ZipFile apk = new ZipFile(sourceDir)) {
            ZipEntry entry = apk.getEntry("classes.dex");
            container = new byte[(int) entry.getSize()];
            try (DataInputStream input = new DataInputStream(apk.getInputStream(entry))) {
                input.readFully(container);
            }
        }
        ByteBuffer header = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer[] dexFiles = new ByteBuffer[BUSINESS_OFFSETS.length];
        for (int i = 0; i < BUSINESS_OFFSETS.length; i++) {
            int offset = BUSINESS_OFFSETS[i];
            int size = header.getInt(offset + 32);
            dexFiles[i] = ByteBuffer.wrap(container, offset, size).slice();
        }
        return dexFiles;
    }
}
