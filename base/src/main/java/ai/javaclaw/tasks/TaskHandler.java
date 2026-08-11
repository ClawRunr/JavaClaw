package ai.javaclaw.tasks;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelRegistry;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.context.JobRunrDashboardLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.regex.Pattern;

import static ai.javaclaw.tasks.Task.Status.awaiting_human_input;
import static ai.javaclaw.tasks.Task.Status.completed;
import static java.util.Optional.ofNullable;

@Component
public class TaskHandler {

    private static final Logger LOGGER = new JobRunrDashboardLogger(LoggerFactory.getLogger(TaskHandler.class));

    private static final String CONVERSATION_ID_PREFIX = "task-";
    private static final String TASK_FILE_EXTENSION = ".md";
    /** The character set task file names are already restricted to by {@code FileSystemTaskRepository}. */
    private static final Pattern UNSAFE_CHARACTERS = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern REPEATED_SEPARATORS = Pattern.compile("_{2,}");

    private final Agent agent;
    private final TaskRepository taskRepository;
    private final ChannelRegistry channelRegistry;

    public TaskHandler(Agent agent, TaskRepository taskRepository, ChannelRegistry channelRegistry) {
        this.agent = agent;
        this.taskRepository = taskRepository;
        this.channelRegistry = channelRegistry;
    }

    @Job(name = "%0", retries = 3)
    public void executeTask(String taskId) {
        Task task = taskRepository.getTaskById(taskId);

        if (!Task.Status.todo.equals(task.getStatus())) {
            throw new IllegalStateException("Cannot handle task '" + task.getName() + "' with status " + task.getStatus() + ". Only tasks that have status todo can be run");
        }

        Task inProgress = taskRepository.save(task.withStatus(Task.Status.in_progress));
        try {
            LOGGER.info("Starting task: {}", task.getName());
            String agentInput = formatTaskForAgent(inProgress);
            TaskResult result = agent.prompt(getConversationId(taskId), agentInput, TaskResult.class);
            taskRepository.save(inProgress.withFeedback(result.feedback()).withStatus(result.newStatus()));
            notifyUser(task.getName(), result);
            LOGGER.info("Finished task: {} with status {}", task.getName(), result.newStatus());
        } catch (Exception e) {
            taskRepository.save(inProgress.withStatus(Task.Status.todo));
            throw e;
        }
    }

    // TODO: currently we lose the conversationId when moving to a channel. Should channel registry track this?
    private void notifyUser(String taskName, TaskResult result) {
        try {
            Channel channel = channelRegistry.getLatestChannel();
            ofNullable(channel).ifPresent(c -> {
                if (completed == result.newStatus) {
                    channel.sendMessage("📋 Task '%s' completed:\n%s".formatted(taskName, result.feedback()));
                } else if (awaiting_human_input == result.newStatus) {
                    channel.sendMessage("📋 Task '%s' is waiting for your input:\n%s".formatted(taskName, result.feedback()));
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Failed to notify user about task '{}': {}", taskName, e.getMessage());
        }
    }

    /**
     * Derives the conversation id used for a task's chat memory.
     *
     * <p>A task's id is the absolute path of its markdown file. Passed straight through it reaches
     * {@code FileSystemChatMemoryRepository}, which resolves {@code conversations/chat-{id}.yaml} -
     * so each task creates a directory tree mirroring its own location on disk instead of a single
     * conversation file, never appears in the conversation list, and on Windows is rejected outright
     * because of the colon in the drive letter.
     *
     * <p>The last two path segments carry everything needed: tasks are stored as
     * {@code {date}/{time}-{name}.md} and recurring ones as {@code recurring/{name}.md}, so the pair
     * is unique across days and stays readable. Characters outside the set task file names already
     * use are replaced, which keeps the result a single safe file name component whatever the id
     * turns out to contain.
     *
     * @throws IllegalArgumentException if no id can be derived, rather than falling back to the
     *                                  path and recreating the problem this method exists to avoid
     */
    static String getConversationId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Cannot derive a conversation id from an empty task id");
        }

        Path taskFile = Path.of(taskId).normalize();
        Path fileName = taskFile.getFileName();
        Path parent = taskFile.getParent();
        if (fileName == null) {
            throw new IllegalArgumentException("Cannot derive a conversation id from a task id that names no file");
        }

        String name = removeExtension(fileName.toString());
        String group = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
        if (name.isBlank() && group.isBlank()) {
            throw new IllegalArgumentException("Cannot derive a conversation id from a task id that names no file");
        }

        return sanitize(CONVERSATION_ID_PREFIX + (group.isBlank() ? name : group + "-" + name));
    }

    private static String removeExtension(String fileName) {
        return fileName.endsWith(TASK_FILE_EXTENSION)
                ? fileName.substring(0, fileName.length() - TASK_FILE_EXTENSION.length())
                : fileName;
    }

    private static String sanitize(String conversationId) {
        String replaced = UNSAFE_CHARACTERS.matcher(conversationId).replaceAll("_");
        return REPEATED_SEPARATORS.matcher(replaced).replaceAll("_");
    }

    private String formatTaskForAgent(Task task) {
        return String.format("""
                Handle the following task and report the new status ('completed' or 'awaiting_human_input') with the feedback what was done
                Task '%s': %s
                """, task.getName(), task.getDescription());
    }

    public record TaskResult(Task.Status newStatus, String feedback) {}
}
