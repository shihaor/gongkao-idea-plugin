package com.fenbi.plugin;

import com.fenbi.plugin.service.PlanService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * IDE 启动后开始计划轮询（每天 21 点后按间隔向 OpenClaw 问询）。
 */
public class PlanStartupActivity implements StartupActivity.DumbAware {
    @Override
    public void runActivity(@NotNull Project project) {
        PlanService.getInstance().start(project);
    }
}
