package ai.javaclaw.providers.claudecode;

import com.sun.security.auth.module.UnixSystem;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

/**
 * The working directory Claude Code runs in. Claude Code writes its cwd into the environment message
 * of every request, so a directory per call would move the prompt-cache prefix each time: one stable,
 * private directory is shared by every call. It sits in a temp directory others may share, so it is
 * only used when it is our own real directory, closed to everyone else.
 */
final class ClaudeCodeWorkdir {

    private static Path processPrivate;

    private ClaudeCodeWorkdir() {
    }

    static synchronized Path get() throws IOException {
        Path shared = shared();
        if (shared != null) {
            return shared;
        }
        if (processPrivate == null || !Files.isDirectory(processPrivate, LinkOption.NOFOLLOW_LINKS)) {
            processPrivate = Files.createTempDirectory("javaclaw-claude-cwd-");
            processPrivate.toFile().deleteOnExit();
        }
        return processPrivate;
    }

    private static Path shared() {
        try {
            Path parent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            int parentMode = (Integer) Files.getAttribute(parent, "unix:mode");
            // A parent others can write without the sticky bit lets them rename our directory away.
            if ((parentMode & 0022) != 0 && (parentMode & 01000) == 0) {
                return null;
            }
            long uid = new UnixSystem().getUid();
            Path path = parent.resolve("javaclaw-claude-cwd-" + uid);
            try {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (FileAlreadyExistsException e) {
                // Adopted below only when it is ours and private.
            }
            boolean ours = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    && ((Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() == uid
                    && ((Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) & 0077) == 0;
            if (!ours) {
                return null;
            }
            // Keeps periodic temp cleaners off a directory that is in use.
            Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
            return path;
        } catch (IOException | RuntimeException | LinkageError e) {
            // No unix attributes (Windows) or an unusable directory: fall back to a private one.
            return null;
        }
    }
}
