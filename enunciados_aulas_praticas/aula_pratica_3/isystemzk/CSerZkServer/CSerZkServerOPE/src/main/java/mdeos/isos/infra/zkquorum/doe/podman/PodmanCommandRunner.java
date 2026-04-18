package mdeos.isos.infra.zkquorum.doe.podman;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PodmanCommandRunner {

    public record CommandResult(int exitCode, String output) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    public CommandResult run(List<String> args, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start command: " + String.join(" ", args), e);
        }

        String output = readAll(process.getInputStream());
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted: " + String.join(" ", args), e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Command timed out: " + String.join(" ", args));
        }

        return new CommandResult(process.exitValue(), output.trim());
    }

    private String readAll(InputStream inputStream) {
        try (inputStream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
