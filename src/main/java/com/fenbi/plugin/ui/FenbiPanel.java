package com.fenbi.plugin.ui;

import com.fenbi.plugin.model.Models.EssayPaper;
import com.fenbi.plugin.model.Models.Plan;
import com.fenbi.plugin.model.Models.QuestionSet;
import com.fenbi.plugin.model.Models.SolutionSet;
import com.fenbi.plugin.service.FenbiApi;
import com.fenbi.plugin.service.FenbiApi.LoginRequiredException;
import com.fenbi.plugin.service.FenbiSession;
import com.fenbi.plugin.service.OpenClawClient;
import com.fenbi.plugin.service.PlanService;
import com.fenbi.plugin.service.PlanSession;
import com.fenbi.plugin.service.Stats;
import com.fenbi.plugin.service.WrongBook;
import com.fenbi.plugin.util.Async;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionListener;
import java.util.Map;

public class FenbiPanel extends JPanel implements Disposable {
    private static final String CARD_BANK = "bank";
    private static final String CARD_QUIZ = "quiz";
    private static final String CARD_REVIEW = "review";
    private static final String CARD_ESSAY = "essay";
    private static final String CARD_SETTINGS = "settings";

    private final Project project;
    private final FenbiSession session;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel deck = new JPanel(cardLayout);
    private final JLabel statusLabel = new JBLabel("未授权");

    private final BankPanel bankPanel;
    private final QuizPanel quizPanel;
    private final ReviewPanel reviewPanel;
    private final EssayPanel essayPanel;
    private final SettingsPanel settingsPanel;
    private final OpenClawClient openClaw;

    private String lastTitle = "";
    private long lastKeypointId;
    private PlanSession planSession;

    public FenbiPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.session = FenbiSession.getInstance();
        this.openClaw = new OpenClawClient(
                () -> props("fenbi.openclawUrl"),
                () -> session.secret("openclawToken"),
                () -> props("fenbi.hookUrl"),
                () -> session.secret("hookToken"),
                () -> props("fenbi.openclawModel"));

        bankPanel = new BankPanel(this);
        quizPanel = new QuizPanel(this, project);
        reviewPanel = new ReviewPanel(this);
        essayPanel = new EssayPanel(this, project);
        settingsPanel = new SettingsPanel(this);

        deck.add(bankPanel, CARD_BANK);
        deck.add(quizPanel, CARD_QUIZ);
        deck.add(reviewPanel, CARD_REVIEW);
        deck.add(essayPanel, CARD_ESSAY);
        deck.add(settingsPanel, CARD_SETTINGS);

        add(createHeader(), BorderLayout.NORTH);
        add(deck, BorderLayout.CENTER);

        showBank();
        updateStatus();
        PlanService.getInstance().setListener(this::startPlan);
    }

    private static String props(String key) {
        return PropertiesComponent.getInstance().getValue(key, "");
    }

    public Project project() {
        return project;
    }

    public FenbiApi api() {
        return session.api();
    }

    public boolean isLoggedIn() {
        return session.isLoggedIn();
    }

    private JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setBorder(JBUI.Borders.empty(4, 10, 4, 6));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        JLabel title = new JBLabel("刷帮帮");
        title.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f));
        statusLabel.setFont(UIUtil.getLabelFont().deriveFont(11f));
        left.add(title);
        left.add(statusLabel);
        header.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right.setOpaque(false);
        right.add(iconButton(AllIcons.Actions.Back, "返回刷题", e -> backToQuiz()));
        right.add(iconButton(AllIcons.Actions.Download, "立即拉取今日计划并开始", e -> askPlanNow()));
        right.add(iconButton(AllIcons.Actions.Refresh, "刷新题库", e -> refresh()));
        right.add(iconButton(AllIcons.General.Settings, "设置", e -> showSettings()));
        header.add(right, BorderLayout.EAST);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(header, BorderLayout.CENTER);
        wrapper.setBorder(JBUI.Borders.customLineBottom(JBColor.namedColor("Borders.color", 0x3a3d43)));
        return wrapper;
    }

    private JButton iconButton(Icon icon, String tooltip, ActionListener listener) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setBorder(JBUI.Borders.empty(3));
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.addActionListener(listener);
        return button;
    }

    // ------------------------------------------------------------------ 导航

    public void showBank() {
        cardLayout.show(deck, CARD_BANK);
        bankPanel.onShown();
        updateStatus();
    }

    public void showSettings() {
        settingsPanel.refresh();
        cardLayout.show(deck, CARD_SETTINGS);
    }

    private void refresh() {
        if (!ensureLogin()) return;
        bankPanel.reload();
        cardLayout.show(deck, CARD_BANK);
    }

    private void backToQuiz() {
        if (quizPanel.isActive()) {
            cardLayout.show(deck, CARD_QUIZ);
        } else {
            showBank();
        }
    }

    public void askPlanNow() {
        if (!ensureLogin()) return;
        settingsPanel.saveConfig();
        if (!PlanService.getInstance().isConfigured()) {
            Messages.showWarningDialog(project,
                    "请先在「设置 → OpenClaw 联动」里填写网关 Token 并保存。", "刷帮帮");
            showSettings();
            return;
        }
        PlanPullDialog dialog = new PlanPullDialog(project);
        dialog.show();
        statusLabel.setText("正在向 OpenClaw 问询…");
        PlanService.getInstance().checkNow(true, new PlanService.Listener() {
            @Override
            public void log(String line) {
                dialog.append(line);
            }

            @Override
            public void done(boolean success, String message) {
                dialog.finish(success, message);
                statusLabel.setText(success ? "计划已就绪" : "问询失败：" + message);
            }
        });
    }

    public boolean login() {
        LoginDialog dialog = new LoginDialog(project);
        if (dialog.showAndGet()) {
            updateStatus();
            bankPanel.reload();
            return true;
        }
        return false;
    }

    public boolean ensureLogin() {
        if (session.isLoggedIn()) return true;
        return login();
    }

    public void logout() {
        session.clear();
        updateStatus();
        bankPanel.reset();
        showBank();
    }

    public void updateStatus() {
        if (session.isLoggedIn()) {
            statusLabel.setText("已授权");
            statusLabel.setForeground(JBColor.namedColor("Component.successForeground", 0x4ea24e));
        } else {
            statusLabel.setText("未授权");
            statusLabel.setForeground(JBColor.GRAY);
        }
    }

    // ------------------------------------------------------------------ 业务

    public void startQuiz(String title, long keypointId) {
        planSession = null;
        startQuizInternal(title, keypointId);
    }

    private void startQuizInternal(String title, long keypointId) {
        if (!ensureLogin()) return;
        lastTitle = title == null ? "" : title;
        lastKeypointId = keypointId;

        int limit = PlanService.configQuestionCount();
        statusLabel.setText("组卷中…");
        Async.run("组卷", () -> {
            long exerciseId = session.api().createExercise(keypointId, limit);
            return session.api().loadQuestions(exerciseId, title);
        }, set -> {
            updateStatus();
            if (set.questions().isEmpty()) {
                statusLabel.setText("没有取到题目");
                Messages.showInfoMessage(project, "没有取到题目，换一个分类试试。", "刷帮帮");
                return;
            }
            quizPanel.start(set);
            cardLayout.show(deck, CARD_QUIZ);
        }, this::handleError);
    }

    public void showReview(QuestionSet set, SolutionSet solutions, Map<Long, Integer> answers, int elapsedSeconds) {
        String retryText = "再练一组";
        Runnable retry = () -> startQuiz(lastTitle, lastKeypointId);
        if (planSession != null) {
            planSession.recordResult(set, solutions, answers, elapsedSeconds);
            if (planSession.hasNext()) {
                retryText = "下一项";
                retry = planSession::next;
            } else {
                retryText = "完成";
                retry = () -> {
                    planSession = null;
                    showBank();
                };
            }
        } else {
            WrongBook.getInstance().record(set, solutions, answers, lastTitle, "", lastKeypointId, "手动");
            Stats.getInstance().record(set, solutions, answers, lastTitle, elapsedSeconds);
        }
        reviewPanel.show(set, solutions, answers, elapsedSeconds, retryText, retry, this::showBank);
        cardLayout.show(deck, CARD_REVIEW);
        updateStatus();
        statusLabel.setText(planSession != null && planSession.hasNext() ? "计划进行中" : "已完成");
    }

    // ------------------------------------------------------------------ 计划模式

    public void startPlan(Plan plan) {
        if (plan == null || plan.items().isEmpty()) return;
        if (!ensureLogin()) return;
        planSession = new PlanSession(plan, this);
        cardLayout.show(deck, CARD_BANK);
        planSession.start();
    }

    public void startPlanItem(PlanSession session, String title, long keypointId) {
        this.planSession = session;
        startQuizInternal(title, keypointId);
    }

    public void setStatusText(String text) {
        statusLabel.setText(text);
    }

    public void showAnalysis(String text) {
        String content = text == null || text.isBlank() ? "（空）" : text;
        reviewPanel.setAnalysis(content);
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("ShuaBangBang")
                .createNotification("刷帮帮 · 大模型分析", content, NotificationType.INFORMATION);
        Notifications.Bus.notify(notification, project);
    }

    public OpenClawClient openClaw() {
        return openClaw;
    }

    public void openEssay(EssayPaper paper) {
        if (!ensureLogin()) return;
        statusLabel.setText("加载申论…");
        Async.run("加载申论", () -> session.api().essayContent(paper), content -> {
            updateStatus();
            essayPanel.show(paper, content);
            cardLayout.show(deck, CARD_ESSAY);
        }, this::handleError);
    }

    // ------------------------------------------------------------------ 错误

    public void handleError(Throwable throwable) {
        updateStatus();
        if (throwable instanceof LoginRequiredException) {
            session.clear();
            updateStatus();
            statusLabel.setText("授权已失效");
            Notification notification = new Notification("ShuaBangBang", "刷帮帮", "授权已失效，请重新授权",
                    NotificationType.WARNING);
            notification.addAction(new NotificationAction("重新授权") {
                @Override
                public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e,
                                            @NotNull Notification notification) {
                    login();
                    notification.expire();
                }
            });
            Notifications.Bus.notify(notification, project);
            return;
        }
        String message = throwable.getMessage();
        if (StringUtil.isEmpty(message)) {
            message = throwable.getClass().getSimpleName();
        }
        statusLabel.setText("出错了");
        Notification notification = new Notification("ShuaBangBang", "刷帮帮", message, NotificationType.ERROR);
        notification.addAction(new NotificationAction("复制详情") {
            @Override
            public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e,
                                        @NotNull Notification n) {
                CopyPasteManager.getInstance().setContents(new StringSelection(String.valueOf(throwable)));
                n.expire();
            }
        });
        Notifications.Bus.notify(notification, project);
    }

    @Override
    public void dispose() {
        PlanService.getInstance().setListener(null);
        bankPanel.dispose();
        quizPanel.dispose();
        reviewPanel.dispose();
        essayPanel.dispose();
    }
}
