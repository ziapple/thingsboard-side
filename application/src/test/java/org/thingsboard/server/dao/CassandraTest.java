package org.thingsboard.server.dao;

import com.datastax.driver.core.*;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CassandraTest {
    private static final String COMMA = ",";
    private static final String COLON = ":";
    private static final String keyspaceName = "thingsboard";
    private static final String url = "106.74.18.237:22586";
    private static final String clusterName = "Thingsboard Cluster";
    private static final String username = "cassandra";
    private static final String password = "cassandra";

    private List<InetSocketAddress> getContactPoints(String url) {
        List<InetSocketAddress> result;
        if (StringUtils.isBlank(url)) {
            result = Collections.emptyList();
        } else {
            result = new ArrayList<>();
            for (String hostPort : url.split(COMMA)) {
                String host = hostPort.split(COLON)[0];
                Integer port = Integer.valueOf(hostPort.split(COLON)[1]);
                result.add(new InetSocketAddress(host, port));
            }
        }
        return result;
    }

    @Test
    public void testCassandraConnection(){
        Cluster.Builder clusterBuilder = Cluster.builder()
                .addContactPointsWithPorts(getContactPoints(url))
                .withClusterName(clusterName);
        clusterBuilder.withCredentials(username, password);
        Cluster cluster = clusterBuilder.build();
        cluster.init();
        Session session;
        if (keyspaceName != null) {
            session = cluster.connect(keyspaceName);
        } else {
            session = cluster.connect();
        }
    }
}
