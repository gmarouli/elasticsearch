/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsRequest;
import org.elasticsearch.action.datastreams.CreateDataStreamAction;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xpack.downsample.incremental.IncrementalDownsamplingService;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertResponse;

public class IncrementalDownsampleIT extends DownsamplingIntegTestCase {

    @Before
    public void setupCluster() throws Exception {
        assertAcked(
            client().admin()
                .cluster()
                .updateSettings(
                    new ClusterUpdateSettingsRequest(TimeValue.THIRTY_SECONDS, TimeValue.THIRTY_SECONDS).persistentSettings(
                        Settings.builder()
                            .put(
                                IncrementalDownsamplingService.INCREMENTAL_DOWNSAMPLING_POLL_INTERVAL_SETTING.getKey(),
                                TimeValue.THIRTY_SECONDS
                            )
                            .build()
                    )
                )
        );
    }

    @After
    public void tearDownCluster() throws Exception {
        assertAcked(
            client().admin()
                .cluster()
                .updateSettings(
                    new ClusterUpdateSettingsRequest(TimeValue.THIRTY_SECONDS, TimeValue.THIRTY_SECONDS).persistentSettings(
                        Settings.builder()
                            .putNull(IncrementalDownsamplingService.INCREMENTAL_DOWNSAMPLING_POLL_INTERVAL_SETTING.getKey())
                            .build()
                    )
                )
        );
    }

    public void testDownsampling() throws Exception {
        String dataStreamName = "id-metrics";
        // Set up template
        putTSDBIndexTemplate(
            "my-template",
            List.of("id-metrics"),
            Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0).build(),
            """
                {
                  "properties": {
                    "attributes.host.name": {
                      "type": "keyword",
                      "time_series_dimension": true
                    },
                    "metrics.cpu_usage": {
                      "type": "double",
                      "time_series_metric": "counter"
                    },
                    "metrics.cpu_temperature": {
                      "type": "double",
                      "time_series_metric": "gauge"
                    }
                  }
                }
                """,
            null,
            null
        );

        assertAcked(
            client().execute(
                CreateDataStreamAction.INSTANCE,
                new CreateDataStreamAction.Request(TimeValue.THIRTY_SECONDS, TimeValue.THIRTY_SECONDS, dataStreamName)
            )
        );
        AtomicBoolean done = new AtomicBoolean(false);
        Thread thread = new Thread(() -> {
            while (done.get() == false) {
                final Instant now = Instant.now();
                Supplier<XContentBuilder> sourceSupplier = () -> {
                    String ts = randomDateForRange(now.minusSeconds(60).toEpochMilli(), now.plusSeconds(29).toEpochMilli());
                    try {
                        return XContentFactory.jsonBuilder()
                            .startObject()
                            .field("@timestamp", ts)
                            .field("attributes.host.name", randomFrom("host1", "host2", "host3"))
                            .field("metrics.cpu_usage", randomDouble())
                            .field("metrics.cpu_temperature", randomDouble())
                            .endObject();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };
                bulkIndex(dataStreamName, sourceSupplier, 100);
                try {
                    Thread.sleep(1_000);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        thread.start();
        String targetDataStream = DataStream.getDefaultDownsampleLayerIndexName(dataStreamName, new DateHistogramInterval("5m"));

        // Wait for downsampling to initialize
        final var clusterService = internalCluster().getCurrentMasterNodeInstance(ClusterService.class);
        final var listener = ClusterServiceUtils.addTemporaryStateListener(
            clusterService,
            clusterState -> clusterState.metadata().getProject().dataStreams().containsKey(targetDataStream),
            TimeValue.timeValueMinutes(10)
        );
        safeAwait(listener, TimeValue.timeValueMinutes(10));
        ensureGreen(targetDataStream);

        assertBusy(
            () -> assertResponse(
                client().prepareSearch(targetDataStream).setQuery(new MatchAllQueryBuilder()).setSize(0),
                targetIndexSearch -> {
                    assertTrue(targetIndexSearch.getHits().getTotalHits().value() > 0);
                }
            ),
            10,
            TimeUnit.MINUTES
        );
        done.set(true);
        thread.join();
    }
}
