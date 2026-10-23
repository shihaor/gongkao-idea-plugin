package com.fenbi.plugin.ui;

import com.fenbi.plugin.model.Models.Material;
import com.fenbi.plugin.model.Models.Question;
import com.fenbi.plugin.model.Models.QuestionSet;
import com.fenbi.plugin.model.Models.Solution;
import com.fenbi.plugin.model.Models.SolutionSet;
import com.fenbi.plugin.service.FenbiApi;
import com.fenbi.plugin.util.Html;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 交卷后的成绩与解析。
 */
public class ReviewPanel extends JPanel implements Disposable {
    private final JLabel titleLabel = new JBLabel("练习报告");
    private final JBCefBrowser browser;
    private final JButton retryButton = new JButton("再练一组");
    private final JButton backButton = new JButton("返回题库");

    private Runnable retryAction = () -> {
    };
    private Runnable backAction = () -> {
    };
    private String currentBody = "";
    private String analysis = "";

    public ReviewPanel(FenbiPanel host) {
        super(new BorderLayout());
        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            browser = null;
            add(new JBLabel("当前 IDE 未启用 JCEF，无法显示解析。"), BorderLayout.CENTER);
        }

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(JBUI.Borders.empty(6, 8, 6, 8));
        JButton back = new JButton(AllIcons.Actions.Back);
        back.setToolTipText("返回题库");
        back.setFocusable(false);
        back.setBorder(JBUI.Borders.empty(2));
        back.setContentAreaFilled(false);
        back.addActionListener(e -> backAction.run());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(back);
        titleLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD, 12.5f));
        left.add(titleLabel);
        top.add(left, BorderLayout.WEST);
        add(top, BorderLayout.NORTH);

        backButton.addActionListener(e -> backAction.run());
        retryButton.addActionListener(e -> retryAction.run());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        bottom.setBorder(JBUI.Borders.empty(6, 8, 8, 8));
        bottom.add(backButton);
        bottom.add(retryButton);
        add(bottom, BorderLayout.SOUTH);
    }

    public void show(QuestionSet set, SolutionSet solutions, Map<Long, Integer> answers,
                     int elapsedSeconds, String retryText, @NotNull Runnable retry, @NotNull Runnable back) {
        this.retryAction = retry;
        this.backAction = back;
        this.analysis = "";
        this.currentBody = build(set, solutions, answers, elapsedSeconds);
        titleLabel.setText(set.title() == null || set.title().isBlank() ? "练习报告" : set.title() + " · 练习报告");
        retryButton.setText(retryText == null || retryText.isBlank() ? "再练一组" : retryText);
        render();
    }

    public void setAnalysis(String text) {
        this.analysis = text == null ? "" : text;
        render();
    }

    private void render() {
        if (browser == null) return;
        String prefix = "";
        if (!analysis.isBlank()) {
            prefix = "<div class=\"ref\" style=\"margin-bottom:16px\"><div class=\"h\">OpenClaw 分析</div>"
                    + "<div class=\"b\">" + Html.escape(analysis).replace("\n", "<br/>") + "</div></div>";
        }
        browser.loadHTML(Html.page(prefix + currentBody));
    }

    private static String build(QuestionSet set, SolutionSet solutions, Map<Long, Integer> answers, int elapsed) {
        Map<Long, Solution> byId = new HashMap<>();
        for (Solution solution : solutions.solutions()) {
            byId.put(solution.questionId(), solution);
        }

        int total = set.questions().size();
        int correct = 0;
        for (Question question : set.questions()) {
            Solution solution = byId.get(question.id());
            Integer chosen = answers.get(question.id());
            if (solution != null && chosen != null
                    && String.valueOf(chosen).equals(FenbiApi.normalizeChoice(solution.answer()))) {
                correct++;
            }
        }
        if (set.questions().isEmpty() && solutions.correctCount() >= 0) {
            correct = solutions.correctCount();
            total = Math.max(total, solutions.questionCount());
        }

        StringBuilder body = new StringBuilder();
        int percent = total == 0 ? 0 : Math.round(correct * 100f / total);
        body.append("<div class=\"hdr\"><div class=\"score\">").append(percent).append("<small>%</small></div>")
                .append("<div class=\"sub\">").append(total).append(" 题 · 答对 ").append(correct)
                .append(" · 用时 ").append(format(elapsed)).append("<br/>")
                .append("<span style=\"color:var(--mute);font-size:11px\">已提交，成绩已同步</span>")
                .append("</div></div>");

        for (int i = 0; i < set.questions().size(); i++) {
            Question question = set.questions().get(i);
            Solution solution = byId.get(question.id());
            Integer chosen = answers.get(question.id());
            String correctChoice = solution == null ? "" : FenbiApi.normalizeChoice(solution.answer());
            boolean answered = chosen != null;
            boolean right = answered && !correctChoice.isBlank()
                    && String.valueOf(chosen).equals(correctChoice);

            body.append("<div class=\"q\">");
            body.append("<div class=\"meta\"><span class=\"badge\">第 ").append(i + 1).append(" 题</span>")
                    .append("<span class=\"badge ").append(right ? "blue" : "").append("\">")
                    .append(right ? "回答正确" : answered ? "回答错误" : "未作答").append("</span>")
                    .append("<span class=\"idx\">").append(answered ? "你的答案 " + Html.letter(chosen) : "未作答")
                    .append("</span></div>");

            for (int materialIndex : question.materialIndexes()) {
                if (materialIndex >= 0 && materialIndex < set.materials().size()) {
                    Material material = set.materials().get(materialIndex);
                    body.append(Html.material(material.content()));
                }
            }

            body.append("<div class=\"stem\">").append(Html.fix(question.content())).append("</div>");
            body.append("<div class=\"opts\">");
            for (int optionIndex = 0; optionIndex < question.options().size(); optionIndex++) {
                String state;
                if (!correctChoice.isBlank() && String.valueOf(optionIndex).equals(correctChoice)) {
                    state = "correct";
                } else if (answered && chosen == optionIndex) {
                    state = "wrong";
                } else {
                    state = "muted";
                }
                body.append(Html.option(optionIndex, question.options().get(optionIndex), state, ""));
            }
            body.append("</div>");

            if (solution != null) {
                if (!correctChoice.isBlank()) {
                    body.append("<div class=\"ans\">正确答案 <b>").append(letterOf(correctChoice))
                            .append("</b>");
                    if (answered) {
                        body.append("　你的答案 <b class=\"").append(right ? "ok" : "no").append("\">")
                                .append(Html.letter(chosen)).append("</b>");
                    }
                    body.append("</div>");
                }
                String analysis = solution.solution();
                if (analysis != null && !analysis.isBlank()) {
                    body.append("<div class=\"sol\"><div class=\"h\">解析</div><div class=\"b\">")
                            .append(Html.fix(analysis)).append("</div></div>");
                }
            } else {
                body.append("<div class=\"sol\"><div class=\"h\">解析</div><div class=\"b\">接口没有返回这道题的解析。</div></div>");
            }
            body.append("</div>");
        }

        if (set.questions().isEmpty()) {
            body.append("<div class=\"empty\">没有题目</div>");
        }
        return body.toString();
    }

    private static String letterOf(String choice) {
        try {
            return Html.letter(Integer.parseInt(choice));
        } catch (Exception e) {
            return Html.escape(choice);
        }
    }

    private static String format(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    @Override
    public void dispose() {
        if (browser != null) {
            browser.dispose();
        }
    }
}
