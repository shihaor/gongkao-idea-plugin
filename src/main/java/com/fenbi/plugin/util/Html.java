package com.fenbi.plugin.util;

import com.intellij.ui.JBColor;

/**
 * 题目区域的 HTML 渲染：统一主题、转义、图片地址修正。
 */
public final class Html {
    private Html() {
    }

    public static boolean isDark() {
        return !JBColor.isBright();
    }

    public static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeAttr(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    /**
     * 粉笔题干里常出现协议相对地址（//cdn...），JCEF 以 about:blank 为基准会解析失败，这里补成 https。
     */
    public static String fix(String html) {
        if (html == null) return "";
        return html.replace("src=\"//", "src=\"https://")
                .replace("src='//", "src='https://")
                .replace("href=\"//", "href=\"https://")
                .replace("href='//", "href='https://")
                .replace("src=\"/", "src=\"https://tiku.fenbi.com/")
                .replace("src='/", "src='https://tiku.fenbi.com/");
    }

    public static String page(String body) {
        return page(body, "");
    }

    public static String page(String body, String script) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>" + css() + "</style>"
                + (script == null || script.isBlank() ? "" : "<script>" + script + "</script>")
                + "</head><body>" + body + "</body></html>";
    }

    private static String css() {
        boolean dark = isDark();
        String vars = dark
                ? "--bg:#1e1f22;--fg:#dfe1e5;--dim:#9da0a8;--mute:#71757c;--card:#2b2d30;--card2:#393b40;"
                + "--border:#3a3d43;--border2:#4b4f57;--accent:#3574f0;--ok:#57965c;--no:#c94f4f;"
                + "--sel:rgba(53,116,240,.14);--okbg:rgba(87,150,92,.15);--nobg:rgba(201,79,79,.15);"
                : "--bg:#ffffff;--fg:#1f2328;--dim:#5d6470;--mute:#8b929c;--card:#f7f8fa;--card2:#eef1f5;"
                + "--border:#d6dae0;--border2:#c3c9d1;--accent:#2f6be0;--ok:#3e8a46;--no:#c0392b;"
                + "--sel:rgba(47,107,224,.10);--okbg:rgba(62,138,70,.12);--nobg:rgba(192,57,43,.10);";

        return ":root{" + vars + "}"
                + "*{box-sizing:border-box}"
                + "html,body{margin:0;padding:0;background:var(--bg);color:var(--fg);}"
                + "body{padding:14px 16px 22px;font:14px/1.85 'Microsoft YaHei UI','Segoe UI',system-ui,sans-serif;"
                + "-webkit-font-smoothing:antialiased;word-break:break-word;}"
                + "img{max-width:100%;height:auto;border-radius:4px;}"
                + "p{margin:0 0 8px;}"
                + ".meta{display:flex;align-items:center;gap:8px;margin-bottom:10px;flex-wrap:wrap;}"
                + ".badge{font-size:11px;line-height:1.7;padding:1px 8px;border-radius:4px;"
                + "border:1px solid var(--border2);color:var(--dim);}"
                + ".badge.blue{color:#7fa6f0;border-color:rgba(53,116,240,.5);background:var(--sel);}"
                + ".idx{margin-left:auto;font:11px Consolas,'JetBrains Mono',monospace;color:var(--mute);}"
                + ".material{background:var(--card);border:1px solid var(--border);border-left:2px solid var(--border2);"
                + "border-radius:0 7px 7px 0;padding:10px 12px;margin-bottom:13px;color:var(--dim);font-size:13px;}"
                + ".material p:last-child{margin-bottom:0;}"
                + ".stem{font-size:14px;}"
                + ".opts{display:flex;flex-direction:column;gap:8px;margin-top:14px;}"
                + ".opt{display:flex;gap:10px;align-items:flex-start;padding:9px 11px;border:1px solid var(--border);"
                + "border-radius:7px;background:var(--card);cursor:pointer;transition:background .12s,border-color .12s;}"
                + ".opt:hover{border-color:var(--border2);background:var(--card2);}"
                + ".opt .k{width:20px;height:20px;flex:0 0 20px;border-radius:50%;border:1px solid var(--border2);"
                + "display:flex;align-items:center;justify-content:center;font-size:11px;color:var(--dim);margin-top:2px;}"
                + ".opt .t{flex:1;}"
                + ".opt.sel{border-color:var(--accent);background:var(--sel);}"
                + ".opt.sel .k{background:var(--accent);border-color:var(--accent);color:#fff;}"
                + ".opt.correct{border-color:var(--ok);background:var(--okbg);}"
                + ".opt.correct .k{background:var(--ok);border-color:var(--ok);color:#fff;}"
                + ".opt.wrong{border-color:var(--no);background:var(--nobg);}"
                + ".opt.wrong .k{background:var(--no);border-color:var(--no);color:#fff;}"
                + ".opt.muted{opacity:.55;}"
                + ".tip{margin-top:14px;font-size:12px;color:var(--mute);}"
                + ".hdr{display:flex;align-items:baseline;gap:10px;margin:2px 0 14px;}"
                + ".score{font:700 30px Consolas,'JetBrains Mono',monospace;color:var(--accent);letter-spacing:-1px;}"
                + ".score small{font-size:13px;color:var(--mute);font-weight:400;}"
                + ".hdr .sub{font-size:12px;color:var(--dim);}"
                + ".q{border-bottom:1px solid var(--border);padding-bottom:20px;margin-bottom:20px;}"
                + ".q:last-child{border-bottom:0;margin-bottom:0;padding-bottom:4px;}"
                + ".sol{margin-top:12px;border:1px solid var(--border);border-radius:8px;background:var(--card);padding:10px 12px;}"
                + ".sol .h{font-size:12px;color:var(--mute);margin-bottom:5px;letter-spacing:.3px;}"
                + ".sol .b{font-size:13px;color:var(--dim);}"
                + ".sol .b p:last-child{margin-bottom:0;}"
                + ".ans{font:12px Consolas,'JetBrains Mono',monospace;color:var(--dim);margin-top:8px;}"
                + ".ans b{color:var(--fg);}"
                + ".ok{color:var(--ok);} .no{color:var(--no);}"
                + ".empty{display:flex;height:70vh;align-items:center;justify-content:center;color:var(--mute);font-size:13px;}"
                + ".ref{margin-top:16px;border:1px solid rgba(87,150,92,.45);background:var(--okbg);border-radius:8px;padding:12px;}"
                + ".ref .h{font-size:12px;color:var(--ok);margin-bottom:8px;font-weight:600;}"
                + ".ref ol{margin:0;padding-left:18px;}"
                + ".ref li{margin-bottom:8px;font-size:13px;color:var(--dim);}"
                + ".ref li b{color:var(--fg);}"
                + ".hintbar{position:sticky;bottom:0;margin-top:16px;padding:8px 0 0;font-size:12px;color:var(--mute);}"
                + "::-webkit-scrollbar{width:10px;height:10px;}"
                + "::-webkit-scrollbar-thumb{background:" + (dark ? "#4a4d52" : "#c6ccd6")
                + ";border-radius:5px;border:2px solid transparent;background-clip:padding-box;}"
                + "::-webkit-scrollbar-track{background:transparent;}";
    }

    public static String letter(int index) {
        return String.valueOf((char) ('A' + Math.min(25, Math.max(0, index))));
    }

    /**
     * 选项行。state: "" / sel / correct / wrong / muted（可组合，用空格分隔）。
     */
    public static String option(int index, String text, String state, String onClickJs) {
        String classes = "opt" + (state == null || state.isBlank() ? "" : " " + state);
        String click = onClickJs == null || onClickJs.isBlank()
                ? "" : " onclick=\"" + escapeAttr(onClickJs) + "\"";
        return "<div class=\"" + classes + "\"" + click + ">"
                + "<div class=\"k\">" + letter(index) + "</div>"
                + "<div class=\"t\">" + escape(text) + "</div>"
                + "</div>";
    }

    public static String material(String content) {
        return "<div class=\"material\">" + fix(content) + "</div>";
    }

    public static String badge(String text, String extraClass) {
        return "<span class=\"badge " + (extraClass == null ? "" : extraClass) + "\">" + escape(text) + "</span>";
    }
}
