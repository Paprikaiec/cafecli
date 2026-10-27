package com.cafecli.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parser contracts stay executable after the v1 user-message wrapper is deleted. */
class ImageReferenceParserNeutralTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"relative", "absolute", "angle", "file-space", "file-nonascii", "file-percent", "punctuation"})
    void resolvesTheExistingReferenceSpellingsWithoutALlmDependency(String spelling) throws Exception {
        String filename = switch (spelling) {
            case "file-space", "file-percent", "angle" -> "space here.png";
            case "file-nonascii" -> "中文截图.png";
            default -> "shot.png";
        };
        Path image = root.resolve(filename);
        Files.write(image, new byte[]{1, 2, 3});
        String reference = switch (spelling) {
            case "relative" -> "@image:shot.png";
            case "absolute" -> "@image:" + image;
            case "angle" -> "@image:<" + image + ">";
            case "file-space", "file-nonascii" -> "@image:<file://" + image + ">";
            case "file-percent" -> "@image:<file://" + image.toString().replace(" ", "%20") + ">";
            case "punctuation" -> "@image:shot.png。这个里面是什么？";
            default -> throw new AssertionError(spelling);
        };
        var parsed = ImageReferenceParser.parse("Analyze " + reference, root);
        assertEquals(List.of(new ImagePart("AQID", "image/png")), parsed.images());
        assertTrue(parsed.text().contains("请直接观察本轮图片内容"));
        assertTrue(parsed.text().contains("历史对话、历史工具结果、网页/仓库信息都只能作为背景"));
        assertTrue(parsed.text().contains("如果当前图片与历史上下文冲突，以当前图片为准"));
        assertTrue(parsed.text().contains("不要调用 MCP、文件系统或浏览器工具重新读取 Image source"));
        assertFalse(parsed.text().contains("当前模型不声明图片能力"));
        if (spelling.equals("punctuation")) assertTrue(parsed.text().contains("。这个里面是什么？"));
    }

    @Test void retainsLosslessLargePngBelowTheApiLimit() throws Exception {
        Path image = root.resolve("wide.png");
        var buffered = new BufferedImage(2600, 1200, BufferedImage.TYPE_INT_RGB);
        var graphics = buffered.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 2600, 1200);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(100, 100, 2200, 800);
        } finally { graphics.dispose(); }
        ImageIO.write(buffered, "png", image.toFile());
        var parsed = ImageReferenceParser.parse("Analyze @image:wide.png", root);
        assertEquals(List.of(new ImagePart(Base64.getEncoder().encodeToString(Files.readAllBytes(image)), "image/png")), parsed.images());
        assertTrue(parsed.images().getFirst().base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
    }

    @Test void plainTextAndClipboardWordBoundariesStayUnchanged() {
        String input = "看看 @clipboardfoo 这是个变量名";
        assertEquals(new ImageReferenceParser.ParsedInput(input, List.of()), ImageReferenceParser.parse(input, root));
        assertEquals(new ImageReferenceParser.ParsedInput("", List.of()), ImageReferenceParser.parse(null, root));
    }

    @ParameterizedTest
    @ValueSource(strings = {"看看这张 @clipboard。这是什么？", "看看这张 @clipboard 图"})
    void clipboardReferencesAreReplacedWhetherCaptureSucceedsOrFails(String input) {
        var parsed = ImageReferenceParser.parse(input, root);
        assertFalse(parsed.text().contains("@clipboard"));
        assertTrue(!parsed.images().isEmpty() || parsed.text().contains("引用无效"));
        if (input.contains("。")) assertTrue(parsed.text().contains("。这是什么？"));
    }

    @Test void invalidReferencesRemainTextAndDoNotPreventSuccessfulAttachments() throws Exception {
        Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        var parsed = ImageReferenceParser.parse("@image:missing.png @image:shot.png", root);
        assertEquals(List.of(new ImagePart("AQID", "image/png")), parsed.images());
        assertTrue(parsed.text().startsWith("请分析以下图片。\n\n"));
        assertTrue(parsed.text().contains("[图片引用无效: missing.png，原因: 文件不存在]"));
        var invalid = ImageReferenceParser.parse("Only @image:missing.png", root);
        assertEquals(List.of(), invalid.images());
        assertEquals("Only\n\n[图片引用无效: missing.png，原因: 文件不存在]", invalid.text());
    }
}
