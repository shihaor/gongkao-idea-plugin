package com.fenbi.plugin.service;

import com.fenbi.plugin.model.Models.Question;
import com.fenbi.plugin.model.Models.QuestionSet;
import com.fenbi.plugin.model.Models.Solution;
import com.fenbi.plugin.model.Models.SolutionSet;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地错题本，AES-256-GCM 加密保存在 ~/.fenbi-plugin/wrongbook.bin。
 */
public final class WrongBook {
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_ENTRIES = 500;
    private static final Path FILE = Paths.get(System.getProperty("user.home"), ".fenbi-plugin", "wrongbook.bin");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final List<Entry> entries = new ArrayList<>();
    private boolean loaded;

    public static WrongBook getInstance() {
        return ApplicationManager.getApplication().getService(WrongBook.class);
    }

    public static final class Entry {
        public long questionId;
        public String type = "";
        public String path = "";
        public long keypointId;
        public String stem = "";
        public String contentHtml = "";
        public List<String> options = new ArrayList<>();
        public Integer your;
        public String correct = "";
        public String analysisHtml = "";
        public String source = "";
        public String updatedAt = "";
        public int wrongCount;
    }

    /**
     * 把一组题里答错的记录进错题本，同一道题去重并累计错误次数。
     */
    public synchronized void record(QuestionSet set, SolutionSet solutions, Map<Long, Integer> answers,
                                    String type, String path, long keypointId, String source) {
        if (set == null || set.questions().isEmpty()) return;
        load();

        Map<Long, Solution> byId = new HashMap<>();
        for (Solution solution : solutions.solutions()) {
            byId.put(solution.questionId(), solution);
        }

        boolean changed = false;
        for (Question question : set.questions()) {
            Solution solution = byId.get(question.id());
            Integer chosen = answers.get(question.id());
            String correct = solution == null ? "" : FenbiApi.normalizeChoice(solution.answer());
            boolean ok = chosen != null && !correct.isBlank() && String.valueOf(chosen).equals(correct);
            if (ok) continue;

            Entry entry = new Entry();
            entry.questionId = question.id();
            entry.type = type == null ? "" : type;
            entry.path = path == null ? "" : path;
            entry.keypointId = keypointId;
            entry.stem = stem(question.content());
            entry.contentHtml = question.content();
            entry.options = question.options();
            entry.your = chosen;
            entry.correct = correct;
            entry.analysisHtml = solution == null ? "" : solution.solution();
            entry.source = source == null ? "" : source;
            entry.wrongCount = 1;
            upsert(entry);
            changed = true;
        }
        if (changed) {
            trim();
            save();
        }
    }

    public synchronized List<Entry> all() {
        load();
        return new ArrayList<>(entries);
    }

    public synchronized int size() {
        load();
        return entries.size();
    }

    public synchronized void remove(long questionId) {
        load();
        if (entries.removeIf(entry -> entry.questionId == questionId)) {
            save();
        }
    }

    public synchronized void clear() {
        load();
        entries.clear();
        save();
    }

    private void upsert(Entry entry) {
        for (Entry existing : entries) {
            if (existing.questionId == entry.questionId) {
                existing.type = entry.type;
                existing.path = entry.path;
                existing.keypointId = entry.keypointId != 0 ? entry.keypointId : existing.keypointId;
                existing.stem = entry.stem;
                existing.contentHtml = entry.contentHtml;
                existing.options = entry.options;
                existing.your = entry.your;
                existing.correct = entry.correct;
                existing.analysisHtml = entry.analysisHtml;
                existing.source = entry.source;
                existing.updatedAt = LocalDateTime.now().format(TIME_FMT);
                existing.wrongCount++;
                entries.remove(existing);
                entries.add(0, existing);
                return;
            }
        }
        entry.updatedAt = LocalDateTime.now().format(TIME_FMT);
        entries.add(0, entry);
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    private static String stem(String html) {
        if (html == null) return "";
        String text = html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
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
            entries.clear();
            if (root.has("entries") && root.get("entries").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("entries")) {
                    Entry entry = new Gson().fromJson(element, Entry.class);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
        } catch (Exception ignored) {
            // 文件损坏或密钥变更：当作空错题本
        }
    }

    private void save() {
        try {
            JsonObject root = new JsonObject();
            root.add("entries", new Gson().toJsonTree(entries));
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
