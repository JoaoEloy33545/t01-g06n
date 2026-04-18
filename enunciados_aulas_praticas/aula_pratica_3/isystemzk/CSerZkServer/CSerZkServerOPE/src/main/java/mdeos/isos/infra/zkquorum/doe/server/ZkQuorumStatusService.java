package mdeos.isos.infra.zkquorum.doe.server;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mdeos.isos.infra.zkquorum.doe.runtime.ZkQuorumVerifier;
import mdeos.isos.infra.zkquorum.doe.podman.PodmanCommandRunner;

@ApplicationScoped
public class ZkQuorumStatusService {

    private static final List<String> NODES = List.of(
        "zoo1", "zoo2", "zoo3", "zoo4", "zoo5", "zoo6"
    );

    private final String podmanBinary;
    private final ZkQuorumVerifier verifier;
    private final PodmanCommandRunner podman = new PodmanCommandRunner();
    private String lastPodmanOutput = "";

    public ZkQuorumStatusService(
        ZkQuorumVerifier verifier,
        @ConfigProperty(name = "zkquorum.podman.binary", defaultValue = "podman") String podmanBinary
    ) {
        this.verifier = verifier;
        this.podmanBinary = podmanBinary;
    }

    public record ClusterSnapshot(List<ZkQuorumVerifier.NodeStatus> nodes, int runningNodes) {}

    public ClusterSnapshot getClusterSnapshot() {
        Map<String, String> podStatus = listPodStatus();
        List<ZkQuorumVerifier.NodeStatus> nodes = NODES.stream()
            .map(node -> {
                String podName = resolvePodName(node, podStatus);
                if (podName == null) {
                    return new ZkQuorumVerifier.NodeStatus(node, "-", "missing", "Pod not found");
                }
                String status = podStatus.get(podName);
                return new ZkQuorumVerifier.NodeStatus(node, podName, "pod", status);
            })
            .collect(Collectors.toList());

        int running = nodes.stream()
            .filter(n -> n.details() != null && n.details().toLowerCase().contains("running"))
            .mapToInt(n -> 1)
            .sum();

        return new ClusterSnapshot(nodes, running);
    }

    public String getLastPodmanOutput() {
        return lastPodmanOutput;
    }

    private String resolvePodName(String node, Map<String, String> podStatus) {
        if (podStatus.containsKey(node)) {
            return node;
        }
        String nodeLower = node.toLowerCase();
        for (String podName : podStatus.keySet()) {
            String podLower = podName.toLowerCase();
            if (podLower.equals(nodeLower)
                || podLower.startsWith(nodeLower + "-")
                || podLower.endsWith("-" + nodeLower)
                || podLower.contains(nodeLower)) {
                return podName;
            }
        }
        return null;
    }

    private Map<String, String> listPodStatus() {
        PodmanCommandRunner.CommandResult result = podman.run(
            List.of(podmanBinary, "pod", "ps", "--format", "{{.Name}} {{.Status}}"),
            java.time.Duration.ofSeconds(5)
        );
        lastPodmanOutput = result.output();
        if (!result.isSuccess() || result.output().isBlank()) {
            return Map.of();
        }
        return result.output().lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .map(line -> line.split("\\s+", 2))
            .filter(parts -> parts.length == 2)
            .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1], (a, b) -> a));
    }
}
