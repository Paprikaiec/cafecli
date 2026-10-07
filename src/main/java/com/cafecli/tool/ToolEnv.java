package com.cafecli.tool;

import com.cafecli.lsp.LspManager;
import com.cafecli.policy.PathGuard;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 从 {@link ToolRegistry} 拆出去的工具组（{@link CommandTool} / {@link SearchTools} / {@link FileTools} …）读取 registry 可变状态的共享入口。
 *
 * <p><b>为什么每一项都是 {@link Supplier} 而不是值</b>：工具在 registry 构造期就注册好了，但它们依赖的状态在注册之后
 * 还会被换掉——
 * <ul>
 *   <li>子任务 registry（{@code ToolRegistry(ToolRegistry parent)}）先委派常规构造把所有工具注册完，
 *       <b>之后</b>才把 {@code projectPath} / {@code pathGuard} 改写成父的工作区，并把 {@code lspManager}
 *       换成父 LSP 的 {@code forkSharingProcess()}；</li>
 *   <li>{@code setProjectPath} 随时整体替换 {@code projectPath} 与 {@code pathGuard}；</li>
 *   <li>{@code setCommandOutputObserver} / {@code setWriteFileObserver} / {@code setLspManager}
 *       随时替换命令输出观察者 / 文件写入观察者 / LSP 管理器。</li>
 * </ul>
 * 若在构造 / 注册时就把值抓下来，子任务的文件访问会被围在 JVM 工作目录（{@code user.dir}）而不是项目根上——
 * 这是安全缺陷，不是风格问题。所以<b>只能</b>在执行器 lambda（或它调用的方法）里 {@code .get()}，
 * 每次工具调用现取；构造期、注册期一律不许 {@code .get()}。
 *
 * <p>后续拆分会继续往这里加 supplier：新状态照同一规则追加一个分量即可。
 * 只有一个工具组用到的状态不进这里，而是以 supplier 形式传给那个组自己的构造器（如 {@link WebTools} / {@link SessionTools}），
 * 同样只许在调用时 {@code .get()}。
 */
record ToolEnv(Supplier<String> projectPath,
               Supplier<PathGuard> pathGuard,
               Supplier<BiConsumer<String, String>> commandOutputObserver,
               Supplier<BiConsumer<String, String[]>> writeFileObserver,
               Supplier<LspManager> lspManager) {
}
