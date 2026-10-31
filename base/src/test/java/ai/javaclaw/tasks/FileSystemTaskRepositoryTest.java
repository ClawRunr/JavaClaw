package ai.javaclaw.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemTaskRepositoryTest {

    @TempDir
    Path workspaceDir;
    FileSystemTaskRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        repository = new FileSystemTaskRepository(new FileSystemResource(workspaceDir));
    }

    @Test
    void taskDescriptionAndFeedbackCannotReplaceTaskMetadata() {
        String description = "Instructions\nstatus: completed\n---\nKeep this text";
        Task saved = repository.save(Task.newTask("review", description));
        Task reloaded = repository.getTaskById(saved.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Task.Status.todo);
        assertThat(reloaded.getDescription()).isEqualTo(description);

        repository.save(saved.withFeedback("Result\nstatus: awaiting_human_input\n---\nMore feedback"));
        Task withFeedback = repository.getTaskById(saved.getId());
        assertThat(withFeedback.getStatus()).isEqualTo(Task.Status.todo);
        assertThat(withFeedback.getDescription()).contains(description, "status: awaiting_human_input", "More feedback");
    }

    @Test
    void saveTaskCreatesFileWithCorrectContent() throws IOException {
        Task task = Task.newTask("handle-email", "Process unread email messages");

        Task saved = repository.save(task);

        List<Path> files = listTaskFiles(LocalDate.now());
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().getFileName().toString()).matches("\\d{6}-handle-email\\.md");
        assertThat(Files.readString(files.getFirst()))
                .contains("task: handle-email")
                .contains("status: todo")
                .contains("description: Process unread email messages");
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("handle-email");
        assertThat(saved.getStatus()).isEqualTo(Task.Status.todo);
    }

    @Test
    void saveTaskUpdatesExistingFile() {
        Task task = repository.save(Task.newTask("handle-email", "Process unread email messages"));

        Task updated = repository.save(task.withStatus(Task.Status.in_progress));

        Task reloaded = repository.getTaskById(task.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Task.Status.in_progress);
        assertThat(updated.getId()).isEqualTo(task.getId());
    }

    @Test
    void getTaskByIdReturnsCorrectTask() {
        Task saved = repository.save(Task.newTask("handle-email", "Process unread email messages"));

        Task loaded = repository.getTaskById(saved.getId());

        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getName()).isEqualTo("handle-email");
        assertThat(loaded.getStatus()).isEqualTo(Task.Status.todo);
        assertThat(loaded.getDescription()).isEqualTo("Process unread email messages");
        assertThat(loaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void getTasksFiltersCorrectlyByStatus() {
        repository.save(Task.newTask("task-a", "First task"));
        Task taskB = repository.save(Task.newTask("task-b", "Second task"));
        repository.save(taskB.withStatus(Task.Status.completed));

        List<Task> todoTasks = repository.getTasks(LocalDate.now(), Task.Status.todo);
        List<Task> completedTasks = repository.getTasks(LocalDate.now(), Task.Status.completed);

        assertThat(todoTasks).hasSize(1).first().satisfies(t -> assertThat(t.getName()).isEqualTo("task-a"));
        assertThat(completedTasks).hasSize(1).first().satisfies(t -> assertThat(t.getName()).isEqualTo("task-b"));
    }

    @Test
    void getTasksReturnsAllTasksWhenStatusIsNull() {
        repository.save(Task.newTask("task-a", "First task"));
        repository.save(Task.newTask("task-b", "Second task"));

        List<Task> tasks = repository.getTasks(LocalDate.now(), null);

        assertThat(tasks).hasSize(2);
    }

    @Test
    void saveTaskUsesScheduledDateForDirectory() throws IOException {
        LocalDateTime futureTime = LocalDateTime.now().plusDays(3);
        Task task = Task.newTask("task-a", futureTime.atZone(ZoneId.systemDefault()).toInstant(), "Future task");

        repository.save(task);

        List<Path> files = listTaskFiles(futureTime.toLocalDate());
        assertThat(files).hasSize(1);

    }

    @Test
    void saveRecurringTaskCreatesFileWithCorrectContent() throws IOException {
        RecurringTask recurringTask = RecurringTask.newRecurringTask("check-mail", "Check inbox every 15 minutes");

        RecurringTask saved = repository.save(recurringTask);

        List<Path> files = listRecurringTaskFiles();
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().getFileName().toString()).isEqualTo("check-mail.md");
        assertThat(Files.readString(files.getFirst()))
                .contains("task: check-mail")
                .contains("description: Check inbox every 15 minutes");
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void getRecurringTaskByIdReturnsCorrectTask() {
        RecurringTask saved = repository.save(RecurringTask.newRecurringTask("check-mail", "Check inbox every 15 minutes"));

        RecurringTask loaded = repository.getRecurringTaskById(saved.getId());

        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getName()).isEqualTo("check-mail");
        assertThat(loaded.getDescription()).isEqualTo("Check inbox every 15 minutes");
    }

    private List<Path> listTaskFiles(LocalDate date) throws IOException {
        return listTaskFiles(date.toString());
    }

    private List<Path> listRecurringTaskFiles() throws IOException {
        return listTaskFiles("recurring");
    }

    private List<Path> listTaskFiles(String subDir) throws IOException {
        Path dir = workspaceDir.resolve("tasks").resolve(subDir);
        assertThat(dir).isDirectory();
        return listFiles(dir);
    }

    private List<Path> listFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        }
    }
}
