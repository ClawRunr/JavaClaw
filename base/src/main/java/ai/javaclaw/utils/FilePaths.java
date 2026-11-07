package ai.javaclaw.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Path helpers shared by everything that reads or guards files. */
public final class FilePaths {

    private FilePaths() {
    }

    /**
     * The absolute, normalized path with symlinks resolved as far as it exists, so neither
     * {@code ..} nor a link (or {@code /tmp} vs {@code /private/tmp}) can step around a check that
     * compares paths. The part that does not exist yet is kept as given.
     */
    public static Path realPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException | SecurityException e) {
            return absolute;
        }
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
