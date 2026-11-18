package cn.local.kapitb;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class RootBootstrap {
    private static final String VECTOR = "/data/adb/modules/zygisk_vector/cli";
    private static final String PACKAGE = "cn.local.kapitb";

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32768];
        int size;
        while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
    }

    private static String root(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-M", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) { copy(input, output); }
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) throw new IOException("Root 操作失败：" + text);
        return text;
    }

    private static boolean enabled() throws Exception {
        JSONArray modules = new JSONObject(root(VECTOR + " modules ls --json")).getJSONArray("data");
        for (int i = 0; i < modules.length(); i++) {
            JSONObject item = modules.getJSONObject(i);
            if (PACKAGE.equals(item.getString("PACKAGE"))) return "enabled".equals(item.getString("STATUS"));
        }
        throw new IllegalStateException("Vector 尚未识别此模块");
    }

    private static File extract(Context context, String asset, String name) throws IOException {
        File result = new File(context.getFilesDir(), name);
        if (result.isFile()) return result;
        File temporary = new File(context.getFilesDir(), name + ".tmp");
        try (InputStream input = context.getAssets().open(asset); FileOutputStream output = new FileOutputStream(temporary)) {
            copy(input, output);
            output.getFD().sync();
        }
        if (!temporary.renameTo(result)) throw new IOException("无法保存适配文件：" + name);
        return result;
    }

    static void status(Context context, String text) {
        context.getSharedPreferences("state", Context.MODE_PRIVATE).edit()
                .putString("status", text).putLong("updated_at", System.currentTimeMillis()).apply();
    }

    static synchronized String apply(Context context, boolean enableModule) throws Exception {
        if (enableModule) {
            root("set -e\n" + VECTOR + " scope set " + PACKAGE + " com.taobao.taobao/0\n"
                    + VECTOR + " modules enable " + PACKAGE);
        }
        if (!enabled()) {
            String text = "Vector 模块已停用，未加载适配。";
            status(context, text);
            return text;
        }
        File apk = extract(context, "kapi.apk", "kapi-" + BuildConfig.PATCH_HASH + ".apk");
        File script = extract(context, "bootstrap.sh", "bootstrap-" + BuildConfig.PATCH_HASH + ".sh");
        String result = root("/system/bin/sh " + quote(script.getAbsolutePath()) + " apply " + quote(apk.getAbsolutePath())
                + (enableModule ? " 1" : " 0"));
        String[] fields = result.split("\\|", 2);
        if (fields.length != 2 || !"apply".equals(fields[0])) throw new IOException("加载结果异常：" + result);
        context.getSharedPreferences("state", Context.MODE_PRIVATE).edit().putString("applied_boot_id", fields[1]).apply();
        String text = "已启用，京东和淘宝适配已加载。重启后会自动加载。";
        status(context, text);
        return text;
    }

    static synchronized String stop(Context context) throws Exception {
        root(VECTOR + " modules disable " + PACKAGE);
        File script = extract(context, "bootstrap.sh", "bootstrap-" + BuildConfig.PATCH_HASH + ".sh");
        root("/system/bin/sh " + quote(script.getAbsolutePath()) + " restore '' 1");
        String text = "已停用，已恢复原版咔皮。京东和淘宝适配均停止。";
        status(context, text);
        return text;
    }
}
