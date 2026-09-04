#!/usr/bin/env bash
#
# 判据：surefire 真的跑了，而且**一条都没 skip**。
#
# 为什么要单独一步：`assumeTrue` 会把 skip 伪装成 pass。套件里有三处会因为环境不满足而静默跳过——
# `SnapshotDoesNotTouchProjectGitTest`（拿不到系统 git）、`SnapshotRestoreStaysInsideProjectRootTest`
# （建不了符号链接）、`AnsiStyleTest`（`AnsiStyle.ENABLED=false`，即 `NO_COLOR` 或 `TERM=dumb`）。
# 这三处恰好覆盖两条安全围栏和一档渲染配色，在 CI 上必须**真的跑**。maven 对 skip 是绿灯，
# 所以「绿灯里混着 skip」这种假绿只能靠读报告本身来抓。
#
# 顺带也断言 failures / errors 为 0：这不是重复劳动，是防 `-Dmaven.test.failure.ignore`——
# 那个开关会让 maven 自己变绿，报告里却仍然记着失败。
#
# 用法：assert-no-skipped-tests.sh [surefire-reports 目录]
# 注意调用它之前不能 `mvn clean`，报告会被一起删掉。
set -euo pipefail

reports_dir="${1:-target/surefire-reports}"

if [ ! -d "$reports_dir" ]; then
    echo "no surefire reports under $reports_dir — the suite never ran" >&2
    exit 1
fi

report_count="$(find "$reports_dir" -name 'TEST-*.xml' -type f | wc -l | tr -d ' ')"
if [ "$report_count" -eq 0 ]; then
    echo "no TEST-*.xml under $reports_dir — the suite never ran" >&2
    exit 1
fi

awk '
function attr(line, key,   pattern) {
    pattern = " " key "=\"[^\"]*\""
    if (!match(line, pattern)) {
        return ""
    }
    return substr(line, RSTART + length(key) + 3, RLENGTH - length(key) - 4)
}

/<testsuite / {
    # 每份报告只有一行 <testsuite>；<testcase> 上没有这些计数属性。
    if (counted[FILENAME]++) {
        next
    }
    name = attr($0, "name")
    t = attr($0, "tests") + 0
    s = attr($0, "skipped") + 0
    f = attr($0, "failures") + 0
    e = attr($0, "errors") + 0

    suites++
    tests += t
    skipped += s
    failures += f
    errors += e

    if (s > 0) {
        offenders = offenders sprintf("  %s — skipped %d of %d\n", name, s, t)
    }
}

END {
    printf "%d suites · %d tests · %d failures · %d errors · %d skipped\n",
            suites, tests, failures, errors, skipped

    if (suites == 0 || tests == 0) {
        print "no tests ran at all" > "/dev/stderr"
        exit 1
    }
    if (skipped > 0) {
        printf "%d test(s) were skipped, not run:\n%s", skipped, offenders > "/dev/stderr"
        print "a skip reads exactly like a pass in the maven log. see this script'"'"'s header." > "/dev/stderr"
        exit 1
    }
    if (failures + errors > 0) {
        printf "reports record %d failure(s) and %d error(s)\n", failures, errors > "/dev/stderr"
        exit 1
    }
}
' "$reports_dir"/TEST-*.xml
