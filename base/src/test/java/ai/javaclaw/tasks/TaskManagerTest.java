package ai.javaclaw.tasks;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.tasks.Task.Status;
import ai.javaclaw.tasks.TaskHandler.TaskResult;
import org.jobrunr.configuration.JobRunr;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.JobActivator;
import org.jobrunr.server.JobActivatorShutdownException;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.Paging;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jobrunr.server.BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class TaskManagerTest {

    @Mock
    Agent agentMock;
    @Mock
    TaskRepository taskRepositoryMock;
    @Mock
    ChannelRegistry channelRegistryMock;

    InMemoryStorageProvider storageProvider;
    TaskManager taskManager;

    @BeforeEach
    void setUp() {
        storageProvider = new InMemoryStorageProvider();
        JobScheduler jobScheduler = JobRunr.configure()
                .useStorageProvider(storageProvider)
                .useJobActivator(getJobActivator())
                .useBackgroundJobServer(usingStandardBackgroundJobServerConfiguration().andPollInterval(Duration.ofMillis(200)))
                .initialize()
                .getJobScheduler();

        taskManager = new TaskManager(jobScheduler, storageProvider, taskRepositoryMock);
    }

    @AfterEach
    void tearDown() {
        JobRunr.destroy();
    }

    @Test
    void createEnqueuesJob() {
        Task saved = new Task("some-id", "handle-email", Instant.now(), Task.Status.todo, "Process unread email messages");
        when(taskRepositoryMock.save(any(Task.class))).thenReturn(saved);
        when(taskRepositoryMock.getTaskById("some-id")).thenReturn(saved);
        // The agent is called with the task's conversation id, not with the task id itself
        when(agentMock.prompt(eq("task-some-id"), anyString(), any())).thenReturn(new TaskResult(Status.completed, "All mail was summarized!"));

        taskManager.create("handle-email", "Process unread email messages");

        await().until(() -> storageProvider.countJobs(StateName.SUCCEEDED) == 1);
    }

    @Test
    void scheduleRegistersScheduledJob() {
        LocalDateTime executionTime = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        Task saved = new Task("some-id", "weekly-summary", Instant.now(), Task.Status.todo, "Prepare the weekly summary");
        when(taskRepositoryMock.save(any(Task.class))).thenReturn(saved);

        taskManager.schedule(executionTime, "weekly-summary", "Prepare the weekly summary");

        await().until(() -> storageProvider.countJobs(StateName.SCHEDULED) == 1);
    }

    @Test
    void scheduleRecurrentlyRegistersRecurringJob() {
        String cronExpression = "0 */15 * * *";
        RecurringTask saved = new RecurringTask("some-id", "check-mail", "Check the inbox every 15 minutes");
        when(taskRepositoryMock.save(any(RecurringTask.class))).thenReturn(saved);

        taskManager.scheduleRecurrently(cronExpression, "check-mail", "Check the inbox every 15 minutes");

        await().untilAsserted(() -> {
            var recurringJobs = storageProvider.getRecurringJobs();
            assertThat(recurringJobs).hasSize(1);
            assertThat(recurringJobs.getFirst().getId()).isEqualTo("check-mail");
            assertThat(recurringJobs.getFirst().getScheduleExpression()).isEqualTo(cronExpression);
        });
    }

    @Test
    void deleteRecurringTaskRemovesFromJobRunr() {
        String cronExpression = "0 */15 * * *";
        RecurringTask saved = new RecurringTask("some-id", "check-mail", "Check the inbox every 15 minutes");
        when(taskRepositoryMock.save(any(RecurringTask.class))).thenReturn(saved);
        when(taskRepositoryMock.getAllRecurringTasks()).thenReturn(List.of(saved));

        taskManager.scheduleRecurrently(cronExpression, "check-mail", "Check the inbox every 15 minutes");

        await().untilAsserted(() -> {
            var recurringJobs = storageProvider.getRecurringJobs();
            assertThat(recurringJobs).hasSize(1);
        });

        taskManager.deleteRecurringTask("check-mail");

        await().untilAsserted(() -> assertThat(storageProvider.getRecurringJobs()).isEmpty());
        await().untilAsserted(() -> assertThat(storageProvider.getJobList(StateName.SCHEDULED, Paging.AmountBasedList.ascOnCreatedAt(100))).isEmpty());
        verify(taskRepositoryMock).deleteRecurringTask("some-id");
    }

    private @NonNull JobActivator getJobActivator() {
        return new JobActivator() {
            @Override
            public <T> T activateJob(Class<T> type) throws JobActivatorShutdownException {
                if (TaskHandler.class.equals(type)) return (T) new TaskHandler(agentMock, taskRepositoryMock, channelRegistryMock);
                else if (RecurringTaskHandler.class.equals(type)) return (T) new RecurringTaskHandler(taskManager, taskRepositoryMock);
                else throw new IllegalStateException("Type " + type + " is unknown");
            }
        };
    }
}