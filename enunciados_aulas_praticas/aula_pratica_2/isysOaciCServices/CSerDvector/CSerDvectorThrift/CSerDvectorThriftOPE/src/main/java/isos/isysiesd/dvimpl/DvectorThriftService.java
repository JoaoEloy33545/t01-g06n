package isos.isysiesd.dvimpl;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import isos.isysiesd.dvapi.thrift.Dvector;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
@Startup
public class DvectorThriftService implements Dvector.Iface {

  private static final Logger LOG = Logger.getLogger(DvectorThriftService.class);
  private static final List<Integer> VECTOR =
      Collections.synchronizedList(new ArrayList<>(List.of(300, 234, 56, 789)));

  @ConfigProperty(name = "dvector.thrift.bind-host", defaultValue = "0.0.0.0")
  String bindHost;

  @ConfigProperty(name = "dvector.thrift.port", defaultValue = "0")
  int configuredPort;

  private volatile TServer server;
  private volatile ExecutorService serverExecutor;
  private volatile int listeningPort = -1;

  void onStartup(@Observes StartupEvent event) {
    startServer();
  }

  public int listeningPort() {
    return listeningPort;
  }

  private synchronized void startServer() {
    if (server != null) {
      return;
    }

    try {
      TServerSocket serverTransport = new TServerSocket(new InetSocketAddress(bindHost, configuredPort));
      listeningPort = serverTransport.getServerSocket().getLocalPort();

      Dvector.Processor<DvectorThriftService> processor = new Dvector.Processor<>(this);
      TThreadPoolServer.Args args = new TThreadPoolServer.Args(serverTransport)
          .processor(processor)
          .protocolFactory(new TBinaryProtocol.Factory());
      server = new TThreadPoolServer(args);

      serverExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dvector-thrift-server");
        t.setDaemon(true);
        return t;
      });
      serverExecutor.submit(server::serve);

      LOG.infof("Dvector Thrift server listening on %s:%d", bindHost, listeningPort);
    } catch (TTransportException e) {
      throw new RuntimeException("Unable to start Dvector Thrift server", e);
    }
  }

  @PreDestroy
  void stopServer() {
    TServer runningServer = server;
    if (runningServer != null) {
      runningServer.stop();
    }

    ExecutorService executor = serverExecutor;
    if (executor != null) {
      executor.shutdownNow();
    }

    server = null;
    serverExecutor = null;
  }

  @Override
  public int read(int pos) throws TException {
    int validPos = validatePosition(pos);
    return VECTOR.get(validPos);
  }

  @Override
  public void write(int pos, int value) throws TException {
    int validPos = validatePosition(pos);
    VECTOR.set(validPos, value);
  }

  @Override
  public String invariantCheck() throws TException {
    return "thrift:not defined";
  }

  @Override
  public int sumVector() throws TException {
    int sum = 0;
    for (Integer value : VECTOR) {
      sum += value;
    }
    return sum;
  }

  private static int validatePosition(int pos) {
    if (pos < 0 || pos >= VECTOR.size()) {
      throw new IllegalArgumentException("Invalid argument 'pos': must be between 0 and " + (VECTOR.size() - 1));
    }
    return pos;
  }
}
