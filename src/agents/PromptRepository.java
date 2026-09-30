package agents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REPOSITORY of the system prompts, stored in data/system_prompts.md
 * (one section "## AgentName" per agent).
 *
 * Agents ask for their prompt every time they write a draft, so a prompt edited
 * in the file or in the GUI is used immediately, without changing the code
 * (assignment, section 5.1: "the user should be able to modify these instructions").
 */
public class PromptRepository {

    private final String filePath;
    private String header = "";
    private final Map<String, String> prompts = new LinkedHashMap<>();

    public PromptRepository(String filePath) throws IOException {
        this.filePath = filePath;
        load();
    }

    public String get(String agentName) {
        return prompts.getOrDefault(agentName, "");
    }

    public void set(String agentName, String prompt) {
        prompts.put(agentName, prompt.trim());
    }

    /** Writes all prompts back to the file (used by the GUI). */
    public void save() throws IOException {
        StringBuilder sb = new StringBuilder(header).append("\n\n");
        for (Map.Entry<String, String> entry : prompts.entrySet()) {
            sb.append("## ").append(entry.getKey()).append("\n")
              .append(entry.getValue()).append("\n\n");
        }
        Files.writeString(Path.of(filePath), sb.toString());
    }

    private void load() throws IOException {
        List<String> lines = Files.readAllLines(Path.of(filePath));
        StringBuilder headerText = new StringBuilder();
        StringBuilder current = new StringBuilder();
        String currentName = null;

        for (String line : lines) {
            if (line.startsWith("## ")) {                  // a new agent section starts
                if (currentName != null) prompts.put(currentName, current.toString().trim());
                currentName = line.substring(3).trim();
                current = new StringBuilder();
            } else if (currentName == null) {
                headerText.append(line).append("\n");      // explanation at the top of the file
            } else {
                current.append(line).append("\n");
            }
        }
        if (currentName != null) prompts.put(currentName, current.toString().trim());
        header = headerText.toString().trim();
    }
}
