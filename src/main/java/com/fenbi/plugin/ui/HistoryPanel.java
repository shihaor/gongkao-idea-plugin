package com.fenbi.plugin.ui;

import com.fenbi.plugin.service.History;
import com.fenbi.plugin.service.PlanStore;
import com.fenbi.plugin.util.Html;
import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 历史做题记录：每次计划完成/中断一条，显示完成百分比、正确率和用时。
 */
public class HistoryPanel extends JPanel implements Disposable {
    private static final int MAX_SHOW = 30;

    private final JBCefBrowser browser;

    public HistoryPanel() {
        super(new BorderLayout());
        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            browser = null;
            JBLabel label = new JBLabel("当前 IDE 未启用 JCEF，无法显示历史记录。");
            label.setBorder(JBUI.Borders.empty(16));
            add(label, BorderLayout.CENTER);
        }
        refresh();
    }

    public void refresh() {
        if (browser == null) return;
        browser.loadHTML(Html.page(build()));
    }

    private String build() {
        History history = History.getInstance();
        List<History.Record> records = new ArrayList<>(history.all());
        History.Record active = history.preview(PlanStore.getInstance().unfinishedToday());

        StringBuilder body = new StringBuilder(style());
        if (active == null && records.isEmpty()) {
            body.append("<div class=\"hs-empty\">还没有做题记录<br/>")
                    .append("完成或中断一次计划后，这里会留下完成百分比和正确率</div>");
            return body.toString();
        }

        body.append("<div class=\"hs-title\">历史做题记录</div>");
        if (active != null) {
            body.append(card(active, "active"));
        }
        int count = Math.min(records.size(), MAX_SHOW);
        for (int i = 0; i < count; i++) {
            body.append(card(records.get(i), records.get(i).status));
        }
        if (records.size() > MAX_SHOW) {
            body.append("<div class=\"hs-more\">仅显示最近 ").append(MAX_SHOW).append(" 条</div>");
        }
        return body.toString();
    }

    private static String card(History.Record record, String mode) {
        int percent = record.totalQuestions == 0 ? 0
                : Math.round(record.completedQuestions * 100f / record.totalQuestions);
        int accuracy = record.completedQuestions == 0 ? -1
                : Math.round(record.correct * 100f / record.completedQuestions);

        String statusText;
        String statusClass;
        String barColor;
        if ("completed".equals(mode)) {
            statusText = "已完成";
            statusClass = "ok";
            barColor = "var(--ok)";
        } else if ("active".equals(mode) || "active".equals(record.status)) {
            statusText = "进行中";
            statusClass = "run";
            barColor = "var(--accent)";
        } else {
            statusText = "已中断";
            statusClass = "warn";
            barColor = "#d6ae58";
        }

        StringBuilder types = new StringBuilder();
        for (History.Item item : record.items) {
            if (types.length() > 0) types.append(" · ");
            types.append(Html.escape(item.type)).append(" ×").append(item.count);
        }

        StringBuilder card = new StringBuilder();
        card.append("<div class=\"hs-card\">");
        card.append("<div class=\"hs-top\"><span class=\"hs-time\">")
                .append(Html.escape(record.updatedAt == null || record.updatedAt.isBlank()
                        ? record.date : record.updatedAt))
                .append("</span><span class=\"hs-badge ").append(statusClass).append("\">")
                .append(statusText).append("</span><span class=\"hs-pct\">")
                .append(percent).append("%</span></div>");
        if (types.length() > 0) {
            card.append("<div class=\"hs-types\">").append(types).append("</div>");
        }
        card.append("<div class=\"hs-bar\"><i style=\"width:").append(percent)
                .append("%;background:").append(barColor).append("\"></i></div>");
        card.append("<div class=\"hs-meta\">完成 ").append(record.completedQuestions).append("/")
                .append(record.totalQuestions).append(" 题");
        if (accuracy >= 0) {
            card.append(" · 正确 ").append(record.correct).append("（").append(accuracy).append("%）");
        }
        if (record.elapsedSeconds > 0) {
            card.append(" · 用时 ").append(format(record.elapsedSeconds));
        }
        card.append("</div></div>");
        return card.toString();
    }

    private static String format(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String style() {
        return "<style>"
                + ".hs-title{font-size:11px;color:var(--mute);letter-spacing:.5px;margin:4px 0 10px}"
                + ".hs-card{background:var(--card);border:1px solid var(--border);border-radius:8px;"
                + "padding:10px 12px;margin-bottom:8px}"
                + ".hs-top{display:flex;align-items:center;gap:8px}"
                + ".hs-time{font-size:11.5px;color:var(--dim);font-family:Consolas,monospace}"
                + ".hs-badge{font-size:10.5px;padding:1px 7px;border-radius:10px;border:1px solid var(--border2)}"
                + ".hs-badge.ok{color:var(--ok);border-color:rgba(87,150,92,.5);background:var(--okbg)}"
                + ".hs-badge.warn{color:#d6ae58;border-color:rgba(214,174,88,.5);background:rgba(214,174,88,.12)}"
                + ".hs-badge.run{color:var(--accent);border-color:rgba(53,116,240,.5);background:var(--sel)}"
                + ".hs-pct{margin-left:auto;font:700 15px Consolas,monospace;color:var(--fg)}"
                + ".hs-types{margin-top:6px;font-size:11.5px;color:var(--dim);line-height:1.7}"
                + ".hs-bar{height:5px;border-radius:3px;background:var(--card2);overflow:hidden;margin-top:7px}"
                + ".hs-bar i{display:block;height:100%;border-radius:3px}"
                + ".hs-meta{margin-top:6px;font-size:11px;color:var(--mute)}"
                + ".hs-more{margin-top:10px;text-align:center;font-size:11px;color:var(--mute)}"
                + ".hs-empty{display:flex;flex-direction:column;align-items:center;justify-content:center;"
                + "height:60vh;color:var(--mute);font-size:13px;text-align:center;line-height:2}"
                + "</style>";
    }

    @Override
    public void dispose() {
        if (browser != null) {
            browser.dispose();
        }
    }
}
