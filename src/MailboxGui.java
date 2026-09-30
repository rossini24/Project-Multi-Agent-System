import agents.Agent;
import agents.EmergencyAgent;
import agents.GenericAgent;
import agents.PromptRepository;
import agents.RoutingAgent;
import llm.LLMClient;
import model.Draft;
import model.Email;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Graphical interface (Swing, included in the JDK). It lets the user (assignment, section 10):
 *  - see the received emails and the agent chosen by the router (with the reason),
 *  - read the proposed reply and the notes for the reviewer (verifications, RAG sources, warnings),
 *  - ACCEPT (possibly after editing), or REJECT with feedback -> a new draft is written,
 *  - change the agent by hand if the routing is wrong,
 *  - see the agents, edit their system prompt, and create a NEW agent without code.
 *
 * The interface only talks to the RoutingAgent (facade): it never calls an LLM itself.
 * LLM calls run in a background thread (SwingWorker) so the window does not freeze.
 */
public class MailboxGui extends JFrame {

    private final RoutingAgent router;
    private final PromptRepository prompts;
    private final LLMClient llmForNewAgents;
    private final List<Email> emails;

    private final DefaultListModel<String> inboxModel = new DefaultListModel<>();
    private final JList<String> inbox = new JList<>(inboxModel);
    private final JTextArea emailView = readOnlyArea();
    private final JComboBox<String> agentBox = new JComboBox<>();
    private final JLabel routingLabel = new JLabel(" ");
    private final JTextArea notesView = readOnlyArea();
    private final JTextArea draftArea = new JTextArea();
    private final JTextField feedbackField = new JTextField();
    private final JLabel statusLabel = new JLabel("Select an email, then click \"Route and draft\".");
    private final JButton routeButton = new JButton("Route and draft");
    private final JButton regenerateButton = new JButton("Reject and regenerate");
    private final JButton acceptButton = new JButton("Accept and send");

    private final Map<Integer, Draft> drafts = new HashMap<>();   // current draft of each email
    private final Set<Integer> sent = new HashSet<>();
    private boolean busy = false;                                  // true while the LLM is working

    public static void open(RoutingAgent router, PromptRepository prompts, LLMClient llm, List<Email> emails) {
        SwingUtilities.invokeLater(() -> new MailboxGui(router, prompts, llm, emails).setVisible(true));
    }

    private MailboxGui(RoutingAgent router, PromptRepository prompts, LLMClient llm, List<Email> emails) {
        super("Medical practice mailbox - multi-agent assistant (local LLM)");
        this.router = router;
        this.prompts = prompts;
        this.llmForNewAgents = llm;
        this.emails = new ArrayList<>(emails);

        for (int i = 0; i < this.emails.size(); i++) inboxModel.addElement(inboxLabel(i));
        refreshAgentBox();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildInboxPanel(), buildWorkPanel());
        split.setDividerLocation(360);
        add(split);

        inbox.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelectedEmail(); });
        routeButton.addActionListener(e -> routeAndDraft());
        regenerateButton.addActionListener(e -> regenerate());
        acceptButton.addActionListener(e -> accept());
        setButtons(false);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1250, 820);
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------ layout

    private JPanel buildInboxPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Inbox"));
        inbox.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(inbox), BorderLayout.CENTER);

        JButton newEmail = new JButton("New email...");
        JButton agents = new JButton("Agents...");
        newEmail.addActionListener(e -> newEmailDialog());
        agents.addActionListener(e -> agentsDialog());
        JPanel buttons = new JPanel(new GridLayout(1, 2, 5, 5));
        buttons.add(newEmail);
        buttons.add(agents);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildWorkPanel() {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JScrollPane emailScroll = new JScrollPane(emailView);
        emailScroll.setBorder(BorderFactory.createTitledBorder("Received email"));
        emailScroll.setPreferredSize(new Dimension(800, 170));
        top.add(emailScroll);

        JPanel routingRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        routingRow.add(routeButton);
        routingRow.add(new JLabel("   Agent:"));
        routingRow.add(agentBox);
        top.add(routingRow);

        JPanel routingInfo = new JPanel(new BorderLayout());
        routingInfo.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        routingInfo.add(routingLabel, BorderLayout.CENTER);
        top.add(routingInfo);

        JScrollPane notesScroll = new JScrollPane(notesView);
        notesScroll.setBorder(BorderFactory.createTitledBorder("Notes for the reviewer"));
        notesScroll.setPreferredSize(new Dimension(800, 120));
        top.add(notesScroll);

        draftArea.setLineWrap(true);
        draftArea.setWrapStyleWord(true);
        draftArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        JScrollPane draftScroll = new JScrollPane(draftArea);
        draftScroll.setBorder(BorderFactory.createTitledBorder("Proposed reply (you can edit it before accepting)"));

        JPanel feedbackRow = new JPanel(new BorderLayout(5, 5));
        feedbackRow.add(new JLabel("Feedback for a new draft: "), BorderLayout.WEST);
        feedbackRow.add(feedbackField, BorderLayout.CENTER);
        JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actionButtons.add(regenerateButton);
        actionButtons.add(acceptButton);
        feedbackRow.add(actionButtons, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout(5, 5));
        bottom.add(feedbackRow, BorderLayout.NORTH);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        bottom.add(statusLabel, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(top, BorderLayout.NORTH);
        panel.add(draftScroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    // ------------------------------------------------------------------ actions

    private void showSelectedEmail() {
        int i = inbox.getSelectedIndex();
        if (i < 0) return;
        Email email = emails.get(i);
        emailView.setText("From: " + email.getSender() + "\nSubject: " + email.getSubject() + "\n\n" + email.getBody());
        emailView.setCaretPosition(0);
        feedbackField.setText("");
        Draft draft = drafts.get(i);
        if (draft != null) {
            showDraft(draft);
        } else {
            routingLabel.setText(" ");
            notesView.setText("");
            draftArea.setText("");
        }
        setButtons(true);
    }

    private void routeAndDraft() {
        int i = inbox.getSelectedIndex();
        if (i < 0) return;
        Email email = emails.get(i);
        runInBackground(i, "The router and the agent are working (the local LLM can take some seconds)...",
                () -> router.process(email));
    }

    private void regenerate() {
        int i = inbox.getSelectedIndex();
        if (i < 0) return;
        Email email = emails.get(i);
        Agent agent = router.findAgent((String) agentBox.getSelectedItem());
        Draft previous = drafts.get(i);
        String comments = feedbackField.getText();

        if (previous == null) {                     // no draft yet: the reviewer chose the agent by hand
            runInBackground(i, "Writing a draft with " + agent.getName() + "...", () -> {
                Draft d = router.draftWith(email, agent, "");
                d.setRoutingInfo(agent.getName() + " - chosen by the human reviewer");
                return d;
            });
        } else {
            runInBackground(i, "Writing a new draft with your feedback...",
                    () -> router.revise(email, agent, previous, comments));
        }
    }

    private void accept() {
        int i = inbox.getSelectedIndex();
        Draft draft = drafts.get(i);
        String text = draftArea.getText().trim();
        if (i < 0 || draft == null || text.isEmpty()) {
            JOptionPane.showMessageDialog(this, "There is no reply to send yet.");
            return;
        }
        boolean edited = !text.equals(draft.getText().trim());
        router.accept(emails.get(i), draft.getAgentName() + (edited ? " (edited)" : ""), text);
        sent.add(i);
        inboxModel.set(i, inboxLabel(i));
        statusLabel.setText("Reply to " + emails.get(i).getSender() + " SENT"
                + (edited ? " (edited by you)" : "") + " and saved in the conversation history.");
    }

    /** Runs an LLM task in a background thread, then shows the draft. */
    private void runInBackground(int index, String message, Supplier<Draft> task) {
        busy = true;
        setButtons(false);
        statusLabel.setText(message);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<Draft, Void>() {
            @Override
            protected Draft doInBackground() {
                return task.get();
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                busy = false;
                setButtons(true);
                try {
                    Draft draft = get();
                    drafts.put(index, draft);
                    inboxModel.set(index, inboxLabel(index));
                    if (inbox.getSelectedIndex() == index) showDraft(draft);
                    statusLabel.setText("Draft ready: read it, then accept it or give feedback and regenerate.");
                } catch (Exception ex) {
                    Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    statusLabel.setText("Error: " + cause.getMessage());
                    JOptionPane.showMessageDialog(MailboxGui.this, cause.getMessage()
                                    + "\n\nCheck that LM Studio is open, the server is started and the models are loaded.",
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void showDraft(Draft draft) {
        agentBox.setSelectedItem(draft.getAgentName());
        routingLabel.setText("<html><body style='width:780px'><b>Routing:</b> "
                + escapeHtml(draft.getRoutingInfo()) + "</body></html>");
        notesView.setText(String.join("\n", draft.getNotes()));
        notesView.setForeground(draft.hasWarning() ? new Color(180, 0, 0) : Color.DARK_GRAY);
        notesView.setCaretPosition(0);
        draftArea.setText(draft.getText());
        draftArea.setCaretPosition(0);
    }

    private void setButtons(boolean enabled) {
        boolean ok = enabled && !busy && inbox.getSelectedIndex() >= 0;
        routeButton.setEnabled(ok);
        regenerateButton.setEnabled(ok);
        acceptButton.setEnabled(ok);
    }

    private String inboxLabel(int i) {
        Email email = emails.get(i);
        String state = sent.contains(i) ? "[SENT] "
                : drafts.containsKey(i) ? "[" + drafts.get(i).getAgentName() + "] " : "";
        return "#" + (i + 1) + "  " + state + email.getSubject();
    }

    private void refreshAgentBox() {
        agentBox.removeAllItems();
        for (Agent agent : router.getAllAgents()) agentBox.addItem(agent.getName());
    }

    // ------------------------------------------------------------------ dialogs

    /** Simulates the arrival of a new email (useful to show the conversation memory). */
    private void newEmailDialog() {
        JTextField sender = new JTextField();
        JTextField subject = new JTextField();
        JTextArea body = new JTextArea(8, 40);
        body.setLineWrap(true);
        JPanel form = new JPanel(new BorderLayout(5, 5));
        JPanel fields = new JPanel(new GridLayout(4, 1, 2, 2));
        fields.add(new JLabel("From (email address):"));
        fields.add(sender);
        fields.add(new JLabel("Subject:"));
        fields.add(subject);
        form.add(fields, BorderLayout.NORTH);
        form.add(new JScrollPane(body), BorderLayout.CENTER);

        int ok = JOptionPane.showConfirmDialog(this, form, "New incoming email", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION || sender.getText().isBlank()) return;
        emails.add(new Email(sender.getText().trim(), subject.getText().trim(), body.getText().trim()));
        inboxModel.addElement(inboxLabel(emails.size() - 1));
        inbox.setSelectedIndex(emails.size() - 1);
    }

    /** See the agents, edit their system prompt, create a new one. */
    private void agentsDialog() {
        JDialog dialog = new JDialog(this, "Agents of the organization", true);
        DefaultListModel<String> names = new DefaultListModel<>();
        names.addElement("RoutingAgent");
        for (Agent agent : router.getAllAgents()) names.addElement(agent.getName());
        JList<String> list = new JList<>(names);

        JTextArea info = readOnlyArea();
        info.setRows(5);
        JTextArea promptArea = new JTextArea();
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);

        list.addListSelectionListener(e -> {
            String name = list.getSelectedValue();
            if (name == null) return;
            info.setText(describe(name));
            info.setCaretPosition(0);
            promptArea.setText(prompts.get(name));
            promptArea.setCaretPosition(0);
        });

        JButton save = new JButton("Save system prompt");
        save.addActionListener(e -> {
            String name = list.getSelectedValue();
            if (name == null) return;
            try {
                prompts.set(name, promptArea.getText());
                prompts.save();
                JOptionPane.showMessageDialog(dialog, "Saved in data/system_prompts.md. It is used from the next draft.");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Could not save: " + ex.getMessage());
            }
        });
        JButton create = new JButton("New agent...");
        create.addActionListener(e -> {
            String created = newAgentDialog(dialog);
            if (created != null) {
                names.addElement(created);
                list.setSelectedValue(created, true);
            }
        });

        JPanel right = new JPanel(new BorderLayout(5, 5));
        JScrollPane infoScroll = new JScrollPane(info);
        infoScroll.setBorder(BorderFactory.createTitledBorder("Agent"));
        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptScroll.setBorder(BorderFactory.createTitledBorder("System prompt (editable)"));
        right.add(infoScroll, BorderLayout.NORTH);
        right.add(promptScroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(create);
        buttons.add(save);
        right.add(buttons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), right);
        split.setDividerLocation(200);
        dialog.add(split);
        dialog.setSize(900, 600);
        dialog.setLocationRelativeTo(this);
        list.setSelectedIndex(0);
        dialog.setVisible(true);
    }

    private String describe(String name) {
        if (name.equals("RoutingAgent")) {
            return "The coordinator. It does not write replies: it chooses the agent.\n"
                    + "Chain: emergency rules -> LLM classifier -> keyword rules -> GenericAgent.\n"
                    + "Its prompt is used by the LLM classifier ({AGENTS} = list of the agents).";
        }
        Agent agent = router.findAgent(name);
        String type = (agent instanceof EmergencyAgent)
                ? "Reactive agent: fixed rules, NO LLM. The text below is its fixed reply."
                : "Cognitive agent: system prompt + own context + local LLM.";
        return "Description: " + agent.getDescription() + "\nKeywords: " + String.join(", ", agent.getKeywords())
                + "\n" + type;
    }

    /** Creates a new specialized agent at runtime: a GenericAgent with its own prompt and context. */
    private String newAgentDialog(Component parent) {
        JTextField name = new JTextField();
        JTextField description = new JTextField();
        JTextField keywords = new JTextField();
        JTextField contextFile = new JTextField();
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser("../data");
            if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                contextFile.setText(chooser.getSelectedFile().getPath());
            }
        });
        JTextArea prompt = new JTextArea(8, 40);
        prompt.setLineWrap(true);
        prompt.setText("You are the front-desk assistant of a private medical practice. You handle ...\n"
                + "Tone: ...\nRules:\n- ...\nFormat: maximum 150 words, signed \"The Front Desk\".");

        JPanel fileRow = new JPanel(new BorderLayout(5, 5));
        fileRow.add(contextFile, BorderLayout.CENTER);
        fileRow.add(browse, BorderLayout.EAST);
        JPanel fields = new JPanel(new GridLayout(8, 1, 2, 2));
        fields.add(new JLabel("Name (one word, e.g. PharmacyAgent):"));
        fields.add(name);
        fields.add(new JLabel("Description (the router reads it to choose the agent):"));
        fields.add(description);
        fields.add(new JLabel("Keywords (comma separated):"));
        fields.add(keywords);
        fields.add(new JLabel("Context file (optional):"));
        fields.add(fileRow);
        JPanel form = new JPanel(new BorderLayout(5, 5));
        form.add(fields, BorderLayout.NORTH);
        JScrollPane promptScroll = new JScrollPane(prompt);
        promptScroll.setBorder(BorderFactory.createTitledBorder("System prompt"));
        form.add(promptScroll, BorderLayout.CENTER);

        int ok = JOptionPane.showConfirmDialog(parent, form, "New specialized agent", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return null;

        String agentName = name.getText().trim();
        if (!agentName.matches("[A-Za-z][A-Za-z0-9]*") || router.findAgent(agentName) != null
                || agentName.equals("RoutingAgent")) {
            JOptionPane.showMessageDialog(parent, "Invalid or already used name (one word, letters and digits).");
            return null;
        }
        List<String> keywordList = new ArrayList<>();
        for (String k : Arrays.asList(keywords.getText().split(","))) {
            if (!k.isBlank()) keywordList.add(k.trim().toLowerCase());
        }
        String file = contextFile.getText().isBlank() ? null : contextFile.getText().trim();

        prompts.set(agentName, prompt.getText());
        try {
            prompts.save();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Prompt not saved to file: " + ex.getMessage());
        }
        router.addAgent(new GenericAgent(agentName, description.getText().trim(), keywordList, file,
                prompts, llmForNewAgents));
        refreshAgentBox();
        JOptionPane.showMessageDialog(parent, agentName + " created. The router can now choose it.");
        return agentName;
    }

    // ------------------------------------------------------------------ small helpers

    private static JTextArea readOnlyArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
