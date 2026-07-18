package ai.javaclaw.channels.whatsapp;

import java.io.IOException;


@FunctionalInterface
interface ProcessLauncher {
    Process launch(ProcessBuilder processBuilder) throws IOException;
}
