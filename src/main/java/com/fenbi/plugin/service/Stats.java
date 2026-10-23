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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 本地练习统计，AES-256-GCM 加密保存在 ~/.fenbi-plugin/stats.bin。
 * 按天累计题量/正确数/用时，并按考点累计。
 */
public final class Stats {
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEEP_DAYS = 60;
    private static final Path FILE = Paths.get(System.getProperty("user.home"), ".fenbi-plugin", "stats.bin");

    private final Map<String, Day> days = new TreeMap<>();
    private boolean loaded;

    public static Stats getInstance() {
        return ApplicationManager.getApplication().getService(Stats.class);
    }

    public static final class TypeStat {
        public int total;
        public int correct;
    }

    public static final class Day {
        public String date = "";
        public int total;
        public int correct;
        public int seconds;
        public Map<String, TypeStat> types = new LinkedHashMap<>();
    }

    /**
     * 从一组题的结果里算正确数并记录。
     */
    public synchronized void record(QuestionSet set, SolutionSet solutions, Map<Long, Integer> answers,
                                    String type, int seconds) {
        if (set == null || set.questions().isEmpty()) return;
        Map<Long, Solution> byId = new HashMap<>();
        for (Solution solution : solutions.solutions()) {
            byId.put(solution.questionId(), solution);
        }
        int correct = 0;
        for (Question question : set.questions()) {
            Solution solution = byId.get(question.id());
            Integer chosen = answers.get(question.id());
            String correctChoice = solution == null ? "" : FenbiApi.normalizeChoice(solution.answer());
            if (chosen != null && !correctChoice.isBlank() && String.valueOf(chosen).equals(correctChoice)) {
                correct++;
            }
        }
        record(type, set.questions().size(), correct, seconds);
    }

    public synchronized void record(String type, int total, int correct, int seconds) {
        if (total <= 0) return;
        load();

        String date = LocalDate.now().toString();
        Day day = days.get(date);
        if (day == null) {
            day = new Day();
            day.date = date;
            days.put(date, day);
        }
        day.total += total;
        day.correct += Math.min(Math.max(0, correct), total);
        day.seconds += Math.max(0, seconds);

        String key = type == null || type.isBlank() ? "其他" : type;
        TypeStat typeStat = day.types.get(key);
        if (typeStat == null) {
            typeStat = new TypeStat();
            day.types.put(key, typeStat);
        }
        typeStat.total += total;
        typeStat.correct += Math.min(Math.max(0, correct), total);

        trim();
        save();
    }

    /**
     * 最近 n 天（含今天），没有数据的天补 0。
     */
    public synchronized List<Day> lastDays(int count) {
        load();
        List<Day> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = count - 1; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            Day day = days.get(date);
            if (day == null) {
                day = new Day();
                day.date = date;
            }
            result.add(day);
        }
        return result;
    }

    public synchronized Day today() {
        load();
        Day day = days.get(LocalDate.now().toString());
        if (day == null) {
            day = new Day();
            day.date = LocalDate.now().toString();
        }
        return day;
    }

    /**
     * 连续有练习的天数；今天还没练时从昨天算起。
     */
    public synchronized int streak() {
        load();
        LocalDate cursor = LocalDate.now();
        if (!hasData(cursor)) {
            cursor = cursor.minusDays(1);
        }
        int streak = 0;
        while (hasData(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private boolean hasData(LocalDate date) {
        Day day = days.get(date.toString());
        return day != null && day.total > 0;
    }

    private void trim() {
        LocalDate cutoff = LocalDate.now().minusDays(KEEP_DAYS);
        days.keySet().removeIf(key -> key.compareTo(cutoff.toString()) < 0);
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
            days.clear();
            if (root.has("days") && root.get("days").isJsonArray()) {
                Gson gson = new Gson();
                for (JsonElement element : root.getAsJsonArray("days")) {
                    Day day = gson.fromJson(element, Day.class);
                    if (day != null && day.date != null && !day.date.isBlank()) {
                        if (day.types == null) {
                            day.types = new LinkedHashMap<>();
                        }
                        days.put(day.date, day);
                    }
                }
            }
        } catch (Exception ignored) {
            // 文件损坏或密钥变更：当作空统计
        }
    }

    private void save() {
        try {
            JsonObject root = new JsonObject();
            root.add("days", new Gson().toJsonTree(new ArrayList<>(days.values())));
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
