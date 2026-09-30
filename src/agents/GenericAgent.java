package agents;

import llm.LLMClient;
import model.Draft;
import model.Email;

import java.util.List;

/**
 * A general-purpose agent, configured only with data: name, description, keywords,
 * system prompt and (optionally) one context file.
 *
 * It has two jobs:
 *  - the default "GenericAgent": safety net when no other agent is suitable
 *    (spam, misdirected emails, questions outside every category). It has NO access
 *    to privileged data (patients, calendar, insurance), so a routing error that ends
 *    here can never leak information.
 *  - the NEW agents created by the user from the GUI ("Agents..." window): they are
 *    GenericAgent objects with another name, prompt and context file. This is how the
 *    framework lets you add an agent WITHOUT writing Java code.
 */
public class GenericAgent extends AbstractAgent {

    private final String contextFile;   // null = no context file

    public GenericAgent(String name, String description, List<String> keywords, String contextFile,
                        PromptRepository prompts, LLMClient llm) {
        super(name, description, keywords, prompts, llm);
        this.contextFile = contextFile;
    }

    @Override
    protected String buildContext(Email email, Draft draft) {
        if (contextFile == null || contextFile.isBlank()) {
            return "No privileged information (patients, calendar, insurance) is available to this agent.";
        }
        draft.addNote("Context file used: " + contextFile);
        return readFile(contextFile);
    }
}
