package com.fenbi.plugin.service;

import com.fenbi.plugin.model.Models.Category;
import com.fenbi.plugin.model.Models.Plan;
import com.fenbi.plugin.model.Models.PlanItem;
import com.fenbi.plugin.model.Models.PlanItemResult;
import com.fenbi.plugin.model.Models.PlanReport;
import com.fenbi.plugin.model.Models.Question;
import com.fenbi.plugin.model.Models.QuestionSet;
import com.fenbi.plugin.model.Models.Solution;
import com.fenbi.plugin.model.Models.SolutionSet;
import com.fenbi.plugin.model.Models.WrongQuestion;
import com.fenbi.plugin.ui.FenbiPanel;
import com.fenbi.plugin.util.Async;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次「计划」的执行过程：按考点串行组卷，记录每项成绩，最后汇总回传。
 * 进度写入 PlanStore，退出 IDE 后可恢复。
 */
public class PlanSession {
    private static final String ANALYSIS_SYSTEM = "你是我的学习教练。请用中文分析，直接给结论，不要客套。";

    private final FenbiPanel host;
    private final Plan plan;
    private PlanStore.State state;
    private final boolean resuming;
    private boolean finished;

    private record Node(Category category, String fullPath) {
    }

    public PlanSession(Plan plan, FenbiPanel host) {
        this.host = host;
        this.plan = plan;
        this.resuming = false;
    }

    public PlanSession(PlanStore.State state, FenbiPanel host) {
        this.host = host;
        this.state = state;
        this.resuming = true;
        List<PlanItem> items = new ArrayList<>();
        for (PlanStore.Item item : state.items) {
            items.add(new PlanItem(item.type, List.of(), item.count));
        }
        this.plan = new Plan(state.planId, state.date, state.deadline, state.note, items, false);
    }

    // ------------------------------------------------------------------ 开始 / 恢复

    public void start() {
        host.setStatusText("正在解析计划考点…");
        Async.run("解析计划", () -> host.api().categories(), this::onCategories, host::handleError);
    }

    public void resume() {
        if (state == null || state.queue.isEmpty()) {
            host.handleError(new IOException("没有可恢复的计划"));
            return;
        }
        startCurrent();
    }

    private void onCategories(List<Category> roots) {
        List<Node> nodes = new ArrayList<>();
        flatten(roots, "", nodes);

        List<String> missing = new ArrayList<>();
        List<PlanStore.Item> queue = new ArrayList<>();
        for (PlanItem item : plan.items()) {
            Node node = find(nodes, item);
            if (node == null) {
                missing.add(item.type());
                continue;
            }
            PlanStore.Item resolved = new PlanStore.Item();
            resolved.type = item.type();
            resolved.path = node.fullPath();
            resolved.keypointId = node.category().id();
            resolved.count = item.count();
            queue.add(resolved);
        }

        if (queue.isEmpty()) {
            host.handleError(new IOException("计划里的考点都没匹配到：" + String.join("、", missing)));
            return;
        }
        if (!missing.isEmpty()) {
            host.setStatusText("未匹配：" + String.join("、", missing));
        }
        PlanStore.State previous = PlanStore.getInstance().unfinishedToday();
        if (previous != null && !previous.planId.equals(plan.planId())) {
            History.getInstance().record(previous, "abandoned");
        }
        state = PlanStore.getInstance().begin(plan, queue);
        startCurrent();
    }

    private static void flatten(List<Category> categories, String prefix, List<Node> output) {
        for (Category category : categories) {
            String path = prefix.isBlank() ? category.name() : prefix + " / " + category.name();
            output.add(new Node(category, path));
            if (category.children() != null && !category.children().isEmpty()) {
                flatten(category.children(), path, output);
            }
        }
    }

    private static Node find(List<Node> nodes, PlanItem item) {
        List<Node> exact = new ArrayList<>();
        for (Node node : nodes) {
            if (node.category().name().equals(item.type())) {
                exact.add(node);
            }
        }
        List<Node> pool = exact;
        if (pool.isEmpty()) {
            pool = new ArrayList<>();
            for (Node node : nodes) {
                String name = node.category().name();
                if (name.contains(item.type()) || item.type().contains(name)) {
                    pool.add(node);
                }
            }
        }
        if (pool.isEmpty()) return null;
        if (!item.path().isEmpty()) {
            for (Node node : pool) {
                boolean matched = true;
                for (String segment : item.path()) {
                    if (!node.fullPath().contains(segment)) {
                        matched = false;
                        break;
                    }
                }
                if (matched) return node;
            }
        }
        return pool.get(0);
    }

    private void startCurrent() {
        if (state == null || state.index >= state.queue.size()) {
            finish();
            return;
        }
        PlanStore.Item item = state.queue.get(state.index);
        host.setStatusText("计划 " + (state.index + 1) + "/" + state.queue.size() + "：" + item.type);

        Async.run("组卷", () -> {
            long exerciseId = state.exerciseId > 0
                    ? state.exerciseId
                    : host.api().createExercise(item.keypointId, item.count);
            PlanStore.getInstance().setExercise(exerciseId);
            return host.api().loadQuestions(exerciseId, item.type);
        }, set -> {
            if (set.questions().isEmpty()) {
                host.handleError(new IOException("没有取到题目，换一个考点试试"));
                return;
            }
            host.showPlanQuiz(this, set, Math.max(0, state.questionIndex));
        }, host::handleError);
    }

    public boolean hasNext() {
        return state != null && state.index < state.queue.size();
    }

    public void next() {
        startCurrent();
    }

    // ------------------------------------------------------------------ 记录成绩

    public void recordResult(QuestionSet set, SolutionSet solutions, Map<Long, Integer> answers, int seconds) {
        if (state == null || state.index >= state.queue.size()) return;
        PlanStore.Item item = state.queue.get(state.index);

        Map<Long, Solution> byId = new HashMap<>();
        for (Solution solution : solutions.solutions()) {
            byId.put(solution.questionId(), solution);
        }

        int correct = 0;
        List<WrongQuestion> wrong = new ArrayList<>();
        for (Question question : set.questions()) {
            Solution solution = byId.get(question.id());
            Integer chosen = answers.get(question.id());
            String correctChoice = solution == null ? "" : FenbiApi.normalizeChoice(solution.answer());
            boolean ok = chosen != null && !correctChoice.isBlank()
                    && String.valueOf(chosen).equals(correctChoice);
            if (ok) {
                correct++;
            } else {
                wrong.add(new WrongQuestion(
                        question.id(),
                        stem(question.content()),
                        chosen == null ? "未作答" : String.valueOf((char) ('A' + chosen)),
                        correctChoice.isBlank() ? "未知" : letter(correctChoice)));
            }
        }

        WrongBook.getInstance().record(set, solutions, answers, item.type, item.path, item.keypointId, "计划");
        Stats.getInstance().record(set, solutions, answers, item.type, seconds);

        PlanStore.Result result = new PlanStore.Result();
        result.type = item.type;
        result.path = item.path;
        result.total = set.questions().size();
        result.correct = correct;
        result.seconds = Math.max(0, seconds);
        for (WrongQuestion question : wrong) {
            PlanStore.Wrong entry = new PlanStore.Wrong();
            entry.questionId = question.questionId();
            entry.stem = question.stem();
            entry.your = question.your();
            entry.correct = question.correct();
            result.wrong.add(entry);
        }
        PlanStore.getInstance().complete(result);

        sendPracticeResult();

        if (!hasNext()) {
            finish();
        }
    }

    /**
     * 每次交卷都按约定格式回传给 OpenClaw（累计到当前进度）。
     */
    private void sendPracticeResult() {
        if (state == null || state.results.isEmpty()) return;

        List<PracticeResult.Module> modules = new ArrayList<>();
        for (PlanStore.Result result : state.results) {
            List<PracticeResult.WrongItem> wrong = new ArrayList<>();
            for (PlanStore.Wrong entry : result.wrong) {
                wrong.add(new PracticeResult.WrongItem(result.type, entry.questionId,
                        entry.stem, entry.your, entry.correct));
            }
            modules.add(new PracticeResult.Module(result.type, result.total,
                    result.correct, result.seconds, wrong));
        }
        JsonObject json = PracticeResult.build(state.planId, state.date, modules, state.elapsedSeconds);
        PlanStore.Result last = state.results.get(state.results.size() - 1);
        host.reportResult(json.toString(), last.type);
    }

    private static String letter(String choice) {
        try {
            return String.valueOf((char) ('A' + Integer.parseInt(choice)));
        } catch (Exception e) {
            return choice;
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

    // ------------------------------------------------------------------ 汇总与回传

    private void finish() {
        if (finished) return;
        finished = true;

        int total = 0;
        int correct = 0;
        int elapsed = state == null ? 0 : state.elapsedSeconds;

        List<PlanItemResult> items = new ArrayList<>();
        if (state != null) {
            for (PlanStore.Result result : state.results) {
                total += result.total;
                correct += result.correct;
                List<WrongQuestion> wrong = new ArrayList<>();
                for (PlanStore.Wrong entry : result.wrong) {
                    wrong.add(new WrongQuestion(entry.questionId, entry.stem, entry.your, entry.correct));
                }
                items.add(new PlanItemResult(result.type, result.path, result.total,
                        result.correct, result.seconds, wrong));
            }
        }

        String planId = state == null ? plan.planId() : state.planId;
        PlanReport report = new PlanReport(planId,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                elapsed, total, correct, items);

        String json = toJson(report);
        saveLocal(json);
        String summary = summary(report);

        History.getInstance().record(state, "completed");
        PlanStore.getInstance().finish();

        OpenClawClient client = host.openClaw();
        if (!client.isChatConfigured()) {
            String message = "未配置大模型，报告已保存到 ~/.fenbi-plugin/last-report.json。\n" + summary;
            com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater(() -> host.showAnalysis(message));
            return;
        }

        host.setStatusText("大模型分析中…");
        Async.run("大模型分析", () -> client.chat(ANALYSIS_SYSTEM,
                        "我是史皓燃。以下是我刚完成的刷帮帮练习结果（JSON）。请按你的安排分析，150 字以内，直接说结论：\n"
                                + "1. 各考点正确率；2. 最薄弱的两个考点；3. 接下来继续刷还是休息，明天怎么练。\n"
                                + "不要重复题目内容，不要输出 JSON。\n\n" + json).content(),
                analysis -> {
                    host.showAnalysis(analysis);
                    host.setStatusText("计划完成");
                    Async.run("推送反馈", () -> {
                        client.wake("【史皓燃 · 刷帮帮练习完成】\n" + summary + "\n\n" + analysis);
                        return null;
                    }, value -> {
                    }, error -> {
                    });
                },
                error -> host.showAnalysis("大模型分析失败：" + error.getMessage()
                        + "\n报告已保存到 ~/.fenbi-plugin/last-report.json"));
    }

    private static String summary(PlanReport report) {
        StringBuilder builder = new StringBuilder();
        for (PlanItemResult item : report.items()) {
            int percent = item.total() == 0 ? 0 : Math.round(item.correct() * 100f / item.total());
            builder.append(item.type()).append(" ").append(item.total()).append(" 题，答对 ")
                    .append(item.correct()).append("（").append(percent).append("%）\n");
        }
        int percent = report.total() == 0 ? 0 : Math.round(report.correct() * 100f / report.total());
        builder.append("合计 ").append(report.total()).append(" 题，答对 ").append(report.correct())
                .append("（").append(percent).append("%），用时 ")
                .append(String.format("%d 分 %d 秒", report.elapsedSeconds() / 60, report.elapsedSeconds() % 60));
        return builder.toString();
    }

    private static String toJson(PlanReport report) {
        JsonObject root = new JsonObject();
        root.addProperty("planId", report.planId());
        root.addProperty("finishedAt", report.finishedAt());
        root.addProperty("elapsedSeconds", report.elapsedSeconds());
        root.addProperty("total", report.total());
        root.addProperty("correct", report.correct());
        root.addProperty("accuracy", report.total() == 0 ? 0 : (double) report.correct() / report.total());

        JsonArray items = new JsonArray();
        for (PlanItemResult item : report.items()) {
            JsonObject object = new JsonObject();
            object.addProperty("type", item.type());
            object.addProperty("path", item.path());
            object.addProperty("total", item.total());
            object.addProperty("correct", item.correct());
            object.addProperty("seconds", item.seconds());
            object.addProperty("accuracy", item.total() == 0 ? 0 : (double) item.correct() / item.total());
            JsonArray wrong = new JsonArray();
            for (WrongQuestion question : item.wrong()) {
                JsonObject w = new JsonObject();
                w.addProperty("questionId", question.questionId());
                w.addProperty("stem", question.stem());
                w.addProperty("your", question.your());
                w.addProperty("correct", question.correct());
                wrong.add(w);
            }
            object.add("wrong", wrong);
            items.add(object);
        }
        root.add("items", items);
        return root.toString();
    }

    private static void saveLocal(String json) {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".fenbi-plugin");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("last-report.json"), json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
        }
    }
}
