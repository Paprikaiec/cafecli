package com.cafecli.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可 grep 的验收：**渲染测试的判据不能取决于「谁在跑测试」**。
 *
 * <p>这两条防的都是同一类缺陷——测试读到了 JVM 全局或宿主环境，于是「哪个测试类先加载」或
 * 「开发者用哪个终端启动 Maven」决定了断言的真假。它们**不会让任何行为断言变红**，只会让套件在
 * 别人的机器上、或换个执行顺序之后莫名其妙地红一条，所以只能靠盯着源文件本身来防。
 *
 * <p>两条都实测过（2026-09-03）：
 * <ul>
 *   <li>{@code TERM_PROGRAM=ghostty} 时 {@code InlineRendererTest} 5 红、unset 时全绿——
 *       {@code TerminalCapabilities} 的 synchronized-output / osc8 探测在终端类型不认识时会回退去读
 *       进程的 {@code TERM_PROGRAM}，绕过测试里 mock 出来的 {@code Terminal}。</li>
 *   <li>{@code TerminalMarkdownRendererTest} 曾在静态初始化里设 {@code paicli.render.color=false}。
 *       {@code AnsiStyle.ENABLED} 是 {@code static final}、每个 JVM 只算一次，于是它单独跑时赢下
 *       加载竞争（绿），在全量套件里输（红）。反向也污染：它从不还原那个全局，先手时会把
 *       {@code AnsiStyleTest} 里两条 {@code assumeTrue(isEnabled())} 变成静默 skip。</li>
 * </ul>
 */
class RenderTestIsolationGuardTest {

    private static final Path TEST_SOURCE_ROOT = Path.of("src", "test", "java");
    private static final Path POM = Path.of("pom.xml");

    /**
     * 没有任何测试写 {@code paicli.render.color} 这个 JVM 全局。
     *
     * <p>色彩档位按环境决定即可——测试要的是「不依赖它」，不是「把它按死」。需要断言纯文本的地方
     * 剥掉 ANSI 样式（{@code AttributedString.fromAnsi(...)}），需要断言宽度的地方按显示列数算。
     *
     * <p>**不要**改成在 pom 里钉死 {@code paicli.render.color=false}：实测那会让
     * {@code ScreenTruthRegressionTest} 两条依赖彩色输出的断言变红，并让 {@code AnsiStyleTest}
     * 两条 skip 掉。
     */
    @Test
    void noTestSeizesTheGlobalColorSwitch() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(TEST_SOURCE_ROOT)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                if (text.contains("setProperty(\"paicli.render.color\"")) {
                    offenders.add(source.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these tests write the JVM-global colour switch, which makes assertions depend on "
                        + "class-load order in both directions: " + offenders);
    }

    /**
     * surefire 的每一份配置都钉死了那两项会嗅探 {@code TERM_PROGRAM} 的终端能力。
     *
     * <p>钉死之后 mock 出来的 {@code Terminal} 才是测试里唯一的能力来源，本地与 CI 才等价。
     * 帧被 DEC 2026 序列包裹这件事本身没有丢覆盖——{@code RenderLoopTest
     * .synchronizedOutputWrapsOneCompleteFrame} 直接传构造参数开着它断言。
     */
    @Test
    void surefirePinsTheCapabilityProbesThatSniffTheHostTerminal() throws IOException {
        String pom = Files.readString(POM, StandardCharsets.UTF_8);
        for (String flag : List.of("-Dpaicli.render.synchronized-output=false",
                "-Dpaicli.render.osc8=false")) {
            assertTrue(pom.contains(flag),
                    "pom.xml must pin " + flag + " for tests: otherwise frame-byte assertions depend "
                            + "on which terminal emulator the developer launched Maven from");
        }
        assertFalse(pom.contains("<argLine>-Dfile.encoding=UTF-8</argLine>"),
                "every surefire <argLine> must go through ${test.argLine}, or the profile it belongs "
                        + "to silently drops the pin");
    }
}
