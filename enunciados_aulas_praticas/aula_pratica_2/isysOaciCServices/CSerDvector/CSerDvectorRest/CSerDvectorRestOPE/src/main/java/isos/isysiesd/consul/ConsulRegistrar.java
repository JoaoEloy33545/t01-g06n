package isos.isysiesd.consul;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.HttpServer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
    private static final int RUNTIME_PORT_MAX_ATTEMPTS = 300;
    private static final long RUNTIME_PORT_RETRY_MILLIS = 200L;
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
    Instance<HttpServer> httpServerInstance;
    @Inject
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8081")
    int configuredHttpPort;

    void onStartup(@Observes StartupEvent event) {
        GenericConsulRegistrar.startAsyncOnce(registrationStarted, LOG, this::register);
    }

    private void register() {
        String consulHost = env("CONSUL_HOST", "localhost");
        int consulPort = Integer.parseInt(env("CONSUL_PORT", "8500"));
        boolean failFast = Boolean.parseBoolean(env("CONSUL_FAIL_FAST", "false"));
        boolean consulLikelyInDockerViaHostPort = "localhost".equalsIgnoreCase(consulHost)
                || "127.0.0.1".equals(consulHost);

        // IMPORTANT in Docker: use the service name so other containers can reach it
        String defaultServiceAddress = consulLikelyInDockerViaHostPort ? "host.docker.internal" : "localhost";
        String serviceAddress = env("SERVICE_ADDRESS", defaultServiceAddress);
        int servicePort = resolveServicePort(configuredHttpPort);
        String serviceName = defaultServiceName(configuredHttpPort, servicePort);
        String serviceId = defaultServiceId(configuredHttpPort, servicePort);
        String servicePath = env("SERVICE_PATH", "/dvector");
        String checkHost = env("SERVICE_CHECK_HOST", serviceAddress);
        int checkPort = Integer.parseInt(env("SERVICE_CHECK_PORT", String.valueOf(servicePort)));
        Map<String, String> metadata = GenericConsulRegistrar.metadata(
                "path", servicePath,
                "remoting-protocol", "rest",
                "rest-operations", "GET /dvector/read, POST /dvector/write, GET /dvector/invariantCheck, GET /dvector/sumVector",
                "rest-operation-inputs", "GET /dvector/read:pos;POST /dvector/write:pos,value;GET /dvector/invariantCheck:;GET /dvector/sumVector:",
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

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private int resolveServicePort(int configuredHttpPort) {
        String servicePortEnv = System.getenv("SERVICE_PORT");
        if (servicePortEnv != null && !servicePortEnv.isBlank()) {
            return Integer.parseInt(servicePortEnv);
        }

        int runtimeServerPort = runtimeHttpPortFromServer();
        if (runtimeServerPort > 0) {
            return runtimeServerPort;
        }

        int recorderPort = readActualHttpPortFromRecorder();
        if (recorderPort > 0) {
            return recorderPort;
        }

        if (configuredHttpPort == 0) {
            int runtimePort = waitForRuntimeHttpPort();
            if (runtimePort > 0) {
                return runtimePort;
            }
        }

        String runtimePort = System.getProperty("quarkus.http.port");
        if (runtimePort != null && !runtimePort.isBlank() && !"0".equals(runtimePort)) {
            return Integer.parseInt(runtimePort);
        }

        if (configuredHttpPort != 0) {
            return configuredHttpPort;
        }

        return 8081;
    }

    private int waitForRuntimeHttpPort() {
        for (int i = 0; i < RUNTIME_PORT_MAX_ATTEMPTS; i++) {
            int runtimeServerPort = runtimeHttpPortFromServer();
            if (runtimeServerPort > 0) {
                return runtimeServerPort;
            }

            int recorderPort = readActualHttpPortFromRecorder();
            if (recorderPort > 0) {
                return recorderPort;
            }

            String runtimePort = System.getProperty("quarkus.http.port");
            if (runtimePort != null && !runtimePort.isBlank() && !"0".equals(runtimePort)) {
                try {
                    return Integer.parseInt(runtimePort);
                } catch (NumberFormatException ignored) {
                    // Continue and retry.
                }
            }
            sleepQuietly(RUNTIME_PORT_RETRY_MILLIS);
        }
        return -1;
    }

    private int runtimeHttpPortFromServer() {
        try {
            if (httpServerInstance != null && httpServerInstance.isResolvable()) {
                int port = httpServerInstance.get().getPort();
                if (port > 0) {
                    return port;
                }
            }
        } catch (Exception ignored) {
            // Fallback to other resolution strategies.
        }
        return -1;
    }

    private static int readActualHttpPortFromRecorder() {
        try {
            Class<?> recorder = Class.forName("io.quarkus.vertx.http.runtime.VertxHttpRecorder");
            var f = recorder.getDeclaredField("actualHttpPort");
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof Integer port && port > 0) {
                return port;
            }
        } catch (Exception ignored) {
            // Fallback to other resolution strategies.
        }
        return -1;
    }

    private static String defaultServiceName(int configuredHttpPort, int servicePort) {
        String explicit = System.getenv("SERVICE_NAME");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (configuredHttpPort == 0) {
            return DEFAULT_SERVICE_NAME + "-" + servicePort;
        }
        return DEFAULT_SERVICE_NAME;
    }

    private static String defaultServiceId(int configuredHttpPort, int servicePort) {
        String explicit = System.getenv("SERVICE_ID");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (configuredHttpPort == 0) {
            return DEFAULT_SERVICE_ID + "-" + servicePort;
        }
        return DEFAULT_SERVICE_ID;
    }

    private static void sleepQuietly() {
        sleepQuietly(REGISTER_RETRY_MILLIS);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
