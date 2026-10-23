package com.fenbi.plugin.util;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * 网络请求统一走后台线程，结果回 EDT。
 */
public final class Async {
    private Async() {
    }

    public static <T> void run(@NotNull String title,
                               @NotNull Callable<T> work,
                               @NotNull Consumer<T> onSuccess,
                               @NotNull Consumer<Throwable> onError) {
        Application application = ApplicationManager.getApplication();
        AppExecutorUtil.getAppExecutorService().submit(() -> {
            try {
                T value = work.call();
                application.invokeLater(() -> {
                    if (!application.isDisposed()) {
                        onSuccess.accept(value);
                    }
                });
            } catch (Throwable throwable) {
                Throwable cause = throwable instanceof java.util.concurrent.ExecutionException && throwable.getCause() != null
                        ? throwable.getCause() : throwable;
                application.invokeLater(() -> {
                    if (!application.isDisposed()) {
                        onError.accept(cause);
                    }
                });
            }
        });
    }
}
