package com.fenbi.plugin.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * 拉取过程的实时日志窗口，方便看清请求、耗时、返回和解析结果。
 */
public class PlanPullDialog extends DialogWrapper {
    private final JBTextArea area = new JBTextArea();
    private final JBLabel status = new JBLabel("准备中…");
    private final StringBuilder buffer = new StringBuilder();
    private final Timer ticker;
    private int seconds;
    private boolean finished;

    public PlanPullDialog(@Nullable Project project) {
        super(project, false);
        setTitle("从大模型拉取计划");
        setModal(false);
        init();
        setSize(700, 480);
        ticker = new Timer(1000, e -> {
            if (finished) return;
            seconds++;
            status.setText("进行中… " + seconds + " 秒");
        });
        ticker.start();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBorder(JBUI.Borders.empty(6));

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.add(new JBScrollPane(area), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(6, 0));
        status.setFont(UIUtil.getLabelFont().deriveFont(12f));
        south.add(status, BorderLayout.WEST);

        JButton copy = new JButton("复制日志");
        copy.addActionListener(e -> CopyPasteManager.getInstance()
                .setContents(new StringSelection(buffer.toString())));
        south.add(copy, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);

        root.setPreferredSize(new Dimension(660, 400));
        return root;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getCancelAction()};
    }

    public void append(String line) {
        ApplicationManager.getApplication().invokeLater(() -> {
            buffer.append(line).append('\n');
            area.setText(buffer.toString());
            area.setCaretPosition(buffer.length());
        });
    }

    public void finish(boolean success, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            finished = true;
            ticker.stop();
            status.setText((success ? "✓ " : "✗ ") + message);
            status.setForeground(success
                    ? JBColor.namedColor("Component.successForeground", 0x4ea24e)
                    : JBColor.RED);
            buffer.append("== ").append(success ? "完成：" : "失败：").append(message).append('\n');
            area.setText(buffer.toString());
            area.setCaretPosition(buffer.length());
        });
    }

    @Override
    protected void dispose() {
        ticker.stop();
        super.dispose();
    }
}
