package com.fenbi.plugin.ui;

import com.fenbi.plugin.service.FenbiApi;
import com.fenbi.plugin.service.FenbiSession;
import com.fenbi.plugin.util.Async;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefCookie;
import com.intellij.ui.jcef.JBCefCookieManager;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 内嵌浏览器授权：用户在页面里用手机号 + 验证码（或账号密码）登录，
 * 插件从 CEF Cookie 仓库读取授权信息（含 HttpOnly），校验通过后写入 PasswordSafe。
 */
public class LoginDialog extends DialogWrapper {
    private static final String TIKU_URL = "https://www.fenbi.com/spa/tiku/xingce/categories";

    private final Project project;
    private final JBCefBrowser browser;
    private final JBLabel statusLabel = new JBLabel("等待登录…（手机号 + 验证码 / 账号密码）");
    private final JBTextArea manualArea = new JBTextArea(2, 36);
    private final Timer poller;

    private volatile boolean checking;
    private volatile boolean finished;
    private String lastChecked = "";

    public LoginDialog(@Nullable Project project) {
        super(project, true);
        this.project = project;
        setTitle("授权题库");

        if (JBCefApp.isSupported()) {
            browser = new JBCefBrowser();
            browser.loadURL(TIKU_URL);
        } else {
            browser = null;
        }

        poller = new Timer(1600, e -> checkCookies());
        poller.setInitialDelay(2200);

        init();
        setSize(900, 700);
        setResizable(true);
        poller.start();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 6));

        JBLabel hint = new JBLabel("<html>在下面的页面里点「登录」，用<b>手机号 + 验证码</b>（或账号密码）登录。<br/>"
                + "如果没看到登录框，点页面右上角的「登录」；登录成功后会自动完成授权，不需要扫码。</html>");
        hint.setBorder(JBUI.Borders.empty(6, 8, 2, 8));
        root.add(hint, BorderLayout.NORTH);

        if (browser != null) {
            JComponent component = browser.getComponent();
            component.setPreferredSize(new Dimension(880, 520));
            root.add(component, BorderLayout.CENTER);
        } else {
            JLabel unsupported = new JLabel("<html>当前 IDE 未启用 JCEF，无法显示扫码页面。<br/>"
                    + "请在下方手动粘贴浏览器里的 Cookie。</html>");
            unsupported.setBorder(JBUI.Borders.empty(16));
            root.add(unsupported, BorderLayout.CENTER);
        }

        JPanel south = new JPanel(new BorderLayout(0, 4));
        statusLabel.setForeground(UIUtil.getContextHelpForeground());
        statusLabel.setBorder(JBUI.Borders.empty(2, 2, 4, 2));
        south.add(statusLabel, BorderLayout.NORTH);

        JPanel manual = new JPanel(new BorderLayout(6, 4));
        manual.setBorder(BorderFactory.createTitledBorder("或手动粘贴 Cookie（备用）"));
        manualArea.setLineWrap(true);
        manualArea.setWrapStyleWord(false);
        manualArea.setBorder(JBUI.Borders.empty(4));
        manual.add(new JBScrollPane(manualArea), BorderLayout.CENTER);

        JButton useManual = new JButton("使用该 Cookie 授权");
        useManual.addActionListener(e -> useManualCookie());
        manual.add(useManual, BorderLayout.EAST);
        south.add(manual, BorderLayout.CENTER);

        root.add(south, BorderLayout.SOUTH);
        return root;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getCancelAction()};
    }

    // ------------------------------------------------------------------ 扫码轮询

    private void checkCookies() {
        if (checking || finished || browser == null || browser.isDisposed()) return;
        checking = true;
        Async.run("检查授权", () -> {
            String header = readCookieHeader();
            if (header.isBlank() || header.equals(lastChecked)) {
                return "";
            }
            lastChecked = header;
            try {
                new FenbiApi(() -> header).checkLogin();
                return header;
            } catch (Exception e) {
                return "";
            }
        }, header -> {
            checking = false;
            if (!finished && header != null && !header.isBlank()) {
                finish(header);
            }
        }, throwable -> {
            checking = false;
            if (!finished) {
                statusLabel.setText("等待登录…（" + shortMessage(throwable) + "）");
            }
        });
    }

    private String readCookieHeader() {
        JBCefBrowser local = browser;
        if (local == null || local.isDisposed()) return "";
        try {
            JBCefCookieManager manager = local.getJBCefCookieManager();
            if (manager == null) {
                manager = JBCefBrowserBase.getGlobalJBCefCookieManager();
            }
            if (manager == null) return "";

            List<JBCefCookie> cookies = manager.getCookies("https://tiku.fenbi.com", true)
                    .get(4, TimeUnit.SECONDS);
            String header = join(cookies);
            if (header.isBlank()) {
                header = join(manager.getCookies());
            }
            return header;
        } catch (Exception e) {
            return "";
        }
    }

    private static String join(List<JBCefCookie> cookies) {
        if (cookies == null) return "";
        StringBuilder builder = new StringBuilder();
        for (JBCefCookie cookie : cookies) {
            String domain = cookie.getDomain();
            if (domain == null || !domain.contains("fenbi.com")) continue;
            String name = cookie.getName();
            String value = cookie.getValue();
            if (name == null || name.isBlank() || value == null) continue;
            if (builder.length() > 0) builder.append("; ");
            builder.append(name).append('=').append(value);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ 手动 Cookie

    private void useManualCookie() {
        String raw = manualArea.getText() == null ? "" : manualArea.getText().trim();
        raw = raw.replaceFirst("(?i)^cookie\\s*:\\s*", "").replaceAll("\\r?\\n+", "; ").trim();
        if (raw.isBlank()) {
            Messages.showWarningDialog(project, "请先粘贴浏览器里的 Cookie。", "授权题库");
            return;
        }
        final String header = raw;
        statusLabel.setText("正在校验 Cookie…");
        Async.run("校验 Cookie", () -> {
            new FenbiApi(() -> header).checkLogin();
            return header;
        }, this::finish, throwable -> statusLabel.setText("Cookie 无效：" + shortMessage(throwable)));
    }

    // ------------------------------------------------------------------ 完成

    private void finish(String header) {
        if (finished) return;
        finished = true;
        poller.stop();
        FenbiSession.getInstance().save(header);
        statusLabel.setText("授权成功");
        SwingUtilities.invokeLater(this::doOKAction);
    }

    private static String shortMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 60 ? message.substring(0, 60) + "…" : message;
    }

    @Override
    protected void dispose() {
        poller.stop();
        if (browser != null) {
            browser.dispose();
        }
        super.dispose();
    }
}
