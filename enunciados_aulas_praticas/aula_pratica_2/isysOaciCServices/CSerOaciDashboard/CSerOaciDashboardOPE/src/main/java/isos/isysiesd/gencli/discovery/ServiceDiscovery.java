package isos.isysiesd.gencli.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.ConfigProvider;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ServiceDiscovery {
  private final ObjectMapper mapper = new ObjectMapper();

  public List<String> listServices() {
    String serviceTagFilter = env("SERVICE_TAG", env("SOAP_SERVICE_TAG", "")).trim();
    List<String> filterTags = splitCsv(serviceTagFilter);
    try (Client client = ClientBuilder.newClient()) {
      String json = client.target(consulBaseUrl() + "/v1/catalog/services")
          .request(MediaType.APPLICATION_JSON)
          .get(String.class);
      JsonNode catalog = mapper.readTree(json);
      if (!catalog.isObject()) {
        return List.of();
      }

      List<String> services = new ArrayList<>();
      for (Map.Entry<String, JsonNode> entry : iterable(catalog.fields())) {
        String serviceName = entry.getKey();
        if ("consul".equalsIgnoreCase(serviceName)) {
          continue;
        }
        JsonNode tags = entry.getValue();
        boolean matchesFilter = filterTags.isEmpty()
            || (tags.isArray() && containsAnyIgnoreCase(tags, filterTags))
            || containsAnyToken(serviceName, filterTags);
        if (matchesFilter) {
          services.add(serviceName);
        }
      }
      return services.stream().sorted().collect(Collectors.toList());
    } catch (Exception e) {
      throw consulFailure("Unable to query Consul catalog", e);
    }
  }

  public List<ServiceInstance> discoverInstances(String serviceName) {
    try (Client client = ClientBuilder.newClient()) {
      String json = client.target(consulBaseUrl() + "/v1/health/service/" + serviceName + "?passing=true")
          .request(MediaType.APPLICATION_JSON)
          .get(String.class);
      JsonNode arr = mapper.readTree(json);
      if (!arr.isArray() || arr.isEmpty()) {
        throw new IllegalStateException("No healthy instances found for " + serviceName);
      }

      List<ServiceInstance> instances = new ArrayList<>();
      for (JsonNode item : arr) {
        JsonNode svc = item.get("Service");
        String id = svc.path("ID").asText(serviceName);
        String address = svc.path("Address").asText();
        if (address == null || address.isBlank()) {
          address = item.path("Node").path("Address").asText();
        }
        address = rewriteAddress(address);
        int port = svc.path("Port").asInt();
        JsonNode meta = svc.path("Meta");
        String servicePath = extractServicePath(meta, svc.path("Tags"), "");
        Map<String, String> metadata = metadataFrom(meta);
        String protocol = detectProtocol(metadata, servicePath);
        instances.add(new ServiceInstance(serviceName, id, address, port, servicePath, protocol, metadata));
      }

      instances.sort(Comparator.comparing(ServiceInstance::id));
      return instances;
    } catch (Exception e) {
      throw consulFailure("Unable to query Consul health for service " + serviceName, e);
    }
  }

  public List<ServiceInstance> discoverCatalogInstances(String serviceName) {
    try (Client client = ClientBuilder.newClient()) {
      String json = client.target(consulBaseUrl() + "/v1/catalog/service/" + serviceName)
          .request(MediaType.APPLICATION_JSON)
          .get(String.class);
      JsonNode arr = mapper.readTree(json);
      if (!arr.isArray() || arr.isEmpty()) {
        return List.of();
      }

      List<ServiceInstance> instances = new ArrayList<>();
      for (JsonNode item : arr) {
        String id = item.path("ServiceID").asText(serviceName);
        String address = item.path("ServiceAddress").asText();
        if (address == null || address.isBlank()) {
          address = item.path("Address").asText();
        }
        address = rewriteAddress(address);
        int port = item.path("ServicePort").asInt();
        JsonNode serviceMeta = item.path("ServiceMeta");
        String servicePath = extractServicePath(serviceMeta, item.path("ServiceTags"), "");
        if (address != null && !address.isBlank() && port > 0) {
          Map<String, String> metadata = metadataFrom(serviceMeta);
          String protocol = detectProtocol(metadata, servicePath);
          instances.add(new ServiceInstance(serviceName, id, address, port, servicePath, protocol, metadata));
        }
      }
      instances.sort(Comparator.comparing(ServiceInstance::id));
      return instances;
    } catch (Exception e) {
      throw consulFailure("Unable to query Consul catalog for service " + serviceName, e);
    }
  }

  public List<String> discoverEndpoints(String serviceName, String servicePath) {
    return discoverInstances(serviceName).stream()
        .map(instance -> "http://" + instance.address() + ":" + instance.port()
            + normalizePath(instance.servicePath().isBlank() ? servicePath : instance.servicePath()))
        .collect(Collectors.toList());
  }

  public String rewriteEndpoint(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      return endpoint;
    }
    try {
      URI uri = URI.create(endpoint);
      String host = uri.getHost();
      if (host == null || host.isBlank()) {
        return endpoint;
      }
      String rewrittenHost = rewriteAddress(host);
      if (host.equalsIgnoreCase(rewrittenHost)) {
        return endpoint;
      }
      URI rewritten = new URI(
          uri.getScheme(),
          uri.getUserInfo(),
          rewrittenHost,
          uri.getPort(),
          uri.getPath(),
          uri.getQuery(),
          uri.getFragment());
      return rewritten.toString();
    } catch (Exception ignored) {
      return endpoint;
    }
  }

  public record ServiceInstance(
      String serviceName,
      String id,
      String address,
      int port,
      String servicePath,
      String protocol,
      Map<String, String> metadata) {
    public String protocol() {
      return normalizeProtocol(protocol);
    }

    public Map<String, String> metadata() {
      return metadata == null ? Map.of() : metadata;
    }
  }

  public static String detectProtocol(Map<String, String> metadata, String servicePath) {
    if (metadata != null && !metadata.isEmpty()) {
      String fromMeta = firstNonBlank(
          valueIgnoreCase(metadata, "remoting-protocol"),
          valueIgnoreCase(metadata, "protocol"),
          valueIgnoreCase(metadata, "remoting_protocol"));
      if (fromMeta != null && !fromMeta.isBlank()) {
        return normalizeProtocol(fromMeta);
      }
    }
    String normalizedPath = servicePath == null ? "" : servicePath.trim();
    while (normalizedPath.startsWith("/")) {
      normalizedPath = normalizedPath.substring(1);
    }
    if (normalizedPath.toLowerCase().startsWith("grpc:")) {
      return "grpc";
    }
    if (normalizedPath.toLowerCase().startsWith("thrift:")) {
      return "thrift";
    }
    return "soap";
  }

  public static String normalizeProtocol(String raw) {
    if (raw == null || raw.isBlank()) {
      return "soap";
    }
    String value = raw.trim().toLowerCase();
    return switch (value) {
      case "rpc", "grpc" -> "grpc";
      case "rest", "soap", "thrift" -> value;
      default -> value;
    };
  }

  private static String valueIgnoreCase(Map<String, String> metadata, String key) {
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static Map<String, String> metadataFrom(JsonNode meta) {
    if (meta == null || !meta.isObject()) {
      return Map.of();
    }
    Map<String, String> metadata = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : iterable(meta.fields())) {
      JsonNode valueNode = entry.getValue();
      if (valueNode == null || valueNode.isNull()) {
        continue;
      }
      String value = valueNode.asText("");
      if (!value.isBlank()) {
        metadata.put(entry.getKey(), value);
      }
    }
    return metadata;
  }

  private static boolean containsAnyIgnoreCase(JsonNode tags, List<String> tagValues) {
    for (JsonNode tag : tags) {
      String current = tag.asText();
      for (String expected : tagValues) {
        if (expected.equalsIgnoreCase(current)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean containsAnyToken(String value, List<String> tokens) {
    String lowered = value.toLowerCase();
    for (String token : tokens) {
      if (lowered.contains(token.toLowerCase())) {
        return true;
      }
    }
    return false;
  }

  private static List<String> splitCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  private static <T> Iterable<T> iterable(java.util.Iterator<T> iterator) {
    return () -> iterator;
  }

  private static String normalizePath(String path) {
    if (path == null || path.isBlank() || "/".equals(path)) {
      return "";
    }
    String trimmed = path.trim();
    String withoutLeadingSlash = trimmed;
    while (withoutLeadingSlash.startsWith("/")) {
      withoutLeadingSlash = withoutLeadingSlash.substring(1);
    }
    String protocolPath = withoutLeadingSlash.toLowerCase();
    if (protocolPath.startsWith("grpc:") || protocolPath.startsWith("thrift:")) {
      return withoutLeadingSlash;
    }
    return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
  }

  private static String extractServicePath(JsonNode meta, JsonNode tags, String fallbackPath) {
    if (meta != null && meta.isObject()) {
      String pathFromMeta = firstNonBlank(
          valueFromMeta(meta, "path"),
          valueFromMeta(meta, "servicePath"),
          valueFromMeta(meta, "service_path"),
          valueFromMeta(meta, "soapPath"),
          valueFromMeta(meta, "soap_path"),
          valueFromMeta(meta, "contextPath"),
          valueFromMeta(meta, "context_path"),
          detectPathInMeta(meta));
      if (pathFromMeta != null && !pathFromMeta.isBlank()) {
        return normalizeDiscoveredServicePath(pathFromMeta);
      }
    }

    if (tags != null && tags.isArray()) {
      for (JsonNode tagNode : tags) {
        String tag = tagNode.asText("");
        String value = extractPathFromTag(tag);
        if (value == null || value.isBlank()) {
          continue;
        }
        return normalizeDiscoveredServicePath(value);
      }
    }

    return normalizeDiscoveredServicePath(fallbackPath);
  }

  private static String normalizeDiscoveredServicePath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return "";
    }
    String trimmed = rawPath.trim();
    String withoutLeadingSlash = trimmed;
    while (withoutLeadingSlash.startsWith("/")) {
      withoutLeadingSlash = withoutLeadingSlash.substring(1);
    }
    String protocolPath = withoutLeadingSlash.toLowerCase();
    if (protocolPath.startsWith("grpc:") || protocolPath.startsWith("thrift:")) {
      return withoutLeadingSlash;
    }
    return normalizePath(trimmed);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  public List<String> listSoapServices() {
    return listServices();
  }

  private static String valueFromMeta(JsonNode meta, String key) {
    JsonNode value = meta.get(key);
    return value == null || value.isNull() ? null : value.asText(null);
  }

  private static String detectPathInMeta(JsonNode meta) {
    for (Map.Entry<String, JsonNode> entry : iterable(meta.fields())) {
      String key = entry.getKey();
      String normalizedKey = key.replaceAll("[_\\-\\s]", "").toLowerCase();
      if (!normalizedKey.contains("path")) {
        continue;
      }
      JsonNode valueNode = entry.getValue();
      if (valueNode != null && !valueNode.isNull()) {
        String value = valueNode.asText("");
        if (!value.isBlank()) {
          return value;
        }
      }
    }
    return null;
  }

  private static String extractPathFromTag(String rawTag) {
    if (rawTag == null || rawTag.isBlank()) {
      return null;
    }

    String tag = rawTag.trim();
    int separator = findSeparator(tag);
    if (separator > 0) {
      String key = tag.substring(0, separator).trim().replaceAll("[_\\-\\s]", "").toLowerCase();
      String value = tag.substring(separator + 1).trim();
      if ((key.contains("path") || key.contains("context")) && !value.isBlank()) {
        return value;
      }
    }

    if (tag.startsWith("/")) {
      return tag;
    }
    return null;
  }

  private static int findSeparator(String value) {
    int eq = value.indexOf('=');
    int colon = value.indexOf(':');
    if (eq < 0) {
      return colon;
    }
    if (colon < 0) {
      return eq;
    }
    return Math.min(eq, colon);
  }

  private static String rewriteAddress(String originalAddress) {
    if (originalAddress == null || originalAddress.isBlank()) {
      return originalAddress;
    }

    for (Map.Entry<String, String> rule : parseHostRewriteRules().entrySet()) {
      if (originalAddress.equalsIgnoreCase(rule.getKey())) {
        return rule.getValue();
      }
    }
    return originalAddress;
  }

  private static Map<String, String> parseHostRewriteRules() {
    String raw = env("SERVICE_HOST_REWRITE", "").trim();
    if (raw.isBlank()) {
      return Map.of();
    }

    Map<String, String> rules = new java.util.LinkedHashMap<>();
    for (String token : raw.split(",")) {
      String part = token.trim();
      int idx = part.indexOf('=');
      if (idx <= 0 || idx >= part.length() - 1) {
        continue;
      }
      String from = part.substring(0, idx).trim();
      String to = part.substring(idx + 1).trim();
      if (!from.isEmpty() && !to.isEmpty()) {
        rules.put(from, to);
      }
    }
    return rules;
  }

  private static String env(String k, String def) {
    String v = readConfig(k);
    return (v == null || v.isBlank()) ? def : v;
  }

  private static String readConfig(String envKey) {
    String value = System.getenv(envKey);
    if (value != null && !value.isBlank()) {
      return value;
    }

    value = System.getProperty(envKey);
    if (value != null && !value.isBlank()) {
      return value;
    }

    String dotKey = envKey.toLowerCase().replace('_', '.');
    try {
      value = ConfigProvider.getConfig().getOptionalValue(envKey, String.class).orElse(null);
      if (value != null && !value.isBlank()) {
        return value;
      }
      value = ConfigProvider.getConfig().getOptionalValue(dotKey, String.class).orElse(null);
      if (value != null && !value.isBlank()) {
        return value;
      }
    } catch (Exception ignored) {
      // Keep fallback behavior when config is not available in this context.
    }
    return null;
  }

  private static String consulBaseUrl() {
    String directUrl = env("CONSUL_URL", "").trim();
    if (!directUrl.isBlank()) {
      return directUrl.endsWith("/") ? directUrl.substring(0, directUrl.length() - 1) : directUrl;
    }
    String host = env("CONSUL_HOST", "localhost");
    String port = env("CONSUL_PORT", "8500");
    return "http://" + host + ":" + port;
  }

  private static RuntimeException consulFailure(String message, Exception cause) {
    String details = message
        + ". Resolved Consul endpoint: " + consulBaseUrl()
        + ". If running locally, set CONSUL_HOST=localhost (or CONSUL_URL=http://localhost:8500).";
    return new RuntimeException(details, cause);
  }
}
