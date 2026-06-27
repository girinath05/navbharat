package com.cts.uam.service;

/**
 * GroqChatService - sends a user message to the AI chatbot and returns a reply.
 *
 * Implementation: GroqChatServiceImpl
 *
 * The chatbot is scoped to CTS-related questions only (configured via the
 * system prompt).
 * Any implementation of this interface must never throw - it must return a
 * user-friendly fallback string if something goes wrong.
 */
public interface GroqChatService {

    /**
     * Sends the user's message to the AI backend and returns the assistant's reply.
     * Never throws - implementations must catch all errors and return a fallback
     * string.
     */
    String ask(String userMessage);
}
