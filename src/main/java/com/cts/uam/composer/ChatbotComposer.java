package com.cts.uam.composer;

import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.select.SelectorComposer;
import org.zkoss.zk.ui.select.annotation.Listen;
import org.zkoss.zk.ui.select.annotation.Wire;
import org.zkoss.zul.Div;
import org.zkoss.zul.Label;
import org.zkoss.zul.Textbox;
import org.zkoss.zul.Button;

import com.cts.uam.service.GroqChatService;
import com.cts.uam.service.GroqChatServiceImpl;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ChatbotComposer - handles the in-app AI chatbot widget.
 *
 * The chatbot is scoped to CTS-related questions only.
 * It calls the Groq AI API via GroqChatService.
 *
 * How it works:
 *   1. User types a message and clicks Send (or presses Enter)
 *   2. User's message is added to the chat as a bubble
 *   3. Send button is disabled while waiting for AI response
 *   4. AI reply is added as a bot bubble
 *   5. Send button is re-enabled
 */
public class ChatbotComposer extends SelectorComposer<Component> {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = Logger.getLogger(ChatbotComposer.class.getName());

    // Chat area where message bubbles are added
    @Wire("#chatbotBody")
    private Div chatbotBody;

    // Text input where user types their message
    @Wire("#chatbotInput")
    private Textbox chatbotInput;

    // Send button - disabled while waiting for AI response to prevent double-firing
    @Wire("#chatbotSendBtn")
    private Button chatbotSendBtn;

    private final GroqChatService chatService = new GroqChatServiceImpl();

    /**
     * Runs once when the chatbot widget loads.
     * Clears any static placeholder content from the ZUL file
     * and shows the bot's welcome message.
     */
    @Override
    public void doAfterCompose(Component comp) throws Exception {
        super.doAfterCompose(comp);

        // Remove old static placeholder bubbles that were in the ZUL file
        chatbotBody.getChildren().clear();
        appendBotMessage(
                "Hi! Ask me anything about Navbharat Clear Pay CTS — the maker-checker " +
                        "flow, UAM, or any page in the app.");
    }

    // Runs when the Send button is clicked
    @Listen("onClick = #chatbotSendBtn")
    public void onSend() {
        sendCurrentMessage();
    }

    // Lets the user press Enter inside the textbox instead of clicking Send
    @Listen("onOK = #chatbotInput")
    public void onInputEnter() {
        sendCurrentMessage();
    }

    /**
     * Reads the text from the input box, sends it to the AI, and shows the reply.
     * Does nothing if the input is blank.
     * Disables the Send button while waiting for the AI response.
     */
    private void sendCurrentMessage() {
        String text = chatbotInput.getValue();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        text = text.trim();

        // Show user's message as a bubble immediately
        appendUserMessage(text);
        chatbotInput.setValue("");

        // Disable Send while waiting so the user cannot double-fire requests
        chatbotSendBtn.setDisabled(true);
        try {
            String reply = chatService.ask(text);
            appendBotMessage(reply);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "ChatbotComposer: unexpected error calling GroqChatService", ex);
            appendBotMessage("Something went wrong. Please try again shortly.");
        } finally {
            // Always re-enable the button whether the call succeeded or failed
            chatbotSendBtn.setDisabled(false);
        }
    }

    // Creates a right-aligned (user) chat bubble and adds it to the chat area
    private void appendUserMessage(String text) {
        Div msg = new Div();
        msg.setSclass("cts-chat-msg cts-chat-msg-user");
        Label lbl = new Label(text);
        msg.appendChild(lbl);
        chatbotBody.appendChild(msg);
        scrollToBottom();
    }

    // Creates a left-aligned (bot) chat bubble and adds it to the chat area
    private void appendBotMessage(String text) {
        Div msg = new Div();
        msg.setSclass("cts-chat-msg cts-chat-msg-bot");
        Label lbl = new Label(text);
        msg.appendChild(lbl);
        chatbotBody.appendChild(msg);
        scrollToBottom();
    }

    /**
     * Scrolls the chat area to show the latest message.
     * Uses a small JS snippet since ZK does not have a built-in scroll-to-bottom component.
     */
    private void scrollToBottom() {
        org.zkoss.zk.ui.util.Clients.evalJavaScript(
                "var b=document.getElementById('chatbotBody'); if(b) b.scrollTop = b.scrollHeight;");
    }
}
