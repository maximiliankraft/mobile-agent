package com.maximiliankraft.mobileagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ApiKeyStore {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mobile-agent-deepseek-api-key";
    private static final String PREFERENCES_NAME = "secure_settings";
    private static final String API_KEY_PREFERENCE = "encrypted_api_key";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int AUTH_TAG_LENGTH_BITS = 128;
    private static final int AUTH_TAG_LENGTH_BYTES = AUTH_TAG_LENGTH_BITS / 8;

    private ApiKeyStore() {
    }

    static String load(Context context) throws GeneralSecurityException, IOException {
        String encodedValue = preferences(context).getString(API_KEY_PREFERENCE, null);
        if (encodedValue == null) {
            return null;
        }

        byte[] encryptedValue;
        try {
            encryptedValue = Base64.decode(encodedValue, Base64.NO_WRAP);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("The saved API key could not be decoded.",
                    exception);
        }
        if (encryptedValue.length <= IV_LENGTH_BYTES + AUTH_TAG_LENGTH_BYTES) {
            throw new GeneralSecurityException("The saved API key is incomplete.");
        }

        byte[] iv = Arrays.copyOfRange(encryptedValue, 0, IV_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encryptedValue, IV_LENGTH_BYTES,
                encryptedValue.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(),
                new GCMParameterSpec(AUTH_TAG_LENGTH_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    static void save(Context context, String apiKey)
            throws GeneralSecurityException, IOException {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("API key must not be empty.");
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_LENGTH_BYTES) {
            throw new GeneralSecurityException("The Android Keystore returned an invalid IV.");
        }
        byte[] ciphertext = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        byte[] encryptedValue = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, encryptedValue, 0, iv.length);
        System.arraycopy(ciphertext, 0, encryptedValue, iv.length, ciphertext.length);

        boolean saved = preferences(context).edit()
                .putString(API_KEY_PREFERENCE, Base64.encodeToString(encryptedValue,
                        Base64.NO_WRAP))
                .commit();
        if (!saved) {
            throw new IOException("Could not save the encrypted API key.");
        }
    }

    static boolean hasSavedKey(Context context) {
        return preferences(context).contains(API_KEY_PREFERENCE);
    }

    static void clear(Context context) throws GeneralSecurityException, IOException {
        boolean removed = preferences(context).edit()
                .remove(API_KEY_PREFERENCE)
                .commit();
        if (!removed) {
            throw new IOException("Could not remove the saved API key.");
        }

        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS);
        }
    }

    private static SecretKey getExistingKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = loadKeyStore();
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            throw new GeneralSecurityException("The saved API key encryption key is missing.");
        }
        Key key = keyStore.getKey(KEY_ALIAS, null);
        if (!(key instanceof SecretKey)) {
            throw new GeneralSecurityException("The saved API key encryption key is invalid.");
        }
        return (SecretKey) key;
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(KEY_ALIAS)) {
            Key key = keyStore.getKey(KEY_ALIAS, null);
            if (!(key instanceof SecretKey)) {
                throw new GeneralSecurityException("The API key encryption key is invalid.");
            }
            return (SecretKey) key;
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE);
        KeyGenParameterSpec keySpec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build();
        keyGenerator.init(keySpec);
        return keyGenerator.generateKey();
    }

    private static KeyStore loadKeyStore() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        return keyStore;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
