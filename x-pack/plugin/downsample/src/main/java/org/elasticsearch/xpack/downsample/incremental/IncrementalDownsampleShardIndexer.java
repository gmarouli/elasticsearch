/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.downsample.incremental;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.Query;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkProcessor2;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.aggregations.support.TimeSeriesIndexSearcher;
import org.elasticsearch.xpack.downsample.DownsampleShardIndexerException;
import org.elasticsearch.xpack.downsample.TimeSeriesBucketCollector;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.elasticsearch.core.Strings.format;

/**
 * An indexer for downsampling that iterates documents collected by {@link TimeSeriesIndexSearcher} ona given time-range,
 * computes the downsample buckets and stores the buckets in the downsampled index.
 * <p>
 * The documents collected by the {@link TimeSeriesIndexSearcher} are expected to be sorted
 * by _tsid in ascending order and @timestamp in descending order.
 */
class IncrementalDownsampleShardIndexer {

    private static final Logger logger = LogManager.getLogger(IncrementalDownsampleShardIndexer.class);
    public static final int DOWNSAMPLE_BULK_ACTIONS = 10000;
    public static final ByteSizeValue DOWNSAMPLE_BULK_SIZE = ByteSizeValue.of(1, ByteSizeUnit.MB);
    public static final ByteSizeValue DOWNSAMPLE_MAX_BYTES_IN_FLIGHT = ByteSizeValue.of(50, ByteSizeUnit.MB);
    private final IndexShard sourceShard;
    private final Client client;
    private final String targetLayer;
    private final Engine.Searcher searcher;
    private final SearchExecutionContext searchExecutionContext;
    private final long startTime;
    private final long endTime;
    private final AtomicInteger numIndexed = new AtomicInteger();
    private final AtomicInteger numFailed = new AtomicInteger();
    private final AtomicInteger numReceived = new AtomicInteger();
    private final AtomicInteger numSent = new AtomicInteger();
    private final AtomicBoolean abort = new AtomicBoolean(false);
    // Used for testing back-pressure
    ByteSizeValue downsampleBulkSize = DOWNSAMPLE_BULK_SIZE;
    ByteSizeValue downsampleMaxBytesInFlight = DOWNSAMPLE_MAX_BYTES_IN_FLIGHT;

    IncrementalDownsampleShardIndexer(
        final long startTime,
        final long endTime,
        final Client client,
        final IndexService sourceIndexService,
        final ShardId shardId,
        final String targetLayer
    ) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.client = client;
        this.sourceShard = sourceIndexService.getShard(shardId.id());
        this.targetLayer = targetLayer;
        this.searcher = sourceShard.acquireSearcher("downsampling");
        Closeable toClose = searcher;
        try {
            this.searchExecutionContext = sourceIndexService.newSearchExecutionContext(
                sourceShard.shardId().id(),
                0,
                searcher,
                () -> 0L,
                null,
                Map.of()
            );
            toClose = null;
        } finally {
            IOUtils.closeWhileHandlingException(toClose);
        }
    }

    public void execute(
        final DownsampleConfig config,
        final String[] metrics,
        final String[] labels,
        final String[] dimensions,
        ActionListener<Long> listener
    ) throws Exception {
        final Query query = createQuery();
        long startTime = client.threadPool().relativeTimeInMillis();
        logger.info(
            "Downsampling time range [{}-{}] on shard {} started",
            Instant.ofEpochMilli(this.startTime),
            Instant.ofEpochMilli(endTime),
            sourceShard.shardId()
        );
        BulkProcessor2 bulkProcessor = createBulkProcessor();
        try (searcher; bulkProcessor) {
            final TimeSeriesIndexSearcher timeSeriesSearcher = new TimeSeriesIndexSearcher(searcher, List.of(this::checkCancelled));
            TimeSeriesBucketCollector bucketCollector = new TimeSeriesBucketCollector(
                config,
                targetLayer,
                sourceShard,
                bulkProcessor,
                dimensions,
                metrics,
                labels,
                this::checkCancelled,
                abort,
                new TimeSeriesBucketCollector.TrackingProgress() {
                    @Override
                    public void setLastIndexingTimestamp(long timestamp) {

                    }

                    @Override
                    public void setDocsProcessed(long docsProcessed) {

                    }

                    @Override
                    public void setLastTargetTimestamp(long timestamp) {

                    }

                    @Override
                    public void setLastSourceTimestamp(long timestamp) {

                    }

                    @Override
                    public void addNumReceived(long count) {
                        numReceived.addAndGet((int) count);
                    }
                },
                searchExecutionContext,
                startTime,
                client
            );
            bucketCollector.preCollection();
            timeSeriesSearcher.search(query, bucketCollector);
        }

        TimeValue duration = TimeValue.timeValueMillis(client.threadPool().relativeTimeInMillis() - startTime);
        logger.info(
            "Shard [{}] successfully sent [{}], received source doc [{}], indexed downsampled doc [{}], failed [{}], took [{}]",
            sourceShard.shardId(),
            numReceived.get(),
            numSent.get(),
            numIndexed.get(),
            numFailed,
            duration
        );

        if (numIndexed.get() != numSent.get()) {
            final String error = "Downsampling task ["
                + Instant.ofEpochMilli(this.startTime)
                + " - "
                + Instant.ofEpochMilli(endTime)
                + "] on shard "
                + sourceShard.shardId()
                + " failed indexing, "
                + " indexed ["
                + numIndexed
                + "] sent ["
                + numSent
                + "]";
            logger.info(error);
            listener.onFailure(new DownsampleShardIndexerException(error, false));
            return;
        }

        if (numFailed.get() > 0) {
            final String error = "Downsampling task ["
                + Instant.ofEpochMilli(this.startTime)
                + " - "
                + Instant.ofEpochMilli(endTime)
                + "] on shard "
                + sourceShard.shardId()
                + " failed indexing ["
                + numFailed.get()
                + "]";
            logger.info(error);
            listener.onFailure(new DownsampleShardIndexerException(error, false));
            return;
        }

        logger.info(
            "Downsampling task ["
                + Instant.ofEpochMilli(this.startTime)
                + " - "
                + Instant.ofEpochMilli(endTime)
                + "] on shard "
                + sourceShard.shardId()
                + " completed"
        );
        listener.onResponse(numIndexed.longValue());
    }

    private Query createQuery() throws IOException {
        return QueryBuilders.rangeQuery("@timestamp").gte(startTime).lt(endTime).toQuery(searchExecutionContext);
    }

    private void checkCancelled() {
        if (abort.get()) {
            logger.warn(
                "Shard [{}] downsample abort, sent [{}], indexed [{}], failed[{}]",
                sourceShard.shardId(),
                numSent.get(),
                numIndexed.get(),
                numFailed.get()
            );
            throw new DownsampleShardIndexerException("Bulk indexing failure", true);
        }
    }

    private BulkProcessor2 createBulkProcessor() {
        final BulkProcessor2.Listener listener = new BulkProcessor2.Listener() {

            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                numSent.addAndGet(request.numberOfActions());
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                long bulkIngestTookMillis = response.getIngestTookInMillis() >= 0 ? response.getIngestTookInMillis() : 0;
                long bulkTookMillis = response.getTook().getMillis();
                numIndexed.addAndGet(request.numberOfActions());
                if (response.hasFailures()) {
                    List<BulkItemResponse> failedItems = Arrays.stream(response.getItems()).filter(BulkItemResponse::isFailed).toList();
                    numFailed.addAndGet(failedItems.size());

                    Map<String, String> failures = failedItems.stream()
                        .collect(
                            Collectors.toMap(
                                BulkItemResponse::getId,
                                BulkItemResponse::getFailureMessage,
                                (msg1, msg2) -> Objects.equals(msg1, msg2) ? msg1 : msg1 + "," + msg2
                            )
                        );
                    logger.error("Shard [{}] failed to populate downsample index. Failures: [{}]", sourceShard.shardId(), failures);

                    abort.set(true);
                }
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Exception failure) {
                if (failure != null) {
                    long items = request.numberOfActions();
                    numFailed.addAndGet((int) items);
                    logger.error(() -> format("Shard [%s] failed to populate downsample index.", sourceShard.shardId()), failure);

                    abort.set(true);
                }
            }
        };

        return BulkProcessor2.builder(client::bulk, listener, client.threadPool())
            .setBulkActions(DOWNSAMPLE_BULK_ACTIONS)
            .setBulkSize(downsampleBulkSize)
            .setMaxBytesInFlight(downsampleMaxBytesInFlight)
            .setMaxNumberOfRetries(3)
            .build();
    }
}
