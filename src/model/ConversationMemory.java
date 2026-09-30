package model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory of the conversations (assignment, section 9): for each sender, the emails
 * received and the replies ACTUALLY SENT (accepted by the human).
 *
 * - Persistent: every sent reply is appended to a text file, reloaded at startup.
 * - Limited: an agent receives only the last few exchanges, each one shortened,
 *   so we do not send too much context to a small local LLM.
 */
public class ConversationMemory {

    private static final int MAX_CHARS_PER_TEXT = 400;

    /** One email received + the reply that was sent. */
    private static class Exchange {
        final String agentName;
        final String subject;
        final String received;
        final String reply;

        Exchange(String agentName, String subject, String received, String reply) {
            this.agentName = agentName;
            this.subject = subject;
            this.received = received;
            this.reply = reply;
        }
    }

    private final String filePath;
    private final Map<String, List<Exchange>> bySender = new HashMap<>();

    public ConversationMemory(String filePath) {
        this.filePath = filePath;
        load();
    }

    /** Called when the human accepts a reply: it is "sent" and remembered. */
    public void record(Email email, String agentName, String sentReply) {
        Exchange exchange = new Exchange(agentName, email.getSubject(), email.getBody(), sentReply);
        remember(email.getSender(), exchange);

        String line = String.join("\t", email.getSender(), agentName,
                flat(email.getSubject()), flat(email.getBody()), flat(sentReply)) + System.lineSeparator();
        try {
            Files.writeString(Path.of(filePath), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not save the conversation history: " + e.getMessage());
        }
    }

    public int count(String sender) {
        return bySender.getOrDefault(sender.toLowerCase(), List.of()).size();
    }

    /** The last 'max' exchanges with this sender, as text for the LLM ("" if none). */
    public String historyFor(String sender, int max) {
        List<Exchange> all = bySender.getOrDefault(sender.toLowerCase(), List.of());
        if (all.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Exchange e : all.subList(Math.max(0, all.size() - max), all.size())) {
            sb.append("- They wrote (subject \"").append(e.subject).append("\"): ")
              .append(shorten(e.received)).append("\n")
              .append("  We replied (").append(e.agentName).append("): ")
              .append(shorten(e.reply)).append("\n");
        }
        return sb.toString();
    }

    private void remember(String sender, Exchange exchange) {
        bySender.computeIfAbsent(sender.toLowerCase(), k -> new ArrayList<>()).add(exchange);
    }

    private void load() {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) return;
        try {
            for (String line : Files.readAllLines(path)) {
                String[] f = line.split("\t", -1);
                if (f.length < 5) continue;
                remember(f[0], new Exchange(f[1], unflat(f[2]), unflat(f[3]), unflat(f[4])));
            }
        } catch (IOException e) {
            System.err.println("Could not read the conversation history: " + e.getMessage());
        }
    }

    private static String shorten(String text) {
        String oneLine = text.replace("\n", " ");
        return oneLine.length() <= MAX_CHARS_PER_TEXT ? oneLine : oneLine.substring(0, MAX_CHARS_PER_TEXT) + "...";
    }

    /** One exchange = one line of the file: new lines are written as \n, tabs become spaces. */
    private static String flat(String text) {
        return text.replace("\r", "").replace("\t", " ").replace("\n", "\\n");
    }

    private static String unflat(String text) {
        return text.replace("\\n", "\n");
    }
}
