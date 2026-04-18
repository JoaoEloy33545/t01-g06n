package isos.isysiesd.consul;

import isos.isysiesd.dvimpl.DvectorThriftService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
@Startup
public class ConsulRegistrar {

    private static final Logger LOG = Logger.getLogger(ConsulRegistrar.class);
    private static final String DEFAULT_SERVICE_NAME = "CSerDvector";
    private static final String DEFAULT_SERVICE_ID = "CSerDvector01";
    private static final int REGISTER_MAX_ATTEMPTS = 15;
    private static final int DEREGISTER_MAX_ATTEMPTS = 5;
    private static final long REGISTER_RETRY_MILLIS = 2000L;
    private static final long DEREGISTER_RETRY_MILLIS = 1000L;
    private static final int PORT_WAIT_MAX_ATTEMPTS = 300;
    private static final long PORT_WAIT_RETRY_MILLIS = 200L;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final GenericConsulRegistrar.Session consulSession = new GenericConsulRegistrar.Session();
    private static final GenericConsulRegistrar.Options CONSUL_OPTIONS =
            new GenericConsulRegistrar.Options(
                    REGISTER_MAX_ATTEMPTS,
                    REGISTER_RETRY_MILLIS,
                    DEREGISTER_MAX_ATTEMPTS,
                    DEREGISTER_RETRY_MILLIS);
    private final AtomicBoolean registrationStarted = new AtomicBoolean(false);
    private final String startingTimestamp = TIMESTAMP_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));

    @Inject
    DvectorThriftService thriftService;

    void onStartup(@Observes StartupEvent event) {
        GenericConsulRegistrar.startAsyncOnce(registrationStarted, LOG, this::register);
    }

    private void register() {
        String consulHost = env("CONSUL_HOST", "localhost");
        int consulPort = Integer.parseInt(env("CONSUL_PORT", "8500"));
        boolean failFast = Boolean.parseBoolean(env("CONSUL_FAIL_FAST", "false"));
        boolean consulLikelyInDockerViaHostPort = "localhost".equalsIgnoreCase(consulHost)
                || "127.0.0.1".equals(consulHost);

        String defaultServiceAddress = consulLikelyInDockerViaHostPort ? "host.docker.internal" : "localhost";
        String serviceAddress = env("SERVICE_ADDRESS", defaultServiceAddress);
        int servicePort = resolveServicePort();
        String serviceName = defaultServiceName(servicePort);
        String serviceId = defaultServiceId(servicePort);
        String servicePath = env("SERVICE_PATH", "thrift:isos.isysiesd.dvapi.thrift.Dvector");
        String checkHost = env("SERVICE_CHECK_HOST", serviceAddress);
        int checkPort = Integer.parseInt(env("SERVICE_CHECK_PORT", String.valueOf(servicePort)));
        Map<String, String> metadata = GenericConsulRegistrar.metadata(
                "path", servicePath,
                "remoting-protocol", "thrift",
                "thrift-operations", "read,write,invariantCheck,sumVector",
                "thrift-operation-inputs", "read:pos;write:pos,value;invariantCheck:;sumVector:",
                "operation-inputs", "read:pos;write:pos,value;invariantCheck:;sumVector:",
                "unidade-curricular", "IESD",
                "starting-timestamp", startingTimestamp,
                "version", "0.1.0");

        GenericConsulRegistrar.ServiceRegistration registration = new GenericConsulRegistrar.ServiceRegistration(
                serviceName,
                serviceId,
                serviceAddress,
                servicePort,
                checkHost,
                checkPort,
                metadata);

        GenericConsulRegistrar.register(consulSession, consulHost, consulPort, failFast, CONSUL_OPTIONS, registration, LOG);
    }

    @PreDestroy
    void deregister() {
        GenericConsulRegistrar.deregister(consulSession, CONSUL_OPTIONS, LOG);
    }

    private int resolveServicePort() {
        String servicePortEnv = System.getenv("SERVICE_PORT");
        if (servicePortEnv != null && !servicePortEnv.isBlank()) {
            return Integer.parseInt(servicePortEnv);
        }

        for (int i = 0; i < PORT_WAIT_MAX_ATTEMPTS; i++) {
            int port = thriftService.listeningPort();
            if (port > 0) {
                return port;
            }
            sleepQuietly(PORT_WAIT_RETRY_MILLIS);
        }

        throw new IllegalStateException("Unable to resolve Thrift server port for Consul registration");
    }

    private static String defaultServiceName(int servicePort) {
        String explicit = System.getenv("SERVICE_NAME");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return DEFAULT_SERVICE_NAME + "-" + servicePort;
    }

    private static String defaultServiceId(int servicePort) {
        String explicit = System.getenv("SERVICE_ID");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return DEFAULT_SERVICE_ID + "-" + servicePort;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
