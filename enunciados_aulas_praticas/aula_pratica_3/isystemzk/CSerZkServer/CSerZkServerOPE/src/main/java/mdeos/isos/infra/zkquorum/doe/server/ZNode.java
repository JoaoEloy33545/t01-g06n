package mdeos.isos.infra.zkquorum.doe.server;

import java.util.ArrayList;
import java.util.List;

public class ZNode {
    private final String path;
    private final String name;
    private final String data;
    private final List<ZNode> children = new ArrayList<>();

    public ZNode(String path, String name, String data) {
        this.path = path;
        this.name = name;
        this.data = data;
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public String getData() {
        return data;
    }

    public List<ZNode> getChildren() {
        return children;
    }

    public void addChild(ZNode child) {
        children.add(child);
    }
}
