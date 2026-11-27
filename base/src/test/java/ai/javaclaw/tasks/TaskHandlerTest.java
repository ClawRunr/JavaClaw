package ai.javaclaw.tasks;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.agent.AgentRequest;
import ai.javaclaw.agent.ConversationKey;
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
    // The conversation the agent is called with
    // -----------------------------------------------------------------------

    @Test
    void callsTheAgentInTheTasksOwnConversation() {
        Task task = givenATodoTask("Buy milk");
        when(agentMock.prompt(any(AgentRequest.class), eq(TaskResult.class)))
                .thenReturn(new TaskResult(Task.Status.completed, "done"));

        taskHandler.executeTask(task.getId());

        AgentRequest request = requestPassedToAgent();
        assertThat(request.kind()).isEqualTo(SessionKind.TASK);
        assertThat(request.channel()).isEqualTo(SessionChannel.INTERNAL);
        assertThat(request.conversation()).isEqualTo(ConversationKey.task(task.getId()));
    }

    private AgentRequest requestPassedToAgent() {
        ArgumentCaptor<AgentRequest> request = ArgumentCaptor.forClass(AgentRequest.class);
        org.mockito.Mockito.verify(agentMock).prompt(request.capture(), eq(TaskResult.class));
        return request.getValue();
    }

    private Task givenATodoTask(String name) {
        return taskRepository.save(new Task(null, name, Instant.now(), Task.Status.todo, "Some description"));
    }
}
