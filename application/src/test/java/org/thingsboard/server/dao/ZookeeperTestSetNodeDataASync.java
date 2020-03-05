package org.thingsboard.server.dao;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * @Author: rocky
 * @Date: Created in 2018/5/13.
 */
public class ZookeeperTestSetNodeDataASync implements Watcher {
    private static final CountDownLatch countDownLatch = new CountDownLatch(1);
    private static final String ADDRESS = "192.168.56.104:2181";
    private static final String PREFIX_SYNC = "/mytest-async-setData4-";
    private static   ZooKeeper zooKeeper ;
    public static void main(String[] args) throws IOException, InterruptedException, KeeperException {
        zooKeeper = new ZooKeeper(ADDRESS, 5000, new ZookeeperTestSetNodeDataASync());
        countDownLatch.await();
        zooKeeper.create(PREFIX_SYNC, "hello".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
        zooKeeper.setData(PREFIX_SYNC, "hello2".getBytes(), -1, new StatCallback(), null);
        Thread.sleep(Integer.MAX_VALUE);

    }
    public void process(WatchedEvent event) {
        if(Event.KeeperState.SyncConnected == event.getState()){
            if(Event.EventType.None == event.getType() && null == event.getPath()){
                countDownLatch.countDown();
            }
        }
    }

}
class StatCallback implements AsyncCallback.StatCallback {
    public void processResult(int rc, String path, Object ctx, Stat stat) {
        System.out.println("rc:"+rc + ", path:" + path + ",ctx:" + ctx + ", stat:" + stat);
    }
}