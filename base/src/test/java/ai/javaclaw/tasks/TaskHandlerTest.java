package ai.javaclaw.tasks;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.SessionChannel;
import ai.javaclaw.agent.SessionKind;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    // A task's id is the absolute path of its markdown file: machine-specific
    // and unreadable as a conversation id in the conversation list.
    // -----------------------------------------------------------------------

    @Test
    void callsTheAgentWithAConversationIdThatIsNotTheTaskFilePath() {
        Task task = givenATodoTask("Buy milk");
        when(agentMock.prompt(any(AgentRequest.class), eq(TaskResult.class)))
                .thenReturn(new TaskResult(Task.Status.completed, "done"));

        taskHandler.executeTask(task.getId());

        AgentRequest request = requestPassedToAgent();
        assertThat(request.kind()).isEqualTo(SessionKind.TASK);
        assertThat(request.channel()).isEqualTo(SessionChannel.INTERNAL);
        assertThat(request.conversationId())
                .isNotEqualTo(task.getId())
                .doesNotContain("/")
                .doesNotContain("\\")
                .startsWith("task-");
    }

    @Test
    void derivesTheConversationIdFromTheTasksDateDirectoryAndFileName() {
        Task task = givenATodoTask("Buy milk");
        when(agentMock.prompt(any(AgentRequest.class), eq(TaskResult.class)))
                .thenReturn(new TaskResult(Task.Status.completed, "done"));

        taskHandler.executeTask(task.getId());

        Path taskFile = Path.of(task.getId());
        String dateDirectory = taskFile.getParent().getFileName().toString();
        String fileName = taskFile.getFileName().toString().replace(".md", "");
        assertThat(requestPassedToAgent().conversationId()).isEqualTo("task-" + dateDirectory + "-" + fileName);
    }

    private AgentRequest requestPassedToAgent() {
        ArgumentCaptor<AgentRequest> request = ArgumentCaptor.forClass(AgentRequest.class);
        org.mockito.Mockito.verify(agentMock).prompt(request.capture(), eq(TaskResult.class));
        return request.getValue();
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
