package com.cts.uam.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GroqChatServiceImpl - calls the Groq AI API to power the in-app chatbot.
 *
 * The chatbot is scoped to CTS-related questions via the system prompt.
 * API key is loaded from groq.properties (never hardcoded, never committed to
 * git).
 *
 * Connection timeout: 10 seconds
 * Response timeout: 20 seconds
 * Max tokens per reply: 300 (keeps answers short and on-topic)
 */
public class GroqChatServiceImpl implements GroqChatService {

    private static final Logger LOG = Logger.getLogger(GroqChatServiceImpl.class.getName());

    // Groq's OpenAI-compatible chat completions endpoint
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    // API key loaded once at class load time from groq.properties on the classpath
    // (that file is gitignored - never commit a real key to source control)
    private static final String API_KEY = loadApiKey();

    /**
     * Loads the Groq API key from src/main/resources/groq.properties.
     * Logs an error and returns null if the file or key is missing.
     * A null key causes ask() to return a user-friendly error message instead of
     * calling the API.
     */
    private static String loadApiKey() {
        try (InputStream in = GroqChatServiceImpl.class
                .getClassLoader()
                .getResourceAsStream("groq.properties")) {

            if (in == null) {
                LOG.severe("GroqChatServiceImpl: groq.properties not found on classpath "
                        + "(expected at src/main/resources/groq.properties).");
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            String key = props.getProperty("groq.api.key");
            if (key == null || key.trim().isEmpty()) {
                LOG.severe("GroqChatServiceImpl: groq.api.key property is missing/empty in groq.properties.");
                return null;
            }
            return key.trim();

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "GroqChatServiceImpl: failed to load groq.properties", ex);
            return null;
        }
    }

    // Fast, cheap model for short FAQ-style answers.
    // Swap for a larger Groq-hosted model if deeper reasoning is needed.
    private static final String MODEL = "llama-3.1-8b-instant";

    // System prompt: tells the AI what app it is helping with and how to behave.
    // Answers are kept short (under 4 sentences) unless the user asks for more.
    private static final String SYSTEM_PROMPT = "You are 'CTS Assistant', a helpful in-app guide for Navbharat/ " +
            "Clear CTS — a Cheque Truncation System covering Inward and " +
            "Outward cheque clearing, a maker-checker workflow (Maker -> TV1 -> " +
            "TV2), and a User Access Management (UAM) module with role-based " +
            "permissions. Answer briefly and clearly, in plain language. If asked " +
            "something outside this application's scope, say you can only help " +
            "with CTS-related questions. Keep answers under 4 sentences unless " +
            "the user asks for detail.";

    // Shared HTTP client - thread-safe, expensive to create, so one instance per
    // service
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    // ── Public API ────────────────────────────────────────────────

    /**
     * Sends a user message to Groq and returns the assistant's reply.
     *
     * Returns a user-friendly error string (never throws) if:
     * - API key is missing
     * - User message is blank
     * - HTTP call fails or returns non-200
     * - Response cannot be parsed
     */
    @Override
    public String ask(String userMessage) {

        // API key missing - cannot make any calls
        if (API_KEY == null || API_KEY.trim().isEmpty()) {
            LOG.severe("GroqChatServiceImpl: API key not loaded — check groq.properties.");
            return "Assistant abhi configure nahi hua hai (API key missing). Admin se contact karein.";
        }

        // Empty message - nothing to send
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return "Kuch type karke poochiye, main madad karne ki koshish karunga.";
        }

        try {
            String requestBody = buildRequestBody(userMessage.trim());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GROQ_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Non-200 status means the Groq server returned an error
            if (response.statusCode() != 200) {
                LOG.warning("GroqChatServiceImpl: Groq API returned status "
                        + response.statusCode() + " — body: " + response.body());
                return "Assistant abhi response nahi de paaya (server busy). Thodi der baad try karein.";
            }

            return extractReply(response.body());

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "GroqChatServiceImpl.ask() failed", ex);
            return "Assistant se connect nahi ho paaya. Apna internet/connection check karein.";
        }
    }

    // ── Private Helpers ───────────────────────────────────────────

    /**
     * Builds the JSON request body in OpenAI chat-completions format.
     * Includes the system prompt (to scope the chatbot) and the user's message.
     */
    private String buildRequestBody(String userMessage) throws Exception {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("model", MODEL);
        root.put("temperature", 0.3); // lower = more focused, less creative
        root.put("max_tokens", 300); // keep replies short

        ArrayNode messages = root.putArray("messages");

        // System message: tells the AI how to behave
        ObjectNode systemMsg = jsonMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", SYSTEM_PROMPT);
        messages.add(systemMsg);

        // User message: what the user actually asked
        ObjectNode userMsg = jsonMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        return jsonMapper.writeValueAsString(root);
    }

    /**
     * Extracts the assistant's reply text from the Groq API response.
     * Expected structure: { choices: [ { message: { content: "..." } } ] }
     * Returns a fallback string if the structure is unexpected.
     */
    private String extractReply(String responseBody) throws Exception {
        JsonNode root = jsonMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String content = choices.get(0).path("message").path("content").asText("");
            if (!content.trim().isEmpty()) {
                return content.trim();
            }
        }
        LOG.warning("GroqChatServiceImpl: unexpected response shape: " + responseBody);
        return "Assistant ka response samajh nahi paaya. Dobara try karein.";
    }
}
