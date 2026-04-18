package isos.isysiesd.gencli.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import calcstubs.AddOperands;
import calcstubs.CalcServiceGrpc;
import calcstubs.NumberAndMaxExponent;
import calcstubs.Result;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;
import isos.isysiesd.dvapi.DvectorGrpc;
import isos.isysiesd.dvapi.InvariantCheckReply;
import isos.isysiesd.dvapi.InvariantCheckRequest;
import isos.isysiesd.dvapi.ReadReply;
import isos.isysiesd.dvapi.ReadRequest;
import isos.isysiesd.dvapi.SumVectorReply;
import isos.isysiesd.dvapi.SumVectorRequest;
import isos.isysiesd.dvapi.WriteReply;
import isos.isysiesd.dvapi.WriteRequest;
import isos.isysiesd.dvapi.thrift.Dvector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class GenericServiceInvoker {

  private static final List<String> DEFAULT_REST_OPERATIONS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");
  private static final long GRPC_CALL_TIMEOUT_SECONDS = 3L;

  @Inject
  SoapProxyFactory soapProxyFactory;

  @Inject
  ObjectMapper mapper;

  public List<String> listOperations(
      String protocol,
      String endpoint,
      String servicePath,
      Map<String, String> metadata) {
    String normalized = normalizeProtocol(protocol);
    return switch (normalized) {
      case "soap" -> soapProxyFactory.listOperationsFromEndpoint(endpoint);
      case "grpc" -> listGrpcOperationsWithFallback(endpoint, servicePath, metadata);
      case "rest" -> listRestOperations(metadata);
      case "thrift" -> listThriftOperations(metadata, servicePath);
      default -> throw new IllegalArgumentException("Unsupported protocol '" + normalized + "'");
    };
  }

  public Map<String, List<String>> listOperationInputs(
      String protocol,
      String endpoint,
      String servicePath,
      List<String> operations,
      Map<String, String> metadata) {
    String normalized = normalizeProtocol(protocol);
    return switch (normalized) {
      case "soap" -> mergeOperationInputHints(
          soapProxyFactory.listOperationInputsFromEndpoint(endpoint),
          operations,
          metadata,
          List.of("soap-operation-inputs", "operation-inputs"));
      case "grpc" -> listGrpcOperationInputsWithFallback(endpoint, servicePath, operations, metadata);
      case "rest" -> restInputHints(metadata, operations);
      case "thrift" -> listThriftOperationInputs(operations, metadata, servicePath);
      default -> Map.of();
    };
  }

  public InterfaceDefinition interfaceDefinition(
      String protocol,
      String endpoint,
      String servicePath,
      String serviceName,
      Map<String, String> metadata) {
    String normalized = normalizeProtocol(protocol);
    return switch (normalized) {
      case "soap" -> new InterfaceDefinition("WSDL", soapProxyFactory.wsdlFromEndpoint(endpoint));
      case "grpc" -> new InterfaceDefinition("Protobuf", grpcInterfaceDefinition(endpoint, servicePath));
      case "rest" -> new InterfaceDefinition("OpenAPI", restInterfaceDefinition(endpoint, servicePath, serviceName, metadata));
      case "thrift" -> new InterfaceDefinition("Thrift IDL", thriftInterfaceDefinition(servicePath, serviceName, metadata));
      default -> new InterfaceDefinition(normalized.toUpperCase(Locale.ROOT), "Unsupported protocol: " + normalized);
    };
  }

  private String grpcInterfaceDefinition(String endpoint, String servicePath) {
    String grpcServiceName = grpcServiceNameFromPath(servicePath);
    if (grpcServiceName == null || grpcServiceName.isBlank()) {
      return "// gRPC service name missing.\n// Expected servicePath like grpc:<fully-qualified-service-name>.";
    }

    ManagedChannel channel = openGrpcChannel(endpoint);
    try {
      List<DescriptorProtos.FileDescriptorProto> protos = fileDescriptorsBySymbol(channel, grpcServiceName);
      if (protos.isEmpty()) {
        return "// No Protobuf descriptors returned by reflection for service '" + grpcServiceName + "'.";
      }
      StringBuilder sb = new StringBuilder();
      for (DescriptorProtos.FileDescriptorProto proto : protos) {
        sb.append("// --- file: ").append(proto.getName()).append(" ---\n");
        sb.append(proto.toString().trim()).append("\n\n");
      }
      return sb.toString().trim();
    } catch (Exception e) {
      throw new RuntimeException("Unable to load Protobuf descriptors from gRPC reflection", e);
    } finally {
      shutdown(channel);
    }
  }

  private String thriftInterfaceDefinition(String servicePath, String serviceName, Map<String, String> metadata) {
    String inferred = thriftServiceName(servicePath, serviceName);
    List<String> operations = listOperations("thrift", "", servicePath, metadata);
    Map<String, List<String>> inputs = listOperationInputs("thrift", "", servicePath, operations, metadata);

    StringBuilder sb = new StringBuilder();
    sb.append("// Generated Thrift IDL (no reflection available)\n");
    sb.append("service ").append(inferred).append(" {\n");
    for (String operation : operations) {
      List<String> params = inputs.getOrDefault(operation, List.of());
      sb.append("  string ").append(thriftIdentifier(operation)).append("(");
      for (int i = 0; i < params.size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(i + 1).append(":string ").append(thriftIdentifier(params.get(i)));
      }
      sb.append(")\n");
    }
    sb.append("}\n");
    return sb.toString().trim();
  }

  private static String thriftServiceName(String servicePath, String serviceName) {
    String candidate = "";
    if (servicePath != null && !servicePath.isBlank()) {
      String trimmed = servicePath.trim();
      while (trimmed.startsWith("/")) {
        trimmed = trimmed.substring(1);
      }
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("thrift:")) {
        candidate = trimmed.substring("thrift:".length()).trim();
      } else {
        candidate = trimmed;
      }
    }
    if (candidate == null || candidate.isBlank()) {
      candidate = serviceName == null ? "" : serviceName.trim();
    }
    if (candidate.isBlank()) {
      candidate = "Service";
    }
    return thriftIdentifier(candidate);
  }

  private static String thriftIdentifier(String raw) {
    if (raw == null || raw.isBlank()) {
      return "value";
    }
    String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_]", "_");
    if (cleaned.isBlank()) {
      return "value";
    }
    if (Character.isDigit(cleaned.charAt(0))) {
      return "_" + cleaned;
    }
    return cleaned;
  }

  private String restInterfaceDefinition(String endpoint, String servicePath, String serviceName, Map<String, String> metadata) {
    List<String> operations = listOperations("rest", endpoint, servicePath, metadata);
    String title = (serviceName == null || serviceName.isBlank()) ? "REST Service" : serviceName.trim();
    String basePath = normalizePath(resolveRestPath("", servicePath, metadata));
    if (basePath.isBlank()) {
      basePath = "/";
    }

    Map<String, List<String>> inputs = listOperationInputs("rest", endpoint, servicePath, operations, metadata);
    StringBuilder sb = new StringBuilder();
    sb.append("openapi: 3.0.0\n");
    sb.append("info:\n");
    sb.append("  title: ").append(escapeYamlScalar(title)).append("\n");
    sb.append("  version: 0.0.0\n");
    sb.append("servers:\n");
    sb.append("  - url: ").append(escapeYamlScalar(endpoint)).append("\n");
    sb.append("paths:\n");

    for (String operation : operations) {
      RestCallSpec spec = parseRestOperation(operation);
      String path = normalizePath(firstNonBlank(spec.path(), basePath));
      if (path.isBlank()) {
        path = "/";
      }
      sb.append("  ").append(escapeYamlKey(path)).append(":\n");
      sb.append("    ").append(spec.method().toLowerCase(Locale.ROOT)).append(":\n");
      sb.append("      operationId: ").append(escapeYamlScalar(normalizedOperationName(operation))).append("\n");
      List<String> fields = inputs.getOrDefault(operation, List.of());
      if (Set.of("POST", "PUT", "PATCH").contains(spec.method()) && !fields.isEmpty()) {
        sb.append("      requestBody:\n");
        sb.append("        required: true\n");
        sb.append("        content:\n");
        sb.append("          application/json:\n");
        sb.append("            schema:\n");
        sb.append("              type: object\n");
        sb.append("              properties:\n");
        for (String field : fields) {
          sb.append("                ").append(escapeYamlKey(field)).append(":\n");
          sb.append("                  type: string\n");
        }
      }
      sb.append("      responses:\n");
      sb.append("        '200':\n");
      sb.append("          description: OK\n");
    }

    return sb.toString().trim();
  }

  private static String escapeYamlScalar(String raw) {
    String value = raw == null ? "" : raw;
    String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
    return "\"" + escaped + "\"";
  }

  private static String escapeYamlKey(String raw) {
    if (raw == null || raw.isBlank()) {
      return "\"\"";
    }
    String candidate = raw.trim();
    if (candidate.matches("[A-Za-z0-9_./\\-]+")) {
      return candidate;
    }
    return escapeYamlScalar(candidate);
  }

  public record InterfaceDefinition(String language, String content) {
  }

  private List<String> listGrpcOperationsWithFallback(
      String endpoint,
      String servicePath,
      Map<String, String> metadata) {
    try {
      List<String> reflected = listGrpcOperations(endpoint, servicePath);
      if (!reflected.isEmpty()) {
        return reflected;
      }
    } catch (Exception ignored) {
      // Fall back to metadata hints when reflection is unavailable.
    }

    List<String> hinted = configuredGrpcOperations(metadata);
    if (!hinted.isEmpty()) {
      return hinted;
    }
    return guessedGrpcOperations(servicePath);
  }

  private Map<String, List<String>> listGrpcOperationInputsWithFallback(
      String endpoint,
      String servicePath,
      List<String> operations,
      Map<String, String> metadata) {
    Map<String, List<String>> reflected = Map.of();
    try {
      reflected = listGrpcOperationInputs(endpoint, servicePath, operations);
    } catch (Exception ignored) {
      // Fall back to metadata hints when reflection is unavailable.
    }

    Map<String, List<String>> merged = mergeOperationInputHints(
        reflected,
        operations,
        metadata,
        List.of("grpc-operation-inputs", "rpc-operation-inputs", "operation-inputs"));
    if (!merged.isEmpty()) {
      return merged;
    }
    return guessedGrpcOperationInputs(servicePath);
  }

  private static List<String> listThriftOperations(Map<String, String> metadata, String servicePath) {
    List<String> configured = splitCsv(firstNonBlank(
        valueIgnoreCase(metadata, "thrift-operations"),
        valueIgnoreCase(metadata, "operations")));
    if (!configured.isEmpty()) {
      return configured;
    }

    Map<String, List<String>> hinted = parseOperationInputHints(
        metadata,
        List.of("thrift-operation-inputs", "operation-inputs"));
    if (!hinted.isEmpty()) {
      return new ArrayList<>(hinted.keySet());
    }

    if (servicePath != null && servicePath.toLowerCase(Locale.ROOT).contains("dvector")) {
      return List.of("read", "write", "invariantCheck", "sumVector");
    }
    return List.of();
  }

  private static Map<String, List<String>> listThriftOperationInputs(
      List<String> operations,
      Map<String, String> metadata,
      String servicePath) {
    Map<String, List<String>> hinted = mergeOperationInputHints(
        Map.of(),
        operations,
        metadata,
        List.of("thrift-operation-inputs", "operation-inputs"));
    if (!hinted.isEmpty()) {
      return hinted;
    }
    if (servicePath != null && servicePath.toLowerCase(Locale.ROOT).contains("dvector")) {
      Map<String, List<String>> fallback = new LinkedHashMap<>();
      fallback.put("read", List.of("pos"));
      fallback.put("write", List.of("pos", "value"));
      fallback.put("invariantCheck", List.of());
      fallback.put("sumVector", List.of());
      return fallback;
    }
    return Map.of();
  }

  private static List<String> configuredGrpcOperations(Map<String, String> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return List.of();
    }

    String configured = firstNonBlank(
        valueIgnoreCase(metadata, "grpc-operations"),
        valueIgnoreCase(metadata, "rpc-operations"),
        valueIgnoreCase(metadata, "operations"));

    List<String> operations = splitCsv(configured);
    if (!operations.isEmpty()) {
      return operations;
    }

    Map<String, List<String>> hintedInputs = parseOperationInputHints(
        metadata,
        List.of("grpc-operation-inputs", "rpc-operation-inputs", "operation-inputs"));
    if (hintedInputs.isEmpty()) {
      return List.of();
    }
    return new ArrayList<>(hintedInputs.keySet());
  }

  private static List<String> guessedGrpcOperations(String servicePath) {
    if (servicePath == null || servicePath.isBlank()) {
      return List.of();
    }
    String normalized = servicePath.toLowerCase(Locale.ROOT);
    if (normalized.contains("dvector")) {
      return List.of("Read", "Write", "InvariantCheck", "SumVector");
    }
    if (normalized.contains("calc")) {
      return List.of("add", "generatePowers", "addSeqOfNumbers", "multipleAdd");
    }
    return List.of();
  }

  private static Map<String, List<String>> guessedGrpcOperationInputs(String servicePath) {
    List<String> guessedOps = guessedGrpcOperations(servicePath);
    if (guessedOps.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> guessed = new LinkedHashMap<>();
    guessed.put("Read", List.of("pos"));
    guessed.put("Write", List.of("pos", "value"));
    guessed.put("InvariantCheck", List.of());
    guessed.put("SumVector", List.of());
    if (servicePath != null && servicePath.toLowerCase(Locale.ROOT).contains("calc")) {
      guessed.clear();
      guessed.put("add", List.of("Id", "op1", "op2"));
      guessed.put("generatePowers", List.of("Id", "baseNumber", "maxExponent"));
      guessed.put("addSeqOfNumbers", List.of("num"));
      guessed.put("multipleAdd", List.of("Id", "op1", "op2"));
    }
    return guessed;
  }

  public List<String> invoke(
      String protocol,
      String endpoint,
      String servicePath,
      String operation,
      List<Object> parameters,
      Map<String, String> metadata) {
    String normalized = normalizeProtocol(protocol);
    List<Object> safeParams = parameters == null ? List.of() : parameters;
    return switch (normalized) {
      case "soap" -> soapProxyFactory.invokeOperation(endpoint, operation, safeParams);
      case "grpc" -> invokeGrpc(endpoint, servicePath, operation, safeParams, metadata);
      case "rest" -> invokeRest(endpoint, servicePath, operation, safeParams, metadata);
      case "thrift" -> invokeThrift(endpoint, operation, safeParams, metadata);
      default -> throw new IllegalArgumentException("Unsupported protocol '" + normalized + "'");
    };
  }

  private static String normalizeProtocol(String protocol) {
    if (protocol == null || protocol.isBlank()) {
      return "soap";
    }
    String normalized = protocol.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "rpc" -> "grpc";
      case "thrift" -> "thrift";
      default -> normalized;
    };
  }

  private static List<String> listRestOperations(Map<String, String> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return DEFAULT_REST_OPERATIONS;
    }
    String configured = firstNonBlank(
        valueIgnoreCase(metadata, "rest-operations"),
        valueIgnoreCase(metadata, "operations"),
        valueIgnoreCase(metadata, "methods"));
    if (configured == null || configured.isBlank()) {
      return DEFAULT_REST_OPERATIONS;
    }

    List<String> operations = new ArrayList<>();
    for (String token : configured.split(",")) {
      String value = token.trim();
      if (!value.isEmpty()) {
        operations.add(value);
      }
    }
    return operations.isEmpty() ? DEFAULT_REST_OPERATIONS : operations;
  }

  private static Map<String, List<String>> restInputHints(Map<String, String> metadata, List<String> operations) {
    if (operations == null || operations.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> fromOperationMetadata = parseOperationInputHints(
        metadata,
        List.of("rest-operation-inputs", "operation-inputs"));
    List<String> postBody = List.of("body");
    Map<String, List<String>> hints = new LinkedHashMap<>();
    for (String operation : operations) {
      List<String> specific = resolveHintForOperation(operation, fromOperationMetadata);
      if (specific != null) {
        hints.put(operation, specific);
        continue;
      }
      String method = parseRestOperation(operation).method();
      if (Set.of("POST", "PUT", "PATCH").contains(method)) {
        hints.put(operation, postBody);
      } else {
        hints.put(operation, List.of());
      }
    }

    if (metadata != null && !metadata.isEmpty()) {
      String bodyFieldNames = firstNonBlank(valueIgnoreCase(metadata, "input-fields"), valueIgnoreCase(metadata, "rest-input-fields"));
      if (bodyFieldNames != null && !bodyFieldNames.isBlank()) {
        List<String> fields = splitCsv(bodyFieldNames);
        if (!fields.isEmpty()) {
          for (String operation : operations) {
            if (Set.of("POST", "PUT", "PATCH").contains(parseRestOperation(operation).method())) {
              hints.put(operation, fields);
            }
          }
        }
      }
    }
    return hints;
  }

  private static Map<String, List<String>> mergeOperationInputHints(
      Map<String, List<String>> detected,
      List<String> operations,
      Map<String, String> metadata,
      List<String> metadataKeys) {
    Map<String, List<String>> merged = new LinkedHashMap<>();
    if (operations != null) {
      for (String operation : operations) {
        merged.put(operation, List.of());
      }
    }
    if (detected != null) {
      merged.putAll(detected);
    }
    Map<String, List<String>> fromMetadata = parseOperationInputHints(metadata, metadataKeys);
    if (!fromMetadata.isEmpty()) {
      if (operations != null && !operations.isEmpty()) {
        for (String operation : operations) {
          List<String> current = merged.get(operation);
          if (current != null && !current.isEmpty() && !hasOnlyGeneratedArgumentNames(current)) {
            continue;
          }
          List<String> hinted = resolveHintForOperation(operation, fromMetadata);
          if (hinted != null) {
            merged.put(operation, hinted);
          }
        }
      } else {
        merged.putAll(fromMetadata);
      }
    }
    return merged;
  }

  private static boolean hasOnlyGeneratedArgumentNames(List<String> names) {
    if (names == null || names.isEmpty()) {
      return true;
    }
    for (String name : names) {
      if (name == null || name.isBlank()) {
        continue;
      }
      String trimmed = name.trim().toLowerCase(Locale.ROOT);
      if (!trimmed.matches("arg\\d+")
          && !trimmed.matches("p\\d+")
          && !"queryorbody".equals(trimmed)
          && !"body".equals(trimmed)) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, List<String>> parseOperationInputHints(
      Map<String, String> metadata,
      List<String> metadataKeys) {
    if (metadata == null || metadata.isEmpty() || metadataKeys == null || metadataKeys.isEmpty()) {
      return Map.of();
    }
    String encoded = null;
    for (String key : metadataKeys) {
      encoded = firstNonBlank(encoded, valueIgnoreCase(metadata, key));
    }
    if (encoded == null || encoded.isBlank()) {
      return Map.of();
    }

    Map<String, List<String>> hints = new LinkedHashMap<>();
    for (String entry : encoded.split(";")) {
      String token = entry == null ? "" : entry.trim();
      if (token.isEmpty()) {
        continue;
      }
      int sep = token.indexOf(':');
      if (sep < 0) {
        sep = token.indexOf('=');
      }
      if (sep <= 0) {
        continue;
      }
      String operation = token.substring(0, sep).trim();
      String fieldsPart = token.substring(sep + 1).trim();
      if (operation.isEmpty()) {
        continue;
      }
      List<String> fields = splitCsv(fieldsPart);
      hints.put(operation, fields);
    }
    return hints;
  }

  private static List<String> resolveHintForOperation(String operation, Map<String, List<String>> hints) {
    if (operation == null || operation.isBlank() || hints == null || hints.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, List<String>> entry : hints.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(operation)) {
        return entry.getValue();
      }
    }
    String normalizedOperation = normalizedOperationName(operation);
    for (Map.Entry<String, List<String>> entry : hints.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        continue;
      }
      if (normalizedOperation.equalsIgnoreCase(normalizedOperationName(key))) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String normalizedOperationName(String operation) {
    if (operation == null || operation.isBlank()) {
      return "";
    }
    String trimmed = operation.trim();
    String[] parts = trimmed.split("\\s+", 2);
    String candidate = parts.length > 1 ? parts[1].trim() : parts[0].trim();
    int slash = candidate.lastIndexOf('/');
    if (slash >= 0 && slash < candidate.length() - 1) {
      candidate = candidate.substring(slash + 1);
    }
    return candidate.trim();
  }

  private List<String> invokeRest(
      String endpoint,
      String servicePath,
      String operation,
      List<Object> parameters,
      Map<String, String> metadata) {
    RestCallSpec spec = parseRestOperation(operation);
    String resolvedPath = resolveRestPath(spec.path(), servicePath, metadata);
    URI uri = withPath(endpoint, resolvedPath);
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    HttpRequest request;
    if (Set.of("GET", "DELETE").contains(spec.method())) {
      URI uriWithQuery = withQueryParameters(uri, parameters);
      request = HttpRequest.newBuilder(uriWithQuery)
          .timeout(Duration.ofSeconds(8))
          .header("Accept", "application/json")
          .method(spec.method(), HttpRequest.BodyPublishers.noBody())
          .build();
    } else {
      String body = restBody(parameters);
      request = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(8))
          .header("Accept", "application/json")
          .header("Content-Type", "application/json")
          .method(spec.method(), HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build();
    }

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("REST HTTP error " + response.statusCode() + ": " + safeBody(response.body()));
      }
      return List.of(response.body() == null ? "" : response.body());
    } catch (Exception e) {
      throw new RuntimeException("REST invocation request failed for endpoint " + endpoint, e);
    }
  }

  private List<String> invokeThrift(
      String endpoint,
      String operation,
      List<Object> parameters,
      Map<String, String> metadata) {
    URI uri = URI.create(endpoint);
    String host = loopbackNormalizedHost(Objects.requireNonNullElse(uri.getHost(), endpoint));
    int port = resolveThriftPort(uri, metadata);
    String op = normalizedOperationName(operation).toLowerCase(Locale.ROOT);

    try (TSocket socket = new TSocket(host, port, 3000)) {
      socket.open();
      Dvector.Client client = new Dvector.Client(new TBinaryProtocol(socket));

      return switch (op) {
        case "read" -> List.of(String.valueOf(client.read(requiredInt(parameters, 0, "pos"))));
        case "write" -> {
          client.write(requiredInt(parameters, 0, "pos"), requiredInt(parameters, 1, "value"));
          yield List.of("ok");
        }
        case "invariantcheck" -> List.of(client.invariantCheck());
        case "sumvector" -> List.of(String.valueOf(client.sumVector()));
        default -> throw new IllegalArgumentException("Unsupported Thrift operation '" + operation + "'");
      };
    } catch (Exception e) {
      throw new RuntimeException("Thrift invocation request failed for endpoint " + endpoint, e);
    }
  }

  private static int resolveThriftPort(URI endpoint, Map<String, String> metadata) {
    if (endpoint.getPort() > 0) {
      return endpoint.getPort();
    }
    String configuredPort = firstNonBlank(
        valueIgnoreCase(metadata, "thrift-port"),
        valueIgnoreCase(metadata, "thrift.port"));
    if (configuredPort != null && !configuredPort.isBlank()) {
      return Integer.parseInt(configuredPort);
    }
    throw new IllegalArgumentException("Thrift endpoint must include host and port");
  }

  private static int requiredInt(List<Object> parameters, int index, String fieldName) {
    if (parameters == null || index < 0 || index >= parameters.size()) {
      throw new IllegalArgumentException("Missing required field '" + fieldName + "'");
    }
    Object value = parameters.get(index);
    if (value == null || String.valueOf(value).isBlank()) {
      throw new IllegalArgumentException("Missing required field '" + fieldName + "'");
    }
    return toInt(value);
  }

  private String restBody(List<Object> parameters) {
    Object payload;
    if (parameters == null || parameters.isEmpty()) {
      payload = Map.of();
    } else if (parameters.size() == 1) {
      payload = parameters.get(0);
    } else {
      payload = parameters;
    }
    try {
      return mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unable to serialize REST request body", e);
    }
  }

  private static URI withQueryParameters(URI baseUri, List<Object> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return baseUri;
    }

    StringBuilder query = new StringBuilder(Optional.ofNullable(baseUri.getQuery()).orElse(""));
    if (parameters.size() == 1 && parameters.get(0) instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        appendQuery(query, String.valueOf(entry.getKey()), entry.getValue());
      }
    } else {
      for (int i = 0; i < parameters.size(); i++) {
        appendQuery(query, "p" + i, parameters.get(i));
      }
    }

    try {
      return new URI(
          baseUri.getScheme(),
          baseUri.getUserInfo(),
          baseUri.getHost(),
          baseUri.getPort(),
          baseUri.getPath(),
          query.toString(),
          baseUri.getFragment());
    } catch (Exception e) {
      return baseUri;
    }
  }

  private static void appendQuery(StringBuilder query, String key, Object value) {
    if (key == null || key.isBlank()) {
      return;
    }
    if (!query.isEmpty()) {
      query.append('&');
    }
    query.append(urlEncode(key));
    query.append('=');
    query.append(urlEncode(value == null ? "" : String.valueOf(value)));
  }

  private static String urlEncode(String raw) {
    return URLEncoder.encode(raw, StandardCharsets.UTF_8);
  }

  private static URI withPath(String endpoint, String path) {
    URI base = URI.create(endpoint);
    String normalizedPath = normalizePath(path);
    try {
      return new URI(
          base.getScheme(),
          base.getUserInfo(),
          base.getHost(),
          base.getPort(),
          normalizedPath,
          base.getQuery(),
          base.getFragment());
    } catch (Exception e) {
      return base;
    }
  }

  private static String resolveRestPath(String operationPath, String servicePath, Map<String, String> metadata) {
    if (operationPath != null && !operationPath.isBlank()) {
      return operationPath;
    }

    String fromMeta = metadata == null
        ? null
        : firstNonBlank(
            valueIgnoreCase(metadata, "path"),
            valueIgnoreCase(metadata, "rest-path"),
            valueIgnoreCase(metadata, "servicePath"));
    return firstNonBlank(fromMeta, servicePath, "");
  }

  private List<String> listGrpcOperations(String endpoint, String servicePath) {
    String serviceName = grpcServiceNameFromPath(servicePath);
    if (serviceName == null || serviceName.isBlank()) {
      return List.of();
    }

    ManagedChannel channel = openGrpcChannel(endpoint);
    try {
      Descriptors.ServiceDescriptor serviceDescriptor = grpcServiceDescriptor(channel, serviceName);
      List<String> operations = new ArrayList<>();
      for (Descriptors.MethodDescriptor method : serviceDescriptor.getMethods()) {
        operations.add(method.getName());
      }
      Collections.sort(operations);
      return operations;
    } catch (Exception e) {
      throw new RuntimeException("Unable to load gRPC operations from reflection [gsv2]", e);
    } finally {
      shutdown(channel);
    }
  }

  private Map<String, List<String>> listGrpcOperationInputs(
      String endpoint,
      String servicePath,
      List<String> operations) {
    String serviceName = grpcServiceNameFromPath(servicePath);
    if (serviceName == null || serviceName.isBlank()) {
      return Map.of();
    }

    ManagedChannel channel = openGrpcChannel(endpoint);
    try {
      Descriptors.ServiceDescriptor serviceDescriptor = grpcServiceDescriptor(channel, serviceName);
      Map<String, List<String>> inputs = new LinkedHashMap<>();
      Set<String> filter = operations == null ? Set.of() : Set.copyOf(operations);

      for (Descriptors.MethodDescriptor method : serviceDescriptor.getMethods()) {
        if (!filter.isEmpty() && !filter.contains(method.getName())) {
          continue;
        }
        List<String> fields = method.getInputType().getFields().stream()
            .map(Descriptors.FieldDescriptor::getName)
            .toList();
        inputs.put(method.getName(), fields);
      }
      return inputs;
    } catch (Exception e) {
      throw new RuntimeException("Unable to load gRPC operation inputs from reflection", e);
    } finally {
      shutdown(channel);
    }
  }

  private List<String> invokeGrpc(
      String endpoint,
      String servicePath,
      String operation,
      List<Object> parameters,
      Map<String, String> metadata) {
    String serviceName = grpcServiceNameFromPath(servicePath);
    if (serviceName == null || serviceName.isBlank()) {
      throw new IllegalArgumentException("gRPC service path must be in the format grpc:<fully-qualified-service-name>");
    }

    ManagedChannel channel = openGrpcChannel(endpoint);
    try {
      return invokeGrpcOnChannel(channel, serviceName, operation, parameters);
    } catch (RuntimeException e) {
      Exception fallbackFailure = null;
      if (looksLikeDvectorService(serviceName)) {
        try {
          return invokeDvectorGrpcFallback(channel, operation, parameters);
        } catch (Exception ex) {
          fallbackFailure = ex;
        }
      } else if (looksLikeCalculatorService(serviceName)) {
        try {
          return invokeCalculatorGrpcFallback(channel, operation, parameters);
        } catch (Exception ex) {
          fallbackFailure = ex;
        }
      }
      Throwable mismatchCandidate = fallbackFailure != null ? fallbackFailure : e;
      if (isGrpcEndpointMismatch(mismatchCandidate) || isGrpcConnectivityIssue(mismatchCandidate)) {
        return tryGrpcAlternateEndpoints(endpoint, serviceName, operation, parameters, metadata, mismatchCandidate);
      }
      throw new RuntimeException("gRPC invocation request failed for endpoint " + endpoint, mismatchCandidate);
    } catch (Exception e) {
      Exception fallbackFailure = null;
      if (looksLikeDvectorService(serviceName)) {
        try {
          return invokeDvectorGrpcFallback(channel, operation, parameters);
        } catch (Exception ex) {
          fallbackFailure = ex;
        }
      } else if (looksLikeCalculatorService(serviceName)) {
        try {
          return invokeCalculatorGrpcFallback(channel, operation, parameters);
        } catch (Exception ex) {
          fallbackFailure = ex;
        }
      }
      Throwable mismatchCandidate = fallbackFailure != null ? fallbackFailure : e;
      if (isGrpcEndpointMismatch(mismatchCandidate) || isGrpcConnectivityIssue(mismatchCandidate)) {
        return tryGrpcAlternateEndpoints(endpoint, serviceName, operation, parameters, metadata, mismatchCandidate);
      }
      throw new RuntimeException("gRPC invocation request failed for endpoint " + endpoint, mismatchCandidate);
    } finally {
      shutdown(channel);
    }
  }

  private List<String> tryGrpcAlternateEndpoints(
      String endpoint,
      String serviceName,
      String operation,
      List<Object> parameters,
      Map<String, String> metadata,
      Throwable originalFailure) {
    int currentPort = -1;
    try {
      currentPort = URI.create(endpoint).getPort();
    } catch (Exception ignored) {
      // Keep default unknown value.
    }

    URI uri = URI.create(endpoint);
    String originalHost = loopbackNormalizedHost(Objects.requireNonNullElse(uri.getHost(), "localhost"));

    LinkedHashSet<Integer> candidatePorts = new LinkedHashSet<>();
    if (currentPort > 0) {
      candidatePorts.add(currentPort);
    }
    Integer fromMetadata = resolveAlternateGrpcPort(endpoint, metadata);
    if (fromMetadata != null && fromMetadata > 0) {
      candidatePorts.add(fromMetadata);
    }
    // Common defaults for separate gRPC listeners.
    candidatePorts.add(9000);
    candidatePorts.add(50051);
    LinkedHashSet<String> candidateHosts = new LinkedHashSet<>();
    candidateHosts.add(originalHost);
    if ("127.0.0.1".equals(originalHost)) {
      candidateHosts.add("localhost");
      candidateHosts.add("host.docker.internal");
    } else if ("localhost".equalsIgnoreCase(originalHost)) {
      candidateHosts.add("127.0.0.1");
      candidateHosts.add("host.docker.internal");
    } else if ("host.docker.internal".equalsIgnoreCase(originalHost)) {
      candidateHosts.add("127.0.0.1");
      candidateHosts.add("localhost");
    }

    RuntimeException lastFailure = null;
    for (String host : candidateHosts) {
      for (Integer port : candidatePorts) {
        if (host.equals(originalHost) && port == currentPort) {
          continue;
        }
        try {
          return invokeGrpcAtHostAndPort(endpoint, host, port, serviceName, operation, parameters);
        } catch (RuntimeException ex) {
          lastFailure = ex;
        }
      }
    }

    if (lastFailure != null) {
      throw new RuntimeException(
          "gRPC invocation request failed for endpoint " + endpoint
              + ". Tried alternates hosts=" + candidateHosts + " ports=" + candidatePorts,
          lastFailure);
    }
    throw new RuntimeException("gRPC invocation request failed for endpoint " + endpoint, originalFailure);
  }

  private List<String> invokeGrpcOnChannel(
      ManagedChannel channel,
      String serviceName,
      String operation,
      List<Object> parameters) throws Exception {
    Descriptors.ServiceDescriptor serviceDescriptor = grpcServiceDescriptor(channel, serviceName);
    Descriptors.MethodDescriptor method = serviceDescriptor.findMethodByName(operation);
    if (method == null) {
      throw new IllegalArgumentException("Unknown gRPC operation '" + operation + "' for service " + serviceName);
    }
    if (method.isClientStreaming() || method.isServerStreaming()) {
      throw new IllegalArgumentException("Only unary gRPC operations are supported by this browser");
    }

    DynamicMessage requestMessage = buildGrpcRequestMessage(method.getInputType(), parameters);
    MethodDescriptor<byte[], byte[]> callDescriptor = MethodDescriptor.<byte[], byte[]>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, operation))
        .setRequestMarshaller(ByteArrayMarshaller.INSTANCE)
        .setResponseMarshaller(ByteArrayMarshaller.INSTANCE)
        .build();

    byte[] responseBytes = io.grpc.stub.ClientCalls.blockingUnaryCall(
        channel,
        callDescriptor,
        CallOptions.DEFAULT.withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        requestMessage.toByteArray());

    DynamicMessage responseMessage = DynamicMessage.parseFrom(method.getOutputType(), responseBytes);
    return extractDynamicMessageValues(responseMessage);
  }

  private List<String> invokeGrpcAtHostAndPort(
      String endpoint,
      String host,
      int port,
      String serviceName,
      String operation,
      List<Object> parameters) {
    URI baseUri = URI.create(endpoint);
    int currentPort = baseUri.getPort();
    String currentHost = loopbackNormalizedHost(Objects.requireNonNullElse(baseUri.getHost(), "localhost"));
    if (port <= 0 || (port == currentPort && currentHost.equals(loopbackNormalizedHost(host)))) {
      throw new RuntimeException("gRPC invocation request failed for endpoint " + endpoint + ": no alternate target");
    }

    String normalizedHost = loopbackNormalizedHost(host);
    ManagedChannel alternateChannel = ManagedChannelBuilder.forAddress(normalizedHost, port)
        .usePlaintext()
        .build();
    try {
      if (looksLikeDvectorService(serviceName)) {
        return invokeDvectorGrpcFallback(alternateChannel, operation, parameters);
      }
      if (looksLikeCalculatorService(serviceName)) {
        return invokeCalculatorGrpcFallback(alternateChannel, operation, parameters);
      }
      return invokeGrpcOnChannel(alternateChannel, serviceName, operation, parameters);
    } catch (Exception ex) {
      throw new RuntimeException("gRPC invocation request failed for endpoint " + endpoint
          + " (alternate target " + normalizedHost + ":" + port + ")", ex);
    } finally {
      shutdown(alternateChannel);
    }
  }

  private static Integer resolveAlternateGrpcPort(String endpoint, Map<String, String> metadata) {
    if (metadata != null && !metadata.isEmpty()) {
      String fromMeta = firstNonBlank(
          valueIgnoreCase(metadata, "grpc-port"),
          valueIgnoreCase(metadata, "grpc.port"),
          valueIgnoreCase(metadata, "quarkus.grpc.server.port"));
      if (fromMeta != null && !fromMeta.isBlank()) {
        try {
          int parsed = Integer.parseInt(fromMeta.trim());
          if (parsed > 0) {
            return parsed;
          }
        } catch (NumberFormatException ignored) {
          // Ignore invalid metadata values.
        }
      }
    }
    try {
      URI uri = URI.create(endpoint);
      int current = uri.getPort();
      if (current > 0 && current != 9000) {
        return 9000;
      }
    } catch (Exception ignored) {
      // Keep null fallback.
    }
    return null;
  }

  private static boolean isGrpcEndpointMismatch(Throwable error) {
    String all = allErrorMessages(error).toLowerCase(Locale.ROOT);
    return all.contains("unimplemented")
        && (all.contains("http status code 404")
            || all.contains("resource not found")
            || all.contains("invalid content-type: text/html"));
  }

  private static boolean isGrpcConnectivityIssue(Throwable error) {
    String all = allErrorMessages(error).toLowerCase(Locale.ROOT);
    return all.contains("deadline_exceeded")
        || all.contains("waiting_for_connection")
        || all.contains("connection refused")
        || all.contains("failed to connect")
        || all.contains("unavailable");
  }

  private static String allErrorMessages(Throwable error) {
    StringBuilder sb = new StringBuilder();
    Throwable current = error;
    int guard = 0;
    while (current != null && guard++ < 12) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        if (!sb.isEmpty()) {
          sb.append(" | ");
        }
        sb.append(current.getMessage());
      }
      current = current.getCause();
    }
    return sb.toString();
  }

  private static boolean looksLikeDvectorService(String serviceName) {
    if (serviceName == null || serviceName.isBlank()) {
      return false;
    }
    String normalized = normalizeServiceSymbol(serviceName).toLowerCase(Locale.ROOT);
    return normalized.endsWith(".dvector") || normalized.contains("dvector");
  }

  private static boolean looksLikeCalculatorService(String serviceName) {
    if (serviceName == null || serviceName.isBlank()) {
      return false;
    }
    String normalized = normalizeServiceSymbol(serviceName).toLowerCase(Locale.ROOT);
    return normalized.endsWith(".calcservice")
        || normalized.contains("calcservice")
        || normalized.contains("calculator");
  }

  private List<String> invokeDvectorGrpcFallback(ManagedChannel channel, String operation, List<Object> parameters) {
    DvectorGrpc.DvectorBlockingStub stub = DvectorGrpc.newBlockingStub(channel)
        .withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    String op = normalizeGrpcOperation(operation);
    return switch (op) {
      case "read" -> {
        int pos = requiredInt(parameterByNameOrIndex(parameters, "pos", 0), "pos");
        ReadReply reply = stub.read(ReadRequest.newBuilder().setPos(pos).build());
        yield List.of(String.valueOf(reply.getValue()));
      }
      case "write" -> {
        int pos = requiredInt(parameterByNameOrIndex(parameters, "pos", 0), "pos");
        int value = requiredInt(parameterByNameOrIndex(parameters, "value", 1), "value");
        WriteReply reply = stub.write(WriteRequest.newBuilder().setPos(pos).setValue(value).build());
        yield List.of(String.valueOf(reply.getSuccess()));
      }
      case "invariantcheck" -> {
        InvariantCheckReply reply = stub.invariantCheck(InvariantCheckRequest.newBuilder().build());
        yield List.of(reply.getResult());
      }
      case "sumvector" -> {
        SumVectorReply reply = stub.sumVector(SumVectorRequest.newBuilder().build());
        yield List.of(String.valueOf(reply.getSum()));
      }
      default -> throw new IllegalArgumentException("Unknown gRPC operation '" + operation + "' for Dvector service");
    };
  }

  private List<String> invokeCalculatorGrpcFallback(ManagedChannel channel, String operation, List<Object> parameters) {
    CalcServiceGrpc.CalcServiceBlockingStub stub = CalcServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    String op = normalizeGrpcOperation(operation);
    return switch (op) {
      case "add" -> {
        String id = String.valueOf(parameterByNameOrIndex(parameters, "Id", 0));
        int op1 = requiredInt(parameterByNameOrIndex(parameters, "op1", 1), "op1");
        int op2 = requiredInt(parameterByNameOrIndex(parameters, "op2", 2), "op2");
        Result reply = stub.add(AddOperands.newBuilder().setId(id).setOp1(op1).setOp2(op2).build());
        yield List.of(String.valueOf(reply.getRes()));
      }
      case "generatepowers" -> {
        String id = String.valueOf(parameterByNameOrIndex(parameters, "Id", 0));
        int baseNumber = requiredInt(parameterByNameOrIndex(parameters, "baseNumber", 1), "baseNumber");
        int maxExponent = requiredInt(parameterByNameOrIndex(parameters, "maxExponent", 2), "maxExponent");
        NumberAndMaxExponent request = NumberAndMaxExponent.newBuilder()
            .setId(id)
            .setBaseNumber(baseNumber)
            .setMaxExponent(maxExponent)
            .build();
        List<String> values = new ArrayList<>();
        stub.generatePowers(request).forEachRemaining(result -> values.add(String.valueOf(result.getRes())));
        yield values;
      }
      case "addseqofnumbers" -> {
        List<Integer> numbers = calculatorNumbersFromParameters(parameters);
        if (numbers.isEmpty()) {
          throw new IllegalArgumentException("Missing required field 'num'");
        }
        java.util.concurrent.CompletableFuture<Result> future = new java.util.concurrent.CompletableFuture<>();
        CalcServiceGrpc.CalcServiceStub asyncStub = CalcServiceGrpc.newStub(channel)
            .withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        StreamObserver<calcstubs.Number> requestObserver = asyncStub.addSeqOfNumbers(new StreamObserver<>() {
          @Override
          public void onNext(Result value) {
            future.complete(value);
          }

          @Override
          public void onError(Throwable t) {
            future.completeExceptionally(t);
          }

          @Override
          public void onCompleted() {
            if (!future.isDone()) {
              future.completeExceptionally(new IllegalStateException("No response received from addSeqOfNumbers"));
            }
          }
        });
        for (Integer number : numbers) {
          requestObserver.onNext(calcstubs.Number.newBuilder().setNum(number).build());
        }
        requestObserver.onCompleted();
        try {
          Result reply = future.get(GRPC_CALL_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS);
          yield List.of(String.valueOf(reply.getRes()));
        } catch (Exception e) {
          throw new RuntimeException("Calculator operation '" + operation + "' failed", e);
        }
      }
      case "multipleadd" -> {
        List<AddOperands> operands = calculatorOperandsFromParameters(parameters);
        if (operands.isEmpty()) {
          throw new IllegalArgumentException("Missing required fields 'op1' and 'op2'");
        }
        java.util.concurrent.CompletableFuture<List<String>> future = new java.util.concurrent.CompletableFuture<>();
        List<String> values = Collections.synchronizedList(new ArrayList<>());
        CalcServiceGrpc.CalcServiceStub asyncStub = CalcServiceGrpc.newStub(channel)
            .withDeadlineAfter(GRPC_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        StreamObserver<AddOperands> requestObserver = asyncStub.multipleAdd(new StreamObserver<>() {
          @Override
          public void onNext(Result value) {
            values.add(String.valueOf(value.getRes()));
          }

          @Override
          public void onError(Throwable t) {
            future.completeExceptionally(t);
          }

          @Override
          public void onCompleted() {
            future.complete(List.copyOf(values));
          }
        });
        for (AddOperands item : operands) {
          requestObserver.onNext(item);
        }
        requestObserver.onCompleted();
        try {
          yield future.get(GRPC_CALL_TIMEOUT_SECONDS + 1, TimeUnit.SECONDS);
        } catch (Exception e) {
          throw new RuntimeException("Calculator operation '" + operation + "' failed", e);
        }
      }
      default -> throw new IllegalArgumentException("Unknown gRPC operation '" + operation + "' for Calculator service");
    };
  }

  private static List<Integer> calculatorNumbersFromParameters(List<Object> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return List.of();
    }
    Object raw = parameterByNameOrIndex(parameters, "num", 0);
    if (raw == null) {
      return parameters.stream()
          .filter(Objects::nonNull)
          .map(GenericServiceInvoker::toInt)
          .toList();
    }
    if (raw instanceof List<?> list) {
      return list.stream()
          .filter(Objects::nonNull)
          .map(GenericServiceInvoker::toInt)
          .toList();
    }
    if (raw instanceof String s) {
      String trimmed = s.trim();
      if (trimmed.isEmpty()) {
        return List.of();
      }
      if (trimmed.contains(",")) {
        String[] parts = trimmed.split(",");
        List<Integer> values = new ArrayList<>(parts.length);
        for (String part : parts) {
          String token = part.trim();
          if (!token.isEmpty()) {
            values.add(Integer.parseInt(token));
          }
        }
        return values;
      }
    }
    return List.of(toInt(raw));
  }

  private static List<AddOperands> calculatorOperandsFromParameters(List<Object> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return List.of();
    }

    if (parameters.size() == 1 && parameters.get(0) instanceof List<?> list) {
      List<AddOperands> values = new ArrayList<>();
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          values.add(addOperandsFromMap(map));
        }
      }
      if (!values.isEmpty()) {
        return values;
      }
    }

    if (parameters.stream().allMatch(p -> p instanceof Map<?, ?>)) {
      List<AddOperands> values = new ArrayList<>();
      for (Object item : parameters) {
        values.add(addOperandsFromMap((Map<?, ?>) item));
      }
      return values;
    }

    Object rawOp1 = parameterByNameOrIndex(parameters, "op1", 1);
    Object rawOp2 = parameterByNameOrIndex(parameters, "op2", 2);
    if (rawOp1 == null || rawOp2 == null) {
      return List.of();
    }

    Object rawId = parameterByNameOrIndex(parameters, "Id", 0);
    if (rawOp1 instanceof List<?> op1List && rawOp2 instanceof List<?> op2List) {
      int size = Math.min(op1List.size(), op2List.size());
      List<AddOperands> values = new ArrayList<>(size);
      List<?> ids = rawId instanceof List<?> idList ? idList : List.of();
      for (int i = 0; i < size; i++) {
        String id = i < ids.size() ? String.valueOf(ids.get(i)) : "";
        values.add(AddOperands.newBuilder()
            .setId(id)
            .setOp1(toInt(op1List.get(i)))
            .setOp2(toInt(op2List.get(i)))
            .build());
      }
      return values;
    }

    if (!(rawOp1 instanceof List<?>) && !(rawOp2 instanceof List<?>)) {
      return List.of(AddOperands.newBuilder()
          .setId(rawId == null ? "" : String.valueOf(rawId))
          .setOp1(toInt(rawOp1))
          .setOp2(toInt(rawOp2))
          .build());
    }
    return List.of();
  }

  private static AddOperands addOperandsFromMap(Map<?, ?> map) {
    Object idValue = mapValueIgnoreCase(map, "Id");
    Object op1Value = mapValueIgnoreCase(map, "op1");
    Object op2Value = mapValueIgnoreCase(map, "op2");
    if (op1Value == null || op2Value == null) {
      throw new IllegalArgumentException("Missing required fields 'op1' and 'op2'");
    }
    return AddOperands.newBuilder()
        .setId(idValue == null ? "" : String.valueOf(idValue))
        .setOp1(toInt(op1Value))
        .setOp2(toInt(op2Value))
        .build();
  }

  private static Object mapValueIgnoreCase(Map<?, ?> map, String key) {
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() != null && String.valueOf(entry.getKey()).equalsIgnoreCase(key)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String normalizeGrpcOperation(String operation) {
    if (operation == null || operation.isBlank()) {
      return "";
    }
    String value = operation.trim();
    int slash = value.lastIndexOf('/');
    if (slash >= 0 && slash < value.length() - 1) {
      value = value.substring(slash + 1);
    }
    return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
  }

  private static Object parameterByNameOrIndex(List<Object> parameters, String name, int index) {
    if (parameters == null || parameters.isEmpty()) {
      return null;
    }
    if (parameters.size() == 1 && parameters.get(0) instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() != null && String.valueOf(entry.getKey()).equalsIgnoreCase(name)) {
          return entry.getValue();
        }
      }
    }
    return index >= 0 && index < parameters.size() ? parameters.get(index) : null;
  }

  private static int requiredInt(Object rawValue, String fieldName) {
    if (rawValue == null) {
      throw new IllegalArgumentException("Missing required field '" + fieldName + "'");
    }
    return toInt(rawValue);
  }

  private DynamicMessage buildGrpcRequestMessage(Descriptors.Descriptor inputType, List<Object> parameters) {
    DynamicMessage.Builder builder = DynamicMessage.newBuilder(inputType);
    if (parameters == null || parameters.isEmpty()) {
      return builder.build();
    }

    if (parameters.size() == 1 && parameters.get(0) instanceof Map<?, ?> rawMap) {
      for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
        if (entry.getKey() == null) {
          continue;
        }
        Descriptors.FieldDescriptor field = inputType.findFieldByName(String.valueOf(entry.getKey()));
        if (field == null) {
          continue;
        }
        assignGrpcField(builder, field, entry.getValue());
      }
      return builder.build();
    }

    List<Descriptors.FieldDescriptor> fields = inputType.getFields();
    for (int i = 0; i < parameters.size() && i < fields.size(); i++) {
      assignGrpcField(builder, fields.get(i), parameters.get(i));
    }
    return builder.build();
  }

  private void assignGrpcField(DynamicMessage.Builder builder, Descriptors.FieldDescriptor field, Object rawValue) {
    if (rawValue == null) {
      return;
    }

    if (field.isRepeated()) {
      if (rawValue instanceof List<?> list) {
        for (Object value : list) {
          builder.addRepeatedField(field, coerceGrpcValue(field, value));
        }
      } else {
        builder.addRepeatedField(field, coerceGrpcValue(field, rawValue));
      }
      return;
    }

    builder.setField(field, coerceGrpcValue(field, rawValue));
  }

  private Object coerceGrpcValue(Descriptors.FieldDescriptor field, Object rawValue) {
    return switch (field.getJavaType()) {
      case STRING -> String.valueOf(rawValue);
      case BOOLEAN -> toBoolean(rawValue);
      case INT -> toInt(rawValue);
      case LONG -> toLong(rawValue);
      case FLOAT -> toFloat(rawValue);
      case DOUBLE -> toDouble(rawValue);
      case BYTE_STRING -> ByteString.copyFromUtf8(String.valueOf(rawValue));
      case ENUM -> enumValue(field.getEnumType(), rawValue);
      case MESSAGE -> messageValue(field.getMessageType(), rawValue);
    };
  }

  private static boolean toBoolean(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static int toInt(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private static long toLong(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private static float toFloat(Object value) {
    if (value instanceof Number n) {
      return n.floatValue();
    }
    return Float.parseFloat(String.valueOf(value));
  }

  private static double toDouble(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    return Double.parseDouble(String.valueOf(value));
  }

  private static Descriptors.EnumValueDescriptor enumValue(Descriptors.EnumDescriptor enumDescriptor, Object rawValue) {
    if (rawValue instanceof Number n) {
      Descriptors.EnumValueDescriptor byNumber = enumDescriptor.findValueByNumber(n.intValue());
      if (byNumber != null) {
        return byNumber;
      }
    }
    String name = String.valueOf(rawValue);
    Descriptors.EnumValueDescriptor byName = enumDescriptor.findValueByName(name);
    if (byName != null) {
      return byName;
    }
    throw new IllegalArgumentException("Unknown enum value '" + name + "' for " + enumDescriptor.getFullName());
  }

  private Object messageValue(Descriptors.Descriptor descriptor, Object rawValue) {
    if (rawValue instanceof Map<?, ?> map) {
      DynamicMessage.Builder nested = DynamicMessage.newBuilder(descriptor);
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() == null) {
          continue;
        }
        Descriptors.FieldDescriptor field = descriptor.findFieldByName(String.valueOf(entry.getKey()));
        if (field == null) {
          continue;
        }
        assignGrpcField(nested, field, entry.getValue());
      }
      return nested.build();
    }
    throw new IllegalArgumentException("Message field '" + descriptor.getFullName() + "' expects an object/map value");
  }

  private static List<String> extractDynamicMessageValues(DynamicMessage message) {
    if (message == null) {
      return List.of();
    }

    List<String> values = new ArrayList<>();
    Map<Descriptors.FieldDescriptor, Object> fields = message.getAllFields();
    if (fields.isEmpty()) {
      return List.of("{}");
    }

    for (Map.Entry<Descriptors.FieldDescriptor, Object> entry : fields.entrySet()) {
      values.add(fieldValueToString(entry.getKey(), entry.getValue()));
    }
    return values;
  }

  private static String fieldValueToString(Descriptors.FieldDescriptor field, Object value) {
    if (value == null) {
      return "";
    }
    if (field.isRepeated() && value instanceof List<?> list) {
      return list.stream()
          .map(v -> scalarGrpcValueToString(field, v))
          .toList()
          .toString();
    }
    return scalarGrpcValueToString(field, value);
  }

  private static String scalarGrpcValueToString(Descriptors.FieldDescriptor field, Object value) {
    if (value instanceof DynamicMessage nested) {
      return nested.toString().trim();
    }
    if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM
        && value instanceof Descriptors.EnumValueDescriptor enumValue) {
      return enumValue.getName();
    }
    return String.valueOf(value);
  }

  private ManagedChannel openGrpcChannel(String endpoint) {
    URI uri = URI.create(endpoint);
    String host = loopbackNormalizedHost(Objects.requireNonNullElse(uri.getHost(), endpoint));
    int port = uri.getPort();
    if (port <= 0) {
      throw new IllegalArgumentException("gRPC endpoint must include host and port: " + endpoint);
    }
    return ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .build();
  }

  private static String loopbackNormalizedHost(String host) {
    if (host == null || host.isBlank() || "localhost".equalsIgnoreCase(host)) {
      return "127.0.0.1";
    }
    return host;
  }

  private Descriptors.ServiceDescriptor grpcServiceDescriptor(ManagedChannel channel, String serviceName) throws Exception {
    String resolvedServiceName = resolveGrpcServiceName(channel, serviceName);
    Map<String, Descriptors.FileDescriptor> descriptorCache = new ConcurrentHashMap<>();
    loadServiceDescriptors(channel, resolvedServiceName, descriptorCache);

    List<Descriptors.ServiceDescriptor> availableDescriptors = new ArrayList<>();
    for (Descriptors.FileDescriptor fileDescriptor : descriptorCache.values()) {
      for (Descriptors.ServiceDescriptor serviceDescriptor : fileDescriptor.getServices()) {
        availableDescriptors.add(serviceDescriptor);
        if (resolvedServiceName.equals(serviceDescriptor.getFullName())) {
          return serviceDescriptor;
        }
      }
    }

    String requestedSimple = simpleServiceName(normalizeServiceSymbol(resolvedServiceName));
    for (Descriptors.ServiceDescriptor descriptor : availableDescriptors) {
      String currentSimple = simpleServiceName(normalizeServiceSymbol(descriptor.getFullName()));
      if (currentSimple.equalsIgnoreCase(requestedSimple)) {
        return descriptor;
      }
    }
    if (availableDescriptors.size() == 1) {
      return availableDescriptors.get(0);
    }

    String available = String.join(", ", listGrpcServices(channel));
    throw new IllegalArgumentException("gRPC service '" + resolvedServiceName + "' not found in descriptors. "
        + "Descriptor services: "
        + availableDescriptors.stream().map(Descriptors.ServiceDescriptor::getFullName).toList()
        + ". Reflection listServices: " + available);
  }

  private static List<String> listGrpcServices(ManagedChannel channel) {
    List<String> names = new ArrayList<>(listGrpcServicesV1(channel));
    if (names.isEmpty()) {
      names.addAll(listGrpcServicesV1Alpha(channel));
    }
    Collections.sort(names);
    return names;
  }

  private static void loadServiceDescriptors(
      ManagedChannel channel,
      String serviceName,
      Map<String, Descriptors.FileDescriptor> built) throws Exception {
    List<DescriptorProtos.FileDescriptorProto> protos = new ArrayList<>();
    protos.addAll(fileDescriptorsBySymbol(channel, serviceName));

    Map<String, DescriptorProtos.FileDescriptorProto> allByName = new HashMap<>();
    for (DescriptorProtos.FileDescriptorProto proto : protos) {
      allByName.put(proto.getName(), proto);
    }

    for (DescriptorProtos.FileDescriptorProto proto : protos) {
      buildFileDescriptor(proto, allByName, built);
    }
  }

  private static List<DescriptorProtos.FileDescriptorProto> fileDescriptorsBySymbol(ManagedChannel channel, String serviceName) {
    List<DescriptorProtos.FileDescriptorProto> protos = descriptorProtosBySymbolV1(channel, serviceName);
    if (!protos.isEmpty()) {
      return protos;
    }
    protos = descriptorProtosBySymbolV1Alpha(channel, serviceName);
    if (!protos.isEmpty()) {
      return protos;
    }

    List<String> available = safeListGrpcServices(channel);
    for (String candidate : available) {
      protos = descriptorProtosBySymbolV1(channel, candidate);
      if (protos.isEmpty()) {
        protos = descriptorProtosBySymbolV1Alpha(channel, candidate);
      }
      if (!protos.isEmpty()) {
        return protos;
      }
    }

    String normalized = normalizeServiceSymbol(serviceName);
    String simple = simpleServiceName(normalized);
    String packageName = packageName(normalized);
    for (String candidate : List.of(
        normalized,
        "." + normalized,
        simple,
        "." + simple,
        packageName,
        "." + packageName)) {
      if (candidate == null || candidate.isBlank() || ".".equals(candidate)) {
        continue;
      }
      protos = descriptorProtosBySymbolV1(channel, candidate);
      if (protos.isEmpty()) {
        protos = descriptorProtosBySymbolV1Alpha(channel, candidate);
      }
      if (!protos.isEmpty()) {
        return protos;
      }
    }

    throw new IllegalStateException("gRPC reflection symbol not found for '" + serviceName
        + "'. Available services: " + String.join(", ", available));
  }

  private static List<DescriptorProtos.FileDescriptorProto> descriptorProtosBySymbolV1(ManagedChannel channel, String symbol) {
    try {
      ServerReflectionResponse response = reflectionRequest(channel, ServerReflectionRequest.newBuilder().setFileContainingSymbol(symbol));
      if (response.hasErrorResponse() || !response.hasFileDescriptorResponse()) {
        return List.of();
      }
      return parseDescriptorProtos(response.getFileDescriptorResponse().getFileDescriptorProtoList());
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static List<DescriptorProtos.FileDescriptorProto> descriptorProtosBySymbolV1Alpha(ManagedChannel channel, String symbol) {
    try {
      io.grpc.reflection.v1alpha.ServerReflectionResponse response = reflectionRequestV1Alpha(
          channel,
          io.grpc.reflection.v1alpha.ServerReflectionRequest.newBuilder().setFileContainingSymbol(symbol));
      if (response.hasErrorResponse() || !response.hasFileDescriptorResponse()) {
        return List.of();
      }
      return parseDescriptorProtos(response.getFileDescriptorResponse().getFileDescriptorProtoList());
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static List<String> listGrpcServicesV1(ManagedChannel channel) {
    try {
      ServerReflectionResponse response = reflectionRequest(channel, ServerReflectionRequest.newBuilder().setListServices(""));
      if (response.hasErrorResponse() || !response.hasListServicesResponse()) {
        return List.of();
      }
      List<String> names = new ArrayList<>();
      response.getListServicesResponse().getServiceList().forEach(svc -> names.add(svc.getName()));
      return names;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static List<String> listGrpcServicesV1Alpha(ManagedChannel channel) {
    try {
      io.grpc.reflection.v1alpha.ServerReflectionResponse response = reflectionRequestV1Alpha(
          channel,
          io.grpc.reflection.v1alpha.ServerReflectionRequest.newBuilder().setListServices(""));
      if (response.hasErrorResponse() || !response.hasListServicesResponse()) {
        return List.of();
      }
      List<String> names = new ArrayList<>();
      response.getListServicesResponse().getServiceList().forEach(svc -> names.add(svc.getName()));
      return names;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static List<DescriptorProtos.FileDescriptorProto> parseDescriptorProtos(List<com.google.protobuf.ByteString> rawDescriptors) {
    List<DescriptorProtos.FileDescriptorProto> protos = new ArrayList<>();
    for (com.google.protobuf.ByteString raw : rawDescriptors) {
      try {
        protos.add(DescriptorProtos.FileDescriptorProto.parseFrom(raw));
      } catch (Exception e) {
        throw new RuntimeException("Unable to parse protobuf descriptor from reflection", e);
      }
    }
    return protos;
  }

  private static Descriptors.FileDescriptor buildFileDescriptor(
      DescriptorProtos.FileDescriptorProto proto,
      Map<String, DescriptorProtos.FileDescriptorProto> allByName,
      Map<String, Descriptors.FileDescriptor> built) throws Exception {
    Descriptors.FileDescriptor cached = built.get(proto.getName());
    if (cached != null) {
      return cached;
    }

    List<Descriptors.FileDescriptor> dependencies = new ArrayList<>();
    for (String dependencyName : proto.getDependencyList()) {
      Descriptors.FileDescriptor dependency = built.get(dependencyName);
      if (dependency == null) {
        DescriptorProtos.FileDescriptorProto depProto = allByName.get(dependencyName);
        if (depProto == null) {
          throw new IllegalStateException("Missing protobuf dependency descriptor: " + dependencyName);
        }
        dependency = buildFileDescriptor(depProto, allByName, built);
      }
      dependencies.add(dependency);
    }

    Descriptors.FileDescriptor descriptor = Descriptors.FileDescriptor.buildFrom(
        proto,
        dependencies.toArray(new Descriptors.FileDescriptor[0]));
    built.put(proto.getName(), descriptor);
    return descriptor;
  }

  private static ServerReflectionResponse reflectionRequest(ManagedChannel channel, ServerReflectionRequest.Builder requestBuilder) {
    ServerReflectionGrpc.ServerReflectionStub stub = ServerReflectionGrpc.newStub(channel);
    java.util.concurrent.CompletableFuture<ServerReflectionResponse> future = new java.util.concurrent.CompletableFuture<>();
    StreamObserver<ServerReflectionResponse> responseObserver = new StreamObserver<>() {
      @Override
      public void onNext(ServerReflectionResponse value) {
        future.complete(value);
      }

      @Override
      public void onError(Throwable t) {
        future.completeExceptionally(t);
      }

      @Override
      public void onCompleted() {
        if (!future.isDone()) {
          future.completeExceptionally(new IllegalStateException("No gRPC reflection response received"));
        }
      }
    };
    StreamObserver<ServerReflectionRequest> requestObserver = stub.serverReflectionInfo(responseObserver);
    requestObserver.onNext(requestBuilder.build());
    requestObserver.onCompleted();
    try {
      return future.get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException("gRPC reflection request failed", e);
    }
  }

  private static io.grpc.reflection.v1alpha.ServerReflectionResponse reflectionRequestV1Alpha(
      ManagedChannel channel,
      io.grpc.reflection.v1alpha.ServerReflectionRequest.Builder requestBuilder) {
    io.grpc.reflection.v1alpha.ServerReflectionGrpc.ServerReflectionStub stub =
        io.grpc.reflection.v1alpha.ServerReflectionGrpc.newStub(channel);
    java.util.concurrent.CompletableFuture<io.grpc.reflection.v1alpha.ServerReflectionResponse> future =
        new java.util.concurrent.CompletableFuture<>();
    StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionResponse> responseObserver = new StreamObserver<>() {
      @Override
      public void onNext(io.grpc.reflection.v1alpha.ServerReflectionResponse value) {
        future.complete(value);
      }

      @Override
      public void onError(Throwable t) {
        future.completeExceptionally(t);
      }

      @Override
      public void onCompleted() {
        if (!future.isDone()) {
          future.completeExceptionally(new IllegalStateException("No gRPC reflection (v1alpha) response received"));
        }
      }
    };
    StreamObserver<io.grpc.reflection.v1alpha.ServerReflectionRequest> requestObserver =
        stub.serverReflectionInfo(responseObserver);
    requestObserver.onNext(requestBuilder.build());
    requestObserver.onCompleted();
    try {
      return future.get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException("gRPC reflection (v1alpha) request failed", e);
    }
  }

  private static String grpcServiceNameFromPath(String servicePath) {
    if (servicePath == null || servicePath.isBlank()) {
      return null;
    }
    String trimmed = servicePath.trim();
    while (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    if (!trimmed.toLowerCase(Locale.ROOT).startsWith("grpc:")) {
      return trimmed;
    }
    return trimmed.substring("grpc:".length()).trim();
  }

  private static String resolveGrpcServiceName(ManagedChannel channel, String requested) {
    String candidate = requested == null ? "" : requested.trim();
    List<String> available = listGrpcServices(channel);
    if (available.isEmpty()) {
      return candidate;
    }

    for (String service : available) {
      if (service.equals(candidate)) {
        return service;
      }
    }
    for (String service : available) {
      if (service.equalsIgnoreCase(candidate)) {
        return service;
      }
    }

    String normalizedCandidate = normalizeServiceSymbol(candidate);
    String simpleCandidate = simpleServiceName(normalizedCandidate);

    for (String service : available) {
      String normalizedService = normalizeServiceSymbol(service);
      if (normalizedService.equals(normalizedCandidate)) {
        return service;
      }
      if (normalizedService.endsWith("." + simpleCandidate)
          || simpleServiceName(normalizedService).equalsIgnoreCase(simpleCandidate)
          || simpleServiceName(normalizedService).toLowerCase(Locale.ROOT).contains(simpleCandidate.toLowerCase(Locale.ROOT))) {
        return service;
      }
    }

    List<String> nonSystem = available.stream()
        .filter(s -> !s.startsWith("grpc.reflection.") && !s.startsWith("grpc.health."))
        .toList();
    for (String service : nonSystem) {
      if (isResolvableServiceSymbol(channel, service)) {
        return service;
      }
    }
    if (nonSystem.size() == 1) {
      return nonSystem.get(0);
    }
    return candidate;
  }

  private static boolean isResolvableServiceSymbol(ManagedChannel channel, String serviceName) {
    try {
      ServerReflectionResponse response = reflectionRequest(
          channel,
          ServerReflectionRequest.newBuilder().setFileContainingSymbol(serviceName));
      return !response.hasErrorResponse();
    } catch (Exception ignored) {
      return false;
    }
  }

  private static String normalizeServiceSymbol(String raw) {
    if (raw == null) {
      return "";
    }
    String value = raw.trim();
    while (value.startsWith("/")) {
      value = value.substring(1);
    }
    value = value.replace('/', '.');
    while (value.contains("..")) {
      value = value.replace("..", ".");
    }
    if (value.startsWith(".")) {
      value = value.substring(1);
    }
    if (value.endsWith(".")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  private static String simpleServiceName(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      return "";
    }
    int idx = symbol.lastIndexOf('.');
    return idx >= 0 ? symbol.substring(idx + 1) : symbol;
  }

  private static String packageName(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      return "";
    }
    int idx = symbol.lastIndexOf('.');
    return idx > 0 ? symbol.substring(0, idx) : "";
  }

  private static List<String> safeListGrpcServices(ManagedChannel channel) {
    try {
      return listGrpcServices(channel);
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private static String normalizePath(String path) {
    if (path == null || path.isBlank() || "/".equals(path)) {
      return "";
    }
    return path.startsWith("/") ? path : "/" + path;
  }

  private static void shutdown(ManagedChannel channel) {
    if (channel == null) {
      return;
    }
    try {
      channel.shutdownNow();
      channel.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String safeBody(String body) {
    if (body == null || body.isBlank()) {
      return "(empty body)";
    }
    return body.length() > 300 ? body.substring(0, 300) + "..." : body;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String valueIgnoreCase(Map<String, String> metadata, String key) {
    if (metadata == null || metadata.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static List<String> splitCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (String token : csv.split(",")) {
      String value = token.trim();
      if (!value.isEmpty()) {
        values.add(value);
      }
    }
    return values;
  }

  private static RestCallSpec parseRestOperation(String operation) {
    if (operation == null || operation.isBlank()) {
      return new RestCallSpec("GET", "");
    }
    String trimmed = operation.trim();
    String[] parts = trimmed.split("\\s+", 2);
    String candidateMethod = parts[0].toUpperCase(Locale.ROOT);
    if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(candidateMethod)) {
      String path = parts.length > 1 ? parts[1].trim() : "";
      return new RestCallSpec(candidateMethod, path);
    }
    return new RestCallSpec("POST", trimmed);
  }

  private record RestCallSpec(String method, String path) {
  }

  private static final class ByteArrayMarshaller implements MethodDescriptor.Marshaller<byte[]> {
    private static final ByteArrayMarshaller INSTANCE = new ByteArrayMarshaller();

    @Override
    public InputStream stream(byte[] value) {
      return new ByteArrayInputStream(value == null ? new byte[0] : value);
    }

    @Override
    public byte[] parse(InputStream stream) {
      try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        in.transferTo(out);
        return out.toByteArray();
      } catch (IOException e) {
        throw new RuntimeException("Unable to decode gRPC response payload", e);
      }
    }
  }

}
