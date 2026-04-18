package mdeos.isos.infra.zkquorum.doe.launcher;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class CSerZkDashboardOPEApp implements QuarkusApplication {

    public static void main(String[] args) {
        Quarkus.run(CSerZkDashboardOPEApp.class, args);
    }

    @Override
    public int run(String... args) {
        System.out.println("CSerZkDashboardOPE ready.");
        System.out.println("UI: http://localhost:5086/");
        Quarkus.waitForExit();
        return 0;
    }
}
