package com.cafecli.ai.openai;

import com.cafecli.ai.Model;
import com.cafecli.ai.message.ImageBlock;
import java.util.Locale;

/** GLM's vision models use the general endpoint and raw image bytes; coding models use the coding endpoint. */
public final class GlmProvider extends OpenAiCompatibleProvider {
    public static final String CODING_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    public static final String VISION_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private final String codingUrl;
    private final String visionUrl;

    public GlmProvider(String apiKey, HttpTimeouts timeouts) { this(apiKey, CODING_URL, VISION_URL, timeouts); }

    public GlmProvider(String apiKey, String codingUrl, String visionUrl, HttpTimeouts timeouts) {
        super(apiKey, codingUrl, timeouts);
        this.codingUrl = completionsUrl(codingUrl);
        this.visionUrl = completionsUrl(visionUrl);
    }

    private static boolean vision(Model model) { return model.id().trim().toLowerCase(Locale.ROOT).startsWith("glm-5v"); }
    @Override protected String endpoint(Model model) { return vision(model) ? visionUrl : codingUrl; }
    @Override protected String imageUrl(Model model, ImageBlock image) {
        return vision(model) ? image.base64() : super.imageUrl(model, image);
    }
}
