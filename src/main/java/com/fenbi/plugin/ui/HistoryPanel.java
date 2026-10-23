package com.fenbi.plugin.ui;

import com.fenbi.plugin.model.Models.Plan;
import com.fenbi.plugin.model.Models.PlanItem;
import com.fenbi.plugin.service.History;
import com.fenbi.plugin.service.PlanStore;
import com.fenbi.plugin.util.Html;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 历史做题记录：点未完成的继续做，点完成的看逐项明细和错题。
 */
public class HistoryPanel extends JPanel implements Disposable {
    private static final String CARD_LIST = "list";
    private static final String CARD_DETAIL = "detail";

    private final FenbiPanel host;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final DefaultListModel<History.Record> model = new DefaultListModel<>();
    private final JBList<History.Record> list = new JBList<>(model);
    private final JLabel hintLabel = new JBLabel("");
    private final JLabel detailTitle = new JBLabel("");
    private final JBCefBrowser browser;

    public HistoryPanel(FenbiPanel host) {
        super(new BorderLayout());
        this.host = host;
        browser = JBCefApp.isSupported() ? new JBCefBrowser() : null;

        deck.add(createListCard(), CARD_LIST);
        deck.add(createDetailCard(), CARD_DETAIL);
        add(deck, BorderLayout.CENTER);
        refresh();
    }

    private JComponent createListCard() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(JBUI.Borders.empty(6, 8, 8, 8));

        list.setCellRenderer(new RecordRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 1) return;
                int index = list.locationToIndex(e.getPoint());
                if (index < 0 || index >= model.size()) return;
                Rectangle bounds = list.getCellBounds(index, index);
                if (bounds == null || !bounds.contains(e.getPoint())) return;
                open(model.get(index));
            }
        });

        hintLabel.setForeground(UIUtil.getContextHelpForeground());
        hintLabel.setFont(UIUtil.getLabelFont().deriveFont(11f));
        hintLabel.setBorder(JBUI.Borders.emptyTop(6));

        card.add(new JScrollPane(list), BorderLayout.CENTER);
        card.add(hintLabel, BorderLayout.SOUTH);
        return card;
    }

    private JComponent createDetailCard() {
        JPanel card = new JPanel(new BorderLayout());

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        JButton back = new JButton(AllIcons.Actions.Back);
        back.setToolTipText("返回历史列表");
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
            JLabel unsupported = new JBLabel("当前 IDE 未启用 JCEF，无法显示详情。");
            unsupported.setBorder(JBUI.Borders.empty(12));
            card.add(unsupported, BorderLayout.CENTER);
        }
        return card;
    }

    public void refresh() {
        List<History.Record> entries = new ArrayList<>();
        History.Record active = History.getInstance().preview(PlanStore.getInstance().unfinishedToday());
        if (active != null) {
            entries.add(active);
        }
        for (History.Record record : History.getInstance().all()) {
            if (active != null && record.planId.equals(active.planId)) {
                continue;
            }
            entries.add(record);
        }

        model.clear();
        for (History.Record record : entries) {
            model.addElement(record);
        }
        hintLabel.setText(entries.isEmpty()
                ? "还没有做题记录，拉取计划或做一组题就会出现。"
                : "点未完成的继续做题；点已完成/已中断的查看明细。");
    }

    private void open(History.Record record) {
        if ("active".equals(record.status)) {
            host.resumePlan();
            return;
        }
        if ("pending".equals(record.status)) {
            start(record);
            return;
        }
        showDetail(record);
    }

    private void start(History.Record record) {
        List<PlanItem> items = new ArrayList<>();
        for (History.Item item : record.items) {
            items.add(new PlanItem(item.type, List.of(), item.count));
        }
        if (items.isEmpty()) return;
        host.startPlan(new Plan(record.planId, record.date, "", "", items, false));
    }

    private void showDetail(History.Record record) {
        detailTitle.setText(record.date + " · " + ("completed".equals(record.status) ? "已完成" : "已中断"));
        cards.show(deck, CARD_DETAIL);
        if (browser == null) return;

        int percent = record.totalQuestions == 0 ? 0
                : Math.round(record.completedQuestions * 100f / record.totalQuestions);
        int accuracy = record.completedQuestions == 0 ? -1
                : Math.round(record.correct * 100f / record.completedQuestions);

        StringBuilder body = new StringBuilder(style());
        body.append("<div class=\"hd\"><span class=\"badge ")
                .append("completed".equals(record.status) ? "ok" : "warn").append("\">")
                .append("completed".equals(record.status) ? "已完成" : "已中断")
                .append("</span><span class=\"pct\">").append(percent).append("%</span></div>");
        body.append("<div class=\"sub\">完成 ").append(record.completedQuestions).append("/")
                .append(record.totalQuestions).append(" 题");
        if (accuracy >= 0) {
            body.append(" · 正确 ").append(record.correct).append("（").append(accuracy).append("%）");
        }
        if (record.elapsedSeconds > 0) {
            body.append(" · 用时 ").append(format(record.elapsedSeconds));
        }
        body.append("</div>");

        if (record.modules == null || record.modules.isEmpty()) {
            body.append("<div class=\"note\">该记录来自旧版本，没有逐项明细。</div>");
        } else {
            body.append("<div class=\"title\">题型明细</div>");
            body.append("<table><tr><th>题型</th><th>题量</th><th>正确</th><th>正确率</th><th>用时</th></tr>");
            for (History.Module module : record.modules) {
                int moduleAccuracy = module.total == 0 ? 0
                        : Math.round(module.correct * 100f / module.total);
                body.append("<tr><td>").append(Html.escape(module.type)).append("</td><td>")
                        .append(module.total).append("</td><td>").append(module.correct)
                        .append("</td><td>").append(moduleAccuracy).append("%</td><td>")
                        .append(format(module.seconds)).append("</td></tr>");
            }
            body.append("</table>");

            List<History.Wrong> wrongItems = new ArrayList<>();
            for (History.Module module : record.modules) {
                if (module.wrong != null) {
                    wrongItems.addAll(module.wrong);
                }
            }
            if (!wrongItems.isEmpty()) {
                body.append("<div class=\"title\">错题（").append(wrongItems.size()).append("）</div>");
                for (History.Wrong wrong : wrongItems) {
                    body.append("<div class=\"wrong\"><div class=\"wt\">")
                            .append(Html.escape(wrong.type)).append("</div><div class=\"wq\">")
                            .append(Html.escape(wrong.stem)).append("</div><div class=\"wa\">你的 ")
                            .append(Html.escape(wrong.your)).append(" · 正确答案 ")
                            .append(Html.escape(wrong.correct)).append("</div></div>");
                }
            }
        }
        browser.loadHTML(Html.page(body.toString()));
    }

    private static String format(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static final class RecordRenderer extends ColoredListCellRenderer<History.Record> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends History.Record> list, History.Record value,
                                             int index, boolean selected, boolean hasFocus) {
            boolean active = "active".equals(value.status);
            boolean pending = "pending".equals(value.status);
            setIcon(active || pending ? AllIcons.Actions.Execute : AllIcons.Nodes.Class);

            String status = active ? "进行中" : pending ? "待开始"
                    : "completed".equals(value.status) ? "已完成" : "已中断";
            append((value.updatedAt == null || value.updatedAt.isBlank() ? value.date : value.updatedAt)
                    + "  ", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES);
            append(status, active || pending
                    ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAY_ATTRIBUTES);

            int percent = value.totalQuestions == 0 ? 0
                    : Math.round(value.completedQuestions * 100f / value.totalQuestions);
            append("  " + percent + "%", SimpleTextAttributes.REGULAR_ATTRIBUTES);

            if (value.items != null && !value.items.isEmpty()) {
                StringBuilder types = new StringBuilder();
                for (History.Item item : value.items) {
                    if (types.length() > 0) types.append("、");
                    types.append(item.type);
                }
                append("   " + types, SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES);
            }
        }
    }

    private static String style() {
        return "<style>"
                + ".hd{display:flex;align-items:baseline;gap:10px}"
                + ".badge{font-size:11px;padding:1px 8px;border-radius:10px;border:1px solid var(--border2)}"
                + ".badge.ok{color:var(--ok);border-color:rgba(87,150,92,.5);background:var(--okbg)}"
                + ".badge.warn{color:#d6ae58;border-color:rgba(214,174,88,.5);background:rgba(214,174,88,.12)}"
                + ".pct{font:700 22px Consolas,monospace;color:var(--fg)}"
                + ".sub{font-size:11.5px;color:var(--mute);margin-top:4px}"
                + ".title{font-size:11px;color:var(--mute);letter-spacing:.5px;margin:18px 0 8px}"
                + "table{width:100%;border-collapse:collapse;font-size:12px}"
                + "th{text-align:left;font-weight:400;color:var(--mute);font-size:11px;padding:4px 6px;"
                + "border-bottom:1px solid var(--border)}"
                + "td{padding:6px;border-bottom:1px solid var(--border);color:var(--dim)}"
                + "td:first-child{color:var(--fg)}"
                + ".wrong{background:var(--card);border:1px solid var(--border);border-radius:7px;"
                + "padding:9px 11px;margin-bottom:7px}"
                + ".wt{font-size:11px;color:var(--mute)}"
                + ".wq{font-size:12.5px;color:var(--fg);line-height:1.7;margin-top:2px}"
                + ".wa{font-size:11.5px;color:var(--mute);margin-top:4px}"
                + ".note{font-size:12px;color:var(--mute);margin-top:14px}"
                + "</style>";
    }

    @Override
    public void dispose() {
        if (browser != null) {
            browser.dispose();
        }
    }
}
