package com.fenbi.plugin.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fenbi.plugin.model.Models.Question;
import com.fenbi.plugin.model.Models.QuestionSet;
import com.fenbi.plugin.model.Models.Solution;
import com.fenbi.plugin.model.Models.SolutionSet;

/**
 * 交卷回传格式：按约定的 practice_result JSON 组装。
 */
public final class PracticeResult {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private PracticeResult() {
    }

    public record WrongItem(String type, long questionId, String stem, String your, String correct) {
    }

    public record Module(String type, int total, int correct, int seconds, List<WrongItem> wrong) {
    }

    public static String stem(String html) {
        if (html == null) return "";
        String text = html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    public static String letter(String choice) {
        try {
            return String.valueOf((char) ('A' + Integer.parseInt(choice)));
        } catch (Exception e) {
            return choice;
        }
    }

    public static JsonObject build(String planId, String date, List<Module> modules, int elapsedSeconds) {
        JsonObject root = new JsonObject();
        root.addProperty("action", "practice_result");
        root.addProperty("date", date);
        root.addProperty("planId", planId == null ? "" : planId);
        root.addProperty("submittedAt", LocalDateTime.now().format(HM));

        JsonArray moduleArray = new JsonArray();
        JsonArray wrongArray = new JsonArray();
        int total = 0;
        int correct = 0;

        for (Module module : modules) {
            JsonObject object = new JsonObject();
            object.addProperty("type", module.type());
            object.addProperty("total", module.total());
            object.addProperty("correct", module.correct());
            object.addProperty("wrong", module.total() - module.correct());
            object.addProperty("accuracy", module.total() == 0
                    ? 0 : Math.round(module.correct() * 100f / module.total()));
            object.addProperty("avgSecPerQ", module.total() == 0
                    ? 0 : Math.round((float) module.seconds() / module.total()));
            moduleArray.add(object);

            total += module.total();
            correct += module.correct();

            for (WrongItem wrong : module.wrong()) {
                JsonObject item = new JsonObject();
                item.addProperty("type", wrong.type());
                item.addProperty("reason", "");
                item.addProperty("note", "");
                item.addProperty("questionId", wrong.questionId());
                item.addProperty("stem", wrong.stem());
                item.addProperty("your", wrong.your());
                item.addProperty("correct", wrong.correct());
                wrongArray.add(item);
            }
        }

        root.add("modules", moduleArray);
        root.add("wrongItems", wrongArray);

        JsonObject overall = new JsonObject();
        overall.addProperty("total", total);
        overall.addProperty("correct", correct);
        overall.addProperty("durationMin", Math.max(0, Math.round(elapsedSeconds / 60f)));
        root.add("overall", overall);
        root.addProperty("selfComment", "");
        return root;
    }

    /**
     * 手动练习（非计划）交卷时的结果。
     */
    public static JsonObject buildManual(String type, String planId, QuestionSet set,
                                         SolutionSet solutions, Map<Long, Integer> answers, int seconds) {
        Map<Long, Solution> byId = new HashMap<>();
        for (Solution solution : solutions.solutions()) {
            byId.put(solution.questionId(), solution);
        }
        int correct = 0;
        List<WrongItem> wrong = new ArrayList<>();
        for (Question question : set.questions()) {
            Solution solution = byId.get(question.id());
            Integer chosen = answers.get(question.id());
            String correctChoice = solution == null ? "" : FenbiApi.normalizeChoice(solution.answer());
            boolean ok = chosen != null && !correctChoice.isBlank()
                    && String.valueOf(chosen).equals(correctChoice);
            if (ok) {
                correct++;
            } else {
                wrong.add(new WrongItem(type, question.id(), stem(question.content()),
                        chosen == null ? "未作答" : String.valueOf((char) ('A' + chosen)),
                        correctChoice.isBlank() ? "未知" : letter(correctChoice)));
            }
        }
        Module module = new Module(type, set.questions().size(), correct, seconds, wrong);
        return build(planId, LocalDate.now().toString(), List.of(module), seconds);
    }
}
