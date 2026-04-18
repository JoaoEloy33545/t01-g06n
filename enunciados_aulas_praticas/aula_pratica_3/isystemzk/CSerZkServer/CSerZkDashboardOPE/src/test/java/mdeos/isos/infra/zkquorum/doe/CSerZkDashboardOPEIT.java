package mdeos.isos.infra.zkquorum.doe;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import mdeos.isos.infra.zkquorum.doe.runtime.ZkQuorumVerifier;

@QuarkusTest
public class CSerZkDashboardOPEIT {

    @Inject
    ZkQuorumVerifier verifier;

    @ConfigProperty(name = "zkquorum.podman.binary")
    String podmanBinary;

    @Test
    void quorumIsHealthy() {
        verifier.ensureHealthy(
            podmanBinary,
            List.of("zoo1", "zoo2", "zoo3", "zoo4"),
            Duration.ofSeconds(90)
        );
    }
}
