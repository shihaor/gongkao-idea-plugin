package com.fenbi.plugin.service;

import com.fenbi.plugin.model.Models.Category;
import com.fenbi.plugin.model.Models.EssayContent;
import com.fenbi.plugin.model.Models.EssayLabel;
import com.fenbi.plugin.model.Models.EssayPaper;
import com.fenbi.plugin.model.Models.Material;
import com.fenbi.plugin.model.Models.Question;
import com.fenbi.plugin.model.Models.QuestionSet;
import com.fenbi.plugin.model.Models.Solution;
import com.fenbi.plugin.model.Models.SolutionSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 粉笔题库接口封装。所有请求都走 tiku.fenbi.com，登录态通过 Cookie 头带上。
 */
public class FenbiApi {
    public static final String BASE = "https://tiku.fenbi.com";
    private static final String COMMON = "app=web&kav=100&av=100&hav=100&version=3.0.0.0";
    private static final String FORM = "application/x-www-form-urlencoded;charset=UTF-8";
    private static final String JSON = "application/json;charset=UTF-8";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final Supplier<String> cookieSupplier;
    private final HttpClient http;

    public FenbiApi(Supplier<String> cookieSupplier) {
        this.cookieSupplier = cookieSupplier;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static class ApiException extends IOException {
        private final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public static class LoginRequiredException extends ApiException {
        public LoginRequiredException() {
            super(401, "授权已失效，请重新授权");
        }
    }

    // ------------------------------------------------------------------ 基础请求

    private String request(String method, String path, String body, String contentType) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", "https://spa.fenbi.com/")
                .header("Origin", "https://spa.fenbi.com");
        String cookie = cookieSupplier.get();
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if ("GET".equalsIgnoreCase(method)) {
            builder.GET();
        } else {
            builder.method(method.toUpperCase(Locale.ROOT),
                    HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }

        int code = response.statusCode();
        if (code == 401 || code == 403) {
            throw new LoginRequiredException();
        }
        if (code >= 400) {
            throw new ApiException(code, "题库接口返回 " + code + "：" + abbreviate(response.body()));
        }
        return response.body();
    }

    private String get(String path) throws IOException {
        return request("GET", path, null, null);
    }

    private static JsonElement parse(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static JsonObject obj(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return new JsonObject();
        }
        JsonObject root = element.getAsJsonObject();
        if (root.has("data") && root.get("data").isJsonObject()) {
            return root.getAsJsonObject("data");
        }
        return root;
    }

    private static JsonArray array(JsonElement element, String... keys) {
        JsonElement current = element;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (current.isJsonArray()) {
                return current.getAsJsonArray();
            }
            if (!current.isJsonObject()) {
                break;
            }
            JsonObject object = current.getAsJsonObject();
            for (String key : keys) {
                if (object.has(key) && object.get(key).isJsonArray()) {
                    return object.getAsJsonArray(key);
                }
            }
            if (object.has("data")) {
                current = object.get("data");
                continue;
            }
            break;
        }
        return new JsonArray();
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static int optInt(JsonObject object, String key, int defaultValue) {
        try {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsInt();
            }
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    private static long optLong(JsonObject object, String key, long defaultValue) {
        try {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsLong();
            }
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    // ------------------------------------------------------------------ 登录态

    public boolean checkLogin() throws IOException {
        categories();
        return true;
    }

    // ------------------------------------------------------------------ 行测

    public List<Category> categories() throws IOException {
        String body = get("/api/xingce/categories?filter=keypoint&" + COMMON);
        JsonArray array = array(parse(body), "categories", "children");
        List<Category> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                result.add(parseCategory(element.getAsJsonObject()));
            }
        }
        return result;
    }

    private static Category parseCategory(JsonObject object) {
        List<Category> children = new ArrayList<>();
        if (object.has("children") && object.get("children").isJsonArray()) {
            for (JsonElement child : object.getAsJsonArray("children")) {
                if (child.isJsonObject()) {
                    children.add(parseCategory(child.getAsJsonObject()));
                }
            }
        }
        return new Category(optLong(object, "id", 0L), str(object, "name"),
                optInt(object, "count", 0), optInt(object, "answerCount", 0), children);
    }

    /**
     * 读取粉笔里保存的组卷偏好（题量、年份范围、正确率区间）。
     */
    public JsonObject userQuizInfo() throws IOException {
        String body = get("/activity/userquiz/getUserQuestionInfo?" + COMMON);
        return obj(parse(body));
    }

    public long createExercise(long keypointId, int limit) throws IOException {
        int yearScope = 0;
        int low = 0;
        int high = 100;
        try {
            JsonObject info = userQuizInfo();
            yearScope = optInt(info, "yearScope", 0);
            low = optInt(info, "correctRatioLow", 0);
            high = optInt(info, "correctRatioHigh", 100);
        } catch (LoginRequiredException e) {
            throw e;
        } catch (Exception ignored) {
            // 偏好拿不到不影响组卷，用默认值
        }
        String form = "type=3&exerciseTimeMode=2"
                + "&keypointId=" + keypointId
                + "&limit=" + Math.max(1, limit)
                + "&yearScope=" + yearScope
                + "&correctRatioLow=" + low
                + "&correctRatioHigh=" + high;
        String body = request("POST", "/api/xingce/exercises?" + COMMON, form, FORM);
        JsonObject object = obj(parse(body));
        long id = optLong(object, "id", 0L);
        if (id == 0L) {
            throw new ApiException(500, "组卷失败，请稍后重试");
        }
        return id;
    }

    public QuestionSet loadQuestions(long exerciseId, String title) throws IOException {
        String body = get("/api/xingce/universal/auth/questions?type=0&id=" + exerciseId + "&" + COMMON);
        JsonObject root = obj(parse(body));
        List<Question> questions = parseQuestions(array(root, "questions"));
        List<Material> materials = parseMaterials(array(root, "materials"));
        Map<Long, String> answers = userAnswers(exerciseId);
        return new QuestionSet(title == null ? "" : title, exerciseId, questions, materials, answers);
    }

    public Map<Long, String> userAnswers(long exerciseId) {
        Map<Long, String> result = new LinkedHashMap<>();
        try {
            String body = get("/api/xingce/exercises/" + exerciseId + "?" + COMMON);
            JsonObject root = obj(parse(body));
            if (root.has("userAnswers") && root.get("userAnswers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("userAnswers").entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject item = entry.getValue().getAsJsonObject();
                    if (item.has("answer") && item.get("answer").isJsonObject()) {
                        JsonObject answer = item.getAsJsonObject("answer");
                        String choice = str(answer, "choice");
                        if (!choice.isBlank()) {
                            result.put(Long.parseLong(entry.getKey()), choice);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 没有答题记录很常见，忽略
        }
        return result;
    }

    private static List<Question> parseQuestions(JsonArray array) {
        List<Question> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            result.add(new Question(
                    optLong(object, "id", 0L),
                    str(object, "content"),
                    optInt(object, "type", 0),
                    optInt(object, "difficulty", 0),
                    parseOptions(object),
                    parseMaterialIndexes(object)));
        }
        return result;
    }

    private static List<Integer> parseMaterialIndexes(JsonObject object) {
        List<Integer> result = new ArrayList<>();
        if (object.has("materialIndexes") && object.get("materialIndexes").isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray("materialIndexes")) {
                try {
                    result.add(element.getAsInt());
                } catch (Exception ignored) {
                }
            }
        } else if (object.has("material") && object.get("material").isJsonPrimitive()) {
            try {
                result.add(object.get("material").getAsInt());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static List<String> parseOptions(JsonObject object) {
        List<String> result = new ArrayList<>();
        if (!object.has("accessories") || !object.get("accessories").isJsonArray()) {
            return result;
        }
        for (JsonElement element : object.getAsJsonArray("accessories")) {
            if (!element.isJsonObject()) continue;
            JsonObject accessory = element.getAsJsonObject();
            if (accessory.has("options") && accessory.get("options").isJsonArray()) {
                for (JsonElement option : accessory.getAsJsonArray("options")) {
                    if (option == null || option.isJsonNull()) {
                        result.add("");
                    } else if (option.isJsonPrimitive()) {
                        result.add(option.getAsString());
                    } else if (option.isJsonObject()) {
                        result.add(str(option.getAsJsonObject(), "content"));
                    } else {
                        result.add("");
                    }
                }
                break;
            }
        }
        return result;
    }

    private static List<Material> parseMaterials(JsonArray array) {
        List<Material> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                result.add(new Material(str(element.getAsJsonObject(), "content")));
            }
        }
        return result;
    }

    /**
     * 提交单题作答。choice 传选项下标（字符串）。
     */
    public void answer(long exerciseId, long questionId, int questionIndex, int choice, int seconds) throws IOException {
        JsonObject payload = new JsonObject();
        JsonObject answer = new JsonObject();
        answer.addProperty("type", 201);
        answer.addProperty("choice", String.valueOf(choice));
        payload.add("answer", answer);
        payload.addProperty("flag", 0);
        payload.addProperty("questionId", questionId);
        payload.addProperty("questionIndex", questionIndex);
        payload.addProperty("time", Math.max(1, seconds));

        JsonArray array = new JsonArray();
        array.add(payload);
        request("POST", "/api/xingce/async/exercises/" + exerciseId + "/incr?" + COMMON,
                array.toString(), JSON);
    }

    public SolutionSet submitAndLoadSolutions(long exerciseId) throws IOException {
        request("POST", "/api/xingce/async/exercises/" + exerciseId + "/submit?" + COMMON, "status=3", FORM);

        JsonObject detail = obj(parse(get("/api/xingce/exercises/" + exerciseId + "?" + COMMON)));
        int correctCount = optInt(detail, "correctCount", -1);
        int questionCount = optInt(detail, "questionCount", -1);

        String body = get("/api/xingce/universal/auth/solutions?type=0&id=" + exerciseId + "&" + COMMON);
        JsonObject root = obj(parse(body));
        List<Solution> solutions = parseSolutions(array(root, "solutions"));
        List<Material> materials = parseMaterials(array(root, "materials"));
        if (questionCount <= 0) {
            questionCount = solutions.size();
        }
        return new SolutionSet(correctCount, questionCount, solutions, materials);
    }

    private static List<Solution> parseSolutions(JsonArray array) {
        List<Solution> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            result.add(new Solution(
                    optLong(object, "id", 0L),
                    str(object, "content"),
                    str(object, "solution"),
                    parseAnswer(object),
                    parseOptions(object)));
        }
        return result;
    }

    private static String parseAnswer(JsonObject object) {
        if (object.has("correctAnswer")) {
            JsonElement element = object.get("correctAnswer");
            if (element.isJsonObject()) {
                JsonObject answer = element.getAsJsonObject();
                for (String key : new String[]{"choice", "answer", "value"}) {
                    String value = str(answer, key);
                    if (!value.isBlank()) return normalizeChoice(value);
                }
            } else if (element.isJsonPrimitive()) {
                return normalizeChoice(element.getAsString());
            }
        }
        for (String key : new String[]{"answer", "rightAnswer"}) {
            String value = str(object, key);
            if (!value.isBlank()) return normalizeChoice(value);
        }
        return "";
    }

    /**
     * 把 "A"/"a"/"0" 统一成下标字符串 "0"。
     */
    public static String normalizeChoice(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        if (trimmed.length() == 1) {
            char c = Character.toUpperCase(trimmed.charAt(0));
            if (c >= 'A' && c <= 'Z') {
                return String.valueOf(c - 'A');
            }
        }
        try {
            int index = Integer.parseInt(trimmed);
            if (index >= 0 && index < 26) {
                return String.valueOf(index);
            }
        } catch (NumberFormatException ignored) {
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ 申论

    public List<EssayLabel> essayLabels() throws IOException {
        String body = get("/api/shenlun/subLabels?" + COMMON);
        JsonArray array = array(parse(body), "subLabels", "labels");
        List<EssayLabel> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            JsonObject meta = object.has("labelMeta") && object.get("labelMeta").isJsonObject()
                    ? object.getAsJsonObject("labelMeta") : object;
            List<Long> paperIds = new ArrayList<>();
            if (meta.has("paperIds") && meta.get("paperIds").isJsonArray()) {
                for (JsonElement paper : meta.getAsJsonArray("paperIds")) {
                    try {
                        paperIds.add(paper.getAsLong());
                    } catch (Exception ignored) {
                    }
                }
            }
            result.add(new EssayLabel(str(object, "name"), paperIds));
        }
        return result;
    }

    public EssayPaper essayPaper(long paperId) throws IOException {
        String body = get("/api/shenlun/papers/" + paperId + "?" + COMMON);
        JsonObject object = obj(parse(body));
        return new EssayPaper(optLong(object, "id", paperId), str(object, "name"),
                str(object, "date"), str(object, "topic"), str(object, "encodeCheckInfo"));
    }

    public EssayContent essayContent(EssayPaper paper) throws IOException {
        String query = "/api/shenlun/universal/auth/solutions?type=7&id=" + paper.id()
                + "&checkId=" + URLEncoder.encode(paper.checkInfo(), StandardCharsets.UTF_8)
                + "&" + COMMON;
        JsonObject root = obj(parse(get(query)));
        List<Material> materials = parseMaterials(array(root, "materials"));
        List<Solution> raw = parseSolutions(array(root, "solutions"));
        List<Solution> questions = new ArrayList<>(raw);
        List<Solution> references = new ArrayList<>();
        for (Solution solution : raw) {
            if (!solution.solution().isBlank()) {
                references.add(solution);
            }
        }
        return new EssayContent(materials, questions, references);
    }
}
