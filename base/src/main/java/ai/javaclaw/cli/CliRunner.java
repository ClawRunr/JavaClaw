package ai.javaclaw.cli;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class CliRunner {

    public CliResult run(List<String> command) throws IOException, InterruptedException {
        return run(command, Duration.ofSeconds(10));
    }

    public CliResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out after " + timeout + ": " + command);
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CliResult(process.exitValue(), stdout, stderr);
    }

    public Process startDaemon(List<String> command) throws IOException {
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }

    public record CliResult(int exitCode, String stdout, String stderr) {

        public boolean isSuccess() {
            return exitCode == 0;
        }

        public String requireSuccess() throws IOException {
            if (!isSuccess()) {
                throw new IOException("Command failed (exit " + exitCode + "): " + stderr.strip());
            }
            return stdout;
        }
    }
}
