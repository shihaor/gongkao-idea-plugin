package com.fenbi.plugin.ui;

import com.fenbi.plugin.service.FenbiApi;
import com.fenbi.plugin.service.WrongBook;
import com.fenbi.plugin.util.Html;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 错题本：加密保存在本地，可回看解析、按考点重练。
 */
public class WrongBookPanel extends JPanel implements Disposable {
    private static final String CARD_LIST = "list";
    private static final String CARD_DETAIL = "detail";

    private final FenbiPanel host;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final DefaultListModel<WrongBook.Entry> model = new DefaultListModel<>();
    private final JBList<WrongBook.Entry> list = new JBList<>(model);
    private final JLabel countLabel = new JBLabel("共 0 题");
    private final JLabel detailTitle = new JBLabel("");
    private final JButton retryButton = new JButton("按考点重练");
    private final JButton deleteButton = new JButton("删除");

    private final JBCefBrowser browser;
    private WrongBook.Entry current;

    public WrongBookPanel(FenbiPanel host) {
        super(new BorderLayout());
        this.host = host;

        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
        } else {
            browser = null;
        }

        deck.add(createListCard(), CARD_LIST);
        deck.add(createDetailCard(), CARD_DETAIL);
        add(deck, BorderLayout.CENTER);
        refresh();
    }

    private JComponent createListCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(JBUI.Borders.empty(6, 8, 8, 8));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(JBUI.Borders.emptyBottom(6));
        countLabel.setForeground(UIUtil.getContextHelpForeground());
        top.add(countLabel, BorderLayout.WEST);
        JButton clear = new JButton("清空");
        clear.addActionListener(e -> clearAll());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        right.add(clear);
        top.add(right, BorderLayout.EAST);

        list.setCellRenderer(new EntryRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 1) return;
                int index = list.locationToIndex(e.getPoint());
                if (index < 0 || index >= model.size()) return;
                Rectangle bounds = list.getCellBounds(index, index);
                if (bounds == null || !bounds.contains(e.getPoint())) return;
                showDetail(model.get(index));
            }
        });

        JLabel hint = new JBLabel("答错的题会自动收进来；单击查看解析，可按考点重练。");
        hint.setForeground(UIUtil.getContextHelpForeground());
        hint.setFont(UIUtil.getLabelFont().deriveFont(11f));
        hint.setBorder(JBUI.Borders.emptyTop(6));

        card.add(top, BorderLayout.NORTH);
        card.add(new JScrollPane(list), BorderLayout.CENTER);
        card.add(hint, BorderLayout.SOUTH);
        return card;
    }

    private JComponent createDetailCard() {
        JPanel card = new JPanel(new BorderLayout());

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        JButton back = new JButton(AllIcons.Actions.Back);
        back.setToolTipText("返回错题列表");
        back.setFocusable(false);
        back.setBorder(JBUI.Borders.empty(2));
        back.setContentAreaFilled(false);
        back.addActionListener(e -> cards.show(deck, CARD_LIST));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(back);
        detailTitle.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD, 12.5f));
        left.add(detailTitle);
        top.add(left, BorderLayout.WEST);
        card.add(top, BorderLayout.NORTH);

        if (browser != null) {
            card.add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            JLabel unsupported = new JBLabel("当前 IDE 未启用 JCEF，无法显示题目。");
            unsupported.setBorder(JBUI.Borders.empty(12));
            card.add(unsupported, BorderLayout.CENTER);
        }

        retryButton.addActionListener(e -> retryCurrent());
        deleteButton.addActionListener(e -> deleteCurrent());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        bottom.setBorder(JBUI.Borders.empty(6, 8, 8, 8));
        bottom.add(deleteButton);
        bottom.add(retryButton);
        card.add(bottom, BorderLayout.SOUTH);
        return card;
    }

    public void refresh() {
        model.clear();
        List<WrongBook.Entry> entries = WrongBook.getInstance().all();
        for (WrongBook.Entry entry : entries) {
            model.addElement(entry);
        }
        countLabel.setText("共 " + entries.size() + " 题");
    }

    private void showDetail(WrongBook.Entry entry) {
        current = entry;
        detailTitle.setText(entry.type + " · 错 " + entry.wrongCount + " 次");
        retryButton.setEnabled(entry.keypointId > 0);
        deleteButton.setEnabled(true);
        cards.show(deck, CARD_DETAIL);

        if (browser == null) return;

        StringBuilder body = new StringBuilder();
        body.append("<div class=\"meta\">")
                .append(Html.badge(entry.type == null || entry.type.isBlank() ? "错题" : entry.type, "blue"))
                .append(Html.badge("错 " + entry.wrongCount + " 次", ""))
                .append(Html.badge(entry.source == null || entry.source.isBlank() ? "手动" : entry.source, ""))
                .append("<span class=\"idx\">").append(Html.escape(entry.updatedAt)).append("</span>")
                .append("</div>");

        if (entry.path != null && !entry.path.isBlank()) {
            body.append("<div class=\"material\">").append(Html.escape(entry.path)).append("</div>");
        }

        body.append("<div class=\"stem\">").append(Html.fix(entry.contentHtml)).append("</div>");
        body.append("<div class=\"opts\">");
        for (int i = 0; i < entry.options.size(); i++) {
            String state;
            if (!entry.correct.isBlank() && String.valueOf(i).equals(entry.correct)) {
                state = "correct";
            } else if (entry.your != null && entry.your == i) {
                state = "wrong";
            } else {
                state = "muted";
            }
            body.append(Html.option(i, entry.options.get(i), state, ""));
        }
        body.append("</div>");

        if (!entry.correct.isBlank()) {
            body.append("<div class=\"ans\">正确答案 <b>").append(letterOf(entry.correct))
                    .append("</b>");
            if (entry.your != null) {
                body.append("　你的答案 <b class=\"").append(entry.your == parse(entry.correct) ? "ok" : "no")
                        .append("\">").append(Html.letter(entry.your)).append("</b>");
            } else {
                body.append("　你的答案 <b>未作答</b>");
            }
            body.append("</div>");
        }

        if (entry.analysisHtml != null && !entry.analysisHtml.isBlank()) {
            body.append("<div class=\"sol\"><div class=\"h\">解析</div><div class=\"b\">")
                    .append(Html.fix(entry.analysisHtml)).append("</div></div>");
        }

        browser.loadHTML(Html.page(body.toString()));
    }

    private static int parse(String choice) {
        try {
            return Integer.parseInt(choice);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String letterOf(String choice) {
        int index = parse(choice);
        return index >= 0 ? Html.letter(index) : Html.escape(choice);
    }

    private void retryCurrent() {
        if (current == null) return;
        if (current.keypointId <= 0) {
            Messages.showInfoMessage(host.project(), "这条错题没有记录考点，无法按考点重练。", "刷帮帮");
            return;
        }
        host.startQuiz(current.type, current.keypointId);
    }

    private void deleteCurrent() {
        if (current == null) return;
        int answer = Messages.showYesNoDialog(host.project(), "从错题本删除这道题？", "刷帮帮",
                Messages.getQuestionIcon());
        if (answer != Messages.YES) return;
        WrongBook.getInstance().remove(current.questionId);
        current = null;
        refresh();
        cards.show(deck, CARD_LIST);
    }

    private void clearAll() {
        if (model.isEmpty()) return;
        int answer = Messages.showYesNoDialog(host.project(), "清空整个错题本？该操作不可恢复。", "刷帮帮",
                Messages.getWarningIcon());
        if (answer != Messages.YES) return;
        WrongBook.getInstance().clear();
        refresh();
        cards.show(deck, CARD_LIST);
    }

    private static final class EntryRenderer extends ColoredListCellRenderer<WrongBook.Entry> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends WrongBook.Entry> list, WrongBook.Entry value,
                                             int index, boolean selected, boolean hasFocus) {
            setIcon(AllIcons.Actions.Cancel);
            append(value.stem, SimpleTextAttributes.REGULAR_ATTRIBUTES);
            if (value.type != null && !value.type.isBlank()) {
                append("   " + value.type, SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES);
            }
            if (value.wrongCount > 1) {
                append("   ×" + value.wrongCount, SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES);
            }
        }
    }

    @Override
    public void dispose() {
        if (browser != null) {
            browser.dispose();
        }
    }
}
