# Medical practice mailbox - a multi-agent email assistant with a local LLM

Java multi-agent system that helps the front desk of a private medical practice answer emails.
A **routing agent** chooses the best **specialized agent** for each email; the agent writes a
**proposed reply** with a **local LLM** (LM Studio); a **human** always accepts, edits or rejects it.
Nothing is sent automatically, and no data leaves the computer (health data is a "special
category" of personal data, GDPR art. 9).

```
Email --> RoutingAgent --> specialized agent --> local LLM --> RoutingAgent --> human reviewer
            (chain:                (system prompt                               accept / edit /
       emergency rules ->          + own context:                               reject + feedback
       LLM classifier ->           files, calendar,                             -> new draft
       keyword rules ->            registry, RAG)
       GenericAgent)
```

## Requirements

- **Java JDK 17 or newer** (tested with JDK 21). No external library, no Maven: only the JDK.
- **LM Studio** (https://lmstudio.ai) with two models downloaded and loaded:
  - chat model: `llama-3.2-3b-instruct`
  - embedding model: `text-embedding-nomic-embed-text-v1.5`
- In LM Studio: *Developer -> Local Server -> Start* (default address `http://localhost:1234`).
  A context length of at least 4096 tokens is enough (the biggest prompt is ~1,500 tokens).

## Compile and run (Windows PowerShell, from the `src` folder)

```
cd src
javac -d out model\*.java llm\*.java calendar\*.java rag\*.java agents\*.java *.java
java -cp out Main            # graphical interface (default)
java -cp out Main console    # same workflow in the terminal
java -cp out Main eval       # experiment: routing accuracy on the 26 test emails
```
On Linux/macOS use `/` instead of `\`. The program must be started from `src` (data is read from `../data/`).

## Project structure

```
PROJECT/
  README.md
  data/                              everything the program reads (and the history it writes)
    mailbox_test_dataset.json          26 test emails (+ expectedAgent, used ONLY by the experiment)
    system_prompts.md                  system prompt of every agent (editable, also from the GUI)
    context_patient_agent.md           practice FAQ and policies            -> PatientAgent
    context_doctor_agent.md            specialists + VERIFIED external doctors -> DoctorAgent
    context_lab_agent.md               lab rules, turnaround times          -> LabAgent
    doctors_schedule.csv               weekly schedule of the 4 doctors     -> calendar
    appointments.csv                   booked appointments                  -> calendar
    insurance_patient_registry.md      insured patients (exact lookup)      -> InsuranceAgent
    lab_records_metadata.md            lab records, metadata only           -> LabAgent
    healthplus/lifesecure/wellcare_member_guide.md   insurers' guides (RAG) -> InsuranceAgent
    conversation_history.txt           created automatically: replies actually sent
  src/
    Main.java                        creates the system (factory) and starts GUI / console / eval
    MailboxGui.java                  Swing interface
    model/      Email, Appointment, DoctorSchedule, Draft, EmailLoader, ConversationMemory
    llm/        LLMClient (interface), LMStudioClient (adapter to LM Studio)
    calendar/   AppointmentCalendar (availability computed in Java, not by the LLM)
    rag/        EmbeddingClient, LMStudioEmbeddingClient, DocumentChunk, DocumentRetriever
    agents/     Agent, AbstractAgent, the 6 agents, RoutingAgent, routing strategies, PromptRepository
```

## The agents

| Agent | Type | Its context | Special mechanism |
|---|---|---|---|
| RoutingAgent | coordinator | descriptions of the agents | chain of responsibility + strategies, memory |
| EmergencyAgent | **reactive** (no LLM) | red-flag rules | fixed reply, always checked first |
| PatientAgent | cognitive | FAQ + calendar facts | free slots computed in Java; never medical advice |
| DoctorAgent | cognitive | specialists + calendar | **identity check** (name + official domain) against impersonation |
| InsuranceAgent | cognitive | registry + **RAG** on guides | member check by email; RAG only in the right insurer's guide |
| LabAgent | cognitive | lab rules + records metadata | record check; **privacy guard** removes clinical values from the draft |
| GenericAgent | cognitive | nothing privileged | safety net; also the class of agents created from the GUI |

## How to...

- **Change the model or the engine**: the constants at the top of `Main.java` (URL, model names).
  For another engine (Ollama, llama.cpp...), write a class that implements `LLMClient`
  (and `EmbeddingClient`) and create it in `Main.createSystem()`: no agent changes.
- **Change a prompt**: edit `data/system_prompts.md`, or use *Agents... -> Save system prompt* in the GUI.
- **Change the knowledge of an agent**: edit its file in `data/` (read again at every email).
- **Add an agent without code**: GUI -> *Agents... -> New agent...* (name, description, keywords,
  prompt, optional context file). The router can choose it immediately.
- **Add an agent with its own logic**: a class that extends `AbstractAgent` (implement
  `buildContext`), plus one line in `Main.createSystem()`.
- **Add a routing strategy**: a class that implements `RoutingStrategy`, added to the list in `Main.createSystem()`.

## Course concepts and design patterns in the code

| Concept | Where |
|---|---|
| Reactive vs cognitive agents, heterogeneous MAS | `EmergencyAgent` (rules, no LLM) vs the LLM agents |
| Centralized / hierarchical organization | `RoutingAgent` coordinates, specialists never talk to each other |
| Manipulation: identity spoofing / Sybil, "certified message origin" | `DoctorAgent.checkIdentity`, member check in `InsuranceAgent` |
| Normative idea: detect a violation and change the outcome | warnings to the reviewer, refusal to share data, privacy guard in `LabAgent` |
| Environment (shared resource) | `AppointmentCalendar`, read by PatientAgent and DoctorAgent |
| Strategy | `RoutingStrategy`: `LlmRoutingStrategy`, `KeywordRoutingStrategy` |
| Chain of Responsibility | `RoutingAgent.routeWith`: emergency -> strategies -> fallback |
| Template Method | `AbstractAgent.handle` (buildContext / checkReply hooks) |
| Adapter | `LMStudioClient`, `LMStudioEmbeddingClient` |
| Mediator / Facade | `RoutingAgent` (between the user interface and the agents) |
| Repository | `PromptRepository`, `ConversationMemory` |
| Factory (composition root) | `Main.createSystem` |

## Known limitations

- A 3B model can misroute ambiguous emails and write imperfect drafts: this is why the human
  review is mandatory and why the reviewer can change the agent by hand.
- Emergency detection uses fixed patterns: an emergency described with unusual words can be missed.
- Bookings proposed in a reply are not written back to `appointments.csv`.
- Agents created from the GUI keep their prompt in `system_prompts.md`, but the agent itself
  (description, keywords, context file) is not recreated at the next start.
- "Today" is fixed to 5 October 2026 (`AppointmentCalendar.TODAY`) so that tests are reproducible.
