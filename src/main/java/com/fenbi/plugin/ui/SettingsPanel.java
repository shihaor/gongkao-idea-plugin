package com.fenbi.plugin.ui;

import com.fenbi.plugin.service.FenbiSession;
import com.fenbi.plugin.service.PlanService;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class SettingsPanel extends JPanel {
    private static final String KEY_COUNT = "fenbi.questionCount";

    private final FenbiPanel host;
    private final JLabel accountStatus = new JBLabel();
    private final JButton loginButton = new JButton("授权题库");
    private final JButton logoutButton = new JButton("断开授权");
    private final JSpinner countSpinner;

    private final JBTextField urlField = new JBTextField();
    private final JBPasswordField tokenField = new JBPasswordField();
    private final JBTextField modelField = new JBTextField();
    private final JBTextField hookField = new JBTextField();
    private final JBPasswordField hookTokenField = new JBPasswordField();
    private final JSpinner hourSpinner = new JSpinner(new SpinnerNumberModel(9, 0, 23, 1));
    private final JSpinner intervalSpinner = new JSpinner(new SpinnerNumberModel(150, 15, 240, 15));
    private final JLabel planStatus = new JBLabel();

    public SettingsPanel(FenbiPanel host) {
        super(new BorderLayout());
        this.host = host;

        int count = PlanService.configQuestionCount();
        countSpinner = new JSpinner(new SpinnerNumberModel(count, 1, 50, 1));
        countSpinner.addChangeListener(e -> PlanService.saveQuestionCount((Integer) countSpinner.getValue()));

        loginButton.addActionListener(e -> {
            if (host.login()) {
                refresh();
            }
        });
        logoutButton.addActionListener(e -> {
            host.logout();
            refresh();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(loginButton);
        buttons.add(logoutButton);

        JPanel account = new JPanel(new BorderLayout(0, 6));
        account.setOpaque(false);
        account.add(accountStatus, BorderLayout.NORTH);
        account.add(buttons, BorderLayout.WEST);

        modelField.setToolTipText("填接口支持的模型名，如 deepseek-chat、gpt-4o-mini、openclaw/main");
        urlField.setToolTipText("OpenAI 兼容地址，如 https://api.deepseek.com 或 http://127.0.0.1:18789");
        hookField.setToolTipText("可选。仅 OpenClaw 需要，填了就把完成结果推到 IM");

        JPanel openClawButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        openClawButtons.setOpaque(false);
        JButton save = new JButton("保存并生效");
        save.addActionListener(e -> saveConfig());
        JButton check = new JButton("立即问询");
        check.addActionListener(e -> {
            saveConfig();
            host.askPlanNow();
        });
        openClawButtons.add(save);
        openClawButtons.add(check);

        planStatus.setForeground(UIUtil.getContextHelpForeground());
        planStatus.setFont(UIUtil.getLabelFont().deriveFont(11f));

        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(new TitledSeparator("练习"))
                .addLabeledComponent(new JBLabel("每组题量"), countSpinner)
                .addComponent(hint("手动点分类时请求的题量；计划模式以计划里的数量为准。"), 1)
                .addComponent(new TitledSeparator("题库授权"))
                .addComponent(account, 0)
                .addComponent(hint("授权信息保存在 IDEA 密码库（PasswordSafe），只在本机使用。"), 1)
                .addComponent(new TitledSeparator("大模型 / OpenClaw"))
                .addLabeledComponent(new JBLabel("接口地址"), urlField)
                .addLabeledComponent(new JBLabel("API Key"), tokenField)
                .addLabeledComponent(new JBLabel("模型"), modelField)
                .addLabeledComponent(new JBLabel("开始时间（点）"), hourSpinner)
                .addLabeledComponent(new JBLabel("问询间隔（分钟）"), intervalSpinner)
                .addLabeledComponent(new JBLabel("结果 Webhook"), hookField)
                .addLabeledComponent(new JBLabel("Webhook Token"), hookTokenField)
                .addComponent(openClawButtons, 4)
                .addComponent(planStatus, 1)
                .addComponent(hint("每天到点后按间隔向大模型问询今日计划（默认早上 9 点）；拿到计划后弹通知，点「开始」进入计划模式。"
                        + "做完会把成绩回传，让大模型分析薄弱点。"), 4)
                .addComponent(hint("直连示例：DeepSeek → https://api.deepseek.com + deepseek-chat；"
                        + "OpenAI → https://api.openai.com + gpt-4o-mini；Ollama → http://localhost:11434 + qwen2.5:7b。"), 1)
                .addComponent(hint("接 OpenClaw 则填 http://...:18789，并在 openclaw.json 打开 "
                        + "gateway.http.endpoints.chatCompletions.enabled。"), 1)
                .addComponent(new TitledSeparator("关于"))
                .addComponent(new JBLabel("刷帮帮 0.8.0"), 0)
                .addComponent(hint("非官方插件，仅个人学习使用。题库数据来自公开接口，改版后可能需要适配。"), 1)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        form.setBorder(JBUI.Borders.empty(4, 12, 12, 12));

        add(new JBScrollPane(form), BorderLayout.CENTER);
        refresh();
    }

    public void saveConfig() {
        PlanService.saveConfig(urlField.getText(), modelField.getText(), hookField.getText(),
                (Integer) hourSpinner.getValue(), (Integer) intervalSpinner.getValue());
        FenbiSession session = FenbiSession.getInstance();
        session.saveSecret("openclawToken", new String(tokenField.getPassword()));
        session.saveSecret("hookToken", new String(hookTokenField.getPassword()));
        PlanService.getInstance().restart();
        planStatus.setText("已保存，将在 " + hourSpinner.getValue() + " 点后每 "
                + intervalSpinner.getValue() + " 分钟问询一次。");
    }

    public void refresh() {
        boolean loggedIn = host.isLoggedIn();
        accountStatus.setText(loggedIn ? "已授权，可以开始刷题" : "未授权");
        accountStatus.setForeground(loggedIn
                ? com.intellij.ui.JBColor.namedColor("Component.successForeground", 0x4ea24e)
                : UIUtil.getContextHelpForeground());
        loginButton.setText(loggedIn ? "重新授权" : "授权题库");
        logoutButton.setEnabled(loggedIn);

        urlField.setText(PlanService.configUrl());
        modelField.setText(PlanService.configModel());
        hookField.setText(PlanService.configHook());
        hourSpinner.setValue(PlanService.configStartHour());
        intervalSpinner.setValue(PlanService.configInterval());
        tokenField.setText(FenbiSession.getInstance().secret("openclawToken"));
        hookTokenField.setText(FenbiSession.getInstance().secret("hookToken"));

        long last = PlanService.getInstance().lastPollAt();
        String error = PlanService.getInstance().lastError();
        if (error != null && !error.isBlank()) {
            planStatus.setText("上次问询失败：" + error);
        } else if (last > 0) {
            String time = DateTimeFormatter.ofPattern("HH:mm")
                    .format(Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()));
            planStatus.setText("上次问询：" + time
                    + (PlanService.lastPlanId().isBlank() ? "" : "，计划 " + PlanService.lastPlanId()));
        } else {
            planStatus.setText("尚未问询。");
        }
    }

    private static JLabel hint(String text) {
        JLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setFont(UIUtil.getLabelFont().deriveFont(11f));
        return label;
    }
}
