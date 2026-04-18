package mdeos.isos.infra.zkquorum.doe.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import mdeos.isos.infra.zkquorum.doe.podman.PodmanCommandRunner;

@ApplicationScoped
public class ZkQuorumVerifier {

    private final PodmanCommandRunner podman = new PodmanCommandRunner();

    public record NodeStatus(String podName, String containerName, String mode, String details) {}

    public void ensureHealthy(String podmanBinary, List<String> nodes, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        RuntimeException lastError = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                QuorumStatus status = inspectCluster(podmanBinary, nodes);
                if (status.isHealthy(nodes.size())) {
                    return;
                }
                lastError = new RuntimeException(status.describe());
            } catch (RuntimeException e) {
                lastError = e;
            }

            sleep(1000);
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("ZooKeeper quorum did not become healthy in time");
    }

    public List<NodeStatus> getNodeStatuses(String podmanBinary, List<String> nodes) {
        List<NodeStatus> statuses = new ArrayList<>();
        for (String node : nodes) {
            try {
                String containerName = resolveContainerName(podmanBinary, node);
                ModeResult modeResult = getNodeMode(podmanBinary, containerName);
                statuses.add(new NodeStatus(node, containerName, modeResult.mode, modeResult.details));
            } catch (RuntimeException e) {
                statuses.add(new NodeStatus(node, "-", "missing", e.getMessage()));
            }
        }
        return statuses;
    }

    private QuorumStatus inspectCluster(String podmanBinary, List<String> nodes) {
        int leaders = 0;
        int followers = 0;
        List<String> unknown = new ArrayList<>();

        for (String node : nodes) {
            String containerName = resolveContainerName(podmanBinary, node);
            ModeResult modeResult = getNodeMode(podmanBinary, containerName);
            if (modeResult.mode.equals("leader")) {
                leaders++;
            } else if (modeResult.mode.equals("follower")) {
                followers++;
            } else {
                unknown.add(node + ":" + modeResult.mode);
            }
        }

        return new QuorumStatus(leaders, followers, unknown);
    }

    private ModeResult getNodeMode(String podmanBinary, String node) {
        PodmanCommandRunner.CommandResult result = podman.run(
            List.of(
                podmanBinary,
                "exec",
                node,
                "sh",
                "-c",
                "for f in /apache-zookeeper-*/bin/zkServer.sh /zookeeper-*/bin/zkServer.sh "
                    + "/bin/zkServer.sh /usr/bin/zkServer.sh; do "
                    + "if [ -x \"$f\" ]; then $f status; exit $?; fi; "
                    + "done; zkServer.sh status"
            ),
            Duration.ofSeconds(10)
        );

        if (result.output().toLowerCase(Locale.ROOT).contains("executable file `sh` not found")) {
            result = podman.run(
                List.of(podmanBinary, "exec", node, "zkServer.sh", "status"),
                Duration.ofSeconds(10)
            );
        }

        String output = result.output().toLowerCase(Locale.ROOT);
        if (output.contains("mode: leader")) {
            return new ModeResult("leader", output);
        }
        if (output.contains("mode: follower")) {
            return new ModeResult("follower", output);
        }
        if (output.contains("mode: standalone")) {
            return new ModeResult("standalone", output);
        }

        String details = output.isBlank() ? "no output" : output;
        if (!result.isSuccess()) {
            return new ModeResult("error", details);
        }
        return new ModeResult("unknown", details);
    }

    private String resolveContainerName(String podmanBinary, String podName) {
        PodmanCommandRunner.CommandResult result = podman.run(
            List.of(podmanBinary, "ps", "--filter", "pod=" + podName, "--format", "{{.Names}}"),
            Duration.ofSeconds(5)
        );

        if (!result.isSuccess() || result.output().isBlank()) {
            throw new IllegalStateException("No running container found for pod " + podName);
        }

        String selected = result.output().lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.endsWith("-infra"))
            .findFirst()
            .orElse("");

        if (selected.isEmpty()) {
            // Fall back to any container (even infra) if that's all we have.
            selected = result.output().lines().findFirst().orElse("").trim();
        }

        if (selected.isEmpty()) {
            throw new IllegalStateException("No running container found for pod " + podName);
        }

        return selected;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record QuorumStatus(int leaders, int followers, List<String> unknown) {
        boolean isHealthy(int expectedNodes) {
            return leaders == 1 && followers == expectedNodes - 1 && unknown.isEmpty();
        }

        String describe() {
            return "Quorum status leaders=" + leaders + " followers=" + followers + " unknown=" + unknown;
        }
    }

    private record ModeResult(String mode, String details) {}
}
