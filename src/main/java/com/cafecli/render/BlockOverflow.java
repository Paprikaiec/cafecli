package com.cafecli.render;

/**
 * The one wording for "there is more of this block than fits, and here is where to read it".
 *
 * <p>It is a single constant on purpose (ADR 0017 D7). Three block kinds overflow — the inline
 * [[工具块]] result preview, {@code DiffBlock} and {@code CodeBlock} — and the promise it makes
 * ("ctrl+o 查看全部") is only honest because the [[转写浏览层]] really does expand the whole thing.
 * ADR 0012 banned "ctrl+o to expand" wording when the thing it promised was in-place expansion, which
 * could almost never be delivered; that premise is gone, so the wording is allowed and is stated once.
 *
 * <p>What {@code omittedSourceLines} counts is <b>source</b> lines, in every caller. For a diff or a
 * code block the cap is itself in source lines; for a tool result the cap is in visual rows, so the
 * count moves with the terminal width — that is intended, and it is not the cross-projection boundary
 * ADR 0010 amendment A11 is about (a retained entry is simply re-rendered at the new width).
 */
public final class BlockOverflow {

    private BlockOverflow() {
    }

    public static String line(int omittedSourceLines) {
        return "… +" + omittedSourceLines + " 行 (ctrl+o 查看全部)";
    }
}
