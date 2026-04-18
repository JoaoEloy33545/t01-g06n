package isos.isysiesd.dvimpl;

import io.quarkus.runtime.annotations.QuarkusMain;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;

@QuarkusMain  
public class Main {
    public static void main(String... args) {
        Quarkus.run(DvectorImpl.class, args);
    }

    public static class DvectorImpl implements QuarkusApplication {

        @Override
        public int run(String... args) throws Exception {
            System.out.println("The place to put initialization logic...");
            Quarkus.waitForExit();
            System.out.println("Somehow, quarkus/java runtime, exited...");
            return 0;
        }
    }
}    
