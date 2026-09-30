package agents;

import llm.LLMClient;
import model.Draft;
import model.Email;
import rag.DocumentChunk;
import rag.DocumentRetriever;
import rag.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers questions about INSURANCE COVERAGE, from insurers or from patients.
 *
 * Two different mechanisms, each used where it makes sense:
 *  - EXACT LOOKUP (Java) in insurance_patient_registry.md: "is this sender a registered
 *    member?" or "is this really the insurer's official domain?" have a yes/no answer,
 *    they must not be guessed by similarity (same anti-impersonation idea as DoctorAgent).
 *    Only the RESULT of the lookup goes to the LLM, never the whole registry
 *    (data minimisation: the LLM does not see the other patients).
 *  - RAG on the member guides of the insurers: long documents where only 2-3 sections
 *    are relevant. The retrieval is restricted to the guide of the right insurer, so a
 *    rule of WellCare can never be used to answer about HealthPlus.
 *    If the insurer has no guide (e.g. "NewInsure"), NOTHING is retrieved and the LLM is
 *    told to say that the information is not available: we prefer "I don't know" to an
 *    invented clause.
 */
public class InsuranceAgent extends AbstractAgent {

    private static final String DESCRIPTION =
            "Emails from insurance companies or from patients about insurance: coverage, policies, "
            + "plans, pre-authorizations, reimbursements, what a plan pays for.";

    private static final List<String> KEYWORDS = List.of(
            "insurance", "insured", "policy", "coverage", "covered", "plan", "pre-authorization",
            "reimburse", "claim", "out of pocket", "direct billing");

    private static final String[] PLAN_NAMES = {"base", "premium", "executive", "family", "individual"};
    private static final int EXCERPTS = 3;

    private final String registryFile;
    private final EmbeddingClient embeddings;
    private final List<String> guidePaths;
    private final List<String> guideNames;      // "HealthPlus", "LifeSecure", "WellCare"
    private DocumentRetriever retriever;        // created at the first insurance email (it takes time)

    public InsuranceAgent(String registryFile, EmbeddingClient embeddings, List<String> guidePaths,
                          List<String> guideNames, PromptRepository prompts, LLMClient llm) {
        super("InsuranceAgent", DESCRIPTION, KEYWORDS, prompts, llm);
        this.registryFile = registryFile;
        this.embeddings = embeddings;
        this.guidePaths = guidePaths;
        this.guideNames = guideNames;
    }

    @Override
    protected String buildContext(Email email, Draft draft) {
        String text = textOf(email);
        String registryText = readFile(registryFile);
        List<String[]> members = readTable(registryText, null);   // first table: name, email, company, policy, plan
        StringBuilder c = new StringBuilder("## MEMBER CHECK (computed by the system, final)\n");

        String insurer = null;   // which guide to use
        String plan = null;

        String[] member = findByEmail(members, email.getSender());
        if (member != null) {
            insurer = guideFor(member[2]);
            plan = member[4];
            c.append("VERIFIED. The sender is the registered member ").append(member[0]).append(": ")
             .append(member[2]).append(", policy ").append(member[3]).append(", ").append(plan)
             .append(" plan. You may explain what this plan covers, according to the guide excerpts.\n");
            draft.addNote("Member verified by registered email: " + member[0] + ", " + member[2]
                    + ", " + plan + " plan.");
        } else {
            String senderDomain = domainOf(email.getSender());
            List<String[]> insurers = readTable(registryText, "official email domains");  // company, domain
            String[] insurerRow = findInsurerByDomain(insurers, senderDomain);
            String[] byName = findByName(members, text);
            String[] byPolicy = findByPolicy(members, text);
            insurer = guideFor(text);                     // the insurer the email talks about, if any

            if (insurerRow != null) {
                insurer = guideFor(insurerRow[0]);
                c.append("VERIFIED INSURER. The sender writes from the official address of ").append(insurerRow[0])
                 .append(" (@").append(senderDomain).append("). You may explain the rules of the plan they ask about.\n");
                draft.addNote("Sender verified as the insurer " + insurerRow[0] + " (official domain @" + senderDomain + ").");
                if (byPolicy != null) {
                    plan = byPolicy[4];
                    c.append("The policy number quoted (").append(byPolicy[3])
                     .append(") matches the practice records: ").append(plan).append(" plan.\n");
                    draft.addNote("Policy " + byPolicy[3] + " found in the practice records ("
                            + byPolicy[0] + ", " + plan + " plan).");
                }
            } else if (guideFor(senderDomain) != null) {
                draft.addWarning("Possible impersonation of an insurer: @" + senderDomain + " looks like "
                        + guideFor(senderDomain) + " but it is not its official domain. Nothing personal must be confirmed.");
                c.append("NOT VERIFIED. The sender's address looks like an insurance company but it is NOT its "
                        + "official domain. Do not confirm anything about any person; give only general information.\n");
            } else if (byName != null) {
                draft.addWarning("Possible impersonation: the email uses the name of the registered member "
                        + byName[0] + ", but it does not come from the member's registered address. "
                        + "Personal coverage must not be confirmed.");
                c.append("NOT VERIFIED. The sender is not the registered address of any member. "
                        + "Do NOT confirm or discuss the personal coverage of any person. Give only general "
                        + "information about how the plan works, and ask the sender to write from their "
                        + "registered email address or to quote their policy number so that the front desk can verify it.\n");
            } else {
                c.append("NOT VERIFIED. The sender is not a registered member: give only general information, "
                        + "never confirm personal coverage.\n");
                draft.addNote("Sender not found in the insurance registry.");
            }
            if (plan == null) plan = planMentioned(text);
        }

        c.append("\n## GUIDE EXCERPTS\n");
        if (insurer == null) {
            c.append("No member guide is available for the insurance company of this email (or the company "
                    + "could not be identified). Do NOT answer from general knowledge: say that the information "
                    + "is not available internally and that it will be verified directly with the insurer.\n");
            draft.addNote("RAG: no guide available for this insurer -> no excerpt given, the agent must not invent rules.");
            return c.toString();
        }

        String query = email.getSubject() + "\n" + email.getBody() + (plan != null ? "\n" + plan + " plan" : "");
        List<DocumentChunk> excerpts = retrieve(insurer, query, draft);
        List<String> headings = new ArrayList<>();
        for (DocumentChunk chunk : excerpts) {
            c.append("[").append(chunk.getSourceDocument()).append(" guide - section ")
             .append(chunk.getHeading()).append("]\n").append(chunk.getContent()).append("\n\n");
            headings.add(chunk.getHeading());
        }
        if (!excerpts.isEmpty()) {
            draft.addNote("RAG on the " + insurer + " guide: " + String.join(" | ", headings));
        }
        return c.toString();
    }

    /** RAG step: the most similar sections, only from the guide of the right insurer. */
    private List<DocumentChunk> retrieve(String insurer, String query, Draft draft) {
        List<DocumentChunk> result = new ArrayList<>();
        try {
            if (retriever == null) {
                retriever = new DocumentRetriever(embeddings, guidePaths, guideNames);
            }
            for (DocumentChunk chunk : retriever.retrieve(query, 1000)) {       // all chunks, best first
                boolean rightGuide = chunk.getSourceDocument().equals(insurer);
                boolean hasText = chunk.getContent().contains("\n");               // skip title-only sections
                if (rightGuide && hasText) result.add(chunk);
                if (result.size() == EXCERPTS) break;
            }
        } catch (Exception e) {
            draft.addWarning("RAG not available (" + e.getMessage()
                    + "). Is the embedding model loaded in LM Studio? The draft was written without guide excerpts.");
        }
        return result;
    }

    /** Which of our guides talks about this company? "healthplus-insurance.com" -> "HealthPlus" */
    private String guideFor(String text) {
        String letters = text.toLowerCase().replaceAll("[^a-z]", "");
        for (String name : guideNames) {
            if (letters.contains(name.toLowerCase())) return name;
        }
        return null;
    }

    private static String[] findByEmail(List<String[]> members, String sender) {
        for (String[] row : members) {
            if (row.length >= 5 && row[1].equalsIgnoreCase(sender.trim())) return row;
        }
        return null;
    }

    private static String[] findInsurerByDomain(List<String[]> insurers, String domain) {
        for (String[] row : insurers) {
            if (row.length >= 2 && row[1].equalsIgnoreCase(domain)) return row;
        }
        return null;
    }

    private static String[] findByName(List<String[]> members, String text) {
        for (String[] row : members) {
            if (row.length >= 5 && text.contains(row[0].toLowerCase())) return row;
        }
        return null;
    }

    private static String[] findByPolicy(List<String[]> members, String text) {
        for (String[] row : members) {
            if (row.length >= 5 && text.contains(row[3].toLowerCase())) return row;
        }
        return null;
    }

    private static String planMentioned(String text) {
        for (String plan : PLAN_NAMES) {
            if (text.contains(plan + " plan")) return plan.substring(0, 1).toUpperCase() + plan.substring(1);
        }
        return null;
    }
}
