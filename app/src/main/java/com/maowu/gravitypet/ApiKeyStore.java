package com.maowu.gravitypet;

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

public final class ApiKeyStore {
    private static final String KEY_ALIAS = "gravity_pet_ai_api_key";
    private static final String STORE_NAME = "encrypted_ai_keys";

    private ApiKeyStore() {}

    public static void save(Context context, String providerId, String apiKey)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        SharedPreferences.Editor editor = context.getSharedPreferences(
                STORE_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(providerId + "_data",
                Base64.encodeToString(encrypted, Base64.NO_WRAP));
        editor.putString(providerId + "_iv",
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        editor.apply();
    }

    public static String load(Context context, String providerId) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(
                STORE_NAME, Context.MODE_PRIVATE);
        String data = preferences.getString(providerId + "_data", null);
        String iv = preferences.getString(providerId + "_iv", null);
        if (data == null || iv == null) return "";

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        byte[] decrypted = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry existing = keyStore.getEntry(KEY_ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
