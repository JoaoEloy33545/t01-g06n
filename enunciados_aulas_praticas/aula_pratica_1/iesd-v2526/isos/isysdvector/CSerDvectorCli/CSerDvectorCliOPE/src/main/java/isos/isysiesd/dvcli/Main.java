package isos.isysiesd.dvcli;

import io.quarkus.runtime.annotations.QuarkusMain;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;

@QuarkusMain  
public class Main {
    public static void main(String... args) {
        Quarkus.run(DvectorClient.class, args);
    }

    public static class DvectorClient implements QuarkusApplication {

        @Override
        public int run(String... args) throws Exception {
            System.out.println("The place to put initialization logic...");
            isos.isysiesd.dvcli.DvectorClient.main(args);
            Quarkus.waitForExit();
            System.out.println("Somehow, quarkus/java runtime, exited...");
            return 0;
        }
    }
}    
