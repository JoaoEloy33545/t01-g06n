package isos.isysiesd.dvimpl;

import isos.isysiesd.dvapi.Dvector;
import jakarta.jws.WebService;
import java.util.Arrays;
import java.util.List;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@WebService(
        endpointInterface = "isos.isysiesd.dvapi.Dvector",
        serviceName = "Dvector",
        portName = "DvectorPort"
)
public class DvectorImpl implements Dvector {

  @ConfigProperty(name = "dvector01-service-port", defaultValue = "8081")
  int port;
  private static final List<Integer> vector = Arrays.asList(300, 234, 56, 789);

  /*
  public void init(@Observes StartupEvent ev, Vertx vertx) {
    vertx.createHttpServer()
            .requestHandler(req -> req.response().endAndForget("Hello from dvector01!"))
            .listenAndAwait(port);
  }
   */
  @Override
  public int read(int pos) {
    int validPos = validatePosition(pos);
    System.out.println("Reading from vector position " + pos);
    return vector.get(validPos);
  }

  @Override
  public void write(int pos, int value) {
    int validPos = validatePosition(pos);
    System.out.println("Writing to vector in position " + pos + " with " + value);
    vector.set(validPos, value);
  }

  @Override
  public String invariantCheck() {
    // The computing logic to validate data consistency
    return "soap:not defined";
  }

  @Override
  public int sumVector() {
    var sum = 0;
    for (int i = 0; i < vector.size() ; i++) {
       sum += vector.get(i);
    }
    return sum;
  }

  private int validatePosition(int pos) {
    if (pos < 0 || pos >= vector.size()) {
      throw new IllegalArgumentException("Invalid argument 'pos': must be between 0 and " + (vector.size() - 1));
    }
    return pos;
  }
}
