package com.fenbi.plugin.ui;

import com.fenbi.plugin.model.Models.EssayContent;
import com.fenbi.plugin.model.Models.EssayPaper;
import com.fenbi.plugin.model.Models.Material;
import com.fenbi.plugin.model.Models.Solution;
import com.fenbi.plugin.util.Html;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;

/**
 * 申论：材料 + 作答 + 参考答案。
 */
public class EssayPanel extends JPanel implements Disposable {
    private final JLabel titleLabel = new JBLabel("申论");
    private final JBCefBrowser browser;
    private final JTextArea answerArea = new JTextArea(5, 40);
    private final JLabel countLabel = new JBLabel("0 字");
    private final JButton refButton = new JButton("查看参考答案");
    private final JSplitPane split;

    private EssayPaper paper;
    private EssayContent content;
    private boolean showReferences;

    public EssayPanel(FenbiPanel host, com.intellij.openapi.project.Project project) {
        super(new BorderLayout());

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        JButton back = new JButton(AllIcons.Actions.Back);
        back.setToolTipText("返回题库");
        back.setFocusable(false);
        back.setBorder(JBUI.Borders.empty(2));
        back.setContentAreaFilled(false);
        back.addActionListener(e -> host.showBank());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(back);
        titleLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD, 12.5f));
        left.add(titleLabel);
        top.add(left, BorderLayout.WEST);
        add(top, BorderLayout.NORTH);

        JComponent contentComponent;
        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            contentComponent = browser.getComponent();
        } else {
            browser = null;
            JLabel unsupported = new JBLabel("当前 IDE 未启用 JCEF，无法显示材料。");
            unsupported.setBorder(JBUI.Borders.empty(12));
            contentComponent = unsupported;
        }

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, contentComponent, createAnswerArea());
        split.setResizeWeight(0.7);
        split.setBorder(null);
        split.setDividerSize(6);
        add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        bottom.setBorder(JBUI.Borders.empty(4, 8, 8, 8));
        refButton.setFocusable(false);
        refButton.addActionListener(e -> {
            showReferences = !showReferences;
            refButton.setText(showReferences ? "收起参考答案" : "查看参考答案");
            render();
        });
        bottom.add(refButton);
        add(bottom, BorderLayout.SOUTH);

        answerArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateCount();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateCount();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateCount();
            }
        });
    }

    private JComponent createAnswerArea() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(JBUI.Borders.empty(2, 8, 0, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel label = new JBLabel("作答区（本地暂存，不自动提交）");
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setFont(UIUtil.getLabelFont().deriveFont(11f));
        header.add(label, BorderLayout.WEST);
        countLabel.setForeground(UIUtil.getContextHelpForeground());
        countLabel.setFont(UIUtil.getLabelFont().deriveFont(11f));
        header.add(countLabel, BorderLayout.EAST);

        answerArea.setLineWrap(true);
        answerArea.setWrapStyleWord(true);
        answerArea.setBorder(JBUI.Borders.empty(6));

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JBScrollPane(answerArea), BorderLayout.CENTER);
        return panel;
    }

    public void show(EssayPaper paper, EssayContent content) {
        this.paper = paper;
        this.content = content;
        this.showReferences = false;
        this.refButton.setText("查看参考答案");
        this.answerArea.setText("");
        this.titleLabel.setText(paper == null ? "申论" : paper.displayName());
        render();
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.7));
    }

    private void render() {
        if (browser == null || content == null) return;
        StringBuilder body = new StringBuilder();
        body.append("<div class=\"meta\">").append(Html.badge("申论", "blue"));
        if (paper != null && paper.topic() != null && !paper.topic().isBlank()) {
            body.append(Html.badge(paper.topic(), ""));
        }
        body.append("</div>");

        if (paper != null) {
            body.append("<div class=\"stem\"><b>").append(Html.escape(paper.displayName())).append("</b></div>");
        }

        for (Material material : content.materials()) {
            body.append(Html.material(material.content()));
        }

        int questionIndex = 0;
        for (Solution question : content.questions()) {
            questionIndex++;
            body.append("<div class=\"q\">");
            body.append("<div class=\"meta\"><span class=\"badge\">第 ").append(questionIndex).append(" 题</span></div>");
            body.append("<div class=\"stem\">").append(Html.fix(question.content())).append("</div>");
            body.append("</div>");
        }

        if (showReferences) {
            body.append("<div class=\"ref\"><div class=\"h\">参考答案</div>");
            if (content.references().isEmpty()) {
                body.append("<div class=\"b\">这套试卷暂时没有返回参考答案。</div>");
            } else {
                for (Solution reference : content.references()) {
                    body.append("<div class=\"b\" style=\"margin-bottom:10px\">")
                            .append(Html.fix(reference.solution()))
                            .append("</div>");
                }
            }
            body.append("</div>");
        }

        browser.loadHTML(Html.page(body.toString()));
    }

    private void updateCount() {
        int count = answerArea.getText().replaceAll("\\s", "").length();
        countLabel.setText(count + " 字");
    }

    @Override
    public void dispose() {
        if (browser != null) {
            browser.dispose();
        }
    }
}
