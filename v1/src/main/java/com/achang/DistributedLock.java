package com.achang;

import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class DistributedLock {

    // zookeeper server 列表
    private String connectString = "hadoop102:2181,hadoop103:2181,hadoop104:2181";

    // 超时时间
    private int sessionTimeout = 2000;
    private ZooKeeper zk;
    private String rootNode = "locks";
    private String subNode = "seq-";
    // 当前 client 等待的子节点
    private String waitPath;
    //ZooKeeper 连接
    private CountDownLatch connectLatch = new CountDownLatch(1);
    //ZooKeeper 节点等待
    private CountDownLatch waitLatch = new CountDownLatch(1);
    // 当前 client 创建的子节点
    private String currentNode;

    // 和 zk 服务建立连接，并创建根节点
    public DistributedLock() throws IOException,InterruptedException, KeeperException {
        zk = new ZooKeeper(connectString, sessionTimeout, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                // 连接建立时, 打开 latch, 唤醒 wait 在该 latch 上的线程
                if (event.getState() == Event.KeeperState.SyncConnected) {
                    connectLatch.countDown();
                }
                // 发生了 waitPath 的删除事件
                if (event.getType() == Event.EventType.NodeDeleted && event.getPath().equals(waitPath)){
                    waitLatch.countDown();
                }
            }
        });
        // 等待连接建立
        connectLatch.await();
        //获取根节点状态
        Stat stat = zk.exists("/" + rootNode, false);
        //如果根节点不存在，则创建根节点，根节点类型为永久节点
        if (stat == null) {
            System.out.println("根节点不存在");
            zk.create("/" + rootNode, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    // 加锁方法
    public void zkLock() {
        try {
            //在根节点下创建临时顺序节点，返回值为创建的节点路径
            currentNode = zk.create("/" + rootNode + "/" + subNode,null,ZooDefs.Ids.OPEN_ACL_UNSAFE,CreateMode.EPHEMERAL_SEQUENTIAL);
            // wait 一小会, 让结果更清晰一些
            Thread.sleep(10);
            // 注意, 没有必要监听"/locks"的子节点的变化情况
            List<String> childrenNodes = zk.getChildren("/" + rootNode, false);
            // 列表中只有一个子节点, 那肯定就是 currentNode , 说明client 获得锁
            if (childrenNodes.size() == 1) {
                return;
            } else {
                //对根节点下的所有临时顺序节点进行从小到大排序
                Collections.sort(childrenNodes);
                //当前节点名称
                String thisNode = currentNode.substring(("/" + rootNode + "/").length());
                //获取当前节点的位置
                int index = childrenNodes.indexOf(thisNode);
                if (index == -1) {
                    System.out.println("数据异常");
                } else if (index == 0) {
                    // index == 0, 说明 thisNode 在列表中最小, 当前client 获得锁
                    return;
                } else {
                    // 获得排名比 currentNode 前 1 位的节点
                    this.waitPath = "/" + rootNode + "/" + childrenNodes.get(index - 1);
                    // 在 waitPath 上注册监听器, 当 waitPath 被删除时,zookeeper 会回调监听器的 process 方法
                    zk.getData(waitPath, true, new Stat());
                    //进入等待锁状态
                    waitLatch.await();
                    return;
                }
            }
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // 解锁方法
    public void zkUnlock() {
        try {
            zk.delete(this.currentNode, -1);
        } catch (InterruptedException | KeeperException e) {
            e.printStackTrace();
        }
    }

}
