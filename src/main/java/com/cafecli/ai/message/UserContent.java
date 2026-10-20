package com.cafecli.ai.message;

/** Content a user message or a tool result may hold: text and images. */
public sealed interface UserContent extends ContentBlock permits TextBlock, ImageBlock {
}
