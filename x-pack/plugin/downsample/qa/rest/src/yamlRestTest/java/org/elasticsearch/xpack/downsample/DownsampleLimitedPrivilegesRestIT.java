/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xcontent.ObjectPath;
import org.junit.ClassRule;

import java.io.IOException;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;

/**
 * Downsampling records the completion of every shard in the target index metadata with an internal action that is not meant to be
 * granted to users: {@code cluster:admin/xpack/downsample/shard_completed}. The shard-level tasks run it under the rollup origin, so
 * the user that started the downsampling does not need any privilege on it, and cannot use it either.
 * <p>
 * The other security tests of downsampling run as a superuser, which is allowed to run any action whether the origin is set or not.
 * This test uses a user that only has index privileges, so it fails if the completion is ever sent with the privileges of the user.
 */
public class DownsampleLimitedPrivilegesRestIT extends ESRestTestCase {

    private static final String ADMIN_USERNAME = "elastic_admin";
    private static final String ADMIN_PASSWORD = "admin-password";

    private static final String LIMITED_USERNAME = "downsample_limited_user";
    private static final String LIMITED_PASSWORD = "limited-password";
    private static final String LIMITED_ROLE = "downsample_limited_role";

    private static final String SOURCE = "limited-source";
    private static final String TARGET = "limited-target";

    @ClassRule
    public static ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .module("x-pack-downsample")
        .module("analysis-common") // the security index uses an analyzer defined by this module
        .setting("xpack.license.self_generated.type", "trial")
        .setting("xpack.security.enabled", "true")
        .user(ADMIN_USERNAME, ADMIN_PASSWORD)
        .build();

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    @Override
    protected Settings restClientSettings() {
        String authentication = basicAuthHeaderValue(ADMIN_USERNAME, new SecureString(ADMIN_PASSWORD.toCharArray()));
        return Settings.builder().put(super.restClientSettings()).put(ThreadContext.PREFIX + ".Authorization", authentication).build();
    }

    public void testUserWithoutClusterPrivilegesCanDownsample() throws Exception {
        createLimitedUser();
        try {
            createSourceIndex();

            // Control: the user does not hold any cluster privilege, so the completion cannot have been recorded with its privileges.
            final ResponseException forbidden = expectThrows(
                ResponseException.class,
                () -> client().performRequest(asLimitedUser(new Request("GET", "/_cluster/state")))
            );
            assertThat(forbidden.getResponse().getStatusLine().getStatusCode(), equalTo(403));

            final Request downsample = asLimitedUser(new Request("POST", "/" + SOURCE + "/_downsample/" + TARGET));
            downsample.setJsonEntity("""
                { "fixed_interval": "1h" }
                """);
            final Response response = client().performRequest(downsample);
            assertThat(entityAsMap(response).get("acknowledged"), equalTo(true));

            // The completion is recorded in the target index metadata, so a later request for the same target index can skip the shard.
            final Request state = new Request("GET", "/_cluster/state/metadata/" + TARGET);
            state.addParameter("filter_path", "metadata.indices." + TARGET + ".downsample");
            final Map<String, Object> clusterState = entityAsMap(client().performRequest(state));
            assertThat(
                "the completion of the shard was not recorded in the target index metadata: " + clusterState,
                ObjectPath.eval("metadata.indices." + TARGET + ".downsample.completed_shards", clusterState),
                equalTo("0")
            );
        } finally {
            deleteLimitedUser();
        }
    }

    private static Request asLimitedUser(Request request) {
        final RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
        options.addHeader("Authorization", basicAuthHeaderValue(LIMITED_USERNAME, new SecureString(LIMITED_PASSWORD.toCharArray())));
        request.setOptions(options);
        return request;
    }

    /**
     * A role with index privileges only: no cluster privilege at all, in particular none that matches the internal action that records
     * the completion of a shard.
     */
    private void createLimitedUser() throws IOException {
        final Request role = new Request("PUT", "/_security/role/" + LIMITED_ROLE);
        role.setJsonEntity("""
            {
              "indices": [
                { "names": [ "%s", "%s" ], "privileges": [ "read", "manage" ] }
              ]
            }
            """.formatted(SOURCE, TARGET));
        client().performRequest(role);

        final Request user = new Request("PUT", "/_security/user/" + LIMITED_USERNAME);
        user.setJsonEntity("""
            { "password": "%s", "roles": [ "%s" ] }
            """.formatted(LIMITED_PASSWORD, LIMITED_ROLE));
        client().performRequest(user);
    }

    private void deleteLimitedUser() throws IOException {
        client().performRequest(new Request("DELETE", "/_security/user/" + LIMITED_USERNAME));
        client().performRequest(new Request("DELETE", "/_security/role/" + LIMITED_ROLE));
    }

    private void createSourceIndex() throws IOException {
        final Request create = new Request("PUT", "/" + SOURCE);
        create.setJsonEntity("""
            {
              "settings": {
                "number_of_shards": 1,
                "number_of_replicas": 0,
                "index": {
                  "mode": "time_series",
                  "routing_path": [ "metricset" ],
                  "time_series": { "start_time": "2021-04-28T00:00:00Z", "end_time": "2021-04-29T00:00:00Z" }
                }
              },
              "mappings": {
                "properties": {
                  "@timestamp": { "type": "date" },
                  "metricset": { "type": "keyword", "time_series_dimension": true },
                  "gauge": { "type": "long", "time_series_metric": "gauge" }
                }
              }
            }
            """);
        client().performRequest(create);
        ensureGreen(SOURCE);

        final Request bulk = new Request("POST", "/" + SOURCE + "/_bulk");
        bulk.addParameter("refresh", "true");
        bulk.setJsonEntity("""
            {"index": {}}
            {"@timestamp": "2021-04-28T18:50:04.467Z", "metricset": "pod", "gauge": 10}
            {"index": {}}
            {"@timestamp": "2021-04-28T18:50:24.467Z", "metricset": "pod", "gauge": 20}
            {"index": {}}
            {"@timestamp": "2021-04-28T20:50:44.467Z", "metricset": "pod", "gauge": 30}
            """);
        client().performRequest(bulk);

        final Request block = new Request("PUT", "/" + SOURCE + "/_settings");
        block.setJsonEntity("""
            { "index.blocks.write": true }
            """);
        client().performRequest(block);
    }
}
