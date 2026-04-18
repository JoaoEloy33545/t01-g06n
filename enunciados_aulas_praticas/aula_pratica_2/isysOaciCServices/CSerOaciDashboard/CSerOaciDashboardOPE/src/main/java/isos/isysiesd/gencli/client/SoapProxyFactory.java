package isos.isysiesd.gencli.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.jaxws.endpoint.dynamic.JaxWsDynamicClientFactory;
import org.apache.cxf.message.Message;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import isos.isysiesd.gencli.discovery.ServiceDiscovery;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SoapProxyFactory {

  @Inject
  ServiceDiscovery discovery;

  public <T> T createProxy(String consulServiceName, String servicePath, Class<T> serviceInterface) {
    String endpoint = resolveEndpoint(consulServiceName, servicePath);
    JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
    factory.setServiceClass(serviceInterface);
    factory.setAddress(endpoint);

    T proxy = serviceInterface.cast(factory.create(serviceInterface));
    configureTimeouts(proxy, 3000, 5000);
    return proxy;
  }

  public Client createDynamicClient(String consulServiceName, String servicePath) {
    String endpoint = resolveEndpoint(consulServiceName, servicePath);
    return createDynamicClientFromEndpoint(endpoint);
  }

  public Client createDynamicClientFromEndpoint(String endpoint) {
    JaxWsDynamicClientFactory factory = JaxWsDynamicClientFactory.newInstance();
    RuntimeException lastError = null;
    for (String wsdlSuffix : List.of("?wsdl", "?WSDL")) {
      String wsdlUrl = endpoint.contains("?") ? endpoint : endpoint + wsdlSuffix;
      try {
        Client dynamicClient = factory.createClient(wsdlUrl);
        dynamicClient.getRequestContext().put(Message.ENDPOINT_ADDRESS, endpoint);
        configureTimeouts(dynamicClient, 3000, 5000);
        return dynamicClient;
      } catch (RuntimeException e) {
        lastError = e;
      }
    }
    throw lastError == null
        ? new RuntimeException("Unable to create SOAP dynamic client for endpoint " + endpoint)
        : lastError;
  }

  public List<String> listOperationsFromEndpoint(String endpoint) {
    RuntimeException lastError = null;
    for (String wsdlSuffix : List.of("?wsdl", "?WSDL")) {
      String wsdlUrl = endpoint.contains("?") ? endpoint : endpoint + wsdlSuffix;
      try {
        String wsdl = fetchWsdl(wsdlUrl);
        List<String> operations = extractOperationsFromWsdl(wsdl);
        if (!operations.isEmpty()) {
          return operations;
        }
      } catch (RuntimeException e) {
        lastError = e;
      }
    }

    throw lastError == null
        ? new IllegalStateException("Unable to extract operations from WSDL for endpoint " + endpoint)
        : lastError;
  }

  public Map<String, List<String>> listOperationInputsFromEndpoint(String endpoint) {
    try {
      WsdlMetadata metadata = loadWsdlMetadata(endpoint);
      return metadata.operationInputNames();
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  public String wsdlFromEndpoint(String endpoint) {
    RuntimeException lastError = null;
    for (String wsdlSuffix : List.of("?wsdl", "?WSDL")) {
      String wsdlUrl = endpoint.contains("?") ? endpoint : endpoint + wsdlSuffix;
      try {
        return fetchWsdl(wsdlUrl);
      } catch (RuntimeException e) {
        lastError = e;
      }
    }
    throw lastError == null
        ? new IllegalStateException("Unable to fetch WSDL for endpoint " + endpoint)
        : lastError;
  }

  public List<String> invokeOperation(String endpoint, String operation, List<Object> parameters) {
    WsdlMetadata metadata = loadWsdlMetadata(endpoint);
    List<String> inputParameterNames = metadata.operationInputNames().getOrDefault(operation, List.of());
    String wrapperLocalName = metadata.operationWrapperNames().getOrDefault(operation, operation);
    String preferredNs = metadata.operationRequestNamespaces().get(operation);
    WrapperQName discoveredQName = metadata.operationWrapperQNames().get(operation);
    String wrapperNs = (preferredNs != null && !preferredNs.isBlank())
        ? preferredNs
        : (discoveredQName != null && discoveredQName.namespaceUri() != null && !discoveredQName.namespaceUri().isBlank()
            ? discoveredQName.namespaceUri()
            : metadata.targetNamespace());
    WrapperQName wrapperQName = new WrapperQName(wrapperNs, wrapperLocalName);
    String soapRequest = buildSoapRequest(wrapperQName.namespaceUri(), wrapperQName.localName(), parameters, inputParameterNames);
    String soapAction = resolveSoapAction(metadata.operationSoapActions(), operation);

    String responseXml = postSoap(endpoint, soapRequest, soapAction);
    return extractSoapResponseValues(responseXml);
  }

  private String resolveEndpoint(String consulServiceName, String servicePath) {
    List<String> endpoints = discovery.discoverEndpoints(consulServiceName, servicePath);
    if (endpoints.isEmpty()) {
      throw new IllegalStateException("No SOAP endpoints found for service " + consulServiceName);
    }
    return endpoints.get(0);
  }

  private static void configureTimeouts(Object proxy, long connectionTimeoutMs, long receiveTimeoutMs) {
    Client client = ClientProxy.getClient(proxy);
    configureTimeouts(client, connectionTimeoutMs, receiveTimeoutMs);
  }

  private static void configureTimeouts(Client client, long connectionTimeoutMs, long receiveTimeoutMs) {
    HTTPConduit conduit = (HTTPConduit) client.getConduit();
    HTTPClientPolicy policy = conduit.getClient() == null ? new HTTPClientPolicy() : conduit.getClient();
    policy.setConnectionTimeout(connectionTimeoutMs);
    policy.setReceiveTimeout(receiveTimeoutMs);
    conduit.setClient(policy);
  }

  private static String fetchWsdl(String wsdlUrl) {
    try {
      HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(wsdlUrl))
          .timeout(Duration.ofSeconds(5))
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("WSDL request failed (" + response.statusCode() + ") for " + wsdlUrl);
      }
      return response.body();
    } catch (Exception e) {
      throw new RuntimeException("Unable to fetch WSDL from " + wsdlUrl, e);
    }
  }

  private static WsdlMetadata loadWsdlMetadata(String endpoint) {
    RuntimeException lastError = null;
    for (String wsdlSuffix : List.of("?wsdl", "?WSDL")) {
      String wsdlUrl = endpoint.contains("?") ? endpoint : endpoint + wsdlSuffix;
      try {
        String wsdl = fetchWsdl(wsdlUrl);
        return extractWsdlMetadata(wsdlUrl, wsdl);
      } catch (RuntimeException e) {
        lastError = e;
      }
    }
    throw lastError == null
        ? new IllegalStateException("Unable to load WSDL metadata for endpoint " + endpoint)
        : lastError;
  }

  private static List<String> extractOperationsFromWsdl(String wsdlContent) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      var builder = factory.newDocumentBuilder();
      var doc = builder.parse(new ByteArrayInputStream(wsdlContent.getBytes(StandardCharsets.UTF_8)));

      Set<String> names = new LinkedHashSet<>();
      var portTypeOps = doc.getElementsByTagNameNS("*", "portType");
      for (int i = 0; i < portTypeOps.getLength(); i++) {
        var portType = portTypeOps.item(i);
        var children = portType.getChildNodes();
        for (int j = 0; j < children.getLength(); j++) {
          var child = children.item(j);
          if ("operation".equals(child.getLocalName())) {
            var attrs = child.getAttributes();
            if (attrs != null && attrs.getNamedItem("name") != null) {
              names.add(attrs.getNamedItem("name").getNodeValue());
            }
          }
        }
      }

      if (names.isEmpty()) {
        var bindingOps = doc.getElementsByTagNameNS("*", "operation");
        for (int i = 0; i < bindingOps.getLength(); i++) {
          var op = bindingOps.item(i);
          var attrs = op.getAttributes();
          if (attrs != null && attrs.getNamedItem("name") != null) {
            names.add(attrs.getNamedItem("name").getNodeValue());
          }
        }
      }

      return names.stream().sorted().toList();
    } catch (Exception e) {
      throw new RuntimeException("Unable to parse WSDL content", e);
    }
  }

  private static WsdlMetadata extractWsdlMetadata(String wsdlUrl, String wsdlContent) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      var builder = factory.newDocumentBuilder();
      var doc = builder.parse(new ByteArrayInputStream(wsdlContent.getBytes(StandardCharsets.UTF_8)));
      Element definitions = doc.getDocumentElement();
      String tns = definitions.getAttribute("targetNamespace");
      if (tns == null || tns.isBlank()) {
        throw new IllegalStateException("WSDL targetNamespace not found");
      }

      Map<String, WrapperQName> operationWrapperQNames = new HashMap<>();
      Map<String, String> operationWrapperNames = new HashMap<>();
      Map<String, List<String>> operationInputNames = extractOperationInputNames(
          definitions,
          operationWrapperNames,
          operationWrapperQNames);
      Map<String, String> operationRequestNamespaces = extractOperationRequestNamespaces(definitions);
      Map<String, String> operationSoapActions = extractOperationSoapActions(definitions);
      return new WsdlMetadata(
          wsdlUrl,
          tns,
          operationInputNames,
          operationSoapActions,
          operationWrapperNames,
          operationWrapperQNames,
          operationRequestNamespaces);
    } catch (Exception e) {
      throw new RuntimeException("Unable to extract metadata from WSDL", e);
    }
  }

  private static String buildSoapRequest(
      String targetNamespace,
      String requestElementName,
      List<Object> parameters,
      List<String> inputParameterNames) {
    StringBuilder sb = new StringBuilder();
    sb.append("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        .append("xmlns:tns=\"").append(xmlEscape(targetNamespace)).append("\">")
        .append("<soapenv:Header/><soapenv:Body>")
        .append("<tns:").append(xmlEscape(requestElementName)).append(">");
    for (int i = 0; i < parameters.size(); i++) {
      Object value = parameters.get(i);
      String paramName = i < inputParameterNames.size() && !inputParameterNames.get(i).isBlank()
          ? inputParameterNames.get(i)
          : "arg" + i;
      sb.append("<").append(xmlEscape(paramName)).append(">")
          .append(xmlEscape(value == null ? "" : String.valueOf(value)))
          .append("</").append(xmlEscape(paramName)).append(">");
    }
    sb.append("</tns:").append(xmlEscape(requestElementName)).append(">")
        .append("</soapenv:Body></soapenv:Envelope>");
    return sb.toString();
  }

  private static String postSoap(String endpoint, String soapRequest, String soapAction) {
    try {
      HttpClient httpClient = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
          .timeout(Duration.ofSeconds(8))
          .header("Content-Type", "text/xml; charset=utf-8")
          .POST(HttpRequest.BodyPublishers.ofString(soapRequest, StandardCharsets.UTF_8))
          .build();
      if (soapAction != null && !soapAction.isBlank()) {
        request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"" + soapAction + "\"")
            .POST(HttpRequest.BodyPublishers.ofString(soapRequest, StandardCharsets.UTF_8))
            .build();
      }
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400 && (response.body() == null || response.body().isBlank())) {
        throw new IllegalStateException("SOAP HTTP error " + response.statusCode());
      }
      return response.body();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("SOAP invocation request failed for endpoint " + endpoint, e);
    }
  }

  private static String resolveSoapAction(Map<String, String> actions, String operation) {
    String direct = actions.get(operation);
    if (direct != null && !direct.isBlank()) {
      return direct;
    }
    for (Map.Entry<String, String> entry : actions.entrySet()) {
      if (entry.getKey() != null
          && entry.getValue() != null
          && entry.getKey().equalsIgnoreCase(operation)
          && !entry.getValue().isBlank()) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static List<String> extractSoapResponseValues(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      var builder = factory.newDocumentBuilder();
      var doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

      Element body = firstElementByLocalName(doc.getDocumentElement(), "Body");
      if (body == null) {
        return List.of(xml);
      }

      Element fault = firstChildElementByLocalName(body, "Fault");
      if (fault != null) {
        String faultMessage = extractFaultMessage(fault);
        throw new IllegalStateException("SOAP Fault: " + faultMessage);
      }

      Element payload = firstChildElement(body);
      if (payload == null) {
        return List.of();
      }

      List<String> values = new ArrayList<>();
      collectLeafElementValues(payload, values);
      if (values.isEmpty()) {
        String text = payload.getTextContent();
        if (text != null && !text.isBlank()) {
          values.add(text.trim());
        }
      }
      return values;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Unable to parse SOAP response", e);
    }
  }

  private static String extractFaultMessage(Element fault) {
    String faultString = findFirstTextByLocalName(fault, "faultstring");
    if (faultString != null && !faultString.isBlank()) {
      return faultString.trim();
    }
    String reasonText = findFirstTextByLocalName(fault, "Text");
    if (reasonText != null && !reasonText.isBlank()) {
      return reasonText.trim();
    }
    String detailText = findFirstTextByLocalName(fault, "detail");
    if (detailText != null && !detailText.isBlank()) {
      return detailText.trim();
    }
    String raw = fault.getTextContent();
    return raw == null || raw.isBlank() ? "Unknown SOAP fault" : raw.trim();
  }

  private static String findFirstTextByLocalName(Element root, String localName) {
    if (localName.equals(root.getLocalName())) {
      String text = root.getTextContent();
      if (text != null && !text.isBlank()) {
        return text;
      }
    }
    Node node = root.getFirstChild();
    while (node != null) {
      if (node instanceof Element el) {
        String text = findFirstTextByLocalName(el, localName);
        if (text != null && !text.isBlank()) {
          return text;
        }
      }
      node = node.getNextSibling();
    }
    return null;
  }

  private static Element firstElementByLocalName(Element root, String localName) {
    if (localName.equals(root.getLocalName())) {
      return root;
    }
    Node node = root.getFirstChild();
    while (node != null) {
      if (node instanceof Element el) {
        Element candidate = firstElementByLocalName(el, localName);
        if (candidate != null) {
          return candidate;
        }
      }
      node = node.getNextSibling();
    }
    return null;
  }

  private static void collectLeafElementValues(Element element, List<String> values) {
    Node child = element.getFirstChild();
    boolean hasChildElement = false;
    while (child != null) {
      if (child instanceof Element el) {
        hasChildElement = true;
        collectLeafElementValues(el, values);
      }
      child = child.getNextSibling();
    }

    if (!hasChildElement) {
      String text = element.getTextContent();
      if (text != null && !text.isBlank()) {
        values.add(text.trim());
      }
    }
  }

  private static Element firstChildElementByLocalName(Element parent, String localName) {
    Node node = parent.getFirstChild();
    while (node != null) {
      if (node instanceof Element el && localName.equals(el.getLocalName())) {
        return el;
      }
      node = node.getNextSibling();
    }
    return null;
  }

  private static Element firstChildElement(Element parent) {
    Node node = parent.getFirstChild();
    while (node != null) {
      if (node instanceof Element el) {
        return el;
      }
      node = node.getNextSibling();
    }
    return null;
  }

  private static String xmlEscape(String input) {
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static Map<String, List<String>> extractOperationInputNames(
      Element definitions,
      Map<String, String> operationWrapperNames,
      Map<String, WrapperQName> operationWrapperQNames) {
    Map<String, MessageDescriptor> messagesByName = new HashMap<>();
    NodeList messages = definitions.getElementsByTagNameNS("*", "message");
    for (int i = 0; i < messages.getLength(); i++) {
      Node node = messages.item(i);
      if (!(node instanceof Element message)) {
        continue;
      }
      String messageName = message.getAttribute("name");
      if (messageName == null || messageName.isBlank()) {
        continue;
      }
      List<String> partNames = new ArrayList<>();
      String elementName = null;
      String elementNs = null;
      Node partNode = message.getFirstChild();
      while (partNode != null) {
        if (partNode instanceof Element part && "part".equals(part.getLocalName())) {
          String partName = part.getAttribute("name");
          if (partName != null && !partName.isBlank()) {
            partNames.add(partName);
          }
          String elementQName = part.getAttribute("element");
          if (elementName == null && elementQName != null && !elementQName.isBlank()) {
            elementName = localPart(elementQName);
            elementNs = namespaceUriForQName(part, elementQName);
            if (elementNs == null || elementNs.isBlank()) {
              elementNs = findSchemaNamespaceForElement(definitions, elementName);
            }
          }
        }
        partNode = partNode.getNextSibling();
      }
      messagesByName.put(messageName, new MessageDescriptor(elementName, elementNs, partNames));
    }

    Map<String, String> operationToInputElement = new HashMap<>();
    Map<String, List<String>> operationInputNames = new HashMap<>();
    NodeList portTypes = definitions.getElementsByTagNameNS("*", "portType");
    for (int i = 0; i < portTypes.getLength(); i++) {
      Node node = portTypes.item(i);
      if (!(node instanceof Element portType)) {
        continue;
      }
      NodeList operations = portType.getElementsByTagNameNS("*", "operation");
      for (int j = 0; j < operations.getLength(); j++) {
        Node opNode = operations.item(j);
        if (!(opNode instanceof Element op)) {
          continue;
        }
        String opName = op.getAttribute("name");
        if (opName == null || opName.isBlank()) {
          continue;
        }
        Element input = firstChildElementByLocalName(op, "input");
        if (input == null) {
          continue;
        }
        String messageQName = input.getAttribute("message");
        if (messageQName == null || messageQName.isBlank()) {
          continue;
        }
        String messageLocal = localPart(messageQName);
        MessageDescriptor descriptor = messagesByName.get(messageLocal);
        if (descriptor == null) {
          continue;
        }

        if (!descriptor.partNames().isEmpty()) {
          operationInputNames.put(opName, descriptor.partNames());
        }

        String inputElementName = descriptor.elementName();
        if (inputElementName != null && !inputElementName.isBlank()) {
          operationToInputElement.put(opName, inputElementName);
          operationWrapperNames.put(opName, inputElementName);
          String schemaNs = findUniqueSchemaNamespaceForElement(definitions, inputElementName);
          String wrapperNs = (schemaNs != null && !schemaNs.isBlank())
              ? schemaNs
              : descriptor.elementNamespace();
          if (wrapperNs != null && !wrapperNs.isBlank()) {
            operationWrapperQNames.put(opName, new WrapperQName(wrapperNs, inputElementName));
          }
        }
      }
    }

    for (Map.Entry<String, String> entry : operationToInputElement.entrySet()) {
      String opName = entry.getKey();
      String elementName = entry.getValue();
      List<String> schemaParams = extractSchemaElementParameterNames(definitions, elementName);
      if (!schemaParams.isEmpty()) {
        operationInputNames.put(opName, schemaParams);
      }
    }
    return operationInputNames;
  }

  private static Map<String, String> extractOperationSoapActions(Element definitions) {
    Map<String, String> actions = new HashMap<>();
    NodeList bindings = definitions.getElementsByTagNameNS("*", "binding");
    for (int i = 0; i < bindings.getLength(); i++) {
      Node bindingNode = bindings.item(i);
      if (!(bindingNode instanceof Element binding)) {
        continue;
      }
      NodeList operations = binding.getElementsByTagNameNS("*", "operation");
      for (int j = 0; j < operations.getLength(); j++) {
        Node operationNode = operations.item(j);
        if (!(operationNode instanceof Element operation)) {
          continue;
        }
        String opName = operation.getAttribute("name");
        if (opName == null || opName.isBlank()) {
          continue;
        }
        Node child = operation.getFirstChild();
        while (child != null) {
          if (child instanceof Element childEl && "operation".equals(childEl.getLocalName())) {
            String action = childEl.getAttribute("soapAction");
            if (action != null && !action.isBlank()) {
              actions.put(opName, action);
            }
          }
          child = child.getNextSibling();
        }
      }
    }
    return actions;
  }

  private static Map<String, String> extractOperationRequestNamespaces(Element definitions) {
    Map<String, String> result = new HashMap<>();
    NodeList bindings = definitions.getElementsByTagNameNS("*", "binding");
    for (int i = 0; i < bindings.getLength(); i++) {
      Node bindingNode = bindings.item(i);
      if (!(bindingNode instanceof Element binding)) {
        continue;
      }

      String bindingType = binding.getAttribute("type");
      String bindingTypeNs = null;
      if (bindingType != null && !bindingType.isBlank()) {
        bindingTypeNs = namespaceUriForQName(binding, bindingType);
      }

      NodeList operations = binding.getElementsByTagNameNS("*", "operation");
      for (int j = 0; j < operations.getLength(); j++) {
        Node operationNode = operations.item(j);
        if (!(operationNode instanceof Element operation)) {
          continue;
        }
        String opName = operation.getAttribute("name");
        if (opName == null || opName.isBlank()) {
          continue;
        }

        if (bindingTypeNs != null && !bindingTypeNs.isBlank()) {
          result.putIfAbsent(opName, bindingTypeNs);
        }

        Element input = firstChildElementByLocalName(operation, "input");
        if (input == null) {
          continue;
        }
        Node child = input.getFirstChild();
        while (child != null) {
          if (child instanceof Element childEl && "body".equals(childEl.getLocalName())) {
            String ns = childEl.getAttribute("namespace");
            if (ns != null && !ns.isBlank()) {
              result.put(opName, ns);
            }
          }
          child = child.getNextSibling();
        }
      }
    }
    return result;
  }

  private static List<String> extractSchemaElementParameterNames(Element definitions, String inputElementName) {
    NodeList schemaElements = definitions.getElementsByTagNameNS("*", "element");
    for (int i = 0; i < schemaElements.getLength(); i++) {
      Node node = schemaElements.item(i);
      if (!(node instanceof Element schemaElement)) {
        continue;
      }
      Node parent = schemaElement.getParentNode();
      if (!(parent instanceof Element parentEl) || !"schema".equals(parentEl.getLocalName())) {
        continue;
      }
      String elementName = schemaElement.getAttribute("name");
      if (inputElementName.equals(elementName)) {
        return extractSequenceElementNames(schemaElement);
      }
    }
    return List.of();
  }

  private static List<String> extractSequenceElementNames(Element rootElement) {
    List<String> names = new ArrayList<>();
    Element complexType = firstChildElementByLocalName(rootElement, "complexType");
    if (complexType == null) {
      return names;
    }
    Element sequence = firstChildElementByLocalName(complexType, "sequence");
    if (sequence == null) {
      return names;
    }
    Node child = sequence.getFirstChild();
    while (child != null) {
      if (child instanceof Element el && "element".equals(el.getLocalName())) {
        String name = el.getAttribute("name");
        if (name != null && !name.isBlank()) {
          names.add(name);
        }
      }
      child = child.getNextSibling();
    }
    return names;
  }

  private static String localPart(String qName) {
    int idx = qName.indexOf(':');
    return idx >= 0 ? qName.substring(idx + 1) : qName;
  }

  private static String namespaceUriForQName(Node context, String qName) {
    int idx = qName.indexOf(':');
    String prefix = idx >= 0 ? qName.substring(0, idx) : "";
    String uri = context.lookupNamespaceURI(prefix.isBlank() ? null : prefix);
    if ((uri == null || uri.isBlank()) && context.getOwnerDocument() != null) {
      uri = context.getOwnerDocument().getDocumentElement().lookupNamespaceURI(prefix.isBlank() ? null : prefix);
    }
    return uri == null ? "" : uri;
  }

  private static String findSchemaNamespaceForElement(Element definitions, String localElementName) {
    NodeList schemaElements = definitions.getElementsByTagNameNS("*", "element");
    for (int i = 0; i < schemaElements.getLength(); i++) {
      Node node = schemaElements.item(i);
      if (!(node instanceof Element schemaElement)) {
        continue;
      }
      Node parent = schemaElement.getParentNode();
      if (!(parent instanceof Element parentEl) || !"schema".equals(parentEl.getLocalName())) {
        continue;
      }
      String elementName = schemaElement.getAttribute("name");
      if (!localElementName.equals(elementName)) {
        continue;
      }
      String tns = parentEl.getAttribute("targetNamespace");
      if (tns != null && !tns.isBlank()) {
        return tns;
      }
    }
    return "";
  }

  private static String findUniqueSchemaNamespaceForElement(Element definitions, String localElementName) {
    NodeList schemaElements = definitions.getElementsByTagNameNS("*", "element");
    String found = null;
    for (int i = 0; i < schemaElements.getLength(); i++) {
      Node node = schemaElements.item(i);
      if (!(node instanceof Element schemaElement)) {
        continue;
      }
      Node parent = schemaElement.getParentNode();
      if (!(parent instanceof Element parentEl) || !"schema".equals(parentEl.getLocalName())) {
        continue;
      }
      String elementName = schemaElement.getAttribute("name");
      if (!localElementName.equals(elementName)) {
        continue;
      }
      String tns = parentEl.getAttribute("targetNamespace");
      if (tns == null || tns.isBlank()) {
        continue;
      }
      if (found == null) {
        found = tns;
      } else if (!found.equals(tns)) {
        return "";
      }
    }
    return found == null ? "" : found;
  }

  private record MessageDescriptor(String elementName, String elementNamespace, List<String> partNames) {
  }

  private record WrapperQName(String namespaceUri, String localName) {
  }

  private record WsdlMetadata(
      String wsdlUrl,
      String targetNamespace,
      Map<String, List<String>> operationInputNames,
      Map<String, String> operationSoapActions,
      Map<String, String> operationWrapperNames,
      Map<String, WrapperQName> operationWrapperQNames,
      Map<String, String> operationRequestNamespaces) {
  }
}
