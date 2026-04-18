package isos.isysiesd.consul;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GenericConsulRegistrar {

    private GenericConsulRegistrar() {
    }

    public record Options(
            int registerMaxAttempts,
            long registerRetryMillis,
            int deregisterMaxAttempts,
            long deregisterRetryMillis) {
    }

    public record ServiceRegistration(
            String serviceName,
            String serviceId,
            String serviceAddress,
            int servicePort,
            String checkHost,
            int checkPort,
            Map<String, String> metadata) {
    }

    public static final class Session {
        volatile String deregisterUrl;
        volatile String registeredServiceId;
        volatile boolean registrationSucceeded;
        volatile Client consulClient;
    }

    public static void startAsyncOnce(AtomicBoolean started, Logger log, Runnable registerAction) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info("Starting Consul registration flow...");
        CompletableFuture.runAsync(registerAction)
                .exceptionally(ex -> {
                    log.warn("Consul registration flow aborted; service will continue without Consul registration", ex);
                    return null;
                });
    }

    public static void register(
            Session session,
            String consulHost,
            int consulPort,
            boolean failFast,
            Options options,
            ServiceRegistration registration,
            Logger log) {
        Client client = session.consulClient;
        if (client == null) {
            client = ClientBuilder.newClient();
            session.consulClient = client;
        }

        String registerUrl = "http://" + consulHost + ":" + consulPort + "/v1/agent/service/register";
        session.deregisterUrl = "http://" + consulHost + ":" + consulPort + "/v1/agent/service/deregister/" + registration.serviceId();
        session.registeredServiceId = registration.serviceId();
        session.registrationSucceeded = false;

        String payload = registrationPayload(registration);
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= options.registerMaxAttempts(); attempt++) {
            try {
                Response response = client.target(registerUrl)
                        .request(MediaType.APPLICATION_JSON)
                        .put(Entity.json(payload));

                if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                    session.registrationSucceeded = true;
                    log.infof("Registered service in Consul: name=%s id=%s address=%s:%d",
                            registration.serviceName(),
                            registration.serviceId(),
                            registration.serviceAddress(),
                            registration.servicePort());
                    log.infof("Consul health check target: tcp://%s:%d",
                            registration.checkHost(),
                            registration.checkPort());
                    return;
                }

                String body = response.hasEntity() ? response.readEntity(String.class) : "";
                throw new RuntimeException("Consul returned HTTP " + response.getStatus() + " " + body);
            } catch (Exception e) {
                lastFailure = new RuntimeException(
                        "Consul registration attempt %d/%d failed".formatted(attempt, options.registerMaxAttempts()),
                        e);
                log.warn(lastFailure.getMessage(), e);
                sleepQuietly(options.registerRetryMillis());
            }
        }

        if (failFast) {
            throw new RuntimeException("Could not register service in Consul after retries", lastFailure);
        }
        log.warn("Could not register service in Consul after retries; continuing startup", lastFailure);
    }

    public static void deregister(Session session, Options options, Logger log) {
        try {
            if (!session.registrationSucceeded || session.deregisterUrl == null || session.registeredServiceId == null) {
                return;
            }

            RuntimeException lastFailure = null;
            Client client = session.consulClient;
            if (client == null) {
                return;
            }

            for (int attempt = 1; attempt <= options.deregisterMaxAttempts(); attempt++) {
                try {
                    Response response = client.target(session.deregisterUrl)
                            .request(MediaType.APPLICATION_JSON)
                            .put(Entity.text(""));
                    if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                        log.infof("Deregistered service from Consul: id=%s", session.registeredServiceId);
                        return;
                    }
                    String body = response.hasEntity() ? response.readEntity(String.class) : "";
                    throw new RuntimeException("Consul returned HTTP " + response.getStatus() + " " + body);
                } catch (Exception e) {
                    lastFailure = new RuntimeException(
                            "Consul deregistration attempt %d/%d failed".formatted(attempt, options.deregisterMaxAttempts()),
                            e);
                    log.warn(lastFailure.getMessage(), e);
                    sleepQuietly(options.deregisterRetryMillis());
                }
            }

            log.warnf(lastFailure, "Could not deregister service from Consul: id=%s", session.registeredServiceId);
            try {
                client.close();
            } catch (Exception ignored) {
                // Best effort during shutdown.
            } finally {
                session.consulClient = null;
            }
        } catch (Throwable t) {
            log.warn("Unexpected error while deregistering service from Consul during shutdown", t);
        }
    }

    private static String registrationPayload(ServiceRegistration registration) {
        StringBuilder meta = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : registration.metadata().entrySet()) {
            if (!first) {
                meta.append(",\n            ");
            }
            first = false;
            meta.append('"').append(escape(entry.getKey())).append("\": \"")
                    .append(escape(entry.getValue())).append('"');
        }

        return """
        {
          "Name": "%s",
          "ID": "%s",
          "Address": "%s",
          "Port": %d,
          "Meta": {
            %s
          },
          "Check": {
            "TCP": "%s:%d",
            "Interval": "10s",
            "DeregisterCriticalServiceAfter": "1m"
          }
        }
        """.formatted(
                escape(registration.serviceName()),
                escape(registration.serviceId()),
                escape(registration.serviceAddress()),
                registration.servicePort(),
                meta,
                escape(registration.checkHost()),
                registration.checkPort());
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static Map<String, String> metadata(String... keyValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        return values;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
