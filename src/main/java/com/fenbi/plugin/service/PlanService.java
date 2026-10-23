package com.fenbi.plugin.service;

import com.fenbi.plugin.model.Models.Plan;
import com.fenbi.plugin.model.Models.PlanItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 计划轮询：每天 21:00 之后按间隔（默认 60 分钟）向 OpenClaw 问询今日计划。
 * 拿到计划后弹通知，点「开始」进入计划模式。
 */
public class PlanService {
    private static final String KEY_URL = "fenbi.openclawUrl";
    private static final String KEY_MODEL = "fenbi.openclawModel";
    private static final String KEY_HOOK = "fenbi.hookUrl";
    private static final String KEY_START_HOUR = "fenbi.planStartHour";
    private static final String KEY_INTERVAL = "fenbi.planInterval";
    private static final String KEY_LAST_ID = "fenbi.lastPlanId";
    private static final String KEY_LAST_DATE = "fenbi.lastPlanDate";
    private static final String DEFAULT_URL = "http://127.0.0.1:18789";

    private static final String PLAN_SYSTEM = "你是我的行测刷题教练，回答只输出 JSON，不要解释。";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private volatile Project project;
    private volatile ScheduledFuture<?> future;
    private volatile boolean polling;
    private volatile long lastPollAt;
    private volatile String lastError = "";
    private volatile Plan pending;
    private volatile Consumer<Plan> listener;

    public static PlanService getInstance() {
        return ApplicationManager.getApplication().getService(PlanService.class);
    }

    public synchronized void start(Project project) {
        if (project != null && !project.isDisposed()) {
            this.project = project;
        }
        if (future != null && !future.isCancelled()) {
            return;
        }
        future = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(this::tick, 20, 60, TimeUnit.SECONDS);
    }

    public synchronized void restart() {
        lastPollAt = 0L;
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        start(project);
    }

    public void setListener(@Nullable Consumer<Plan> listener) {
        this.listener = listener;
        Plan plan = pending;
        if (plan != null && listener != null) {
            pending = null;
            ApplicationManager.getApplication().invokeLater(() -> listener.accept(plan));
        }
    }

    public void offer(Plan plan) {
        Consumer<Plan> current = listener;
        if (current != null) {
            ApplicationManager.getApplication().invokeLater(() -> current.accept(plan));
        } else {
            pending = plan;
        }
    }

    public void checkNow() {
        checkNow(true, null);
    }

    public void checkNow(boolean autoStart) {
        checkNow(autoStart, null);
    }

    public void checkNow(boolean autoStart, Listener listener) {
        AppExecutorUtil.getAppExecutorService().submit(() -> poll(autoStart, listener));
    }

    /**
     * 问询过程回调：log 会带时间戳，done 表示本次结束。
     */
    public interface Listener {
        void log(String line);

        void done(boolean success, String message);
    }

    public String lastError() {
        return lastError;
    }

    public boolean isConfigured() {
        return openClawConfigured();
    }

    public long lastPollAt() {
        return lastPollAt;
    }

    public boolean consumedToday() {
        String date = PropertiesComponent.getInstance().getValue(KEY_LAST_DATE, "");
        return LocalDate.now().toString().equals(date);
    }

    public static String lastPlanId() {
        return PropertiesComponent.getInstance().getValue(KEY_LAST_ID, "");
    }

    // ------------------------------------------------------------------ 调度

    private void tick() {
        try {
            if (!openClawConfigured()) {
                lastError = "未配置 OpenClaw 地址或 Token";
                return;
            }
            if (consumedToday()) {
                return;
            }
            if (!inWindow()) {
                return;
            }
            long intervalMs = Math.max(15, PropertiesComponent.getInstance().getInt(KEY_INTERVAL, 150)) * 60_000L;
            if (lastPollAt != 0 && System.currentTimeMillis() - lastPollAt < intervalMs) {
                return;
            }
            poll(false, null);
        } catch (Throwable ignored) {
        }
    }

    private boolean inWindow() {
        int hour = LocalTime.now().getHour();
        int startHour = PropertiesComponent.getInstance().getInt(KEY_START_HOUR, 9);
        return hour >= startHour || hour < 1;
    }

    private boolean openClawConfigured() {
        String url = configUrl();
        String token = FenbiSession.getInstance().secret("openclawToken");
        return url != null && !url.isBlank() && token != null && !token.isBlank();
    }

    // ------------------------------------------------------------------ 问询

    private void poll(boolean autoStart, Listener listener) {
        if (polling) {
            log(listener, "已有一次问询在进行，跳过本次");
            finish(listener, false, "已有问询在进行");
            return;
        }
        polling = true;
        lastPollAt = System.currentTimeMillis();
        try {
            log(listener, "开始向大模型问询" + (autoStart ? "（手动）" : "（定时）"));
            log(listener, "地址：" + configUrl() + "　模型：" + configModel());
            log(listener, "API Key：" + (FenbiSession.getInstance().secret("openclawToken").isBlank()
                    ? "未配置" : "已配置"));
            OpenClawClient client = client();
            if (!client.isChatConfigured()) {
                fail(listener, "大模型接口未配置完整：请在设置里填写 API Key");
                return;
            }
            log(listener, "请求地址：" + client.chatEndpoint());
            if (!FenbiSession.getInstance().isLoggedIn()) {
                fail(listener, "请先授权题库，再生成计划");
                return;
            }
            log(listener, "授权正常，正在取可用考点…");
            String prompt = planPrompt();
            log(listener, "提示词 " + prompt.length() + " 字符，发送请求（最长等待 120 秒）…");

            OpenClawClient.ChatResult result;
            try {
                result = client.chat(PLAN_SYSTEM, prompt);
            } catch (Exception e) {
                fail(listener, "请求失败：" + e.getMessage());
                return;
            }
            log(listener, "收到回复：" + result.content().length() + " 字符，用时 "
                    + String.format(java.util.Locale.ROOT, "%.1f", result.elapsedMs() / 1000.0) + " 秒"
                    + (result.promptTokens() > 0
                    ? "，tokens " + result.promptTokens() + " + " + result.completionTokens() : ""));

            Plan plan = parsePlan(result.content());
            if (plan == null) {
                fail(listener, "解析失败，回复开头：" + abbreviate(result.content()));
                return;
            }
            log(listener, "解析成功：planId=" + plan.planId() + "，date=" + plan.date());

            PropertiesComponent props = PropertiesComponent.getInstance();
            if (plan.rest() || plan.items().isEmpty()) {
                log(listener, "今天休息：" + (plan.note().isBlank() ? "不用刷题" : plan.note()));
                props.setValue(KEY_LAST_ID, plan.planId());
                props.setValue(KEY_LAST_DATE, LocalDate.now().toString());
                lastError = "";
                notifyPlan(plan, false);
                finish(listener, true, "今天休息");
                return;
            }

            StringBuilder items = new StringBuilder();
            for (PlanItem item : plan.items()) {
                if (items.length() > 0) items.append("、");
                items.append(item.type()).append(" ×").append(item.count());
            }
            log(listener, "计划：" + items);

            if (plan.planId().equals(props.getValue(KEY_LAST_ID, ""))) {
                log(listener, "和上次计划相同，不重复下发");
                lastError = "";
                finish(listener, true, "计划未变化");
                return;
            }
            if (!plan.date().isBlank() && !plan.date().equals(LocalDate.now().toString())) {
                fail(listener, "返回的计划日期不是今天（" + plan.date() + "）");
                return;
            }

            props.setValue(KEY_LAST_ID, plan.planId());
            props.setValue(KEY_LAST_DATE, LocalDate.now().toString());
            lastError = "";
            notifyPlan(plan, !autoStart);
            if (autoStart) {
                log(listener, "开始解析考点并组卷…");
                offer(plan);
            }
            finish(listener, true, "计划已就绪");
        } catch (Throwable throwable) {
            fail(listener, "异常：" + (throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName() : throwable.getMessage()));
        } finally {
            polling = false;
        }
    }

    private static void log(Listener listener, String message) {
        if (listener != null) {
            listener.log("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message);
        }
    }

    private void fail(Listener listener, String message) {
        lastError = message;
        log(listener, "失败：" + message);
        if (listener != null) {
            listener.done(false, message);
        }
    }

    private static void finish(Listener listener, boolean success, String message) {
        if (listener != null) {
            listener.done(success, message);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }

    private OpenClawClient client() {
        PropertiesComponent props = PropertiesComponent.getInstance();
        return new OpenClawClient(
                () -> configUrl(),
                () -> FenbiSession.getInstance().secret("openclawToken"),
                () -> props.getValue(KEY_HOOK, ""),
                () -> FenbiSession.getInstance().secret("hookToken"),
                () -> props.getValue(KEY_MODEL, "openclaw/main"));
    }

    private String planPrompt() {
        LocalDateTime now = LocalDateTime.now();
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        String prompt = "我是史皓燃。现在是 " + now.format(TIME_FMT) + "（" + weekday + "），我在工位，"
                + "通过「刷帮帮」插件拉取你的安排来做强化训练。\n"
                + "请你按自己的判断安排今天：该刷题就给考点和题量，该休息就明确让我休息。\n"
                + "只输出 JSON，不要 markdown 代码块，不要解释。两种格式：\n"
                + "刷题：{\"planId\":\"yyyyMMdd-HHmm\",\"date\":\"yyyy-MM-dd\",\"rest\":false,"
                + "\"deadline\":\"23:30\",\"items\":[{\"type\":\"考点名\",\"count\":10}],\"note\":\"一句话\"}\n"
                + "休息：{\"planId\":\"yyyyMMdd-HHmm\",\"date\":\"yyyy-MM-dd\",\"rest\":true,"
                + "\"items\":[],\"note\":\"为什么休息\"}\n"
                + "要求：\n"
                + "1. type 必须是下面「可用考点」里的名字，原样使用；\n"
                + "2. 2~4 个考点，每个 5~15 题，总量 20~40 题；状态不好就少安排，或者直接让我休息；\n"
                + "3. 参考我最近的正确率，薄弱考点多安排；没有历史数据就均衡安排。";
        String names = availableKeypoints();
        if (!names.isBlank()) {
            prompt += "\n\n可用考点：" + names;
        }
        prompt += historyContext();
        return prompt;
    }

    /**
     * 把上一次练习结果带给模型，直连大模型时也能做针对性安排。
     */
    private static String historyContext() {
        try {
            java.nio.file.Path file = java.nio.file.Paths.get(System.getProperty("user.home"),
                    ".fenbi-plugin", "last-report.json");
            if (!java.nio.file.Files.exists(file)) {
                return "";
            }
            String json = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            if (json.length() > 3000) {
                json = json.substring(0, 3000) + "…";
            }
            return "\n\n我上一次的练习结果（JSON，供你安排时参考）：\n" + json;
        } catch (Exception e) {
            return "";
        }
    }

    private String availableKeypoints() {
        try {
            java.util.List<String> names = new ArrayList<>();
            collectNames(FenbiSession.getInstance().api().categories(), names);
            if (names.isEmpty()) return "";
            return String.join("、", names.subList(0, Math.min(names.size(), 80)));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void collectNames(java.util.List<com.fenbi.plugin.model.Models.Category> categories,
                                     java.util.List<String> output) {
        for (com.fenbi.plugin.model.Models.Category category : categories) {
            if (output.size() >= 80) return;
            String name = category.name();
            if (name != null && !name.isBlank() && !output.contains(name)) {
                output.add(name);
            }
            if (category.children() != null && !category.children().isEmpty()) {
                collectNames(category.children(), output);
            }
        }
    }

    private void notifyPlan(Plan plan, boolean withStartAction) {
        if (plan.rest() || plan.items().isEmpty()) {
            String restText = plan.note().isBlank() ? "今天安排休息，不用刷题。" : "今天安排休息：" + plan.note();
            Notification rest = NotificationGroupManager.getInstance()
                    .getNotificationGroup("ShuaBangBang")
                    .createNotification("刷帮帮 · 今日休息", restText, NotificationType.INFORMATION);
            rest.addAction(NotificationAction.createSimple("知道了", rest::expire));
            Notifications.Bus.notify(rest, currentProject());
            return;
        }

        StringBuilder text = new StringBuilder("今日计划：");
        for (int i = 0; i < plan.items().size(); i++) {
            if (i > 0) text.append("、");
            text.append(plan.items().get(i).type()).append(" ×").append(plan.items().get(i).count());
        }
        if (!plan.deadline().isBlank()) {
            text.append("（截止 ").append(plan.deadline()).append("）");
        }
        if (!plan.note().isBlank()) {
            text.append("\n").append(plan.note());
        }

        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("ShuaBangBang")
                .createNotification("刷帮帮 · 今日计划", text.toString(), NotificationType.INFORMATION);
        Project target = currentProject();
        if (withStartAction) {
            notification.addAction(new NotificationAction("开始") {
                @Override
                public void actionPerformed(@org.jetbrains.annotations.NotNull com.intellij.openapi.actionSystem.AnActionEvent e,
                                            @org.jetbrains.annotations.NotNull Notification n) {
                    openPlan(plan);
                    n.expire();
                }
            });
        }
        notification.addAction(NotificationAction.createSimple(withStartAction ? "忽略" : "知道了", notification::expire));
        Notifications.Bus.notify(notification, target);
    }

    private void openPlan(Plan plan) {
        Project target = currentProject();
        offer(plan);
        if (target == null) return;
        ToolWindow window = ToolWindowManager.getInstance(target).getToolWindow("刷帮帮");
        if (window != null) {
            window.show();
        }
    }

    @Nullable
    private Project currentProject() {
        Project current = project;
        if (current != null && !current.isDisposed()) {
            return current;
        }
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return projects.length > 0 ? projects[0] : null;
    }

    // ------------------------------------------------------------------ 解析

    @Nullable
    private static Plan parsePlan(String answer) {
        if (answer == null) return null;
        String cleaned = answer.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonObject root = JsonParser.parseString(cleaned.substring(start, end + 1)).getAsJsonObject();
            List<PlanItem> items = new ArrayList<>();
            JsonArray array = root.has("items") && root.get("items").isJsonArray()
                    ? root.getAsJsonArray("items") : new JsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String type = str(item, "type");
                int count = Math.max(1, Math.min(50, optInt(item, "count", 10)));
                if (type.isBlank()) continue;
                List<String> path = new ArrayList<>();
                if (item.has("path") && item.get("path").isJsonArray()) {
                    for (JsonElement segment : item.getAsJsonArray("path")) {
                        if (segment.isJsonPrimitive()) path.add(segment.getAsString());
                    }
                }
                items.add(new PlanItem(type, path, count));
            }
            JsonElement restElement = root.get("rest");
            boolean hasRestField = restElement != null && !restElement.isJsonNull();
            boolean explicitRest = hasRestField && restElement.getAsBoolean();
            if (explicitRest) {
                items.clear();
            }
            if (items.isEmpty() && hasRestField && !explicitRest) {
                return null;
            }
            boolean rest = explicitRest || items.isEmpty();

            String planId = str(root, "planId");
            String date = str(root, "date");
            if (date.isBlank()) date = LocalDate.now().toString();
            if (planId.isBlank()) {
                planId = "plan-" + date + "-" + Math.abs(answer.hashCode());
            }
            return new Plan(planId, date, str(root, "deadline"), str(root, "note"), items, rest);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        try {
            if (object != null && object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsInt();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    // ------------------------------------------------------------------ 配置读取（设置页复用）

    public static String configUrl() {
        String value = secure("openclawUrl", KEY_URL, DEFAULT_URL);
        return value == null || value.isBlank() ? DEFAULT_URL : value.trim();
    }

    public static String configModel() {
        return secure("openclawModel", KEY_MODEL, "openclaw/main");
    }

    public static String configHook() {
        return secure("hookUrl", KEY_HOOK, "");
    }

    public static int configStartHour() {
        return secureInt("planStartHour", KEY_START_HOUR, 9);
    }

    public static int configInterval() {
        return secureInt("planInterval", KEY_INTERVAL, 150);
    }

    public static int configQuestionCount() {
        return secureInt("questionCount", "fenbi.questionCount", 10);
    }

    public static void saveQuestionCount(int value) {
        SecureConfig config = SecureConfig.getInstance();
        config.put("questionCount", String.valueOf(Math.max(1, Math.min(50, value))));
        config.save();
    }

    public static void saveConfig(String url, String model, String hook, int startHour, int interval) {
        SecureConfig config = SecureConfig.getInstance();
        config.put("openclawUrl", url == null ? "" : url.trim());
        config.put("openclawModel", model == null || model.isBlank() ? "openclaw/main" : model.trim());
        config.put("hookUrl", hook == null ? "" : hook.trim());
        config.put("planStartHour", String.valueOf(Math.max(0, Math.min(23, startHour))));
        config.put("planInterval", String.valueOf(Math.max(15, Math.min(240, interval))));
        config.save();
    }

    private static String secure(String key, String legacyKey, String defaultValue) {
        SecureConfig config = SecureConfig.getInstance();
        String value = config.get(key, "");
        if (!value.isBlank()) {
            return value;
        }
        String legacy = PropertiesComponent.getInstance().getValue(legacyKey, "");
        if (legacy != null && !legacy.isBlank()) {
            config.put(key, legacy);
            config.save();
            return legacy;
        }
        return defaultValue;
    }

    private static int secureInt(String key, String legacyKey, int defaultValue) {
        String value = secure(key, legacyKey, "");
        try {
            return value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static void resetToday() {
        PropertiesComponent.getInstance().setValue(KEY_LAST_DATE, "");
        PropertiesComponent.getInstance().setValue(KEY_LAST_ID, "");
    }
}
