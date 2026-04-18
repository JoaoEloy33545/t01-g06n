package mdeos.isos.infra.zkquorum.doe.runtime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.LaunchMode;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

import mdeos.isos.infra.zkquorum.doe.podman.PodmanCommandRunner;
import mdeos.isos.infra.zkquorum.doe.server.ZkQuorumScaleService;
import mdeos.isos.infra.zkquorum.doe.server.ExpectedNodesStore;

@Startup
@ApplicationScoped
public class ZkQuorumOrchestrator {

    private static final List<String> NODES = List.of(
        "zoo1", "zoo2", "zoo3", "zoo4", "zoo5", "zoo6"
    );
    private static final Logger LOG = Logger.getLogger(ZkQuorumOrchestrator.class);

    @ConfigProperty(name = "zkquorum.podman.binary")
    String podmanBinary;

    @ConfigProperty(name = "zkquorum.manifest.path")
    String manifestPath;

    @ConfigProperty(name = "zkquorum.startup.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "zkquorum.startup.timeout-seconds", defaultValue = "90")
    int startupTimeoutSeconds;

    @ConfigProperty(name = "zkquorum.startup.strict", defaultValue = "false")
    boolean strictStartup;

    private final PodmanCommandRunner podman = new PodmanCommandRunner();
    private final ZkQuorumVerifier verifier;
    private final ZkQuorumScaleService scaleService;
    private final ExpectedNodesStore expectedNodesStore;
    private boolean startedByUs;
    private String resolvedManifestPath;

    public ZkQuorumOrchestrator(
        ZkQuorumVerifier verifier,
        ZkQuorumScaleService scaleService,
        ExpectedNodesStore expectedNodesStore
    ) {
        this.verifier = verifier;
        this.scaleService = scaleService;
        this.expectedNodesStore = expectedNodesStore;
    }

    @PostConstruct
    void startIfNeeded() {
        if (!enabled) {
            return;
        }

        verifyPodmanAvailable();
        resolvedManifestPath = resolveManifestPath(manifestPath);

        if (podsExist()) {
            startedByUs = false;
        } else {
            podman.run(List.of(podmanBinary, "play", "kube", resolvedManifestPath), Duration.ofMinutes(5));
            startedByUs = true;
        }

        if (LaunchMode.current().isDevOrTest()) {
            CompletableFuture.runAsync(() -> runWithContextClassLoader(this::scaleAndVerify));
        } else {
            scaleAndVerify();
        }
    }

    @PreDestroy
    void stopIfNeeded() {
        if (!enabled) {
            return;
        }
        if (!startedByUs) {
            return;
        }

        podman.run(List.of(podmanBinary, "kube", "down", resolvedManifestPath), Duration.ofMinutes(2));
    }

    private boolean podsExist() {
        for (String node : NODES) {
            PodmanCommandRunner.CommandResult result = podman.run(
                List.of(podmanBinary, "pod", "exists", node),
                Duration.ofSeconds(5)
            );
            if (!result.isSuccess()) {
                return false;
            }
        }
        return true;
    }

    private void startExistingPods() {
        for (String node : NODES) {
            PodmanCommandRunner.CommandResult result = podman.run(
                List.of(podmanBinary, "pod", "start", node),
                Duration.ofSeconds(20)
            );
            if (!result.isSuccess() && !result.output().toLowerCase().contains("already running")) {
                throw new IllegalStateException("Failed to start pod " + node + ": " + result.output());
            }
        }
    }

    private void verifyPodmanAvailable() {
        PodmanCommandRunner.CommandResult result = podman.run(
            List.of(podmanBinary, "--version"),
            Duration.ofSeconds(5)
        );
        if (!result.isSuccess()) {
            throw new IllegalStateException("Podman is not available: " + result.output());
        }
    }

    private void scaleAndVerify() {
        try {
            if (startedByUs) {
                scaleService.setDesiredNodeCount(expectedNodesStore.get());
            }
            List<String> running = scaleService.getRunningNodes();
            if (running.isEmpty()) {
                LOG.warn("No running pods found for quorum verification.");
                return;
            }
            verifier.ensureHealthy(podmanBinary, running, Duration.ofSeconds(startupTimeoutSeconds));
        } catch (RuntimeException e) {
            LOG.error("ZooKeeper quorum did not become healthy: " + e.getMessage());
            LOG.error("Node status snapshot: " + verifier.getNodeStatuses(podmanBinary, NODES));
            if (strictStartup && !LaunchMode.current().isDevOrTest()) {
                throw e;
            }
            LOG.warn("Continuing startup because zkquorum.startup.strict=false");
        }
    }

    private void runWithContextClassLoader(Runnable task) {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(ZkQuorumOrchestrator.class.getClassLoader());
        try {
            task.run();
        } finally {
            current.setContextClassLoader(previous);
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
}
