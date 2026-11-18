package local.overtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class OaCredentials {
    private static final String ALIAS = "overtime-oa-login";
    private static final String LOGIN = "login";
    private static final int IV_BYTES = 12;
    private final SharedPreferences preferences;

    static final class Account {
        final String username;
        final String password;

        Account(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    OaCredentials(Context context) {
        preferences = context.getSharedPreferences("oa_credentials", Context.MODE_PRIVATE);
    }

    boolean remembered() {
        return preferences.contains(LOGIN);
    }

    Account read() throws IOException, GeneralSecurityException, JSONException {
        String saved = preferences.getString(LOGIN, null);
        if (saved == null) {
            return null;
        }
        byte[] sealed = Base64.decode(saved, Base64.NO_WRAP);
        byte[] iv = Arrays.copyOfRange(sealed, 0, IV_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        byte[] plaintext = cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        try {
            JSONArray fields = new JSONArray(new String(plaintext, StandardCharsets.UTF_8));
            return new Account(fields.getString(0), fields.getString(1));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    void save(Account account) throws IOException, GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] plaintext = new JSONArray().put(account.username).put(account.password)
                .toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try {
            encrypted = cipher.doFinal(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
        byte[] sealed = Arrays.copyOf(cipher.getIV(), IV_BYTES + encrypted.length);
        System.arraycopy(encrypted, 0, sealed, IV_BYTES, encrypted.length);
        if (!preferences.edit().putString(LOGIN, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) {
            throw new IOException("无法保存 OA 登录信息。");
        }
    }

    void forget() {
        preferences.edit().remove(LOGIN).apply();
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null || !create) {
            return existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
