package com.fenbi.plugin.ui;

import com.fenbi.plugin.model.Models.Category;
import com.fenbi.plugin.model.Models.EssayLabel;
import com.fenbi.plugin.model.Models.EssayPaper;
import com.fenbi.plugin.service.FenbiApi.LoginRequiredException;
import com.fenbi.plugin.util.Async;
import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 题库：行测分类树 + 申论试卷列表。
 */
public class BankPanel extends JPanel {
    private final CategoryTab categoryTab;
    private final EssayTab essayTab;
    private final WrongBookPanel wrongBookPanel;
    private final StatsPanel statsPanel;

    public BankPanel(FenbiPanel host) {
        super(new BorderLayout());
        JBTabbedPane tabs = new JBTabbedPane();
        categoryTab = new CategoryTab(host);
        essayTab = new EssayTab(host);
        wrongBookPanel = new WrongBookPanel(host);
        statsPanel = new StatsPanel();
        tabs.addTab("行测", categoryTab);
        tabs.addTab("申论", essayTab);
        tabs.addTab("错题本", wrongBookPanel);
        tabs.addTab("统计", statsPanel);
        add(tabs, BorderLayout.CENTER);
    }

    public void onShown() {
        categoryTab.loadIfNeeded();
        essayTab.loadIfNeeded();
        wrongBookPanel.refresh();
        statsPanel.refresh();
    }

    public void dispose() {
        wrongBookPanel.dispose();
        statsPanel.dispose();
    }

    public void reload() {
        categoryTab.reload();
        essayTab.reload();
    }

    public void reset() {
        categoryTab.reset();
        essayTab.reset();
    }

    // ------------------------------------------------------------------ 行测

    private static final class CategoryTab extends JPanel {
        private final FenbiPanel host;
        private final JBList<Category> list = new JBList<>();
        private final DefaultListModel<Category> model = new DefaultListModel<>();
        private final Deque<List<Category>> stack = new ArrayDeque<>();
        private final Deque<String> names = new ArrayDeque<>();
        private final JLabel pathLabel = new JBLabel("全部分类");
        private final JButton backButton = new JButton("返回");
        private final JLabel hintLabel = new JBLabel("单击分类开始练习；带箭头的分类可以继续下钻。");

        private List<Category> current = List.of();
        private boolean loaded;
        private boolean loading;

        CategoryTab(FenbiPanel host) {
            super(new BorderLayout());
            this.host = host;
            setBorder(JBUI.Borders.empty(6, 8, 8, 8));

            JPanel top = new JPanel(new BorderLayout(6, 0));
            top.setOpaque(false);
            top.setBorder(JBUI.Borders.emptyBottom(6));
            backButton.setVisible(false);
            backButton.addActionListener(e -> goBack());
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            left.setOpaque(false);
            left.add(backButton);
            left.add(pathLabel);
            top.add(left, BorderLayout.WEST);

            list.setModel(model);
            list.setCellRenderer(new CategoryRenderer());
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() != 1) return;
                    int index = list.locationToIndex(e.getPoint());
                    if (index < 0 || index >= model.size()) return;
                    Rectangle bounds = list.getCellBounds(index, index);
                    if (bounds == null || !bounds.contains(e.getPoint())) return;
                    Category category = model.get(index);
                    if (category.isLeaf()) {
                        host.startQuiz(category.name(), category.id());
                    } else {
                        stack.push(current);
                        names.push(category.name());
                        current = category.children();
                        showCurrent();
                    }
                }
            });

            hintLabel.setForeground(UIUtil.getContextHelpForeground());
            hintLabel.setFont(UIUtil.getLabelFont().deriveFont(11f));
            hintLabel.setBorder(JBUI.Borders.emptyTop(6));

            add(top, BorderLayout.NORTH);
            add(new JScrollPane(list), BorderLayout.CENTER);
            add(hintLabel, BorderLayout.SOUTH);
        }

        void loadIfNeeded() {
            if (!loaded && !loading && host.isLoggedIn()) {
                reload();
            }
        }

        void reload() {
            if (loading) return;
            if (!host.ensureLogin()) return;
            loading = true;
            hintLabel.setText("正在加载分类…");
            list.setEnabled(false);
            Async.run("加载分类", () -> host.api().categories(), categories -> {
                loading = false;
                loaded = true;
                list.setEnabled(true);
                stack.clear();
                names.clear();
                current = categories;
                showCurrent();
                hintLabel.setText(categories.isEmpty()
                        ? "没有取到分类，请检查授权后点右上角刷新。"
                        : "单击分类开始练习；带箭头的分类可以继续下钻。");
            }, throwable -> {
                loading = false;
                loaded = true;
                list.setEnabled(true);
                hintLabel.setText("加载失败：" + shortMessage(throwable));
                host.handleError(throwable);
            });
        }

        void reset() {
            loaded = false;
            loading = false;
            model.clear();
            stack.clear();
            names.clear();
            current = List.of();
            pathLabel.setText("全部分类");
            backButton.setVisible(false);
        }

        private void showCurrent() {
            model.clear();
            for (Category category : current) {
                model.addElement(category);
            }
            pathLabel.setText(names.isEmpty() ? "全部分类" : names.peek());
            backButton.setVisible(!stack.isEmpty());
            if (!model.isEmpty()) {
                list.setSelectedIndex(0);
                list.ensureIndexIsVisible(0);
            }
        }

        private void goBack() {
            if (stack.isEmpty()) return;
            current = stack.pop();
            if (!names.isEmpty()) names.pop();
            showCurrent();
        }
    }

    private static final class CategoryRenderer extends ColoredListCellRenderer<Category> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends Category> list, Category value,
                                             int index, boolean selected, boolean hasFocus) {
            setIcon(value.isLeaf() ? AllIcons.Nodes.Class : AllIcons.Nodes.Folder);
            append(value.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            if (value.count() > 0) {
                append("   " + value.count() + " 题", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES);
            }
            if (!value.isLeaf()) {
                append("   下钻", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES);
            }
        }
    }

    // ------------------------------------------------------------------ 申论

    private static final class EssayTab extends JPanel {
        private final FenbiPanel host;
        private final JBList<Object> list = new JBList<>();
        private final DefaultListModel<Object> model = new DefaultListModel<>();
        private final JLabel pathLabel = new JBLabel("申论试卷");
        private final JButton backButton = new JButton("返回");
        private final JLabel hintLabel = new JBLabel("先选考试类型，再选一套试卷。");

        private boolean loaded;
        private boolean loading;

        EssayTab(FenbiPanel host) {
            super(new BorderLayout());
            this.host = host;
            setBorder(JBUI.Borders.empty(6, 8, 8, 8));

            JPanel top = new JPanel(new BorderLayout(6, 0));
            top.setOpaque(false);
            top.setBorder(JBUI.Borders.emptyBottom(6));
            backButton.setVisible(false);
            backButton.addActionListener(e -> showLabels());
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            left.setOpaque(false);
            left.add(backButton);
            left.add(pathLabel);
            top.add(left, BorderLayout.WEST);

            list.setModel(model);
            list.setCellRenderer(new EssayRenderer());
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() != 1) return;
                    int index = list.locationToIndex(e.getPoint());
                    if (index < 0 || index >= model.size()) return;
                    Rectangle bounds = list.getCellBounds(index, index);
                    if (bounds == null || !bounds.contains(e.getPoint())) return;
                    Object value = model.get(index);
                    if (value instanceof EssayPaper paper) {
                        host.openEssay(paper);
                    } else if (value instanceof EssayLabel label) {
                        loadPapers(label);
                    }
                }
            });

            hintLabel.setForeground(UIUtil.getContextHelpForeground());
            hintLabel.setFont(UIUtil.getLabelFont().deriveFont(11f));
            hintLabel.setBorder(JBUI.Borders.emptyTop(6));

            add(top, BorderLayout.NORTH);
            add(new JScrollPane(list), BorderLayout.CENTER);
            add(hintLabel, BorderLayout.SOUTH);
        }

        void loadIfNeeded() {
            if (!loaded && !loading && host.isLoggedIn()) {
                reload();
            }
        }

        void reload() {
            if (loading) return;
            if (!host.ensureLogin()) return;
            loading = true;
            hintLabel.setText("正在加载申论试卷…");
            list.setEnabled(false);
            Async.run("加载申论", () -> host.api().essayLabels(), labels -> {
                loading = false;
                loaded = true;
                list.setEnabled(true);
                model.clear();
                for (EssayLabel label : labels) {
                    model.addElement(label);
                }
                pathLabel.setText("申论试卷");
                backButton.setVisible(false);
                hintLabel.setText(labels.isEmpty() ? "没有取到试卷。" : "先选考试类型，再选一套试卷。");
            }, throwable -> {
                loading = false;
                loaded = true;
                list.setEnabled(true);
                hintLabel.setText("加载失败：" + shortMessage(throwable));
                host.handleError(throwable);
            });
        }

        void reset() {
            loaded = false;
            loading = false;
            model.clear();
            pathLabel.setText("申论试卷");
            backButton.setVisible(false);
        }

        private void showLabels() {
            reload();
        }

        private void loadPapers(EssayLabel label) {
            loading = true;
            list.setEnabled(false);
            hintLabel.setText("正在加载「" + label.name() + "」的试卷…");
            Async.run("加载试卷", () -> {
                List<EssayPaper> papers = new ArrayList<>();
                int limit = Math.min(label.paperIds().size(), 12);
                for (int i = 0; i < limit; i++) {
                    try {
                        papers.add(host.api().essayPaper(label.paperIds().get(i)));
                    } catch (LoginRequiredException e) {
                        throw e;
                    } catch (Exception ignored) {
                        // 单套失败不影响其他
                    }
                    try {
                        Thread.sleep(80L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return papers;
            }, papers -> {
                loading = false;
                list.setEnabled(true);
                model.clear();
                for (EssayPaper paper : papers) {
                    model.addElement(paper);
                }
                pathLabel.setText(label.name());
                backButton.setVisible(true);
                hintLabel.setText(papers.isEmpty()
                        ? "没有取到试卷，稍后重试。"
                        : "单击试卷开始作答；参考答案在题目页里查看。");
            }, throwable -> {
                loading = false;
                list.setEnabled(true);
                hintLabel.setText("加载失败：" + shortMessage(throwable));
                host.handleError(throwable);
            });
        }
    }

    private static final class EssayRenderer extends ColoredListCellRenderer<Object> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<?> list, Object value, int index,
                                             boolean selected, boolean hasFocus) {
            if (value instanceof EssayLabel label) {
                setIcon(AllIcons.Nodes.Folder);
                append(label.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("   " + label.paperIds().size() + " 套", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES);
            } else if (value instanceof EssayPaper paper) {
                setIcon(AllIcons.Nodes.Class);
                append(paper.displayName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        }
    }

    private static String shortMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message.length() > 80 ? message.substring(0, 80) + "…" : message;
    }
}
