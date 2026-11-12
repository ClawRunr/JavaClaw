package ai.javaclaw.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Path helpers shared by everything that reads or writes files. */
public final class FilePaths {

    private FilePaths() {
    }

    /**
     * Creates the directory, and any missing parents, if it does not exist yet.
     *
     * @return {@code dir}
     * @throws UncheckedIOException when it cannot be created
     */
    public static Path ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + dir, e);
        }
    }
}
