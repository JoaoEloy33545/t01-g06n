package mdeos.isos.infra.zkquorum.doe.server;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import mdeos.isos.infra.zkquorum.doe.podman.PodmanCommandRunner;

@ApplicationScoped
public class ZkQuorumScaleService {

    private static final List<String> NODES = List.of(
        "zoo1", "zoo2", "zoo3", "zoo4", "zoo5", "zoo6"
    );

    private final String podmanBinary;
    private final PodmanCommandRunner podman = new PodmanCommandRunner();

    public ZkQuorumScaleService(
        @ConfigProperty(name = "zkquorum.manifest.path") String manifestPath,
        @ConfigProperty(name = "zkquorum.podman.binary", defaultValue = "podman") String podmanBinary
    ) {
        this.manifestPath = resolveManifestPath(manifestPath);
        this.podmanBinary = podmanBinary;
    }

    private final String manifestPath;
    public void setDesiredNodeCount(int desired) {
        int clamped = Math.max(0, Math.min(6, desired));
        if (clamped == 0) {
            podman.run(List.of(podmanBinary, "kube", "down", manifestPath), Duration.ofMinutes(2));
            return;
        }

        String scaledManifest = buildScaledManifest(clamped);
        Path tempManifest = writeTempManifest(scaledManifest, clamped);
        podman.run(List.of(podmanBinary, "kube", "down", manifestPath), Duration.ofMinutes(2));
        podman.run(List.of(podmanBinary, "play", "kube", tempManifest.toString()), Duration.ofMinutes(5));
    }

    public void reconfigureAndRestart(int desired) {
        int clamped = Math.max(0, Math.min(6, desired));
        podman.run(List.of(podmanBinary, "kube", "down", manifestPath), Duration.ofMinutes(2));
        if (clamped == 0) {
            return;
        }
        String scaledManifest = buildScaledManifest(clamped);
        Path tempManifest = writeTempManifest(scaledManifest, clamped);
        podman.run(List.of(podmanBinary, "play", "kube", tempManifest.toString()), Duration.ofMinutes(5));
    }

    public List<String> getRunningNodes() {
        return NODES.stream()
            .filter(this::hasRunningContainer)
            .collect(Collectors.toList());
    }

    private boolean podExists(String node) {
        PodmanCommandRunner.CommandResult result = podman.run(
            List.of(podmanBinary, "pod", "exists", node),
            Duration.ofSeconds(5)
        );
        return result.isSuccess();
    }

    private void startPod(String node) {
        PodmanCommandRunner.CommandResult result = podman.run(
            List.of(podmanBinary, "pod", "start", node),
            Duration.ofSeconds(30)
        );
        if (!result.isSuccess() && !result.output().toLowerCase().contains("already running")) {
            throw new IllegalStateException("Failed to start pod " + node + ": " + result.output());
        }
    }

    private void stopPod(String node) {
        PodmanCommandRunner.CommandResult result = podman.run(
            List.of(podmanBinary, "pod", "stop", node),
            Duration.ofSeconds(30)
        );
        if (!result.isSuccess()
            && !result.output().toLowerCase().contains("already stopped")
            && !result.output().toLowerCase().contains("not running")) {
            throw new IllegalStateException("Failed to stop pod " + node + ": " + result.output());
        }
    }

    private String buildScaledManifest(int desired) {
        String base = readManifest(manifestPath);
        List<String> docs = List.of(base.split("\n---\n"));
        String servers = buildServers(desired);
        return docs.stream()
            .filter(doc -> {
                String name = extractPodName(doc);
                if (name == null) {
                    return false;
                }
                int index = parseIndex(name);
                return index > 0 && index <= desired;
            })
            .map(doc -> doc.replaceAll("(?m)^\\s*value:\\s*\"server\\.[^\"]*\"\\s*$",
                "          value: \"" + servers + "\""))
            .collect(Collectors.joining("\n---\n"));
    }

    private String readManifest(String path) {
        try {
            return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read manifest: " + path, e);
        }
    }

    private Path writeTempManifest(String content, int desired) {
        try {
            Path temp = Files.createTempFile("zk-quorum-" + desired + "-", ".yaml");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            return temp;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write temp manifest", e);
        }
    }

    private String buildServers(int desired) {
        return IntStream.rangeClosed(1, desired)
            .mapToObj(i -> "server." + i + "=zoo" + i + ":2888:3888;2181")
            .collect(Collectors.joining(" "));
    }

    private String extractPodName(String doc) {
        for (String line : doc.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("name: zoo")) {
                return trimmed.substring("name: ".length());
            }
        }
        return null;
    }

    private int parseIndex(String name) {
        if (name == null || !name.startsWith("zoo")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String resolveManifestPath(String configuredPath) {
        Path configured = Paths.get(configuredPath);
        if (Files.exists(configured)) {
            return configured.toString();
        }

        Path[] fallbacks = new Path[] {
            Paths.get("CSerZkServerOPE").resolve(configuredPath),
            Paths.get("CSerZkDashboardOPE").resolve(configuredPath),
            Paths.get("CSerZkServer").resolve(configuredPath)
        };
        for (Path fallback : fallbacks) {
            if (Files.exists(fallback)) {
                return fallback.toString();
            }
        }

        throw new IllegalStateException(
            "Manifest not found at " + configuredPath + " or within module roots"
        );
    }

    private boolean hasRunningContainer(String node) {
        PodmanCommandRunner.CommandResult result = podman.run(
            List.of(podmanBinary, "ps", "--filter", "pod=" + node, "--format", "{{.Names}}"),
            Duration.ofSeconds(5)
        );
        return result.isSuccess() && !result.output().isBlank();
    }
}
