package com.cafecli.cli;

import com.cafecli.agent.AgentSession;
import com.cafecli.hitl.ApprovalPolicy;
import com.cafecli.hitl.ApprovalPump;
import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.render.Renderer;
import com.cafecli.render.inline.InlineRenderer;

import com.cafecli.util.AnsiStyle;

import com.cafecli.render.inline.CafeCliLineReader;

import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.MaskingCallback;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Locale;

/**
 * 交互 REPL：读输入的循环、键绑定、提交路由。
 *
 * <p>从 CLI 入口抽出的目的只有一个——**可被驱动**。它不构造终端、不构造渲染器、不构造事件循环，
 * 只接受它们；因此可以在没有真实 TTY 的环境里用建在管道流上的终端驱动，把「输出期键入是否被吞」
 * 这类 regime 行为断言在单元里，而不是靠人肉在真终端上试。装配仍然留在 {@link Main}。
 *
 * <p><b>行编辑器常驻读取</b>（issue 07，ADR 0014）：提交之后立刻回到 {@code readLine}，回合在事件
 * 循环的执行器线程上跑。主线程因此退化为**纯输入泵**，终端按键只有一个消费者——回合期的裸 raw-mode
 * 轮询器（它只认三个键、其余字节 drain 丢弃）连同它的转义序列分类器一并退役，ESC 的「等一等看后面
 * 还有没有」改由 JLine 自己的 {@code ambiguous-binding} 承担（≈100ms，经构造器 {@code variables} 传入）。
 *
 * <p>斜杠命令的**执行**不在这里：REPL 只负责路由（普通提交 / 斜杠命令 / [[审批应答]]），命令体交给
 * {@link SlashCommandRouter}。这条边界让路由断言不必站起一个 Agent / MCP / Skill 注册表。
 */
public final class InteractiveRepl {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InteractiveRepl.class);

    /** 只用来校验 {@code m} 应答里那段替换参数是不是合法 JSON。 */
    private static final ObjectMapper APPROVAL_JSON = new ObjectMapper();

    /** 斜杠命令的执行出口。REPL 只看结果决定继续读还是退出。 */
    @FunctionalInterface
    public interface SlashCommandRouter {

        enum Outcome {
            /** 命令已处理，回到读输入。 */
            HANDLED,
            /** 用户要求退出会话。 */
            EXIT
        }

        Outcome route(CliCommandParser.ParsedCommand command, String rawInput);
    }

    /**
     * 普通提交在进入事件循环之前要走的准备：展开 MCP resource / 本地 {@code @path} mention，
     * 再过 UserPromptSubmit hook。放在 REPL 之外，是为了让路由断言不必依赖 Agent 与 MCP。
     */
    @FunctionalInterface
    public interface PromptPreparation {

        Result prepare(String rawInput);

        /**
         * @param blocked 被 UserPromptSubmit hook 拦下
         * @param reason  拦截原因（给用户，不是给模型）
         * @param prompt  实际交给 Agent 的 prompt（展开 + hook additionalContext 之后）
         */
        record Result(boolean blocked, String reason, String prompt) {

            public static Result accepted(String prompt) {
                return new Result(false, null, prompt);
            }

            public static Result blocked(String reason) {
                return new Result(true, reason, null);
            }
        }
    }

    private final Terminal terminal;
    private final LineReader lineReader;
    private final Renderer renderer;
    private final AgentSession session;
    private final ApprovalPump approvalPump;
    /**
     * [[取消键]]那条一句话规则的唯一实现（**有回合在跑就停回合**）。三个入口——ESC widget、
     * {@code readLine} 抛出的 {@code UserInterruptException}、模态自持读取时认下的中断——共用它，
     * 所以取消范围不可能分头漂移。
     */
    private final SessionInterruptGuard interrupts;
    private final SlashCommandRouter router;
    private final PromptPreparation promptPreparation;
    /**
     * 屏幕上此刻立着一张审批卡。
     *
     * <p>它不是 {@code approvalPump.hasPending()} 的镜像，而是**卡片的生命周期**：入队通知按 ADR 0010
     * 修订 A14 发生在 {@code queue.add} <b>之前</b>（否则通知可能讲一条已被取走的审批），所以在被通知
     * 的那一刻队列还是空的。右提示符若直接读队列，就会在最需要它的那一刻答错。
     */
    private volatile boolean approvalShown;
    private ApprovalRequest approvalViewSource;
    private com.cafecli.render.ApprovalRequest approvalView;

    private synchronized com.cafecli.render.ApprovalRequest approvalView(ApprovalRequest request) {
        if (approvalViewSource != request) {
            approvalViewSource = request;
            approvalView = request.renderView();
        }
        return approvalView;
    }
    /**
     * 下一次读取要预填的那一行——[[取消键]]表里「取消保留已键入文本」那一格的全部状态。
     *
     * <p>只被输入线程读写（它就是那个循环），所以不需要同步。
     */
    private String pendingInput;
    /**
     * 上一次「空闲且框为空」的 Ctrl+C 落在什么时候（{@code System.nanoTime()}，0 表示没武装）。
     *
     * <p>窗口一过就重新计数——几分钟前按过的那一下不该让眼前这一下把会话退掉。任何**别的**结果的
     * Ctrl+C（停了一个回合、或清掉了一行草稿）也把它清零：那两下不是在说「我要退出」。
     */
    private long exitArmedAtNanos;

    /** 空闲且框为空时连按两次的确认窗口。 */
    private static final long EXIT_CONFIRM_WINDOW_NANOS = 2_000_000_000L;

    /** 上一次读到文件尾是什么时候（见 {@link #inputStreamHasEnded()}）。 */
    private long lastEofNanos;

    /**
     * 「两次文件尾之间的间隔短到不可能是人按出来的」的界。人的连击最快也在几十毫秒量级（键盘自动
     * 重复更慢），而一条已经结束的输入流是以循环本身的速度交出文件尾的。
     */
    private static final long DEAD_STREAM_GAP_NANOS = 5_000_000L;

    public InteractiveRepl(Terminal terminal,
                           LineReader lineReader,
                           Renderer renderer,
                           AgentSession session,
                           ApprovalPump approvalPump,
                           SessionInterruptGuard interrupts,
                           SlashCommandRouter router,
                           PromptPreparation promptPreparation) {
        this.terminal = terminal;
        this.lineReader = lineReader;
        this.renderer = renderer;
        this.session = session;
        this.approvalPump = approvalPump;
        this.interrupts = interrupts;
        this.router = router;
        this.promptPreparation = promptPreparation;
        bindCancelKey();
        bindNewlineKey();
        if (lineReader instanceof CafeCliLineReader capture) {
            capture.setRightPromptSupplier(this::currentRightPrompt);
        }
        if (session != null) {
            // 回合开始 / 结束都发生在执行器线程上，而此刻输入线程正坐在 readLine 里。没有这一下，
            // 右提示符要等到用户下一次按键才跟上——回合已经结束了，框里还写着「esc 停止」。
            session.setActivityListener(this::refreshInputBox);
        }
    }

    /**
     * 右提示符是这条通路的**可发现性**：空闲 {@code message / @path / @image} → 回合中
     * {@code steer · esc 停止} → 审批待决 {@code y / n / a}。
     *
     * <p>纯读、无副作用：它由行编辑器在每次重排[[输入框]]时调用（输入线程），也由 readLine 的调用点
     * 求值一次。有[[审批应答]]待决时它压过回合状态——待决审批挡着回合，先答它。
     */
    private String currentRightPrompt() {
        if (approvalShown || (approvalPump != null && approvalPump.hasPending())) {
            return "y / n / a";
        }
        if (session != null && !session.isIdle()) {
            return "steer · esc 停止";
        }
        return renderer.inputRightPrompt();
    }

    /**
     * ESC 成为常驻读取器上的一个普通 widget（issue 07）。
     *
     * <p>它接替的是被删掉的裸轮询器：那个轮询器自己读到字节 27、取一次 80/20/120ms 突发、再判断这是
     * 孤立 ESC 还是方向键的前缀。JLine 的 {@code ambiguous-binding} 就是同一个「等一等看后面还有没有」
     * 的算法，所以分类器照删、窗口对齐（≈100ms，在建构点经 {@code variables} 传入）。
     *
     * <p><b>ESC 单一职责</b>（issue 08 的[[取消键]]表）：**只做取消**。有回合在跑就停回合，否则
     * **什么都不做**——它此前兼任的「清空输入」交给行编辑器内建的整行删除。理由是 ESC 的含义从此
     * 不依赖状态：用户不必先判断现在是什么模式才知道按下去会发生什么。
     */
    private void bindCancelKey() {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("cafecli-cancel", () -> {
            if (turnIsRunning()) {
                cancelRunningTurn();
                lineReader.callWidget(LineReader.REDISPLAY);
            }
            return true;
        });
        Reference cancel = new Reference("cafecli-cancel");
        String esc = KeyMap.esc();
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(cancel, esc);
            }
        }
    }

    /**
     * 手动换行：Shift+Enter / Option+Enter 在[[输入框]]里插一个 {@code '\n'} 而不是提交。
     *
     * <p><b>为什么要显式绑</b>：Enter 是 {@code accept-line}，而「换行」在终端上<b>没有一个键</b>——
     * 它是各终端各自编码的组合键，JLine 默认一个都不认，于是那串转义序列落到 {@code self-insert}，
     * 直接把字符打进缓冲区（真机手工验收报的就是这个）。所以这里把已知的几种编码全部收下：
     * <ul>
     *   <li>{@code ESC CR} / {@code ESC LF}——Option/Alt+Enter（Terminal.app 开「Option as Meta」、
     *       iTerm2 的 {@code Esc+}），这是 macOS 上最可靠的一种；</li>
     *   <li>{@code CSI 13 ; 2 u} / {@code CSI 13 ; 5 u}——fixterms/kitty 键盘协议的 Shift+Enter 与
     *       Ctrl+Enter（ghostty / kitty / wezterm / foot）；</li>
     *   <li>{@code CSI 27 ; 2 ; 13 ~} / {@code CSI 27 ; 5 ; 13 ~}——xterm {@code modifyOtherKeys=1}
     *       的同两个键。</li>
     * </ul>
     *
     * <p><b>剩下的那一档不是我们能修的</b>：Terminal.app 默认给 Shift+Enter 发的就是裸 {@code CR}，
     * 与 Enter <b>逐字节相同</b>，进程里无法区分。那一档要么在终端里把 Shift+Enter 改绑成
     * {@code \033\r}，要么用 Option+Enter。
     *
     * <p>与 ESC 取消不冲突：{@code ESC} 自己已经是一个前缀（方向键），JLine 的 {@code ambiguous-binding}
     * （100ms，经构造器 {@code variables} 传入）本来就在做这个判定，多一条 {@code ESC CR} 不改变它。
     */
    private void bindNewlineKey() {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("cafecli-newline", () -> {
            lineReader.getBuffer().write('\n');
            return true;
        });
        // 续行不再挂 JLine 默认的 "%M> "：框里每行都顶一个 `>` 会读成好几次提交，而这一段是同一次。
        // 两个空格与首行的 `> ` 对齐，和[[提交块]]续行缩进两列是同一条规矩。这个变量在每次重排时才读
        // （LineReaderImpl:4592），所以 setVariable 有效——与 INPUT_RC_FILE_NAME / AMBIGUOUS_BINDING
        // 那条「只能走构造器」的约束不同。
        lineReader.setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "  ");
        Reference newline = new Reference("cafecli-newline");
        String esc = KeyMap.esc();
        String[] sequences = {
            esc + "\r", esc + "\n",
            "\033[13;2u", "\033[13;5u",
            "\033[27;2;13~", "\033[27;5;13~",
        };
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                for (String sequence : sequences) {
                    map.bind(newline, sequence);
                }
            }
        }
    }

    private boolean turnIsRunning() {
        return interrupts != null && interrupts.turnIsRunning();
    }

    /**
     * 输入流本身已经结束了——不是有人按了 Ctrl+D。
     *
     * <p>这是一条**活性**守卫，不是语义的一部分：两者在 {@code readLine} 那头是同一个
     * {@code EndOfFileException}，而回合运行中的 Ctrl+D 要「无操作」就得回去继续读——如果流真的
     * 断了，那就是一个满速空转的循环。区分它们的是**节奏**：断掉的流以循环自己的速度连续交出
     * 文件尾，人按不出这个间隔。守卫因此只在几毫秒内连着来两次时才认定流断了。
     */
    private boolean inputStreamHasEnded() {
        long now = System.nanoTime();
        long previous = lastEofNanos;
        lastEofNanos = now;
        return previous != 0L && now - previous < DEAD_STREAM_GAP_NANOS;
    }

    private boolean exitIsArmed() {
        return exitArmedAtNanos != 0L
                && System.nanoTime() - exitArmedAtNanos < EXIT_CONFIRM_WINDOW_NANOS;
    }

    /**
     * 停回合，然后把审批卡的生命周期收口。
     *
     * <p>取消本身在 {@link SessionInterruptGuard} 里，与信号通路是同一份实现；这里只多做一件属于
     * 输入线程的事——被拒掉的审批不再待决了，屏幕上那张卡要撤掉。
     */
    private void cancelRunningTurn() {
        if (interrupts != null) {
            interrupts.interrupt();
        }
        syncApprovalCard();
    }

    /** 读输入直到 Ctrl+D / {@code /exit}；两条退出路径都道别并关闭渲染器。 */
    public void run() {
        while (true) {
            Main.refreshTerminalColumns(terminal);
            syncApprovalCard();
            String submitted;
            try {
                submitted = readPromptInput();
            } catch (UserInterruptException e) {
                // Ctrl+C：行编辑器常驻即 JLine 的 INT handler 常驻，中断在这里以异常浮上来而不是
                // 落到 JVM 默认处理器上（那会连会话一起毁掉）。
                if (turnIsRunning()) {
                    cancelRunningTurn();
                    // **框内文字原样保留**（[[取消键]]表）：中断把这一行从 readLine 里带出来，
                    // 我们把它 seed 回下一次读取。「先停下，然后按我说的做」必须是一个连续动作。
                    pendingInput = e.getPartialLine();
                    exitArmedAtNanos = 0L;
                } else if (e.getPartialLine() != null && !e.getPartialLine().isEmpty()) {
                    // 没有回合可停，那一行就是它能停的东西：框被清空（这一行作废，不 seed 回去）。
                    exitArmedAtNanos = 0L;
                } else if (exitIsArmed()) {
                    // 空闲、框也空：**连按两次退出**。装上会话级中断之后 Ctrl+C 不再杀进程
                    // （ADR 0016 D3 明码标价的代价），这一格是它换回来的那条不靠信号的退出路。
                    sayGoodbye();
                    return;
                } else {
                    exitArmedAtNanos = System.nanoTime();
                    renderer.stream().println(AnsiStyle.subtle("(再按一次 Ctrl+C 退出)"));
                }
                continue;
            } catch (EndOfFileException e) {
                // Ctrl+D 只在**没有东西可停**时退出（[[取消键]]表）：它就在 Ctrl+C 旁边，而按错的
                // 代价是整个会话。框里有字时这个异常根本不会来——JLine 只在 buffer 为空时抛它，
                // 非空时走内建的 delete-char-or-list。
                if (!turnIsRunning() || inputStreamHasEnded()) {
                    sayGoodbye();
                    return;
                }
                continue;
            }
            lastEofNanos = 0L;

            String input = submitted == null ? "" : submitted.trim();

            // [[审批应答]]走同一条提交通路（issue 07）：有审批待决时这一次提交属于审批，不属于事件
            // 循环。顺序有意义——先看审批再判空，因为**空提交在审批待决时是「再问一次」**，不是空行。
            if (approvalPump.hasPending()) {
                answerPendingApproval(input);
                continue;
            }

            if (input.isEmpty()) {
                continue;
            }

            CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
            // 转写浏览层是显示动作，不是一个回合：它必须在回写转写块之前被截住。
            if (Main.handleTranscriptCommand(command, renderer)) {
                continue;
            }
            if (command.type() != CliCommandParser.CommandType.NONE) {
                renderer.beginTurn();
                Main.printSubmittedInput(renderer, renderer.stream(), input);
                if (router.route(command, input) == SlashCommandRouter.Outcome.EXIT) {
                    sayGoodbye();
                    return;
                }
                continue;
            }

            // 转写块用的是**展开之前**的原文：MCP resource 与本地 @path 只在这条可见的源提示留存之后
            // 才展开，用户看到的仍是自己敲的那一行。
            PromptPreparation.Result prepared = promptPreparation.prepare(input);
            if (prepared.blocked()) {
                renderer.beginTurn();
                Main.printSubmittedInput(renderer, renderer.stream(), input);
                renderer.stream().println("⛔ 提交被 hook 拦截：" + prepared.reason());
                renderer.stream().println();
                continue;
            }
            // [[转向消息]]（issue 10）：回合已经在跑时，这次提交注入的是**正在跑的那个回合**，而不是
            // 排在它后面。这正是常驻[[输入框]]的价值——用户能在模型跑偏时纠正它。
            //
            // 「有没有回合能接这条消息」与「把它放进去」必须是同一个原子动作，所以这里不读
            // isIdle()：回合可能恰好在两次调用之间结束，消息会落进一个再也没人 drain 的槽里。
            // {@code steer} 在槽自己的锁下一并回答两者，返回 false 就照常走普通提交。
            if (session != null && session.steer(prepared.prompt())) {
                // 提交瞬间就回显，否则按下回车后可能十几秒看不到任何反应。回显的是**展开之前**的
                // 原文，与普通提交同一条规则。转向消息不开启新回合：它属于正在跑的那一个。
                Main.printSteeringInput(renderer, renderer.stream(), input);
                continue;
            }

            if (!(renderer instanceof InlineRenderer)) {
                renderer.stream().println();
            }
            renderer.beginTurn();
            Main.printSubmittedInput(renderer, renderer.stream(), input);
            // ReAct 模式：经事件循环执行器跑（单写入者）。投递之后**立刻回到 readLine**——回合在
            // 执行器线程上跑，主线程退化为纯输入泵，[[输入框]]因此在回合运行期间也活着。
            session.postEvent(new AgentSession.UserSubmit(prepared.prompt()));
        }
    }

    private void sayGoodbye() {
        renderer.stream().println("\n👋 再见!");
        renderer.close();
    }

    /**
     * 一次读取，可能带着上一次被取消时留下的那一行。
     *
     * <p>{@code buffer} 参数是 JLine 自己的「预填输入行」入口（{@code readLine} 起始时写进 buffer、
     * 光标落在末尾），所以保留已键入文本不需要我们自己去动行编辑器的状态。取出即清空：它属于紧接着
     * 的这一次读取，不属于之后每一次。
     */
    private String readPromptInput() throws UserInterruptException, EndOfFileException {
        String seed = pendingInput;
        pendingInput = null;
        renderer.beforeInput();
        try {
            return lineReader.readLine(renderer.inputPrompt(), currentRightPrompt(),
                    (MaskingCallback) null, seed);
        } finally {
            renderer.afterInput();
        }
    }

    /**
     * 一条[[审批应答]]进了队列（{@link ApprovalPump} 的入队通知，由请求方线程调用）。
     *
     * <p>这里只做两件事：把卡片交给渲染线程画，然后请行编辑器重排一次[[输入框]]，让右提示符当场
     * 切到 {@code y / n / a}。两件事都不读终端。
     */
    public void onApprovalPending(ApprovalRequest request) {
        approvalShown = true;
        renderer.showApproval(approvalView(request), "");
        refreshInputBox();
    }

    /** 队列空了就把卡片撤掉——取消 reject 掉的、以及答完最后一条，都从这里收口。 */
    private void syncApprovalCard() {
        if (approvalShown && !approvalPump.hasPending()) {
            approvalShown = false;
            renderer.dismissApproval();
        }
    }

    /**
     * 这一次提交属于一条待决的[[审批应答]]。
     *
     * <p>没有决定就**什么都不做**——审批留在队列里、请求方继续 park、卡片再问一次。这是空提交
     * （「再问一次」）与无法识别的答案共用的形状：误触回车既不是批准也不是拒绝。
     */
    private void answerPendingApproval(String input) {
        ApprovalPump.Pending head = approvalPump.peekPending();
        if (head == null) {
            syncApprovalCard();
            return;
        }
        Answer answer = parseApprovalAnswer(input, head.request());
        if (answer.decision() == null) {
            renderer.showApproval(approvalView(head.request()), answer.notice());
            refreshInputBox();
            return;
        }
        try {
            ApprovalPump.Pending taken = approvalPump.poll(Duration.ofMillis(1));
            if (taken == null) {
                return;
            }
            approvalPump.resolve(taken, answer.decision());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        ApprovalPump.Pending next = approvalPump.peekPending();
        if (next == null) {
            approvalShown = false;
            renderer.dismissApproval();
        } else {
            renderer.showApproval(approvalView(next.request()), "");
        }
        refreshInputBox();
    }

    /** 一次应答的结果：要么是决定，要么是「再问一次」外加一句提示。 */
    private record Answer(ApprovalResult decision, String notice) {

        static Answer of(ApprovalResult decision) {
            return new Answer(decision, "");
        }

        static Answer askAgain(String notice) {
            return new Answer(null, notice == null ? "" : notice);
        }
    }

    /**
     * 把输入框里提交的一行读成一个审批决定，读不出来就返回「再问一次」。
     *
     * <p>纯函数、无 IO：{@code y} / {@code n} / {@code a} 是文档里承诺的三个，{@code s}（跳过）与
     * {@code m}（改写参数）保留了退役掉的单键提示器的全部能力，只是不再另开一轮提问——范围与参数
     * 写在同一行里（{@code a server} / {@code m {"path":"b.txt"}}）。会话级「全部批准」的粘性范围
     * 由 {@link ApprovalPump} 自己维持，这里不变。
     */
    private static Answer parseApprovalAnswer(String input, ApprovalRequest request) {
        String line = input == null ? "" : input.trim();
        if (line.isEmpty()) {
            // 空提交＝再问一次（PRD user story 17）：不带提示，因为用户没有答错什么。
            return Answer.askAgain("");
        }
        String head = line.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String rest = line.length() > head.length() ? line.substring(head.length()).trim() : "";
        boolean sensitive = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();
        switch (head) {
            case "y", "yes" -> {
                return Answer.of(ApprovalResult.approve());
            }
            case "n", "no" -> {
                return Answer.of(ApprovalResult.reject(rest));
            }
            case "a", "all" -> {
                if (sensitive) {
                    return Answer.askAgain("敏感操作不能整体批准，请用 y / n / s / m");
                }
                String server = ApprovalPolicy.mcpServerName(request.toolName());
                boolean byServer = server != null && !server.isBlank()
                        && ("server".equals(rest) || "s".equals(rest));
                return Answer.of(byServer
                        ? ApprovalResult.approveAllByServer()
                        : ApprovalResult.approveAll());
            }
            case "s", "skip" -> {
                return Answer.of(ApprovalResult.skip());
            }
            case "m", "modify" -> {
                if (rest.isEmpty()) {
                    return Answer.askAgain("m 之后请给出替换用的 JSON 参数");
                }
                try {
                    APPROVAL_JSON.readTree(rest);
                } catch (Exception e) {
                    return Answer.askAgain("参数不是合法 JSON：" + e.getMessage());
                }
                return Answer.of(ApprovalResult.modify(rest));
            }
            default -> {
                return Answer.askAgain("无法识别的回答「" + line + "」；请用 y / n / a（或 s / m）");
            }
        }
    }

    /**
     * 让行编辑器在**输入线程之外**的状态变化之后重排一次[[输入框]]。
     *
     * <p>用的是 JLine 自己的跨线程重绘纪律（{@code printAbove} 就是这么做的）：拿 reader 的锁、
     * 只在它确实在读时重排。交出画笔之后重排不写终端，只发布一帧给渲染循环，所以这条路上没有第二个
     * 写者、也没有回边——渲染线程从不调用它。
     */
    private void refreshInputBox() {
        if (lineReader instanceof CafeCliLineReader capture) {
            capture.refreshInputBox();
        }
    }
}
