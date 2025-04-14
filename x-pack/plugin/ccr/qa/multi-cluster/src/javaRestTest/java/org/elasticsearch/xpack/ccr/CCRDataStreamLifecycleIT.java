/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ccr;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.LocalClusterConfigProvider;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;

import java.io.IOException;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;

public class CCRDataStreamLifecycleIT extends AbstractCCRRestTestCase {

    public static LocalClusterConfigProvider commonConfig = c -> c.distribution(DistributionType.DEFAULT)
        .setting("xpack.security.enabled", "true")
        .setting("xpack.license.self_generated.type", "trial")
        .setting("cluster.lifecycle.default.rollover", "max_docs=1")
        .setting("data_streams.lifecycle.poll_interval", "1s")
        .user("admin", "admin-password", "superuser", false);

    public static ElasticsearchCluster leaderCluster = ElasticsearchCluster.local().name("leader-cluster").apply(commonConfig).build();

    public static ElasticsearchCluster followerCluster = ElasticsearchCluster.local()
        .name("follow-cluster")
        .apply(commonConfig)
        .setting("cluster.remote.leader_cluster.seeds", () -> "\"" + leaderCluster.getTransportEndpoints() + "\"")
        .build();

    @ClassRule
    public static RuleChain ruleChain = RuleChain.outerRule(leaderCluster).around(followerCluster);

    public CCRDataStreamLifecycleIT(@Name("targetCluster") TargetCluster targetCluster) {
        super(targetCluster);
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws Exception {
        return leaderFollower();
    }

    @Override
    protected ElasticsearchCluster getLeaderCluster() {
        return leaderCluster;
    }

    @Override
    protected ElasticsearchCluster getFollowerCluster() {
        return followerCluster;
    }

    @Override
    protected Settings restClientSettings() {
        String token = basicAuthHeaderValue("admin", new SecureString("admin-password".toCharArray()));
        return Settings.builder().put(ThreadContext.PREFIX + ".Authorization", token).build();
    }

    public void testBasicCCRAndDataStreamLifecycle() throws Exception {
        if (targetCluster != TargetCluster.FOLLOWER) {
            logger.info("skipping test, waiting for target cluster [follow]");
            return;
        }
        try (RestClient leaderClient = buildLeaderClient()) {
            String dataStreamName = "logs-1";
            putTemplateForDataStream(leaderClient, dataStreamName);
            putTemplateForDataStream(client(), dataStreamName);
            putAutoFollowPattern("my-ds-pattern", "leader_cluster", dataStreamName + "*");

            createDataStream(leaderClient, dataStreamName);
            ensureGreen(dataStreamName);

            indexDoc(leaderClient, dataStreamName);

            assertBusy(() -> {
                List<String> dataStreamBackingIndexNames = getDataStreamBackingIndexNames(dataStreamName);
                assertThat(dataStreamBackingIndexNames, hasSize(2));
            });

            var retention = TimeValue.ZERO;
            logger.info("Setting retention for [{}:{}]", dataStreamName, retention.getStringRep());
            setRetention(leaderClient, dataStreamName, retention);
            setRetention(client(), dataStreamName, retention);
            assertBusy(() -> {
                List<String> dataStreamBackingIndexNames = getDataStreamBackingIndexNames(dataStreamName);
                assertThat(dataStreamBackingIndexNames, hasSize(1));
            });
        }
    }

    private void putTemplateForDataStream(RestClient client, String dataStreamName) throws IOException {
        Request putComposableIndexTemplateRequest = new Request("POST", "/_index_template/my-template");
        putComposableIndexTemplateRequest.setJsonEntity(Strings.format("""
            {
              "index_patterns": [ "%s*" ],
              "data_stream": {},
              "template": {
                "settings": {
                  "index.number_of_replicas": 0
                },
                "lifecycle": {
                  "enabled": true
                }
              }
            }""", dataStreamName));
        assertAcknowledged(client.performRequest(putComposableIndexTemplateRequest));
    }

    private void createDataStream(RestClient client, String dataStreamName) throws IOException {
        assertAcknowledged(client.performRequest(new Request("PUT", "/_data_stream/" + dataStreamName)));
    }

    private void indexDoc(RestClient client, String dataStreamName) throws IOException {
        Request createDocRequest = new Request("POST", "/" + dataStreamName + "/_doc");
        createDocRequest.setJsonEntity("{ \"@timestamp\": \"2022-01-01\", \"message\": \"foo\" }");
        assertOK(client.performRequest(createDocRequest));
    }

    private void setRetention(RestClient client, String dataStreamName, TimeValue retention) throws IOException {
        Request request = new Request("PUT", "/_data_stream/" + dataStreamName + "/_lifecycle");
        request.setJsonEntity(Strings.format("""
            {
              "enabled": true,
               "data_retention": "%s"
            }""", retention.getStringRep()));
        assertAcknowledged(client.performRequest(request));
    }

}
