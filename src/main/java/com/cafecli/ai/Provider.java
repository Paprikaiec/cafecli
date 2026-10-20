package com.cafecli.ai;

/**
 * A stateless adapter that sends one request to a model and streams the answer back. Everything a request needs — the
 * model record, the context, the options — is passed on every call; a provider keeps no conversation state.
 *
 * <p>Failures never surface as exceptions from {@code stream}: they end the returned stream with a
 * {@link StreamEvent.Failed} event classified by {@link ErrorKind}. See {@link AssistantStream} for the event
 * contract.
 */
public interface Provider {

    AssistantStream stream(Model model, Context context, StreamOptions options);
}
