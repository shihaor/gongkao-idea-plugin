package com.fenbi.plugin.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地加密配置。整份配置序列化成 JSON，AES-256-GCM 加密后写入
 * {@code ~/.fenbi-plugin/config.bin}，密钥由固定口令派生。
 *
 * 说明：密钥固定在插件里，能防止配置文件被直接看到，但不能防住反编译；
 * 如果需要更强的保护，应改为让用户输入口令。
 */
public final class SecureConfig {
    private static final String PASSPHRASE = "shr123456789000";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final Path FILE = Paths.get(System.getProperty("user.home"), ".fenbi-plugin", "config.bin");

    private final Map<String, String> values = new LinkedHashMap<>();
    private boolean loaded;

    public static SecureConfig getInstance() {
        return ApplicationManager.getApplication().getService(SecureConfig.class);
    }

    public synchronized String get(String key, String defaultValue) {
        load();
        String value = values.get(key);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    public synchronized boolean has(String key) {
        load();
        String value = values.get(key);
        return value != null && !value.isEmpty();
    }

    public synchronized void put(String key, String value) {
        load();
        values.put(key, value == null ? "" : value);
    }

    public synchronized void save() {
        load();
        try {
            byte[] plain = new com.google.gson.Gson().toJson(values).getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain);

            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);

            Files.createDirectories(FILE.getParent());
            Files.write(FILE, output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            byte[] all = Files.readAllBytes(FILE);
            if (all.length <= IV_LENGTH) return;

            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(all, IV_LENGTH, all.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(TAG_BITS, iv));
            String json = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);

            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            values.clear();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                values.put(entry.getKey(), entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString());
            }
        } catch (Exception ignored) {
            // 文件损坏或密钥变更：当作空配置，用户重新填一次即可
        }
    }

    static SecretKeySpec keySpec() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(PASSPHRASE.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
