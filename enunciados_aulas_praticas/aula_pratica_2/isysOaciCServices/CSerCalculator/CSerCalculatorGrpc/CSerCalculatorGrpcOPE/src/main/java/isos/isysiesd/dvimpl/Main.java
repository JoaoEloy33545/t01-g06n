package isos.isysiesd.dvimpl;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class Main {
  public static void main(String... args) {
    Quarkus.run(CalculatorApp.class, args);
  }

  public static class CalculatorApp implements QuarkusApplication {
    @Override
    public int run(String... args) {
      Quarkus.waitForExit();
      return 0;
    }
  }
}
