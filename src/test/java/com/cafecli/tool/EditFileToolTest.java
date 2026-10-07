package com.cafecli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * edit_file 纯计算内核的直测：不经文件系统、锁与 registry，只看「原文 + 编辑 → 结果」。
 * 端到端行为（落盘、5MB 上限、审计、LSP）仍由 ToolRegistryTest 覆盖。
 */
class EditFileToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EditFileTool.Request request(EditFileTool.Edit... edits) {
        return new EditFileTool.Request("demo.txt", List.of(edits));
    }

    private static EditFileTool.Edit edit(String oldString, String newString) {
        return new EditFileTool.Edit(oldString, newString, false);
    }

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    void bomIsMatchedAroundAndRestoredOnDisk() {
        EditFileTool.Outcome outcome = EditFileTool.apply("﻿alpha\nbeta\n", request(edit("alpha", "gamma")));

        assertNull(outcome.error());
        assertEquals("﻿gamma\nbeta\n", outcome.diskContent());
        assertEquals("alpha\nbeta\n", outcome.before(), "diff 观察者看的是去掉 BOM 的 LF 正文");
        assertEquals("gamma\nbeta\n", outcome.after());
    }

    @Test
    void crlfFileMatchesInLfSpaceAndKeepsCrlfOnDisk() {
        EditFileTool.Outcome outcome = EditFileTool.apply("one\r\ntwo\r\nthree\r\n", request(edit("one\ntwo", "uno\ndos")));

        assertNull(outcome.error());
        assertEquals("uno\r\ndos\r\nthree\r\n", outcome.diskContent());
        assertEquals("uno\ndos\nthree\n", outcome.after());
    }

    @Test
    void bareCrFileKeepsCrOnDisk() {
        EditFileTool.Outcome outcome = EditFileTool.apply("one\rtwo\r", request(edit("two", "dos")));

        assertNull(outcome.error());
        assertEquals("one\rdos\r", outcome.diskContent());
    }

    @Test
    void fuzzyFallbackMatchesAWholeLineAndReportsItsLineNumber() {
        String raw = "first\nprint(“hi”) — done   \nlast\n";

        EditFileTool.Outcome outcome = EditFileTool.apply(raw, request(edit("print(\"hi\") - done", "print('bye')")));

        assertNull(outcome.error());
        assertEquals("first\nprint('bye')\nlast\n", outcome.diskContent());
        assertTrue(outcome.summary().contains("第1条: 模糊匹配命中第2行"), outcome.summary());
    }

    @Test
    void ambiguousFuzzyMatchIsRejectedUnlessReplaceAll() {
        String raw = "say(“x”)\nsay(‘x’)\nsay(“x”)\n";

        EditFileTool.Outcome rejected = EditFileTool.apply(raw, request(edit("say(\"x\")", "ok")));
        EditFileTool.Outcome replaced = EditFileTool.apply(raw,
                request(new EditFileTool.Edit("say(\"x\")", "ok", true)));

        assertTrue(rejected.error().contains("模糊匹配 2 处"), rejected.error());
        assertNull(replaced.error());
        assertEquals("ok\nsay(‘x’)\nok\n", replaced.diskContent());
    }

    @Test
    void multiLineOldStringNeverFallsBackToFuzzy() {
        EditFileTool.Outcome outcome = EditFileTool.apply("a “b”\nc\n", request(edit("a \"b\"\nc", "z")));

        assertTrue(outcome.error().contains("跨多行"), outcome.error());
    }

    @Test
    void overlappingEditsAreRejected() {
        EditFileTool.Outcome outcome = EditFileTool.apply("abcdef", request(edit("abcd", "x"), edit("cdef", "y")));

        assertEquals("第1条与第2条区间重叠", outcome.error());
    }

    @Test
    void exactDuplicateNeedsReplaceAllAndThenReplacesEveryOccurrence() {
        EditFileTool.Outcome rejected = EditFileTool.apply("x = 1; x = 1; x = 1;", request(edit("x = 1", "y = 2")));
        EditFileTool.Outcome replaced = EditFileTool.apply("x = 1; x = 1; x = 1;",
                request(new EditFileTool.Edit("x = 1", "y = 2", true)));

        assertTrue(rejected.error().contains("匹配 3 处"), rejected.error());
        assertEquals("y = 2; y = 2; y = 2;", replaced.diskContent());
        assertTrue(replaced.summary().contains("替换 3 处匹配"), replaced.summary());
    }

    @Test
    void stringifiedEditsArrayIsParsedBack() throws Exception {
        EditFileTool.Parsed parsed = EditFileTool.parse(json(
                "{\"path\":\"a.txt\",\"edits\":\"[{\\\"old_string\\\":\\\"a\\\",\\\"new_string\\\":\\\"b\\\"}]\"}"));

        assertNull(parsed.error());
        assertEquals("a.txt", parsed.request().path());
        assertEquals(List.of(new EditFileTool.Edit("a", "b", false)), parsed.request().edits());
    }

    @Test
    void topLevelOldAndNewStringAreFoldedIntoEdits() throws Exception {
        EditFileTool.Parsed parsed = EditFileTool.parse(json(
                "{\"path\":\"a.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}"));

        assertNull(parsed.error());
        assertEquals(List.of(new EditFileTool.Edit("a", "b", false)), parsed.request().edits());
    }

    @Test
    void missingNewStringIsAStructuralError() throws Exception {
        EditFileTool.Parsed parsed = EditFileTool.parse(json(
                "{\"path\":\"a.txt\",\"edits\":[{\"old_string\":\"a\",\"new_string\":\"b\"},{\"old_string\":\"c\"}]}"));

        assertNull(parsed.request());
        assertNotNull(parsed.error());
        assertTrue(parsed.error().startsWith("第2条缺少 new_string"), parsed.error());
    }
}
