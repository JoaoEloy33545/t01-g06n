package mdeos.isos.infra.zkquorum.doe.server;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

@ApplicationScoped
public class ZkClientService {

    private final ExpectedNodesStore expectedNodesStore;
    private final InMemoryZkStore fallbackStore = new InMemoryZkStore();
    private final boolean allowDegradedOps;

    public ZkClientService(
        ExpectedNodesStore expectedNodesStore,
        @ConfigProperty(name = "zkquorum.ui.allow-offline-ops", defaultValue = "true") boolean allowDegradedOps
    ) {
        this.expectedNodesStore = expectedNodesStore;
        this.allowDegradedOps = allowDegradedOps;
    }

    public ZNode getTree(String connectString, String rootPath) {
        try {
            return withClient(connectString, zk -> buildTree(zk, rootPath));
        } catch (IllegalStateException e) {
            if (allowDegradedOps) {
                return fallbackStore.getTree(rootPath);
            }
            throw e;
        }
    }

    public ZNode getTreeBestEffort(String connectString, String rootPath) {
        try {
            return getTree(connectString, rootPath);
        } catch (RuntimeException e) {
            if (allowDegradedOps) {
                return fallbackStore.getTree(rootPath);
            }
            throw e;
        }
    }

    public ZNode getTreeOffline(String rootPath) {
        return fallbackStore.getTree(rootPath);
    }

    public boolean canConnect(String connectString, int seconds, int attempts) {
        try {
            tryConnect(connectString, seconds, attempts);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getData(String connectString, String path) {
        try {
            return withClient(connectString, zk -> readData(zk, path));
        } catch (IllegalStateException e) {
            if (allowDegradedOps) {
                return fallbackStore.getData(path);
            }
            throw e;
        }
    }

    public String getDataBestEffort(String connectString, String path) {
        try {
            return getData(connectString, path);
        } catch (RuntimeException e) {
            if (allowDegradedOps) {
                return fallbackStore.getData(path);
            }
            throw e;
        }
    }

    public String getDataOffline(String path) {
        return fallbackStore.getData(path);
    }

    public void createOrUpdate(String connectString, String path, String data) {
        try {
            withClient(connectString, zk -> {
                createParentsIfNeeded(zk, path);
                byte[] payload = data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8);
                if (zk.exists(path, false) == null) {
                    zk.create(path, payload, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } else {
                    zk.setData(path, payload, -1);
                }
                return null;
            });
        } catch (IllegalStateException e) {
            if (allowDegradedOps) {
                fallbackStore.createOrUpdate(path, data);
                return;
            }
            throw e;
        }
    }

    public void createOrUpdateBestEffort(String connectString, String path, String data) {
        try {
            createOrUpdate(connectString, path, data);
        } catch (RuntimeException e) {
            if (allowDegradedOps) {
                fallbackStore.createOrUpdate(path, data);
                return;
            }
            throw e;
        }
    }

    public void createOrUpdateOffline(String path, String data) {
        fallbackStore.createOrUpdate(path, data);
    }

    public void delete(String connectString, String path, boolean recursive) {
        try {
            withClient(connectString, zk -> {
                if (recursive) {
                    deleteRecursive(zk, path);
                } else {
                    zk.delete(path, -1);
                }
                return null;
            });
        } catch (IllegalStateException e) {
            if (allowDegradedOps) {
                fallbackStore.delete(path, recursive);
                return;
            }
            throw e;
        }
    }

    public void deleteBestEffort(String connectString, String path, boolean recursive) {
        try {
            delete(connectString, path, recursive);
        } catch (RuntimeException e) {
            if (allowDegradedOps) {
                fallbackStore.delete(path, recursive);
                return;
            }
            throw e;
        }
    }

    public void deleteOffline(String path, boolean recursive) {
        fallbackStore.delete(path, recursive);
    }

    public boolean isOfflineAllowed() {
        return allowDegradedOps;
    }

    private ZNode buildTree(ZooKeeper zk, String path) throws KeeperException, InterruptedException {
        String name = path.equals("/") ? "/" : path.substring(path.lastIndexOf('/') + 1);
        String data = readData(zk, path);
        ZNode node = new ZNode(path, name, data);
        List<String> children = zk.getChildren(path, false);
        for (String child : children) {
            String childPath = path.equals("/") ? "/" + child : path + "/" + child;
            node.addChild(buildTree(zk, childPath));
        }
        return node;
    }

    private String readData(ZooKeeper zk, String path) throws KeeperException, InterruptedException {
        byte[] payload = zk.getData(path, false, null);
        if (payload == null || payload.length == 0) {
            return "";
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private void deleteRecursive(ZooKeeper zk, String path) throws KeeperException, InterruptedException {
        List<String> children = new ArrayList<>(zk.getChildren(path, false));
        for (String child : children) {
            String childPath = path.equals("/") ? "/" + child : path + "/" + child;
            deleteRecursive(zk, childPath);
        }
        zk.delete(path, -1);
    }

    private void createParentsIfNeeded(ZooKeeper zk, String path) throws KeeperException, InterruptedException {
        if ("/".equals(path)) {
            return;
        }
        String[] parts = path.split("/");
        String current = "";
        for (int i = 1; i < parts.length - 1; i++) {
            current += "/" + parts[i];
            if (zk.exists(current, false) == null) {
                zk.create(current, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        }
    }

    private <T> T withClient(String connectString, ZkClientAction<T> action) {
        ZooKeeper zk = null;
        try {
            zk = connect(connectString);
            return action.apply(zk);
        } catch (Exception e) {
            throw new IllegalStateException("ZooKeeper error: " + e.getMessage(), e);
        } finally {
            if (zk != null) {
                try {
                    zk.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private ZooKeeper connect(String connectString) throws Exception {
        return tryConnect(connectString, 10, 3);
    }

    private ZooKeeper tryConnect(String connectString, int seconds, int attempts) throws Exception {
        IllegalStateException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            CountDownLatch latch = new CountDownLatch(1);
            ZooKeeper zk = new ZooKeeper(connectString, 30000, new Watcher() {
                @Override
                public void process(WatchedEvent event) {
                    if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                        latch.countDown();
                    }
                }
            });

            boolean connected = latch.await(seconds, TimeUnit.SECONDS);
            if (connected) {
                return zk;
            }

            try {
                zk.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            int expected = Math.max(1, expectedNodesStore.get());
            int quorum = expected / 2 + 1;
            lastError = new IllegalStateException(
                "ZooKeeper connection not established. For a " + expected
                    + "-node pool, at least " + quorum + " nodes must be running. Connect string: "
                    + connectString
            );

            Thread.sleep(500);
        }

        throw lastError == null
            ? new IllegalStateException("ZooKeeper quorum not available: " + connectString)
            : lastError;
    }

    @FunctionalInterface
    private interface ZkClientAction<T> {
        T apply(ZooKeeper zk) throws Exception;
    }
}
