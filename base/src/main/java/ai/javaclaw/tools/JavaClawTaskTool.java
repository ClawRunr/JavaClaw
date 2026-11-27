package ai.javaclaw.tools;

import ai.javaclaw.tasks.RecurringTask;
import ai.javaclaw.tasks.TaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDateTime;
import java.util.List;

import static java.util.Optional.ofNullable;

/**
 * Creates and manages high-level tasks for AI assistants.
 * Each task is persisted as a Markdown file in the workspace directory.
 */
public class JavaClawTaskTool {

    private static final Logger logger = LoggerFactory.getLogger(JavaClawTaskTool.class);
    private final TaskManager taskManager;
    private final TaskEventHandler taskEventHandler;


    public JavaClawTaskTool(TaskManager taskManager, TaskEventHandler taskEventHandler) {
        this.taskManager = taskManager;
        this.taskEventHandler = taskEventHandler;
    }

    public interface TaskEventHandler {

        void taskCreated(String name, String description);

        void taskScheduled(LocalDateTime executionTime, String name, String description);

        void recurringTaskCreated(String cronExpression, String name, String description);

    }

    @Tool(description = """
            Use this tool to manage high-level tasks that represent major units of work.
            Tasks are persistent, trackable entities that you can work on backed by JobRunr.
            
            ## When to Use:
            - When a user provides a new goal or assignment.
            - When a user provides multiple goals (create a separate task for EACH).
            - To formalize a request into a trackable entity before starting work.
            
            ## Constraints:
            - Name: Short, descriptive identifier (e.g., 'research-market', 'update-docs'). Spaces will be converted to underscores.
            - Description: Detailed explanation of what needs to be achieved.
            """)
    public String createTask(String name, String description) {
        try {
            this.taskManager.create(name, description);
            ofNullable(taskEventHandler).ifPresent(x -> x.taskCreated(name, description));
            return String.format("Task '%s' has been created successfully.", name);
        } catch (Exception e) {
            logger.error("Failed to create task", e);
            return "Error: Could not create task. " + e.getMessage();
        }
    }

    @Tool(description = """
            Schedules a task using JobRunr for a specific date and time in the future.
            Use this when a user explicitly mentions a time or date (e.g., "Remind me next Monday at 9 AM" or "Schedule at 3pm").
            
            - executionTime: The specific local date and time (without timezone) when the task should run in this format YYYY-MM-ddTHH:mm:ss (example 2025-03-17T09:00:00).
            - name: Short, descriptive identifier (e.g., 'monday-morning-sync').
            - description: Detailed instructions on what the task entails.
            """)
    public String scheduleTask(String executionTime, String name, String description) {
        try {
            // using string to work around tool call argument parsing exception
            LocalDateTime executionTimeAsLocalDateTime = LocalDateTime.parse(executionTime);
            this.taskManager.schedule(executionTimeAsLocalDateTime, name, description);
            ofNullable(taskEventHandler).ifPresent(x -> x.taskScheduled(executionTimeAsLocalDateTime, name, description));
            return String.format("Task '%s' has been scheduled for %s.", name, executionTime);
        } catch (Exception e) {
            logger.error("Failed to schedule task", e);
            return "Error: Could not schedule task. " + e.getMessage();
        }
    }

    @Tool(description = """
            Schedules a task using JobRunr that repeats at regular intervals based on a cron expression.
            Use this for recurring activities like daily reports, weekly checks, etc.
            
            - cronExpression: A standard quartz-style cron expression (e.g., '0 12 * * *' for daily at noon or '* * * * *' for every minute. Do not use ? in a cron expression).
            - name: Short, descriptive identifier (e.g., 'weekly-log-cleanup').
            - description: Detailed instructions on what the task entails.
            """)
    public String scheduleRecurringTask(String cronExpression, String name, String description) {
        try {
            this.taskManager.scheduleRecurrently(cronExpression, name, description);
            ofNullable(taskEventHandler).ifPresent(x -> x.recurringTaskCreated(cronExpression, name, description));
            return String.format("Task '%s' has been scheduled recurrently with cron expression '%s'.", name, cronExpression);
        } catch (Exception e) {
            logger.error("Failed to schedule recurring task", e);
            return "Error: Could not schedule recurring task. " + e.getMessage();
        }
    }

    @Tool(description = """
            Deletes a recurring task by name, stopping it from running again.
            Use this when a user wants to remove, cancel, or stop a recurring task.
            
            - name: The name of the recurring task to delete (e.g., 'weekly-log-cleanup').
            """)
    public String deleteRecurringTask(String name) {
        try {
            this.taskManager.deleteRecurringTask(name);
            return String.format("Recurring task '%s' has been deleted successfully.", name);
        } catch (Exception e) {
            logger.error("Failed to delete recurring task", e);
            return "Error: Could not delete recurring task. " + e.getMessage();
        }
    }

    @Tool(description = """
            List all recurring tasks with their id, name and description
            Use this when a user wants to list their recurring tasks.
            """)
    public String listRecurringTasks() {
        List<RecurringTask> allRecurringTasks = taskManager.getAllRecurringTasks();
        StringBuilder sb = new StringBuilder();
        sb.append("Recurring tasks:").append(System.lineSeparator());
        allRecurringTasks.forEach(rt -> {
            sb.append("- id: ").append(rt.getId()).append(System.lineSeparator());
            sb.append("  name: ").append(rt.getName()).append(System.lineSeparator());
            sb.append("  description: ").append(rt.getDescription(), 0, Math.min(rt.getDescription().length(), 100)).append(System.lineSeparator());
        });
        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private TaskManager taskManager;
        private TaskEventHandler taskEventHandler;


        public JavaClawTaskTool.Builder taskManager(TaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        public JavaClawTaskTool.Builder agentTaskEventHandler(TaskEventHandler taskEventHandler) {
            this.taskEventHandler = taskEventHandler;
            return this;
        }

        public JavaClawTaskTool build() {
            return new JavaClawTaskTool(this.taskManager, this.taskEventHandler);
        }

    }
}