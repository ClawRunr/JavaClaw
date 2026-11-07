package ai.javaclaw.tasks;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentEvent;
import ai.javaclaw.approval.Denial;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.tasks.TaskHandler.TaskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskHandlerTest {

    @TempDir
    Path workspaceDir;

    @Mock
    Agent agentMock;
    @Mock
    ChannelRegistry channelRegistryMock;

    TaskRepository taskRepository;
    TaskHandler taskHandler;

    @BeforeEach
    void setUp() throws IOException {
        taskRepository = new FileSystemTaskRepository(new FileSystemResource(workspaceDir));
        taskHandler = new TaskHandler(agentMock, taskRepository, channelRegistryMock);
    }

    // -----------------------------------------------------------------------
    // The conversation id the agent is called with
    //
    // A task's id is the absolute path of its markdown file. Handing that to
    // the agent as a conversation id makes the memory repository resolve
    // conversations/chat-<absolute path>.yaml, which spreads one file per task
    // across a directory tree mirroring the filesystem.
    // -----------------------------------------------------------------------

    @Test
    void callsTheAgentWithAConversationIdThatIsNotTheTaskFilePath() {
        Task task = givenATodoTask("Buy milk");
        when(agentMock.prompt(anyString(), anyString(), eq(TaskResult.class), any()))
                .thenReturn(new TaskResult(Task.Status.completed, "done"));

        taskHandler.executeTask(task.getId());

        assertThat(conversationIdPassedToAgent())
                .isNotEqualTo(task.getId())
                .doesNotContain("/")
                .doesNotContain("\\")
                .startsWith("task-");
    }

    @Test
    void derivesTheConversationIdFromTheTasksDateDirectoryAndFileName() {
        Task task = givenATodoTask("Buy milk");
        when(agentMock.prompt(anyString(), anyString(), eq(TaskResult.class), any()))
                .thenReturn(new TaskResult(Task.Status.completed, "done"));

        taskHandler.executeTask(task.getId());

        Path taskFile = Path.of(task.getId());
        String dateDirectory = taskFile.getParent().getFileName().toString();
        String fileName = taskFile.getFileName().toString().replace(".md", "");
        assertThat(conversationIdPassedToAgent()).isEqualTo("task-" + dateDirectory + "-" + fileName);
    }

    // -----------------------------------------------------------------------
    // Blocked tools
    // -----------------------------------------------------------------------

    @Test
    void aTaskWhoseToolWasBlockedReportsTheToolAndWhyAndWaitsForAHuman() {
        Task task = givenATodoTask("Write report");
        when(agentMock.prompt(anyString(), anyString(), eq(TaskResult.class), any())).thenAnswer(invocation -> {
            Consumer<AgentEvent> events = invocation.getArgument(3);
            events.accept(new AgentEvent.ApprovalResolved(null, "call-1", "Write",
                    new Denial(Denial.Reason.NOBODY_TO_ASK, "it needs approval and nobody can be asked here")));
            return new TaskResult(Task.Status.completed, "I could not write the file.");
        });

        taskHandler.executeTask(task.getId());

        Task saved = taskRepository.getTaskById(task.getId());
        assertThat(saved.getStatus()).isEqualTo(Task.Status.awaiting_human_input);
        assertThat(saved.getDescription())
                .contains("Blocked tool calls")
                .contains("Write: not run because it needs approval and nobody can be asked here")
                .contains("I could not write the file.");
    }

    @Test
    void approvedToolsLeaveTheAgentsResultAlone() {
        Task task = givenATodoTask("Write report");
        when(agentMock.prompt(anyString(), anyString(), eq(TaskResult.class), any())).thenAnswer(invocation -> {
            Consumer<AgentEvent> events = invocation.getArgument(3);
            events.accept(new AgentEvent.ApprovalResolved("call-1", "call-1", "Write", null));
            return new TaskResult(Task.Status.completed, "done");
        });

        taskHandler.executeTask(task.getId());

        Task saved = taskRepository.getTaskById(task.getId());
        assertThat(saved.getStatus()).isEqualTo(Task.Status.completed);
        assertThat(saved.getDescription()).endsWith("Agent feedback: done");
    }

    private String conversationIdPassedToAgent() {
        ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(agentMock)
                .prompt(conversationId.capture(), anyString(), eq(TaskResult.class), any());
        return conversationId.getValue();
    }

    private Task givenATodoTask(String name) {
        return taskRepository.save(new Task(null, name, Instant.now(), Task.Status.todo, "Some description"));
    }

    // -----------------------------------------------------------------------
    // Deriving the id, in isolation
    // -----------------------------------------------------------------------

    @Test
    void keepsTheDateDirectorySoTasksOnDifferentDaysDoNotShareAConversation() {
        String monday = TaskHandler.getConversationId("/workspace/tasks/2026-08-10/103000-buy_milk.md");
        String tuesday = TaskHandler.getConversationId("/workspace/tasks/2026-08-11/103000-buy_milk.md");

        assertThat(monday).isEqualTo("task-2026-08-10-103000-buy_milk");
        assertThat(tuesday).isEqualTo("task-2026-08-11-103000-buy_milk");
        assertThat(monday).isNotEqualTo(tuesday);
    }

    @Test
    void usesTheNameAloneWhenTheTaskIdHasNoDirectory() {
        String id = TaskHandler.getConversationId("some-id");

        assertThat(id).isEqualTo("task-some-id");
    }

    @Test
    void namesRecurringTasksAfterTheirOwnDirectory() {
        String id = TaskHandler.getConversationId("/workspace/tasks/recurring/water_the_plants.md");

        assertThat(id).isEqualTo("task-recurring-water_the_plants");
    }

    @Test
    void replacesCharactersThatCannotSafelyAppearInAFileName() {
        String id = TaskHandler.getConversationId("/workspace/tasks/2026-08-11/103000-a b:c.md");

        assertThat(id).isEqualTo("task-2026-08-11-103000-a_b_c");
    }

    @Test
    void collapsesTheSeparatorsIntroducedByReplacement() {
        String id = TaskHandler.getConversationId("/workspace/tasks/2026-08-11/103000-a???b.md");

        assertThat(id).isEqualTo("task-2026-08-11-103000-a_b");
    }

    @Test
    void resolvesRelativeSegmentsBeforeDerivingTheId() {
        String id = TaskHandler.getConversationId("/workspace/tasks/2026-08-11/../2026-08-10/103000-buy_milk.md");

        assertThat(id).isEqualTo("task-2026-08-10-103000-buy_milk");
    }

    @Test
    void refusesATaskIdItCannotDeriveAnIdFrom() {
        assertThatThrownBy(() -> TaskHandler.getConversationId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskHandler.getConversationId("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
