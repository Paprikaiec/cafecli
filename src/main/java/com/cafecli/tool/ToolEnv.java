package com.cafecli.tool;

import com.cafecli.lsp.LspManager;
import com.cafecli.policy.PathGuard;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 文件、命令与搜索叶子在每次执行时取得的工作区、策略守卫与观察者。
 * Supplier 在执行器内求值，使每次调用使用自己的项目绑定、取消令牌和输出接收者。
 */
record ToolEnv(Supplier<String> projectPath,
               Supplier<PathGuard> pathGuard,
               Supplier<BiConsumer<String, String>> commandOutputObserver,
               Supplier<BiConsumer<String, String[]>> writeFileObserver,
               Supplier<LspManager> lspManager) {
}
