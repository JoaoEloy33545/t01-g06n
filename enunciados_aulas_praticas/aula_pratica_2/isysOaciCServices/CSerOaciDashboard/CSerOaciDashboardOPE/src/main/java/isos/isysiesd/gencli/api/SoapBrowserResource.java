package isos.isysiesd.gencli.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import isos.isysiesd.gencli.client.GenericServiceInvoker;
import isos.isysiesd.gencli.discovery.ServiceDiscovery;
import isos.isysiesd.gencli.discovery.ServiceDiscovery.ServiceInstance;

@Path("/soap-browser")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SoapBrowserResource {

  @Inject
  ServiceDiscovery discovery;

  @Inject
  GenericServiceInvoker invoker;

  @GET
  @Path("/services")
  public List<ServiceView> services(@QueryParam("path") String servicePath) {
    String normalizedPath = normalizePath(servicePath);
    return discovery.listServices().stream()
        .map(service -> toServiceView(service, normalizedPath))
        .toList();
  }

  @GET
  @Path("/operations")
  public OperationsResponse operations(
      @QueryParam("serviceName") String serviceName,
      @QueryParam("instanceId") String instanceId,
      @QueryParam("path") String servicePath,
      @QueryParam("endpoint") String endpoint,
      @QueryParam("protocol") String protocol) {
    try {
      ServiceInstance selected = resolveInstanceOrExplicit(serviceName, instanceId, endpoint, protocol, servicePath);
      String resolvedProtocol = resolveProtocol(protocol, selected, servicePath);
      String resolvedPath = resolveServicePath(servicePath, selected);
      String resolvedEndpoint = resolveEndpoint(selected, resolvedPath, endpoint, serviceName);

      List<String> operations = invoker.listOperations(resolvedProtocol, resolvedEndpoint, resolvedPath, selected.metadata());
      Map<String, List<String>> inputsByOperation = invoker.listOperationInputs(
          resolvedProtocol,
          resolvedEndpoint,
          resolvedPath,
          operations,
          selected.metadata());
      return new OperationsResponse(resolvedEndpoint, resolvedProtocol, operations, inputsByOperation);
    } catch (Exception e) {
      try {
        ServiceInstance selected = resolveInstanceOrExplicit(serviceName, instanceId, endpoint, protocol, servicePath);
        String resolvedProtocol = resolveProtocol(protocol, selected, servicePath);
        String resolvedPath = resolveServicePath(servicePath, selected);
        String resolvedEndpoint = resolveEndpoint(selected, resolvedPath, endpoint, serviceName);
        if ("grpc".equalsIgnoreCase(resolvedProtocol)) {
          Map<String, List<String>> hintedInputs = new LinkedHashMap<>(invoker.listOperationInputs(
              resolvedProtocol,
              resolvedEndpoint,
              resolvedPath,
              List.of(),
              selected.metadata()));
          List<String> hintedOps = hintedInputs.keySet().stream().toList();
          return new OperationsResponse(resolvedEndpoint, resolvedProtocol, hintedOps, hintedInputs);
        }
      } catch (Exception ignored) {
        // Fall through and return a structured error below.
      }
      Response response = Response.status(Response.Status.BAD_GATEWAY)
          .type(MediaType.APPLICATION_JSON)
          .entity(Map.of(
              "error", "Operations discovery failed",
              "details", firstUsefulMessage(e)))
          .build();
      throw new WebApplicationException(response);
    }
  }

  @GET
  @Path("/idl")
  public InterfaceDefinitionResponse idl(
      @QueryParam("serviceName") String serviceName,
      @QueryParam("instanceId") String instanceId,
      @QueryParam("path") String servicePath,
      @QueryParam("endpoint") String endpoint,
      @QueryParam("protocol") String protocol) {
    try {
      ServiceInstance selected = resolveInstanceOrExplicit(serviceName, instanceId, endpoint, protocol, servicePath);
      String resolvedProtocol = resolveProtocol(protocol, selected, servicePath);
      String resolvedPath = resolveServicePath(servicePath, selected);
      String resolvedEndpoint = resolveEndpoint(selected, resolvedPath, endpoint, serviceName);

      GenericServiceInvoker.InterfaceDefinition idl = invoker.interfaceDefinition(
          resolvedProtocol,
          resolvedEndpoint,
          resolvedPath,
          serviceName,
          selected.metadata());
      return new InterfaceDefinitionResponse(idl.language(), idl.content());
    } catch (Exception e) {
      Response response = Response.status(Response.Status.BAD_GATEWAY)
          .type(MediaType.APPLICATION_JSON)
          .entity(Map.of(
              "error", "Interface definition retrieval failed",
              "details", firstUsefulMessage(e)))
          .build();
      throw new WebApplicationException(response);
    }
  }

  @POST
  @Path("/invoke")
  public InvokeResponse invoke(InvokeRequest request) {
    validate(request);

    ServiceInstance selected = resolveInstanceOrExplicit(
        request.serviceName(),
        request.instanceId(),
        request.endpoint(),
        request.protocol(),
        request.servicePath());
    String protocol = resolveProtocol(request.protocol(), selected, request.servicePath());
    String servicePath = resolveServicePath(request.servicePath(), selected);
    String endpoint = resolveEndpoint(selected, servicePath, request.endpoint(), request.serviceName());
    try {
      List<String> response = invoker.invoke(
          protocol,
          endpoint,
          servicePath,
          request.operation(),
          request.parameters(),
          selected.metadata());
      return new InvokeResponse(endpoint, protocol, request.operation(), response);
    } catch (Exception e) {
      String message = userFriendlyInvokeError(e, request.operation(), protocol);
      Response.Status status = isArgumentShapeError(e, protocol) ? Response.Status.BAD_REQUEST : Response.Status.BAD_GATEWAY;
      Response response = Response.status(status)
          .type(MediaType.APPLICATION_JSON)
          .entity(Map.of(
              "error", "Invocation failed",
              "details", message))
          .build();
      throw new WebApplicationException(response);
    }
  }

  private ServiceView toServiceView(String serviceName, String servicePath) {
    List<InstanceView> instances = discoverHealthyFirst(serviceName).stream()
        .map(instance -> new InstanceView(
            instance.id(),
            instance.address(),
            instance.port(),
            instance.servicePath(),
            instance.protocol(),
            endpointFor(instance, servicePath),
            instance.metadata()))
        .toList();
    return new ServiceView(serviceName, instances);
  }

  private ServiceInstance resolveInstance(String serviceName, String instanceId) {
    if (serviceName == null || serviceName.isBlank()) {
      return new ServiceInstance("", "", "", 0, "", "soap", Map.of());
    }
    List<ServiceInstance> instances = discoverHealthyFirst(serviceName);
    if (instances.isEmpty()) {
      throw new WebApplicationException("No registered instances found for service " + serviceName, Response.Status.BAD_REQUEST);
    }
    return instances.stream()
        .filter(instance -> Objects.equals(instance.id(), instanceId))
        .findFirst()
        .orElse(instances.get(0));
  }

  private ServiceInstance resolveInstanceOrExplicit(
      String serviceName,
      String instanceId,
      String explicitEndpoint,
      String requestProtocol,
      String servicePath) {
    try {
      return resolveInstance(serviceName, instanceId);
    } catch (WebApplicationException ex) {
      if (explicitEndpoint != null && !explicitEndpoint.isBlank()) {
        String protocol = ServiceDiscovery.normalizeProtocol(firstNonBlank(requestProtocol, ServiceDiscovery.detectProtocol(Map.of(), servicePath), "soap"));
        return new ServiceInstance(serviceName == null ? "" : serviceName, "", "", 0, servicePath == null ? "" : servicePath, protocol, Map.of());
      }
      throw ex;
    }
  }

  private List<ServiceInstance> discoverHealthyFirst(String serviceName) {
    try {
      List<ServiceInstance> healthy = discovery.discoverInstances(serviceName);
      if (healthy != null && !healthy.isEmpty()) {
        return healthy;
      }
    } catch (Exception ignored) {
      // Fall back to catalog when health API is unavailable.
    }
    return discovery.discoverCatalogInstances(serviceName);
  }

  private String resolveEndpoint(ServiceInstance selected, String servicePath, String explicitEndpoint, String serviceName) {
    if (explicitEndpoint != null && !explicitEndpoint.isBlank()) {
      String rewritten = discovery.rewriteEndpoint(explicitEndpoint);
      String fallbackPath = normalizePath(firstNonBlank(servicePath, selected.servicePath(), guessPathFromServiceName(serviceName)));
      return withPathIfMissing(rewritten, fallbackPath);
    }

    String fallbackPath = firstNonBlank(servicePath, selected.servicePath(), guessPathFromServiceName(serviceName));
    return discovery.rewriteEndpoint(endpointFor(selected, fallbackPath));
  }

  private static String resolveServicePath(String requestPath, ServiceInstance selected) {
    return firstNonBlank(requestPath, selected.servicePath(), "");
  }

  private static String resolveProtocol(String requestProtocol, ServiceInstance selected, String servicePath) {
    if (requestProtocol != null && !requestProtocol.isBlank()) {
      return ServiceDiscovery.normalizeProtocol(requestProtocol);
    }
    if (selected.protocol() != null && !selected.protocol().isBlank()) {
      return ServiceDiscovery.normalizeProtocol(selected.protocol());
    }
    return ServiceDiscovery.detectProtocol(selected.metadata(), servicePath);
  }

  private static String endpointFor(ServiceInstance instance, String fallbackPath) {
    if ("grpc".equalsIgnoreCase(instance.protocol())
        || "thrift".equalsIgnoreCase(instance.protocol())
        || (instance.servicePath() != null && (instance.servicePath().toLowerCase().startsWith("grpc:")
        || instance.servicePath().toLowerCase().startsWith("thrift:")))) {
      return "http://" + instance.address() + ":" + instance.port();
    }
    String path = instance.servicePath();
    if (path == null || path.isBlank()) {
      path = normalizePath(fallbackPath);
    } else {
      path = normalizePath(path);
    }
    return "http://" + instance.address() + ":" + instance.port() + path;
  }

  private static String withPathIfMissing(String endpoint, String fallbackPath) {
    if (endpoint == null || endpoint.isBlank() || fallbackPath == null || fallbackPath.isBlank()) {
      return endpoint;
    }
    String normalizedFallback = fallbackPath.trim().toLowerCase();
    while (normalizedFallback.startsWith("/")) {
      normalizedFallback = normalizedFallback.substring(1);
    }
    if (normalizedFallback.startsWith("grpc:") || normalizedFallback.startsWith("thrift:")) {
      return endpoint;
    }
    try {
      URI uri = URI.create(endpoint);
      String currentPath = uri.getPath();
      if (currentPath != null && !currentPath.isBlank() && !"/".equals(currentPath)) {
        return endpoint;
      }
      URI rewritten = new URI(
          uri.getScheme(),
          uri.getUserInfo(),
          uri.getHost(),
          uri.getPort(),
          normalizePath(fallbackPath),
          uri.getQuery(),
          uri.getFragment());
      return rewritten.toString();
    } catch (Exception e) {
      return endpoint;
    }
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

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private static String guessPathFromServiceName(String serviceName) {
    if (serviceName == null || serviceName.isBlank()) {
      return "";
    }
    String base = serviceName.trim();
    int dash = base.indexOf('-');
    if (dash > 0) {
      base = base.substring(0, dash);
    }
    // Prefer last camel-case token: CSerDvector -> dvector
    String token = base.replaceAll("([a-z])([A-Z])", "$1 $2").trim();
    String[] parts = token.split("\\s+");
    if (parts.length > 0) {
      String last = parts[parts.length - 1].replaceAll("[^A-Za-z0-9]", "");
      if (!last.isBlank()) {
        return "/" + last.toLowerCase();
      }
    }
    return "";
  }

  private static void validate(InvokeRequest request) {
    if (request == null) {
      throw new WebApplicationException("Request body is required", Response.Status.BAD_REQUEST);
    }
    if ((request.serviceName() == null || request.serviceName().isBlank())
        && (request.endpoint() == null || request.endpoint().isBlank())) {
      throw new WebApplicationException("Provide serviceName or endpoint", Response.Status.BAD_REQUEST);
    }
    if (request.operation() == null || request.operation().isBlank()) {
      throw new WebApplicationException("operation is required", Response.Status.BAD_REQUEST);
    }
  }

  private static String userFriendlyInvokeError(Throwable error, String operation, String protocol) {
    String normalized = ServiceDiscovery.normalizeProtocol(protocol);
    String detail = firstUsefulMessage(error);
    if (isMissingArguments(error, normalized)) {
      return switch (normalized) {
        case "soap" -> "Missing SOAP arguments for operation '" + operation + "'. "
            + "Provide all required input values in the operation order shown by the browser. "
            + "Details: " + detail;
        case "grpc" -> "Missing gRPC (RPC) arguments for operation '" + operation + "'. "
            + "Provide required request fields (as JSON object with field names or ordered values). "
            + "Details: " + detail;
        case "rest" -> "Missing REST arguments for operation '" + operation + "'. "
            + "Provide required query parameters or JSON body fields for the selected HTTP method. "
            + "Details: " + detail;
        default -> "Missing arguments for operation '" + operation + "'. Details: " + detail;
      };
    }
    if (isMalformedArguments(error, normalized)) {
      return switch (normalized) {
        case "soap" -> "Invalid SOAP argument format for operation '" + operation + "'. "
            + "Check value types and argument order exactly as defined in WSDL inputs. "
            + "Details: " + detail;
        case "grpc" -> "Invalid gRPC (RPC) argument format for operation '" + operation + "'. "
            + "Check field names and value types in the request payload. "
            + "Details: " + detail;
        case "rest" -> "Invalid REST argument format for operation '" + operation + "'. "
            + "Check JSON/query syntax and value types expected by the endpoint. "
            + "Details: " + detail;
        default -> "Invalid argument format for operation '" + operation + "'. Details: " + detail;
      };
    }
    if (isArgumentShapeError(error, normalized)) {
      return "Invalid arguments for operation '" + operation + "'. "
          + "The " + normalized.toUpperCase() + " service rejected the argument shape. "
          + "Check argument names/count/order and detected input fields. "
          + "Details: " + detail;
    }
    if (detail == null || detail.isBlank()) {
      return normalized.toUpperCase() + " invocation failed for operation '" + operation + "'.";
    }
    return normalized.toUpperCase() + " invocation failed for operation '" + operation + "': " + detail;
  }

  private static boolean isMissingArguments(Throwable error, String protocol) {
    String all = allMessages(error).toLowerCase();
    boolean genericMissing = all.contains("missing required")
        || all.contains("required field")
        || all.contains("required parameter")
        || all.contains("missing argument")
        || all.contains("not enough arguments")
        || all.contains("expects")
        || all.contains("expected")
        || all.contains("no value for");
    if ("soap".equalsIgnoreCase(protocol)) {
      return genericMissing
          || all.contains("unexpected element")
          || all.contains("arg0")
          || all.contains("arg1");
    }
    if ("grpc".equalsIgnoreCase(protocol)) {
      return genericMissing
          || all.contains("invalid_argument")
          || all.contains("field")
          || all.contains("message field");
    }
    if ("rest".equalsIgnoreCase(protocol)) {
      return genericMissing
          || all.contains("http error 400")
          || all.contains("http error 422");
    }
    return genericMissing;
  }

  private static boolean isMalformedArguments(Throwable error, String protocol) {
    String all = allMessages(error).toLowerCase();
    boolean malformed = all.contains("numberformatexception")
        || all.contains("for input string")
        || all.contains("invalid integer")
        || all.contains("invalid number")
        || all.contains("cannot deserialize")
        || all.contains("json parse")
        || all.contains("unable to serialize")
        || all.contains("invalid value")
        || all.contains("unknown field")
        || all.contains("unmarshalexception")
        || all.contains("unmarshalling error")
        || all.contains("invalid protocol buffer");
    if ("soap".equalsIgnoreCase(protocol)) {
      return malformed || all.contains("unexpected element");
    }
    if ("grpc".equalsIgnoreCase(protocol)) {
      return malformed || all.contains("expects an object");
    }
    return malformed;
  }

  private static boolean isArgumentShapeError(Throwable error, String protocol) {
    String all = allMessages(error).toLowerCase();
    boolean soapStyle = (all.contains("unexpected element") && all.contains("arg"))
        || all.contains("unmarshalling error")
        || all.contains("unmarshalexception");
    if ("grpc".equalsIgnoreCase(protocol)) {
      return soapStyle
          || all.contains("unknown field")
          || all.contains("invalid protocol buffer")
          || all.contains("expects an object");
    }
    if ("rest".equalsIgnoreCase(protocol)) {
      return all.contains("invalid parameters")
          || all.contains("unable to serialize")
          || all.contains("json");
    }
    return soapStyle;
  }

  private static String allMessages(Throwable error) {
    StringBuilder sb = new StringBuilder();
    Throwable current = error;
    int guard = 0;
    while (current != null && guard++ < 10) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        if (!sb.isEmpty()) {
          sb.append(" | ");
        }
        sb.append(current.getMessage().trim());
      }
      current = current.getCause();
    }
    return sb.toString();
  }

  private static String firstUsefulMessage(Throwable error) {
    Throwable current = error;
    int guard = 0;
    while (current != null && guard++ < 10) {
      String message = current.getMessage();
      if (message != null && !message.isBlank()) {
        String trimmed = message.trim();
        if (!trimmed.startsWith("SOAP invocation request failed for endpoint ")
            && !trimmed.startsWith("gRPC invocation request failed for endpoint ")
            && !trimmed.startsWith("REST invocation request failed for endpoint ")) {
          return trimmed;
        }
      }
      current = current.getCause();
    }
    return allMessages(error);
  }

  public record ServiceView(String serviceName, List<InstanceView> instances) {
  }

  public record InstanceView(
      String id,
      String address,
      int port,
      String servicePath,
      String protocol,
      String endpoint,
      Map<String, String> metadata) {
    public Map<String, String> metadata() {
      return metadata == null ? Map.of() : metadata;
    }
  }

  public record InvokeRequest(
      String serviceName,
      String servicePath,
      String instanceId,
      String protocol,
      String endpoint,
      String operation,
      List<Object> parameters) {
    public List<Object> parameters() {
      return parameters == null ? List.of() : parameters;
    }
  }

  public record InvokeResponse(String endpoint, String protocol, String operation, List<String> result) {
  }

  public record OperationsResponse(String endpoint, String protocol, List<String> operations, Map<String, List<String>> inputsByOperation) {
  }

  public record InterfaceDefinitionResponse(String language, String content) {
  }
}
