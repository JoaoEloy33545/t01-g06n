package mdeos.isos.infra.zkquorum.doe.server;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class ExpectedNodesStore {

    private final Path stateFile;
    private final AtomicInteger cached;

    public ExpectedNodesStore(
        @ConfigProperty(name = "zkquorum.nodes.initial", defaultValue = "4") int defaultValue,
        @ConfigProperty(name = "zkquorum.state.dir", defaultValue = ".state") String stateDir
    ) {
        this.stateFile = Paths.get(stateDir).resolve("expected-nodes.txt");
        this.cached = new AtomicInteger(loadOrDefault(defaultValue));
    }

    public int get() {
        return cached.get();
    }

    public void set(int value) {
        cached.set(value);
        persist(value);
    }

    private int loadOrDefault(int defaultValue) {
        if (!Files.exists(stateFile)) {
            persist(defaultValue);
            return defaultValue;
        }
        try {
            String raw = Files.readString(stateFile, StandardCharsets.UTF_8).trim();
            return Integer.parseInt(raw);
        } catch (Exception e) {
            persist(defaultValue);
            return defaultValue;
        }
    }

    private void persist(int value) {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, Integer.toString(value), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best-effort persistence only.
        }
    }
}
