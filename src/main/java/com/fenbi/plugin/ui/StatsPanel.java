package com.fenbi.plugin.ui;

import com.fenbi.plugin.service.Stats;
import com.fenbi.plugin.util.Html;
import com.intellij.openapi.Disposable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 近 7 天统计：今日题量/正确率、连续天数、每日题量、分类正确率。
 */
public class StatsPanel extends JPanel implements Disposable {
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final JBCefBrowser browser;

    public StatsPanel() {
        super(new BorderLayout());
        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            browser = null;
            JBLabel label = new JBLabel("当前 IDE 未启用 JCEF，无法显示统计图表。");
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
        List<Stats.Day> days = Stats.getInstance().lastDays(7);
        Stats.Day today = days.get(days.size() - 1);
        int streak = Stats.getInstance().streak();

        int weekTotal = 0;
        for (Stats.Day day : days) {
            weekTotal += day.total;
        }

        StringBuilder body = new StringBuilder();
        body.append(style());

        if (weekTotal == 0) {
            body.append("<div class=\"st-empty\">还没有练习数据<br/>")
                    .append("做完一组题并交卷，这里就会出现近 7 天统计</div>");
            return body.toString();
        }

        int accuracy = today.total == 0 ? -1 : Math.round(today.correct * 100f / today.total);

        body.append("<div class=\"st-kpis\">")
                .append(kpi(String.valueOf(today.total), "今日题量", "b"))
                .append(kpi(accuracy < 0 ? "--" : accuracy + "%", "今日正确率", "g"))
                .append(kpi(String.valueOf(streak), "连续天数", ""))
                .append("</div>");

        body.append("<div class=\"st-title\">近 7 天题量</div>");
        body.append("<div class=\"st-chart\">");
        int max = 1;
        for (Stats.Day day : days) {
            max = Math.max(max, day.total);
        }
        for (int i = 0; i < days.size(); i++) {
            Stats.Day day = days.get(i);
            int height = day.total == 0 ? 3 : Math.max(8, Math.round(day.total * 100f / max));
            boolean isToday = i == days.size() - 1;
            body.append("<div class=\"st-col\"><div class=\"st-bar")
                    .append(isToday ? " today" : "")
                    .append("\" style=\"height:").append(height).append("%\"></div></div>");
        }
        body.append("</div><div class=\"st-axis\">");
        for (Stats.Day day : days) {
            body.append("<span>").append(label(day.date)).append("</span>");
        }
        body.append("</div>");
        body.append("<div class=\"st-sub\">近 7 天共 ").append(weekTotal).append(" 题</div>");

        Map<String, int[]> types = new LinkedHashMap<>();
        for (Stats.Day day : days) {
            if (day.types == null) continue;
            for (Map.Entry<String, Stats.TypeStat> entry : day.types.entrySet()) {
                int[] value = types.computeIfAbsent(entry.getKey(), key -> new int[2]);
                value[0] += entry.getValue().total;
                value[1] += entry.getValue().correct;
            }
        }
        if (!types.isEmpty()) {
            List<Map.Entry<String, int[]>> rows = new ArrayList<>(types.entrySet());
            rows.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());

            body.append("<div class=\"st-title\">分类正确率</div><div class=\"st-rows\">");
            int limit = Math.min(rows.size(), 8);
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, int[]> row = rows.get(i);
                int total = row.getValue()[0];
                int correct = row.getValue()[1];
                int percent = total == 0 ? 0 : Math.round(correct * 100f / total);
                String color = percent >= 75 ? "var(--ok)"
                        : percent >= 60 ? "var(--accent)" : "#d6ae58";
                body.append("<div class=\"st-row\"><span class=\"st-name\">")
                        .append(Html.escape(row.getKey()))
                        .append("</span><span class=\"st-track\"><i style=\"width:")
                        .append(percent).append("%;background:").append(color)
                        .append("\"></i></span><span class=\"st-pct\">")
                        .append(percent).append("%</span></div>");
            }
            body.append("</div>");
        }

        return body.toString();
    }

    private static String kpi(String value, String label, String colorClass) {
        return "<div class=\"st-kpi\"><div class=\"st-v " + colorClass + "\">" + value
                + "</div><div class=\"st-l\">" + label + "</div></div>";
    }

    private static String label(String date) {
        try {
            return LocalDate.parse(date).format(DAY_FMT);
        } catch (Exception e) {
            return date;
        }
    }

    private static String style() {
        return "<style>"
                + ".st-kpis{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:18px}"
                + ".st-kpi{background:var(--card);border:1px solid var(--border);border-radius:8px;padding:10px}"
                + ".st-v{font:700 20px Consolas,'JetBrains Mono',monospace;color:var(--fg)}"
                + ".st-v.b{color:var(--accent)}.st-v.g{color:var(--ok)}"
                + ".st-l{font-size:10.5px;color:var(--mute);margin-top:2px}"
                + ".st-title{font-size:11px;color:var(--mute);letter-spacing:.5px;margin:16px 0 8px}"
                + ".st-chart{display:flex;align-items:flex-end;gap:7px;height:120px;padding-top:6px;"
                + "border-bottom:1px solid var(--border)}"
                + ".st-col{flex:1;height:100%;display:flex;align-items:flex-end}"
                + ".st-bar{width:100%;border-radius:4px 4px 0 0;min-height:3px;"
                + "background:linear-gradient(180deg,var(--accent),rgba(53,116,240,.3))}"
                + ".st-bar.today{background:linear-gradient(180deg,var(--ok),rgba(87,150,92,.3))}"
                + ".st-axis{display:flex;gap:7px;margin-top:6px}"
                + ".st-axis span{flex:1;text-align:center;font-size:10px;color:var(--mute)}"
                + ".st-sub{margin-top:8px;font-size:11px;color:var(--mute)}"
                + ".st-rows{font-size:12px;color:var(--dim)}"
                + ".st-row{display:flex;align-items:center;gap:9px;margin-bottom:8px}"
                + ".st-name{width:76px;flex:0 0 76px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                + ".st-track{flex:1;height:6px;border-radius:3px;background:var(--card2);overflow:hidden}"
                + ".st-track i{display:block;height:100%;border-radius:3px}"
                + ".st-pct{width:38px;text-align:right;font:11px Consolas,monospace;color:var(--mute)}"
                + ".st-empty{display:flex;flex-direction:column;align-items:center;justify-content:center;"
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
