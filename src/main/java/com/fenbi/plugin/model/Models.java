package com.fenbi.plugin.model;

import java.util.List;

/**
 * 粉笔接口的数据模型。字段全部按接口实际返回裁剪，缺失的用默认值。
 */
public final class Models {
    private Models() {
    }

    public record Category(long id, String name, int count, int answerCount, List<Category> children) {
        public boolean isLeaf() {
            return children == null || children.isEmpty();
        }
    }

    public record Question(long id, String content, int type, int difficulty,
                           List<String> options, List<Integer> materialIndexes) {
    }

    public record Material(String content) {
    }

    public record Solution(long questionId, String content, String solution, String answer, List<String> options) {
    }

    public record QuestionSet(String title, long exerciseId, List<Question> questions,
                              List<Material> materials, java.util.Map<Long, String> userAnswers) {
    }

    public record SolutionSet(int correctCount, int questionCount,
                              List<Solution> solutions, List<Material> materials) {
    }

    public record EssayLabel(String name, List<Long> paperIds) {
    }

    public record EssayPaper(long id, String name, String date, String topic, String checkInfo) {
        public String displayName() {
            StringBuilder sb = new StringBuilder();
            if (name != null && !name.isBlank()) sb.append(name);
            if (date != null && !date.isBlank()) sb.append(' ').append(date);
            if (topic != null && !topic.isBlank()) sb.append(" · ").append(topic);
            return sb.toString();
        }
    }

    public record EssayContent(List<Material> materials, List<Solution> questions, List<Solution> references) {
    }

    // ------------------------------------------------------------------ OpenClaw 联动

    public record PlanItem(String type, List<String> path, int count) {
    }

    public record Plan(String planId, String date, String deadline, String note,
                       List<PlanItem> items, boolean rest) {
    }

    public record WrongQuestion(long questionId, String stem, String your, String correct) {
    }

    public record PlanItemResult(String type, String path, int total, int correct, int seconds,
                                 List<WrongQuestion> wrong) {
    }

    public record PlanReport(String planId, String finishedAt, int elapsedSeconds,
                             int total, int correct, List<PlanItemResult> items) {
    }
}
