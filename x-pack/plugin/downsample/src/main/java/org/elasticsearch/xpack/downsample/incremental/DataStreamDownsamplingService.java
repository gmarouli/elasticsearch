/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.action.support.RefCountingListener;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This service is responsible for downsampling a time range for a given data stream.
 */
public class DataStreamDownsamplingService {

    public static final DownsampleConfig CONFIG = new DownsampleConfig(new DateHistogramInterval("5m"));
    private static final Logger logger = LogManager.getLogger(DataStreamDownsamplingService.class);
    private final Client client;
    private final IndicesService indicesService;
    private final PocHelper pocHelper;
    private final Clock clock;

    public DataStreamDownsamplingService(IndicesService indicesService, PocHelper pocHelper, Clock clock, Client client) {
        this.indicesService = indicesService;
        this.client = client;
        this.pocHelper = pocHelper;
        this.clock = clock;
    }

    public void performDownsampling(
        ProjectMetadata projectMetadata,
        DataStream dataStream,
        DownsampleConfig targetDownsampleConfig,
        DownsamplingProgressTracker progressTracker,
        ActionListener<Void> listener
    ) {
        try (RefCountingListener refCountingListener = new RefCountingListener(listener)) {
            for (int i = dataStream.getIndices().size() - 1; i >= 0; i--) {
                performDownsamplingPerIndex(
                    projectMetadata,
                    dataStream,
                    dataStream.getIndices().get(i),
                    targetDownsampleConfig,
                    progressTracker,
                    refCountingListener.acquire()
                );
            }
        }
    }

    private void performDownsamplingPerIndex(
        ProjectMetadata projectMetadata,
        DataStream dataStream,
        Index index,
        DownsampleConfig downsampleConfig,
        DownsamplingProgressTracker progressTracker,
        ActionListener<Void> listener
    ) {
        IndexMetadata indexMetadata = projectMetadata.index(index);
        var numberOfShards = indexMetadata.getNumberOfShards();
        Map<ShardId, DownsamplingProgressTracker.TimeRange> localShards = new HashMap<>(numberOfShards);
        Instant now = clock.instant();
        for (int i = 0; i < numberOfShards; i++) {
            IndexShard indexShard = indicesService.getShardOrNull(new ShardId(indexMetadata.getIndex(), i));
            if (indexShard == null) {
                continue;
            }
            DownsamplingProgressTracker.TimeRange timeRangeToDownsample = progressTracker.mostRecentTimeRangeToDownsample(
                indexShard.shardId(),
                now,
                indexMetadata.getTimeSeriesStart(),
                indexMetadata.getTimeSeriesEnd()
            );
            if (timeRangeToDownsample != null) {
                localShards.put(indexShard.shardId(), timeRangeToDownsample);
            }
        }
        if (localShards.isEmpty()) {
            logger.info("No local shards that need downsampling found for this data stream.");
            listener.onResponse(null);
            return;
        }

        String downsampleLayerIndexName = DataStream.getDefaultDownsampleLayerIndexName(
            dataStream.getName(),
            downsampleConfig.getInterval()
        );
        SubscribableListener.<GetMappingsResponse>newForked(
            l -> client.admin().indices().getMappings(new GetMappingsRequest(TimeValue.THIRTY_SECONDS).indices(index.getName()), l)
        ).andThenApply(r -> pocHelper.getFieldsPerType(downsampleConfig, indexMetadata, r)).<Long>andThen((l, fields) -> {
            AtomicLong totalIndexedAggregates = new AtomicLong();
            try (RefCountingListener refCountingListener = new RefCountingListener(l.map(ignored -> totalIndexedAggregates.get()))) {
                for (ShardId shardId : localShards.keySet()) {
                    DownsamplingProgressTracker.TimeRange timeRange = localShards.get(shardId);
                    final var downsampleShardIndexer = new IncrementalDownsampleShardIndexer(
                        timeRange,
                        client,
                        indicesService.indexServiceSafe(indexMetadata.getIndex()),
                        shardId,
                        downsampleLayerIndexName
                    );
                    ActionListener<Long> acquiredListener = refCountingListener.acquire(indexedAggregatesCount -> {
                        totalIndexedAggregates.addAndGet(indexedAggregatesCount);
                        progressTracker.updateLastDownsampledBucket(shardId, timeRange.end());
                    });
                    try {

                        downsampleShardIndexer.execute(
                            CONFIG,
                            fields.metrics().toArray(new String[0]),
                            fields.labels().toArray(new String[0]),
                            fields.dimensions().toArray(new String[0]),
                            acquiredListener
                        );
                    } catch (Exception e) {
                        logger.error("Mary: got an unexpected exception while downsampling data stream " + dataStream.getName(), e);
                        acquiredListener.onFailure(e);
                    }
                }
            }

        }).<BroadcastResponse>andThen((l, totalIndexedDocuments) -> {
            if (totalIndexedDocuments == 0) {
                l.onResponse(null);
            } else {
                pocHelper.refreshDownsampleLayer(dataStream.getName(), downsampleConfig, l);
            }
        }).<Void>andThen(ignored -> {}).addListener(listener);
    }
}
