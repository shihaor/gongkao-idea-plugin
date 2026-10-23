package com.fenbi.plugin.ui;

import com.fenbi.plugin.model.Models.Material;
import com.fenbi.plugin.model.Models.Question;
import com.fenbi.plugin.model.Models.QuestionSet;
import com.fenbi.plugin.service.FenbiApi;
import com.fenbi.plugin.service.FenbiApi.LoginRequiredException;
import com.fenbi.plugin.util.Async;
import com.fenbi.plugin.util.Html;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * 做题面板：JCEF 渲染题干与选项，点击选项通过 JS 桥回传。
 */
public class QuizPanel extends JPanel implements Disposable {
    private final FenbiPanel host;
    private final Project project;

    private final JLabel titleLabel = new JBLabel("");
    private final JLabel progressLabel = new JBLabel("");
    private final JLabel timerLabel = new JBLabel("00:00");
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton prevButton = new JButton("上一题");
    private final JButton nextButton = new JButton("下一题");
    private final JButton submitButton = new JButton("交卷");

    private final JBCefBrowser browser;
    private final JBCefJSQuery query;
    private final Timer ticker;

    private QuestionSet set;
    private int index;
    private final Map<Long, Integer> selected = new LinkedHashMap<>();
    private final Map<Long, Integer> spentSeconds = new LinkedHashMap<>();
    private long exerciseStart;
    private long questionStart;
    private boolean submitting;
    private boolean submitted;
    private IntConsumer progressListener;

    public QuizPanel(FenbiPanel host, Project project) {
        super(new BorderLayout());
        this.host = host;
        this.project = project;

        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            query = JBCefJSQuery.create((JBCefBrowserBase) browser);
            query.addHandler(this::onJsMessage);
            add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            browser = null;
            query = null;
            JLabel unsupported = new JBLabel("<html>当前 IDE 未启用 JCEF，无法渲染题目。<br/>"
                    + "请在 Help → Find Action 里搜索 Registry，确认 ide.browser.jcef.enabled 已勾选后重启。</html>");
            unsupported.setBorder(JBUI.Borders.empty(16));
            add(unsupported, BorderLayout.CENTER);
        }

        add(createTop(), BorderLayout.NORTH);
        add(createBottom(), BorderLayout.SOUTH);

        ticker = new Timer(1000, e -> updateTimer());
        ticker.setRepeats(true);

        prevButton.addActionListener(e -> goPrev());
        nextButton.addActionListener(e -> goNext());
        submitButton.addActionListener(e -> submit());
    }

    private JComponent createTop() {
        JPanel top = new JPanel(new BorderLayout(6, 4));
        top.setBorder(JBUI.Borders.empty(6, 8, 6, 8));

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);

        JButton back = new JButton(AllIcons.Actions.Back);
        back.setToolTipText("返回题库");
        back.setFocusable(false);
        back.setBorder(JBUI.Borders.empty(2));
        back.setContentAreaFilled(false);
        back.addActionListener(e -> {
            stop();
            host.showBank();
        });

        titleLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD, 12.5f));
        titleLabel.setText("练习");
        timerLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        timerLabel.setForeground(UIUtil.getContextHelpForeground());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(back);
        left.add(titleLabel);
        row.add(left, BorderLayout.WEST);
        row.add(timerLabel, BorderLayout.EAST);

        progressBar.setMaximum(1);
        progressBar.setValue(0);
        progressBar.setPreferredSize(new Dimension(10, 3));
        progressBar.setBorderPainted(false);

        JPanel bottomRow = new JPanel(new BorderLayout(8, 0));
        bottomRow.setOpaque(false);
        progressLabel.setForeground(UIUtil.getContextHelpForeground());
        progressLabel.setFont(UIUtil.getLabelFont().deriveFont(11f));
        bottomRow.add(progressLabel, BorderLayout.WEST);
        bottomRow.add(progressBar, BorderLayout.CENTER);

        JPanel wrapper = new JPanel(new BorderLayout(0, 5));
        wrapper.setOpaque(false);
        wrapper.add(row, BorderLayout.NORTH);
        wrapper.add(bottomRow, BorderLayout.SOUTH);

        top.add(wrapper, BorderLayout.CENTER);
        return top;
    }

    private JComponent createBottom() {
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(JBUI.Borders.empty(6, 8, 8, 8));

        prevButton.setFocusable(false);
        nextButton.setFocusable(false);
        submitButton.setFocusable(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(prevButton);
        left.add(submitButton);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(nextButton);

        bottom.add(left, BorderLayout.WEST);
        bottom.add(right, BorderLayout.EAST);
        return bottom;
    }

    // ------------------------------------------------------------------ 生命周期

    public void start(QuestionSet questionSet) {
        start(questionSet, 0);
    }

    public void start(QuestionSet questionSet, int startIndex) {
        this.set = questionSet;
        this.index = Math.max(0, Math.min(startIndex, Math.max(0, questionSet.questions().size() - 1)));
        this.submitting = false;
        this.submitted = false;
        selected.clear();
        spentSeconds.clear();
        if (questionSet.userAnswers() != null) {
            for (Map.Entry<Long, String> entry : questionSet.userAnswers().entrySet()) {
                try {
                    selected.put(entry.getKey(), Integer.parseInt(FenbiApi.normalizeChoice(entry.getValue())));
                } catch (Exception ignored) {
                }
            }
        }
        exerciseStart = System.currentTimeMillis();
        questionStart = exerciseStart;
        submitButton.setEnabled(true);
        render();
        ticker.start();
    }

    private void stop() {
        ticker.stop();
        set = null;
        selected.clear();
        spentSeconds.clear();
    }

    /**
     * 是否有一组还没交卷的题。
     */
    public boolean isActive() {
        return set != null && !submitted && !set.questions().isEmpty();
    }

    public void setProgressListener(IntConsumer progressListener) {
        this.progressListener = progressListener;
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        if (set == null || set.questions().isEmpty()) {
            if (browser != null) {
                browser.loadHTML(Html.page("<div class=\"empty\">没有题目</div>"));
            }
            return;
        }
        Question question = set.questions().get(index);
        int total = set.questions().size();

        titleLabel.setText(set.title() == null || set.title().isBlank() ? "练习" : set.title());
        progressLabel.setText("第 " + (index + 1) + " / " + total + " 题");
        progressBar.setMaximum(total);
        progressBar.setValue(index + 1);
        prevButton.setEnabled(index > 0);
        nextButton.setText(index == total - 1 ? "交卷" : "下一题");

        if (progressListener != null) {
            progressListener.accept(index);
        }

        if (browser == null) return;

        StringBuilder body = new StringBuilder();
        body.append("<div class=\"meta\">")
                .append(Html.badge(set.title() == null || set.title().isBlank() ? "练习" : set.title(), "blue"))
                .append(Html.badge(question.options().size() + " 个选项", ""))
                .append("<span class=\"idx\">").append(index + 1).append(" / ").append(total).append("</span>")
                .append("</div>");

        for (int materialIndex : question.materialIndexes()) {
            if (materialIndex >= 0 && materialIndex < set.materials().size()) {
                Material material = set.materials().get(materialIndex);
                body.append(Html.material(material.content()));
            }
        }

        body.append("<div class=\"stem\">").append(Html.fix(question.content())).append("</div>");
        body.append("<div class=\"opts\">");

        Integer chosen = selected.get(question.id());
        for (int i = 0; i < question.options().size(); i++) {
            boolean isChosen = chosen != null && chosen == i;
            body.append(Html.option(i, question.options().get(i), isChosen ? "sel" : "", "pick(" + i + ")"));
        }
        body.append("</div>");

        if (chosen != null) {
            body.append("<div class=\"tip\">已选择 ").append(Html.letter(chosen))
                    .append("，可以改选；交卷后查看答案与解析。</div>");
        } else {
            body.append("<div class=\"tip\">点击选项作答，键盘 1~4 也可以快速选择。</div>");
        }

        String keyboard = "document.addEventListener('keydown',function(e){"
                + "if(e.key>='1'&&e.key<='4'){var n=parseInt(e.key)-1;var els=document.querySelectorAll('.opt');"
                + "if(els[n]){els[n].click();}}"
                + "});";
        String script = "function pick(i){" + query.inject("i") + "}" + keyboard;
        browser.loadHTML(Html.page(body.toString(), script));
    }

    private void updateTimer() {
        if (set == null) return;
        long total = (System.currentTimeMillis() - exerciseStart) / 1000;
        timerLabel.setText(format(total));
    }

    private static String format(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d", m, s);
    }

    // ------------------------------------------------------------------ 交互

    private JBCefJSQuery.Response onJsMessage(String argument) {
        try {
            int choice = Integer.parseInt(argument.trim());
            ApplicationManager.getApplication().invokeLater(() -> choose(choice));
        } catch (Exception ignored) {
        }
        return null;
    }

    private void choose(int choice) {
        if (set == null || index < 0 || index >= set.questions().size()) return;
        final QuestionSet current = set;
        Question question = current.questions().get(index);
        Integer old = selected.put(question.id(), choice);
        if (old == null) {
            int seconds = (int) Math.max(1, (System.currentTimeMillis() - questionStart) / 1000);
            spentSeconds.put(question.id(), seconds);
        }
        render();

        final int questionIndex = index;
        final int seconds = spentSeconds.getOrDefault(question.id(), 1);
        Async.run("提交答案", () -> {
            host.api().answer(current.exerciseId(), question.id(), questionIndex, choice, seconds);
            return null;
        }, value -> {
        }, throwable -> {
            if (throwable instanceof LoginRequiredException) {
                host.handleError(throwable);
            }
        });
    }

    private void goPrev() {
        if (set == null || index <= 0) return;
        index--;
        questionStart = System.currentTimeMillis();
        render();
    }

    private void goNext() {
        if (set == null) return;
        if (index < set.questions().size() - 1) {
            index++;
            questionStart = System.currentTimeMillis();
            render();
        } else {
            submit();
        }
    }

    private void submit() {
        if (set == null || submitting) return;
        int result = Messages.showYesNoDialog(project,
                "确认交卷？交卷后会显示答案和解析。", "交卷", "交卷", "取消", null);
        if (result != Messages.YES) return;

        submitting = true;
        submitButton.setEnabled(false);
        ticker.stop();
        int elapsed = (int) ((System.currentTimeMillis() - exerciseStart) / 1000);
        QuestionSet current = set;

        Async.run("交卷", () -> host.api().submitAndLoadSolutions(current.exerciseId()), solutions -> {
            submitting = false;
            submitted = true;
            submitButton.setEnabled(true);
            host.showReview(current, solutions, selected, elapsed);
        }, throwable -> {
            submitting = false;
            submitButton.setEnabled(true);
            ticker.start();
            host.handleError(throwable);
        });
    }

    @Override
    public void dispose() {
        ticker.stop();
        if (query != null) {
            query.dispose();
        }
        if (browser != null) {
            browser.dispose();
        }
    }
}
