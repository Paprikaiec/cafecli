package com.cafecli.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageReferenceParserTest {

    @Test
    void attachesLocalImageAsImageAttachment(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("shot.png");
        Files.write(image, new byte[]{1, 2, 3});

        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "分析一下 @image:" + image,
                tempDir);

        assertTrue(!message.images().isEmpty());
        assertEquals("image/png", message.images().getFirst().mimeType());
        assertTrue(message.text().contains("请直接观察本轮图片内容"));
        assertTrue(message.text().contains("历史对话、历史工具结果、网页/仓库信息都只能作为背景"));
        assertTrue(message.text().contains("如果当前图片与历史上下文冲突，以当前图片为准"));
        assertTrue(message.text().contains("不要调用 MCP、文件系统或浏览器工具重新读取 Image source"));
    }

    @Test
    void keepsOversizedLocalImageAsLosslessPngWhenWithinApiLimit(@TempDir Path tempDir) throws Exception {
        // 单色填充的 2600x1200 PNG 远低于 5MB，新策略下应直通 PNG，不再 resize+JPEG。
        Path image = tempDir.resolve("wide.png");
        BufferedImage buffered = new BufferedImage(2600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
            graphics.setColor(Color.BLUE);
            graphics.fillRect(100, 100, 2200, 800);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(buffered, "png", image.toFile());

        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "分析一下 @image:" + image,
                tempDir);

        assertTrue(!message.images().isEmpty());
        ImagePart imagePart = message.images().getFirst();
        assertEquals("image/png", imagePart.mimeType(),
                "字节在 5MB 上限内时保持原始 PNG，不再因尺寸超过 2000 强制转 JPEG");
        assertTrue(imagePart.base64().length() <= ImageProcessor.API_IMAGE_MAX_BASE64_SIZE);
    }

    @Test
    void attachesLocalImageWithoutModelCapabilityGate(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("shot.png");
        Files.write(image, new byte[]{1, 2, 3});

        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "分析一下 @image:" + image,
                tempDir);

        assertTrue(!message.images().isEmpty());
        assertFalse(message.text().contains("当前模型不声明图片能力"));
    }

    @Test
    void resolvesFileUriWithUnencodedSpaces(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("path with spaces.png");
        Files.write(image, new byte[]{1, 2, 3});

        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "看看 @image:<file://" + image + ">",
                tempDir);

        assertTrue(!message.images().isEmpty(), "未编码空格的 file:// 应能解析");
    }

    @Test
    void resolvesFileUriWithNonAsciiPath(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("中文截图.png");
        Files.write(image, new byte[]{1, 2, 3});

        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "看看 @image:<file://" + image + ">",
                tempDir);

        assertTrue(!message.images().isEmpty(), "非 ASCII 路径的 file:// 应能解析");
    }

    @Test
    void resolvesFileUriWithPercentEncodedPath(@TempDir Path tempDir) throws Exception {
        Path image = tempDir.resolve("space here.png");
        Files.write(image, new byte[]{1, 2, 3});

        String encoded = image.toString().replace(" ", "%20");
        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "看看 @image:<file://" + encoded + ">",
                tempDir);

        assertTrue(!message.images().isEmpty(), "%20 编码路径仍应被解码为本地路径");
    }

    @Test
    void barePathStopsAtFullWidthPunctuation(@TempDir Path tempDir) throws Exception {
        // 用例对应 docs/phase-21-image-input-manual-test.md Case 9：
        // "@image:./shot.png。这是什么？"  —— 路径应在全角句号处截断，不再吞掉后面的中文。
        Path image = tempDir.resolve("shot.png");
        Files.write(image, new byte[]{1, 2, 3});

        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "帮我看 @image:" + image + "。这个里面是什么？",
                tempDir);

        assertTrue(!message.images().isEmpty(),
                "全角句号不该被当成路径一部分，导致文件查不到");
        // 文本里依然保留中文标点和提问
        assertTrue(message.text().contains("。这个里面是什么？"),
                "@image: ref 被替换后中文文本应保留：" + message.text());
    }

    @Test
    void clipboardTokenDoesNotMatchWhenAttachedToWord(@TempDir Path tempDir) {
        // "@clipboardfoo" 不应该被识别成 @clipboard，文本应原样保留，也不会触发抓图分支
        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "看看 @clipboardfoo 这是个变量名", tempDir);

        assertFalse(!message.images().isEmpty(), "@clipboardfoo 不应触发抓图");
        assertTrue(message.text().contains("@clipboardfoo"),
                "未命中的 @clipboardfoo 应原样保留：" + message.text());
    }

    @Test
    void clipboardTokenStripsBeforeFullWidthPunctuation(@TempDir Path tempDir) {
        // 全角句号 / 中文问号紧跟 @clipboard 应该正常作为 word boundary：token 被替换，
        // 标点保留在文本里。
        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "看看这张 @clipboard。这是什么？", tempDir);

        assertFalse(message.text().contains("@clipboard"),
                "@clipboard 字面量应已被替换：" + message.text());
        assertTrue(message.text().contains("。这是什么？"),
                "标点和后续中文应保留：" + message.text());
    }

    @Test
    void clipboardTokenIsStrippedFromTextRegardlessOfGrabOutcome(@TempDir Path tempDir) {
        // 不依赖剪贴板真实状态：无论 grab 成功还是失败，@clipboard 字面量都不应留在
        // 发给 LLM 的文本里——成功时变 image part，失败时变 [图片引用无效…] 提示。
        ImageReferenceParser.ParsedInput message = ImageReferenceParser.parse(
                "看看这张 @clipboard 图", tempDir);

        assertFalse(message.text().contains("@clipboard"),
                "@clipboard 字面量应已被 strip：" + message.text());
        boolean attached = !message.images().isEmpty();
        boolean errored = message.text().contains("引用无效");
        assertTrue(attached || errored,
                "@clipboard 应触发抓图分支（成功附加或返回错误提示）：" + message.text());
    }
}
