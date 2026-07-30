package com.cafecli.hook;

import java.nio.file.Path;
import java.util.List;

/**
 * 项目 Hook 信任确认器：把未信任的项目级 {@code hooks.json} 摆到用户面前（展示实际命令），收集一次批准/拒绝。
 *
 * 交互态由 CLI 在主线程接线（读终端只在主线程，根治与 LineReader 抢终端）；
 * headless 传 {@code null}——从不弹窗，未钉住的项目 hook 静默跳过。
 */
@FunctionalInterface
public interface HookTrustConfirmer {

    /** @return true=信任并激活（会钉住指纹）；false=拒绝（本会话跳过项目 hook）。 */
    boolean confirm(Path projectDir, List<String> commands);
}
