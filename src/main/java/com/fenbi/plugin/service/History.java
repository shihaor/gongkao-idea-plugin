package com.fenbi.plugin.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 历史做题记录，AES-256-GCM 加密保存在 ~/.fenbi-plugin/history.bin。
 * 每次计划完成或中断时写入一条，最多保留 200 条。
 */
public final class History {
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_RECORDS = 200;
    private static final Path FILE = Paths.get(System.getProperty("user.home"), ".fenbi-plugin", "history.bin");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final List<Record> records = new ArrayList<>();
    private boolean loaded;

    public static History getInstance() {
        return ApplicationManager.getApplication().getService(History.class);
    }

    public static final class Item {
        public String type = "";
        public int count;
    }

    public static final class Record {
        public String planId = "";
        public String date = "";
        public String updatedAt = "";
        public String status = "";
        public int totalQuestions;
        public int completedQuestions;
        public int correct;
        public int elapsedSeconds;
        public List<Item> items = new ArrayList<>();
    }

    /**
     * 把当前计划状态写成一条记录（同 planId 会覆盖）。
     */
    public synchronized void record(PlanStore.State state, String status) {
        if (state == null) return;
        load();
        Record record = convert(state, status);
        records.removeIf(existing -> existing.planId.equals(record.planId));
        records.add(0, record);
        while (records.size() > MAX_RECORDS) {
            records.remove(records.size() - 1);
        }
        save();
    }

    /**
     * 生成用于展示的临时记录（不落盘），用于「进行中」的那条。
     */
    public synchronized Record preview(PlanStore.State state) {
        if (state == null) return null;
        return convert(state, "active");
    }

    public synchronized List<Record> all() {
        load();
        return new ArrayList<>(records);
    }

    private static Record convert(PlanStore.State state, String status) {
        Record record = new Record();
        record.planId = state.planId == null ? "" : state.planId;
        record.date = state.date == null ? "" : state.date;
        record.updatedAt = LocalDateTime.now().format(TIME_FMT);
        record.status = status;

        int total = 0;
        if (state.queue != null) {
            for (PlanStore.Item item : state.queue) {
                total += Math.max(0, item.count);
                Item entry = new Item();
                entry.type = item.type;
                entry.count = item.count;
                record.items.add(entry);
            }
        }
        record.totalQuestions = total;

        int done = 0;
        int correct = 0;
        if (state.results != null) {
            for (PlanStore.Result result : state.results) {
                done += Math.max(0, result.total);
                correct += Math.max(0, result.correct);
            }
        }
        if (!"completed".equals(status) && state.questionIndex > 0) {
            done += state.questionIndex;
        }
        record.completedQuestions = Math.min(done, Math.max(0, total));
        record.correct = correct;
        record.elapsedSeconds = Math.max(0, state.elapsedSeconds);
        return record;
    }

    // ------------------------------------------------------------------ 加密存取

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            byte[] all = Files.readAllBytes(FILE);
            if (all.length <= IV_LENGTH) return;

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, SecureConfig.keySpec(),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(all, 0, IV_LENGTH)));
            String json = new String(cipher.doFinal(Arrays.copyOfRange(all, IV_LENGTH, all.length)),
                    StandardCharsets.UTF_8);

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            records.clear();
            if (root.has("records") && root.get("records").isJsonArray()) {
                Gson gson = new Gson();
                for (JsonElement element : root.getAsJsonArray("records")) {
                    Record record = gson.fromJson(element, Record.class);
                    if (record != null) {
                        if (record.items == null) record.items = new ArrayList<>();
                        records.add(record);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            JsonObject root = new JsonObject();
            root.add("records", new Gson().toJsonTree(records));
            byte[] plain = root.toString().getBytes(StandardCharsets.UTF_8);

            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, SecureConfig.keySpec(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain);

            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);

            Files.createDirectories(FILE.getParent());
            Files.write(FILE, output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
