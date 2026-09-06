package com.bydxdm.edgetunnelupdater;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the Cloudflare token locally using an Android Keystore AES key. */
public final class SecureStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "EdgeTunnelUpdaterTokenKey";
    private static final String PREFS = "secure_settings";
    private static final String TOKEN = "cloudflare_token";

    private SecureStore() {
    }

    public static void saveToken(Context context, String token) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // Keystore 密钥默认要求随机化加密：加密时不能传入调用方生成的 IV，
        // 否则 init 会抛 InvalidAlgorithmParameterException，IV 必须取自 cipher 本身。
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + "."
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(TOKEN, packed).apply();
    }

    public static String readToken(Context context) {
        try {
            String packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(TOKEN, null);
            if (packed == null || !packed.contains(".")) return "";
            String[] pieces = packed.split("\\.", 2);
            byte[] iv = Base64.decode(pieces[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(pieces[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void clearToken(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(TOKEN).apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
