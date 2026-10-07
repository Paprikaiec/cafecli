package com.cafecli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * edit_file 的纯计算内核：参数容错与结构校验（{@link #parse}）+ 在原文上应用多条编辑（{@link #apply}）。
 *
 * <p>只做字符串运算：不碰文件系统、不加锁、不查取消、不做 5MB 上限——这些 I/O 外壳留在调用方
 * （{@code FileTools.editFile}），策略拒绝因此仍从同一处抛出、走同一条审计。
 * 匹配统一在 LF 空间进行；写盘内容还原原文的 BOM 与换行风格。
 */
final class EditFileTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String DESCRIPTION =
            "编辑已有文件（仅限项目根目录之内，单文件 5MB 上限）。"
            + "同一文件多处修改请用一次调用的多条 edits[]，不要分多次 edit_file 调用。"
            + "每条 old_string 对原始内容匹配，默认须唯一命中（replace_all=true 替换全部非重叠匹配）；"
            + "空 new_string 表示删除；new_string 缺失（key 不传）会报错。"
            + "新建文件或整文件重写请用 write_file。";

    private EditFileTool() {
    }

    /** 一次 edit_file 调用：未解析的目标路径 + 已通过结构校验的编辑（至少一条）。 */
    record Request(String path, List<Edit> edits) {}

    record Edit(String oldString, String newString, boolean replaceAll) {}

    /** {@link #parse} 的结果：{@code error} 非 null 表示参数不合法（原样回给模型），否则 {@code request} 可用。 */
    record Parsed(Request request, String error) {
        static Parsed ok(Request request) {
            return new Parsed(request, null);
        }

        static Parsed failed(String error) {
            return new Parsed(null, error);
        }
    }

    /**
     * {@link #apply} 的结果：{@code error} 非 null 表示未改动（原样回给模型）；否则 {@code before} / {@code after}
     * 是 LF 空间的前后正文（给 diff 观察者），{@code diskContent} 是应写盘的内容（已还原 BOM 与换行风格）。
     */
    record Outcome(String error, String before, String after, String diskContent, String summary) {
        static Outcome failed(String error) {
            return new Outcome(error, null, null, null, null);
        }

        static Outcome edited(String before, String after, String diskContent, String summary) {
            return new Outcome(null, before, after, diskContent, summary);
        }
    }

    private record EditSpan(int start, int end, String replacement, int editIndex, boolean fuzzy, int lineNumber) {}

    static JsonNode schema() {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string");
        ObjectNode editsArr = props.putObject("edits").put("type", "array");
        ObjectNode items = editsArr.putObject("items").put("type", "object");
        ObjectNode itemProps = items.putObject("properties");
        itemProps.putObject("old_string").put("type", "string");
        itemProps.putObject("new_string").put("type", "string");
        itemProps.putObject("replace_all").put("type", "boolean");
        items.putArray("required").add("old_string").add("new_string");
        schema.putArray("required").add("path").add("edits");
        return schema;
    }

    /**
     * 参数容错 + 反序列化 + 结构校验（fail-fast，序号 1-based）。容错两条：{@code edits} 被字符串化时解析回数组；
     * 顶层 {@code old_string} / {@code new_string} 折进 {@code edits[]}。
     */
    static Parsed parse(JsonNode args) {
        // Tolerance: stringified edits → parse into array; top-level old_string/new_string → fold into edits[]
        JsonNode editsRaw = args.path("edits");
        if (editsRaw.isTextual()) {
            String raw = editsRaw.asText();
            JsonNode parsed;
            try {
                parsed = MAPPER.readTree(raw);
            } catch (Exception e) {
                return Parsed.failed("edits 参数格式错误（期望 JSON 数组）: " + e.getMessage());
            }
            ObjectNode mutable = (ObjectNode) args.deepCopy();
            mutable.set("edits", parsed.isArray() ? parsed : MAPPER.createArrayNode().add(parsed));
            args = mutable;
        }
        JsonNode topOld = args.path("old_string");
        if (!topOld.isMissingNode() && topOld.isTextual()) {
            ObjectNode mutable = args instanceof ObjectNode ? (ObjectNode) args : (ObjectNode) args.deepCopy();
            ObjectNode syntheticEdit = MAPPER.createObjectNode()
                    .put("old_string", topOld.asText())
                    .put("new_string", args.path("new_string").asText(""));
            mutable.withArray("edits").add(syntheticEdit);
            mutable.remove("old_string");
            mutable.remove("new_string");
            args = mutable;
        }

        // A. Deserialize path + edits[]
        String pathStr;
        List<Edit> edits;
        try {
            pathStr = args.path("path").asText(null);
            JsonNode editsNode = args.path("edits");
            if (editsNode.isMissingNode() || editsNode.isNull()) {
                return Parsed.failed("edits 不能为空");
            }
            edits = new ArrayList<>();
            for (JsonNode item : editsNode) {
                String oldString = item.path("old_string").isMissingNode() ? null : item.path("old_string").asText();
                String newString = item.path("new_string").isMissingNode() ? null : item.path("new_string").asText();
                boolean replaceAll = item.path("replace_all").asBoolean(false);
                edits.add(new Edit(oldString, newString, replaceAll));
            }
        } catch (Exception e) {
            return Parsed.failed("参数解析失败: " + e.getMessage());
        }

        // B. Structural validation (fail-fast, 1-based)
        if (edits.isEmpty()) return Parsed.failed("edits 不能为空");
        for (int i = 0; i < edits.size(); i++) {
            Edit e = edits.get(i);
            if (e.oldString() == null || e.oldString().isEmpty())
                return Parsed.failed("old_string 为空，请用 write_file 新建或整文件重写");
            if (e.newString() == null)
                return Parsed.failed("第" + (i + 1) + "条缺少 new_string（删除请传空串 \"\"）");
            if (e.oldString().equals(e.newString()))
                return Parsed.failed("第" + (i + 1) + "条 old_string 与 new_string 相同，无需修改");
        }
        return Parsed.ok(new Request(pathStr, edits));
    }

    /** 在 {@code rawContent}（文件原文，可含 BOM / CRLF / CR）上应用 {@code request} 的全部编辑：先精确匹配，未命中再整行模糊匹配。 */
    static Outcome apply(String rawContent, Request request) {
        String bom = extractBom(rawContent);
        String bodyRaw = stripBom(rawContent);
        LineEnding lineEnding = detectLineEnding(bodyRaw);
        String content = normalizeToLF(bodyRaw);  // LF-space for all matching

        // E. Collect spans (exact match, then fuzzy fallback)
        List<EditSpan> spans = new ArrayList<>();
        for (int i = 0; i < request.edits().size(); i++) {
            Edit e = request.edits().get(i);
            List<Integer> positions = occurrences(content, e.oldString());
            if (positions.isEmpty()) {
                // Fuzzy fallback: whole-line only
                String normOldForFuzzy = normalizeToLF(e.oldString());
                if (normOldForFuzzy.contains("\n")) {
                    return Outcome.failed("第" + (i + 1) + "条 old_string 跨多行，仅支持整行模糊匹配；请改用精确匹配");
                }
                // Scan lines in content for a fuzzy-matching whole line
                List<EditSpan> fuzzySpans = new ArrayList<>();
                int lineStart = 0;
                int lineNum = 1;
                while (lineStart <= content.length()) {
                    int lineEnd = content.indexOf('\n', lineStart);
                    if (lineEnd == -1) lineEnd = content.length();
                    String line = content.substring(lineStart, lineEnd);
                    if (normalizeForFuzzyMatch(line).equals(normalizeForFuzzyMatch(normOldForFuzzy))) {
                        fuzzySpans.add(new EditSpan(lineStart, lineEnd, e.newString(), i + 1, true, lineNum));
                    }
                    if (lineEnd == content.length()) break;
                    lineStart = lineEnd + 1;
                    lineNum++;
                }
                if (fuzzySpans.isEmpty()) {
                    return Outcome.failed("第" + (i + 1) + "条 old_string 未找到，请确认与文件完全一致（含缩进/空白/换行）");
                }
                if (fuzzySpans.size() > 1 && !e.replaceAll()) {
                    return Outcome.failed("第" + (i + 1) + "条 old_string 模糊匹配 " + fuzzySpans.size() + " 处，请缩小范围或使用 replace_all=true");
                }
                spans.addAll(e.replaceAll() ? fuzzySpans : List.of(fuzzySpans.get(0)));
                continue;  // skip the uniqueness check below (already handled for fuzzy)
            }
            if (positions.size() > 1 && !e.replaceAll())
                return Outcome.failed("第" + (i + 1) + "条 old_string 匹配 " + positions.size()
                        + " 处，请缩小范围或使用 replace_all=true");
            for (int pos : positions)
                spans.add(new EditSpan(pos, pos + e.oldString().length(), e.newString(), i + 1, false, 0));
        }

        // F. Sort + overlap check
        spans.sort(Comparator.comparingInt(EditSpan::start));
        for (int i = 1; i < spans.size(); i++) {
            EditSpan prev = spans.get(i - 1), cur = spans.get(i);
            if (prev.end() > cur.start())
                return Outcome.failed(
                        "第" + prev.editIndex() + "条与第" + cur.editIndex() + "条区间重叠");
        }

        // G. Apply all spans
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        int totalMatches = spans.size();
        for (EditSpan span : spans) {
            sb.append(content, cursor, span.start());
            sb.append(span.replacement());
            cursor = span.end();
        }
        sb.append(content, cursor, content.length());
        String newContent = sb.toString();

        if (content.equals(newContent)) return Outcome.failed("编辑后文件内容无变化");

        // H. 还原 BOM 与换行风格 + 摘要（5MB 上限由调用方在落盘前检查）
        String diskContent = bom + restoreLineEndings(newContent, lineEnding);
        StringBuilder fuzzyNotes = new StringBuilder();
        for (EditSpan span : spans) {
            if (span.fuzzy()) {
                fuzzyNotes.append("，第").append(span.editIndex()).append("条: 模糊匹配命中第").append(span.lineNumber()).append("行");
            }
        }
        String summary = "文件已编辑: " + request.path() + "，共 " + request.edits().size() + " 条编辑，替换 " + totalMatches + " 处匹配"
                + fuzzyNotes;
        return Outcome.edited(content, newContent, diskContent, summary);
    }

    private static List<Integer> occurrences(String text, String needle) {
        List<Integer> result = new ArrayList<>();
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            result.add(idx);
            idx += needle.length();
        }
        return result;
    }

    private static String extractBom(String s) {
        return s.startsWith("﻿") ? "﻿" : "";
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    private enum LineEnding { LF, CRLF, CR }

    private static LineEnding detectLineEnding(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                return (i + 1 < s.length() && s.charAt(i + 1) == '\n') ? LineEnding.CRLF : LineEnding.CR;
            }
            if (c == '\n') return LineEnding.LF;
        }
        return LineEnding.LF;
    }

    private static String normalizeToLF(String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String restoreLineEndings(String s, LineEnding ending) {
        return switch (ending) {
            case CRLF -> s.replace("\n", "\r\n");
            case CR   -> s.replace("\n", "\r");
            case LF   -> s;
        };
    }

    private static String normalizeForFuzzyMatch(String s) {
        // 1. NFKC normalization
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
        // 2. Smart quotes → straight
        n = n.replace('“', '"').replace('”', '"')  // " "
             .replace('‘', '\'').replace('’', '\'') // ' '
             .replace('‚', '\'').replace('‛', '\'') // ‚ ‛
             .replace('«', '"').replace('»', '"');  // « »
        // 3. Em/en dash → hyphen
        n = n.replace('—', '-').replace('–', '-').replace('‒', '-');
        // 4. Full-width space and ideographic space → regular space
        n = n.replace('　', ' ').replace(' ', ' ');
        // 5. Strip trailing whitespace
        n = n.stripTrailing();
        return n;
    }
}
