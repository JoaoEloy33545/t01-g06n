package mdeos.isos.infra.zkquorum.doe.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryZkStore {

    private final Map<String, String> data = new ConcurrentHashMap<>();

    public InMemoryZkStore() {
        data.put("/", "");
    }

    public synchronized ZNode getTree(String rootPath) {
        if (!data.containsKey(rootPath)) {
            data.put(rootPath, "");
        }
        return buildTree(rootPath);
    }

    public synchronized String getData(String path) {
        return data.getOrDefault(path, "");
    }

    public synchronized void createOrUpdate(String path, String value) {
        createParentsIfNeeded(path);
        data.put(path, value == null ? "" : value);
    }

    public synchronized void delete(String path, boolean recursive) {
        if ("/".equals(path)) {
            return;
        }
        if (recursive) {
            deleteRecursive(path);
            return;
        }
        if (hasChildren(path)) {
            throw new IllegalStateException("Node has children: " + path);
        }
        data.remove(path);
    }

    private ZNode buildTree(String path) {
        String name = "/".equals(path) ? "/" : path.substring(path.lastIndexOf('/') + 1);
        ZNode node = new ZNode(path, name, data.getOrDefault(path, ""));
        for (String child : getChildren(path)) {
            node.addChild(buildTree(child));
        }
        return node;
    }

    private List<String> getChildren(String path) {
        String prefix = "/".equals(path) ? "/" : path + "/";
        return data.keySet().stream()
            .filter(p -> p.startsWith(prefix) && !p.equals(path))
            .map(p -> p.substring(prefix.length()))
            .filter(p -> !p.isEmpty() && !p.contains("/"))
            .map(p -> prefix + p)
            .sorted()
            .collect(Collectors.toList());
    }

    private boolean hasChildren(String path) {
        String prefix = "/".equals(path) ? "/" : path + "/";
        return data.keySet().stream()
            .anyMatch(p -> p.startsWith(prefix) && !p.equals(path));
    }

    private void deleteRecursive(String path) {
        String prefix = "/".equals(path) ? "/" : path + "/";
        List<String> toRemove = new ArrayList<>();
        for (String key : data.keySet()) {
            if (key.equals(path) || key.startsWith(prefix)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            data.remove(key);
        }
    }

    private void createParentsIfNeeded(String path) {
        if ("/".equals(path)) {
            return;
        }
        String[] parts = path.split("/");
        String current = "";
        for (int i = 1; i < parts.length - 1; i++) {
            current += "/" + parts[i];
            data.putIfAbsent(current, "");
        }
    }

}
