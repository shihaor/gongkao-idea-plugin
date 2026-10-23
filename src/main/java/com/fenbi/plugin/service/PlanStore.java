package com.fenbi.plugin.service;

import com.fenbi.plugin.model.Models.Plan;
import com.google.gson.Gson;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 计划状态：加密保存当前计划、进度和已完成的小结，
 * 退出 IDE 后可以从上次的题号继续。
 */
public final class PlanStore {
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final Path FILE = Paths.get(System.getProperty("user.home"), ".fenbi-plugin", "plan-state.bin");

    private State state;
    private String fetchedPlanId = "";
    private String fetchedDate = "";
    private boolean loaded;

    public static PlanStore getInstance() {
        return ApplicationManager.getApplication().getService(PlanStore.class);
    }

    public static final class Item {
        public String type = "";
        public String path = "";
        public long keypointId;
        public int count;
    }

    public static final class Wrong {
        public long questionId;
        public String stem = "";
        public String your = "";
        public String correct = "";
    }

    public static final class Result {
        public String type = "";
        public String path = "";
        public int total;
        public int correct;
        public int seconds;
        public List<Wrong> wrong = new ArrayList<>();
    }

    public static final class State {
        public String planId = "";
        public String date = "";
        public String deadline = "";
        public String note = "";
        public List<Item> items = new ArrayList<>();
        public List<Item> queue = new ArrayList<>();
        public int index;
        public long exerciseId;
        public int questionIndex;
        public List<Result> results = new ArrayList<>();
        public int elapsedSeconds;
        public boolean finished;
        public String updatedAt = "";
    }

    public synchronized State begin(Plan plan, List<Item> queue) {
        load();
        State created = new State();
        created.planId = plan.planId();
        created.date = plan.date();
        created.deadline = plan.deadline();
        created.note = plan.note();
        created.items = new ArrayList<>(queue);
        created.queue = new ArrayList<>(queue);
        created.index = 0;
        created.finished = false;
        created.updatedAt = LocalDateTime.now().toString();
        state = created;
        save();
        return created;
    }

    public synchronized State unfinishedToday() {
        load();
        if (state == null || state.finished) return null;
        if (!LocalDate.now().toString().equals(state.date)) return null;
        if (state.queue == null || state.queue.isEmpty()) return null;
        if (state.index >= state.queue.size()) return null;
        return state;
    }

    public synchronized void setExercise(long exerciseId) {
        load();
        if (state == null) return;
        state.exerciseId = exerciseId;
        state.updatedAt = LocalDateTime.now().toString();
        save();
    }

    public synchronized void setQuestion(int index) {
        load();
        if (state == null) return;
        if (state.questionIndex == index) return;
        state.questionIndex = Math.max(0, index);
        state.updatedAt = LocalDateTime.now().toString();
        save();
    }

    public synchronized void complete(Result result) {
        load();
        if (state == null) return;
        state.results.add(result);
        state.elapsedSeconds += Math.max(0, result.seconds);
        state.index++;
        state.exerciseId = 0;
        state.questionIndex = 0;
        state.updatedAt = LocalDateTime.now().toString();
        save();
    }

    public synchronized void finish() {
        load();
        if (state == null) return;
        state.finished = true;
        state = null;
        save();
    }

    public synchronized void clear() {
        load();
        state = null;
        save();
    }

    /**
     * 标记今天已经拉到过计划，避免多个 IDE 实例重复问询。
     */
    public synchronized void markFetched(Plan plan) {
        load();
        fetchedPlanId = plan.planId();
        fetchedDate = plan.date().isBlank() ? LocalDate.now().toString() : plan.date();
        save();
    }

    public synchronized boolean fetchedToday() {
        load();
        return LocalDate.now().toString().equals(fetchedDate);
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
            Gson gson = new Gson();
            if (root.has("state") && !root.get("state").isJsonNull()) {
                state = gson.fromJson(root.get("state"), State.class);
            }
            if (root.has("fetchedPlanId") && !root.get("fetchedPlanId").isJsonNull()) {
                fetchedPlanId = root.get("fetchedPlanId").getAsString();
            }
            if (root.has("fetchedDate") && !root.get("fetchedDate").isJsonNull()) {
                fetchedDate = root.get("fetchedDate").getAsString();
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            JsonObject root = new JsonObject();
            Gson gson = new Gson();
            if (state != null) {
                root.add("state", gson.toJsonTree(state));
            }
            root.addProperty("fetchedPlanId", fetchedPlanId);
            root.addProperty("fetchedDate", fetchedDate);
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
