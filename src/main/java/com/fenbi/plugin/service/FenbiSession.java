package com.fenbi.plugin.service;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;

/**
 * 登录态（Cookie）与凭据管理。
 * 所有配置统一加密保存在 ~/.fenbi-plugin/config.bin（AES-256-GCM）。
 */
public final class FenbiSession {
    private static final String SERVICE = "FenbiQuiz";
    private static final String ACCOUNT = "cookie";

    private final CredentialAttributes attributes = new CredentialAttributes(SERVICE, ACCOUNT);

    private volatile String cookie;
    private volatile boolean loaded;
    private volatile FenbiApi api;

    public static FenbiSession getInstance() {
        return ApplicationManager.getApplication().getService(FenbiSession.class);
    }

    public FenbiApi api() {
        FenbiApi local = api;
        if (local == null) {
            synchronized (this) {
                if (api == null) {
                    api = new FenbiApi(this::cookie);
                }
                local = api;
            }
        }
        return local;
    }

    public String cookie() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    SecureConfig config = SecureConfig.getInstance();
                    String value = config.get("cookie", "");
                    if (value.isBlank()) {
                        try {
                            String legacy = PasswordSafe.getInstance().getPassword(attributes);
                            if (legacy != null && !legacy.isBlank()) {
                                value = legacy;
                                config.put("cookie", legacy);
                                config.save();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    cookie = value;
                    loaded = true;
                }
            }
        }
        return cookie == null ? "" : cookie;
    }

    public boolean isLoggedIn() {
        return !cookie().isBlank();
    }

    public void save(String cookie) {
        this.cookie = cookie == null ? "" : cookie;
        this.loaded = true;
        this.api = new FenbiApi(this::cookie);
        SecureConfig config = SecureConfig.getInstance();
        config.put("cookie", this.cookie);
        config.save();
    }

    public void clear() {
        save("");
    }

    /**
     * 读取额外的凭据（计划地址 token / webhook token）。
     */
    private final java.util.Map<String, String> memorySecrets = new java.util.concurrent.ConcurrentHashMap<>();

    public String secret(String account) {
        String memory = memorySecrets.get(account);
        if (memory != null) {
            return memory;
        }
        SecureConfig config = SecureConfig.getInstance();
        String value = config.get("secret." + account, "");
        if (!value.isBlank()) {
            return value;
        }
        try {
            String legacy = PasswordSafe.getInstance().getPassword(new CredentialAttributes(SERVICE, account));
            if (legacy != null && !legacy.isBlank()) {
                config.put("secret." + account, legacy);
                config.save();
                return legacy;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public void saveSecret(String account, String value) {
        String normalized = value == null ? "" : value;
        memorySecrets.put(account, normalized);
        SecureConfig config = SecureConfig.getInstance();
        config.put("secret." + account, normalized);
        config.save();
    }
}
