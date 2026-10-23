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
 * 一次「计划」的执行过程：按考点串行组卷，记录每项成绩，最后汇总回传 OpenClaw。
 */
public class PlanSession {
    private static final String ANALYSIS_SYSTEM = "你是我的学习教练。请用中文分析，直接给结论，不要客套。";

    private final Plan plan;
    private final FenbiPanel host;
    private final List<Resolved> queue = new ArrayList<>();
    private final List<PlanItemResult> results = new ArrayList<>();
    private final long startedAt = System.currentTimeMillis();

    private int index;
    private boolean finished;

    private record Resolved(PlanItem item, Category category, String fullPath) {
    }

    private record Node(Category category, String fullPath) {
    }

    public PlanSession(Plan plan, FenbiPanel host) {
        this.plan = plan;
        this.host = host;
    }

    // ------------------------------------------------------------------ 解析考点

    public void start() {
        host.setStatusText("正在解析计划考点…");
        Async.run("解析计划", () -> host.api().categories(), this::onCategories, host::handleError);
    }

    private void onCategories(List<Category> roots) {
        List<Node> nodes = new ArrayList<>();
        flatten(roots, "", nodes);

        List<String> missing = new ArrayList<>();
        for (PlanItem item : plan.items()) {
            Node node = find(nodes, item);
            if (node == null) {
                missing.add(item.type());
            } else {
                queue.add(new Resolved(item, node.category(), node.fullPath()));
            }
        }

        if (queue.isEmpty()) {
            host.handleError(new IOException("计划里的考点都没匹配到：" + String.join("、", missing)));
            return;
        }
        if (!missing.isEmpty()) {
            host.setStatusText("未匹配：" + String.join("、", missing));
        }
        index = 0;
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
        if (pool.isEmpty()) {
            return null;
        }
        if (!item.path().isEmpty()) {
            for (Node node : pool) {
                boolean matched = true;
                for (String segment : item.path()) {
                    if (!node.fullPath().contains(segment)) {
                        matched = false;
                        break;
                    }
                }
                if (matched) {
                    return node;
                }
            }
        }
        return pool.get(0);
    }

    private void startCurrent() {
        Resolved resolved = queue.get(index);
        String title = resolved.item().type();
        host.setStatusText("计划 " + (index + 1) + "/" + queue.size() + "：" + title);
        host.startPlanItem(this, title + "（" + (index + 1) + "/" + queue.size() + "）", resolved.category().id());
    }

    public boolean hasNext() {
        return index + 1 < queue.size();
    }

    public void next() {
        if (!hasNext()) return;
        index++;
        startCurrent();
    }

    // ------------------------------------------------------------------ 记录成绩

    public void recordResult(QuestionSet set, SolutionSet solutions, Map<Long, Integer> answers, int seconds) {
        if (index >= queue.size()) return;
        Resolved resolved = queue.get(index);

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

        results.add(new PlanItemResult(resolved.item().type(), resolved.fullPath(),
                set.questions().size(), correct, seconds, wrong));

        WrongBook.getInstance().record(set, solutions, answers,
                resolved.item().type(), resolved.fullPath(), resolved.category().id(), "计划");
        Stats.getInstance().record(set, solutions, answers, resolved.item().type(), seconds);

        if (!hasNext()) {
            finish();
        }
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
        for (PlanItemResult result : results) {
            total += result.total();
            correct += result.correct();
        }
        int elapsed = (int) ((System.currentTimeMillis() - startedAt) / 1000);
        PlanReport report = new PlanReport(plan.planId(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                elapsed, total, correct, results);

        String json = toJson(report);
        saveLocal(json);
        String summary = summary(report);

        OpenClawClient client = host.openClaw();
        if (!client.isChatConfigured()) {
            String message = "未配置 OpenClaw，报告已保存到 ~/.fenbi-plugin/last-report.json。\n" + summary;
            com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater(() -> host.showAnalysis(message));
            return;
        }

        host.setStatusText("OpenClaw 分析中…");
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
