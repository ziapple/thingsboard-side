/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.cluster.routing;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.msg.cluster.ServerAddress;
import org.thingsboard.server.common.msg.cluster.ServerType;
import org.thingsboard.server.service.cluster.discovery.DiscoveryService;
import org.thingsboard.server.service.cluster.discovery.DiscoveryServiceListener;
import org.thingsboard.server.service.cluster.discovery.ServerInstance;
import org.thingsboard.server.utils.MiscUtils;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Cluster service implementation based on consistent hash ring
 * 一致性算法，哈希环
 * n 台服务器从0到n-1编号，将值的关键字哈希后对n取余便得到了服务器的编号。
 * 在一致性哈希算法中，服务器也和关键字一样进行哈希。哈希空间足够大（一般取[0,2^32）)，并且被当作一个收尾相接的环（哈希环的由来），
 * 对服务器进行哈希就相当于将服务器放置在这个哈希环上。当我们需要查找一个关键字时，将它哈希（就是把它也定位到环上），
 * 然后沿着哈希环顺时针移动直到找到下一台服务器，当到达哈希环尾端后仍然找不到服务器时，使用第一台服务器。
 * 理论上这样就搞定上面说到的问题啦，但是在实践中，经过哈希后的服务器经常在环上聚集起来，这就会使得第一台服务器的压力大于其它服务器。
 * 这可以通过让服务器在环上分布得更均匀来改善。具体通过以下做法来实现：引入虚拟节点的概念，通过replica count（副本数）
 * 来控制每台物理服务器对应的虚节点数，当我们要添加一台服务器时，从0 到 replica count - 1 循环，
 * 将哈希关键字改为服务器关键字加上虚节点编号（hash(ser_str#1),hash(ser_str#2)...）生成虚节点的位置，将虚节点放置到哈希环上。
 * 这样做能有效地将服务器均匀分配到环上。注意到这里所谓的服务器副本是虚拟的节点，
 * 所以完全不涉及服务器之间的数据同步问题（简单地讲，就是现在变成了二段映射，先找到虚节点，然后再根据虚节点找到对应的物理机）
 */

@Service
@Slf4j
public class ConsistentClusterRoutingService implements ClusterRoutingService {

    @Autowired
    private DiscoveryService discoveryService;

    @Value("${cluster.hash_function_name}")
    private String hashFunctionName;
    @Value("${cluster.vitrual_nodes_size}")
    private Integer virtualNodesSize;

    private ServerInstance currentServer;

    private HashFunction hashFunction;

    private ConsistentHashCircle[] circles;
    private ConsistentHashCircle rootCircle;

    @PostConstruct
    public void init() {
        log.info("Initializing Cluster routing service!");
        this.hashFunction = MiscUtils.forName(hashFunctionName);
        this.currentServer = discoveryService.getCurrentServer();
        this.circles = new ConsistentHashCircle[ServerType.values().length];
        for (ServerType serverType : ServerType.values()) {
            circles[serverType.ordinal()] = new ConsistentHashCircle();
        }
        rootCircle = circles[ServerType.CORE.ordinal()];
        addNode(discoveryService.getCurrentServer());
        for (ServerInstance instance : discoveryService.getOtherServers()) {
            addNode(instance);
        }
        logCircle();
        log.info("Cluster routing service initialized!");
    }

    @Override
    public ServerAddress getCurrentServer() {
        return discoveryService.getCurrentServer().getServerAddress();
    }

    @Override
    public Optional<ServerAddress> resolveById(EntityId entityId) {
        return resolveByUuid(rootCircle, entityId.getId());
    }

    /**
     * 返回调度服务器
     * 1. 当前服务器与计算要调度的服务器不一致，返回调度服务器
     * 2. 一致，返回空
     * @param circle
     * @param uuid
     * @return
     */
    private Optional<ServerAddress> resolveByUuid(ConsistentHashCircle circle, UUID uuid) {
        Assert.notNull(uuid);
        if (circle.isEmpty()) {
            return Optional.empty();
        }
        Long hash = hashFunction.newHasher().putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits()).hash().asLong();
        if (!circle.containsKey(hash)) {
            ConcurrentNavigableMap<Long, ServerInstance> tailMap =
                    circle.tailMap(hash);
            hash = tailMap.isEmpty() ?
                    circle.firstKey() : tailMap.firstKey();
        }
        ServerInstance result = circle.get(hash);
        if (!currentServer.equals(result)) {
            return Optional.of(result.getServerAddress());
        } else {
            return Optional.empty();
        }
    }

    @Override
    public void onServerAdded(ServerInstance server) {
        log.info("On server added event: {}", server);
        addNode(server);
        logCircle();
    }

    @Override
    public void onServerUpdated(ServerInstance server) {
        log.debug("Ignoring server onUpdate event: {}", server);
    }

    @Override
    public void onServerRemoved(ServerInstance server) {
        log.info("On server removed event: {}", server);
        removeNode(server);
        logCircle();
    }

    private void addNode(ServerInstance instance) {
        for (int i = 0; i < virtualNodesSize; i++) {
            circles[instance.getServerAddress().getServerType().ordinal()].put(hash(instance, i).asLong(), instance);
        }
    }

    private void removeNode(ServerInstance instance) {
        for (int i = 0; i < virtualNodesSize; i++) {
            circles[instance.getServerAddress().getServerType().ordinal()].remove(hash(instance, i).asLong());
        }
    }

    private HashCode hash(ServerInstance instance, int i) {
        return hashFunction.newHasher().putString(instance.getHost(), MiscUtils.UTF8).putInt(instance.getPort()).putInt(i).hash();
    }

    private void logCircle() {
        log.trace("Consistent Hash Circle Start");
        Arrays.asList(circles).forEach(ConsistentHashCircle::log);
        log.trace("Consistent Hash Circle End");
    }

}
