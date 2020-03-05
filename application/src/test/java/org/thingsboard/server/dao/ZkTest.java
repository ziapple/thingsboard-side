package org.thingsboard.server.dao;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.RetryForever;
import org.apache.curator.utils.CloseableUtils;
import org.junit.Test;
import org.thingsboard.server.service.cluster.discovery.DiscoveryService;

public class ZkTest implements PathChildrenCacheListener {
    private String zkUrl = "192.168.56.104:2181";
    private CuratorFramework client;
    private PathChildrenCache cache;

    @Test
    public void initZkClient() {
        try {
            client = CuratorFrameworkFactory.newClient(zkUrl, 3000, 3000, new RetryForever(1000));
            client.start();
            client.blockUntilConnected();
            cache = new PathChildrenCache(client, "/", true);
            cache.getListenable().addListener(this);


        } catch (Exception e) {
            CloseableUtils.closeQuietly(cache);
            CloseableUtils.closeQuietly(client);
            e.printStackTrace();
        }
    }

    @Override
    public void childEvent(CuratorFramework curatorFramework, PathChildrenCacheEvent pathChildrenCacheEvent) throws Exception {
        System.out.println(pathChildrenCacheEvent);
    }
}
