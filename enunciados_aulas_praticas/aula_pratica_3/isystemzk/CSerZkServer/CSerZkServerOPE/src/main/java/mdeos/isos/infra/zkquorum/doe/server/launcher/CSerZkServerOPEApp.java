package mdeos.isos.infra.zkquorum.doe.server.launcher;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;

public class CSerZkServerOPEApp implements QuarkusApplication {

    public static void main(String[] args) {
        Quarkus.run(CSerZkServerOPEApp.class, args);
    }

    @Override
    public int run(String... args) {
        System.out.println("CSerZkServerOPE ready.");
        System.out.println("Podman manifest: src/main/resources/podman/zk-quorum.yaml");
        Quarkus.waitForExit();
        return 0;
    }
}
