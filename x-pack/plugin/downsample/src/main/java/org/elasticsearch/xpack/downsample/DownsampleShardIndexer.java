/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.downsample;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
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
import org.elasticsearch.index.mapper.TimeSeriesIdFieldMapper;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.aggregations.support.TimeSeriesIndexSearcher;
import org.elasticsearch.tasks.TaskCancelledException;
import org.elasticsearch.xpack.core.downsample.DownsampleAfterBulkInfo;
import org.elasticsearch.xpack.core.downsample.DownsampleBeforeBulkInfo;
import org.elasticsearch.xpack.core.downsample.DownsampleIndexerAction;
import org.elasticsearch.xpack.core.downsample.DownsampleShardIndexerStatus;
import org.elasticsearch.xpack.core.downsample.DownsampleShardPersistentTaskState;
import org.elasticsearch.xpack.core.downsample.DownsampleShardTask;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.elasticsearch.core.Strings.format;

/**
 * An indexer for downsampling that iterates documents collected by {@link TimeSeriesIndexSearcher},
 * computes the downsample buckets and stores the buckets in the downsampled index.
 * <p>
 * The documents collected by the {@link TimeSeriesIndexSearcher} are expected to be sorted
 * by _tsid in ascending order and @timestamp in descending order.
 */
class DownsampleShardIndexer {

    private static final Logger logger = LogManager.getLogger(DownsampleShardIndexer.class);
    public static final int DOWNSAMPLE_BULK_ACTIONS = 10000;
    public static final ByteSizeValue DOWNSAMPLE_BULK_SIZE = ByteSizeValue.of(1, ByteSizeUnit.MB);
    public static final ByteSizeValue DOWNSAMPLE_MAX_BYTES_IN_FLIGHT = ByteSizeValue.of(50, ByteSizeUnit.MB);
    private final IndexShard indexShard;
    private final Client client;
    private final DownsampleMetrics downsampleMetrics;
    private final String downsampleIndex;
    private final Engine.Searcher searcher;
    private final SearchExecutionContext searchExecutionContext;
    private final DownsampleShardTask task;
    private final DownsampleShardPersistentTaskState state;
    private final AtomicBoolean abort = new AtomicBoolean(false);
    // Used for testing back-pressure
    ByteSizeValue downsampleBulkSize = DOWNSAMPLE_BULK_SIZE;
    ByteSizeValue downsampleMaxBytesInFlight = DOWNSAMPLE_MAX_BYTES_IN_FLIGHT;

    DownsampleShardIndexer(
        final DownsampleShardTask task,
        final Client client,
        final IndexService indexService,
        final DownsampleMetrics downsampleMetrics,
        final ShardId shardId,
        final String downsampleIndex,
        final DownsampleShardPersistentTaskState state
    ) {
        this.task = task;
        this.client = client;
        this.downsampleMetrics = downsampleMetrics;
        this.indexShard = indexService.getShard(shardId.id());
        this.downsampleIndex = downsampleIndex;
        this.searcher = indexShard.acquireSearcher("downsampling");
        this.state = state;
        Closeable toClose = searcher;
        try {
            this.searchExecutionContext = indexService.newSearchExecutionContext(
                indexShard.shardId().id(),
                0,
                searcher,
                () -> 0L,
                null,
                Collections.emptyMap()
            );
            toClose = null;
        } finally {
            IOUtils.closeWhileHandlingException(toClose);
        }
    }

    public DownsampleIndexerAction.ShardDownsampleResponse execute(
        final DownsampleConfig config,
        final String[] metrics,
        final String[] labels,
        final String[] dimensions
    ) throws IOException {
        final Query initialStateQuery = createQuery();
        if (initialStateQuery instanceof MatchNoDocsQuery) {
            return new DownsampleIndexerAction.ShardDownsampleResponse(indexShard.shardId(), task.getNumIndexed());
        }
        long startTime = client.threadPool().relativeTimeInMillis();
        task.setTotalShardDocCount(searcher.getDirectoryReader().numDocs());
        task.setDownsampleShardIndexerStatus(DownsampleShardIndexerStatus.STARTED);
        task.updatePersistentTaskState(
            new DownsampleShardPersistentTaskState(DownsampleShardIndexerStatus.STARTED, null),
            ActionListener.noop()
        );
        logger.info("Downsampling task [" + task.getPersistentTaskId() + " on shard " + indexShard.shardId() + " started");
        BulkProcessor2 bulkProcessor = createBulkProcessor();
        try (searcher; bulkProcessor) {
            final TimeSeriesIndexSearcher timeSeriesSearcher = new TimeSeriesIndexSearcher(searcher, List.of(this::checkCancelled));
            TimeSeriesBucketCollector bucketCollector = new TimeSeriesBucketCollector(
                config,
                downsampleIndex,
                indexShard,
                bulkProcessor,
                dimensions,
                metrics,
                labels,
                this::checkCancelled,
                abort,
                new TimeSeriesBucketCollector.TrackingProgress() {
                    @Override
                    public void setLastIndexingTimestamp(long timestamp) {
                        task.setLastIndexingTimestamp(timestamp);
                    }

                    @Override
                    public void setDocsProcessed(long docsProcessed) {
                        task.setDocsProcessed(docsProcessed);
                    }

                    @Override
                    public void setLastTargetTimestamp(long timestamp) {
                        task.setLastTargetTimestamp(timestamp);
                    }

                    @Override
                    public void setLastSourceTimestamp(long timestamp) {
                        task.setLastSourceTimestamp(timestamp);
                    }

                    @Override
                    public void addNumReceived(long count) {
                        task.addNumReceived(count);
                    }
                },
                searchExecutionContext,
                client
            );
            bucketCollector.preCollection();
            timeSeriesSearcher.search(initialStateQuery, bucketCollector);
        }

        TimeValue duration = TimeValue.timeValueMillis(client.threadPool().relativeTimeInMillis() - startTime);
        logger.info(
            "Shard [{}] successfully sent [{}], received source doc [{}], indexed downsampled doc [{}], failed [{}], took [{}]",
            indexShard.shardId(),
            task.getNumReceived(),
            task.getNumSent(),
            task.getNumIndexed(),
            task.getNumFailed(),
            duration
        );

        if (task.getNumIndexed() != task.getNumSent()) {
            task.setDownsampleShardIndexerStatus(DownsampleShardIndexerStatus.FAILED);
            final String error = "Downsampling task ["
                + task.getPersistentTaskId()
                + "] on shard "
                + indexShard.shardId()
                + " failed indexing, "
                + " indexed ["
                + task.getNumIndexed()
                + "] sent ["
                + task.getNumSent()
                + "]";
            logger.info(error);
            downsampleMetrics.recordShardOperation(duration.millis(), DownsampleMetrics.ActionStatus.MISSING_DOCS);
            throw new DownsampleShardIndexerException(error, false);
        }

        if (task.getNumFailed() > 0) {
            final String error = "Downsampling task ["
                + task.getPersistentTaskId()
                + "] on shard "
                + indexShard.shardId()
                + " failed indexing ["
                + task.getNumFailed()
                + "]";
            logger.info(error);
            downsampleMetrics.recordShardOperation(duration.millis(), DownsampleMetrics.ActionStatus.FAILED);
            throw new DownsampleShardIndexerException(error, false);
        }

        task.setDownsampleShardIndexerStatus(DownsampleShardIndexerStatus.COMPLETED);
        task.updatePersistentTaskState(
            new DownsampleShardPersistentTaskState(DownsampleShardIndexerStatus.COMPLETED, null),
            ActionListener.noop()
        );
        logger.info("Downsampling task [" + task.getPersistentTaskId() + " on shard " + indexShard.shardId() + " completed");
        downsampleMetrics.recordShardOperation(duration.millis(), DownsampleMetrics.ActionStatus.SUCCESS);
        return new DownsampleIndexerAction.ShardDownsampleResponse(indexShard.shardId(), task.getNumIndexed());
    }

    private Query createQuery() {
        if (this.state.started() && this.state.tsid() != null) {
            return SortedSetDocValuesField.newSlowRangeQuery(TimeSeriesIdFieldMapper.NAME, this.state.tsid(), null, true, false);
        }
        return new MatchAllDocsQuery();
    }

    private void checkCancelled() {
        if (task.isCancelled()) {
            logger.warn(
                "Shard [{}] downsampled abort, sent [{}], indexed [{}], failed[{}]",
                indexShard.shardId(),
                task.getNumSent(),
                task.getNumIndexed(),
                task.getNumFailed()
            );
            task.setDownsampleShardIndexerStatus(DownsampleShardIndexerStatus.CANCELLED);
            task.updatePersistentTaskState(
                new DownsampleShardPersistentTaskState(DownsampleShardIndexerStatus.CANCELLED, null),
                ActionListener.noop()
            );
            logger.info("Downsampling task [" + task.getPersistentTaskId() + "] on shard " + indexShard.shardId() + " cancelled");
            throw new DownsampleShardIndexerException(
                new TaskCancelledException(format("Shard %s downsample cancelled", indexShard.shardId())),
                format("Shard %s downsample cancelled", indexShard.shardId()),
                false
            );

        }
        if (abort.get()) {
            logger.warn(
                "Shard [{}] downsample abort, sent [{}], indexed [{}], failed[{}]",
                indexShard.shardId(),
                task.getNumSent(),
                task.getNumIndexed(),
                task.getNumFailed()
            );
            task.setDownsampleShardIndexerStatus(DownsampleShardIndexerStatus.FAILED);
            task.updatePersistentTaskState(
                new DownsampleShardPersistentTaskState(DownsampleShardIndexerStatus.FAILED, null),
                ActionListener.noop()
            );
            throw new DownsampleShardIndexerException("Bulk indexing failure", true);
        }
    }

    private BulkProcessor2 createBulkProcessor() {
        final BulkProcessor2.Listener listener = new BulkProcessor2.Listener() {

            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                task.addNumSent(request.numberOfActions());
                task.setBeforeBulkInfo(
                    new DownsampleBeforeBulkInfo(
                        client.threadPool().absoluteTimeInMillis(),
                        executionId,
                        request.estimatedSizeInBytes(),
                        request.numberOfActions()
                    )
                );
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                long bulkIngestTookMillis = response.getIngestTookInMillis() >= 0 ? response.getIngestTookInMillis() : 0;
                long bulkTookMillis = response.getTook().getMillis();
                task.addNumIndexed(request.numberOfActions());
                task.setAfterBulkInfo(
                    new DownsampleAfterBulkInfo(
                        client.threadPool().absoluteTimeInMillis(),
                        executionId,
                        bulkIngestTookMillis,
                        bulkTookMillis,
                        response.hasFailures(),
                        RestStatus.OK.getStatus()
                    )
                );
                task.updateBulkInfo(bulkIngestTookMillis, bulkTookMillis);

                if (response.hasFailures()) {
                    List<BulkItemResponse> failedItems = Arrays.stream(response.getItems()).filter(BulkItemResponse::isFailed).toList();
                    task.addNumFailed(failedItems.size());

                    Map<String, String> failures = failedItems.stream()
                        .collect(
                            Collectors.toMap(
                                BulkItemResponse::getId,
                                BulkItemResponse::getFailureMessage,
                                (msg1, msg2) -> Objects.equals(msg1, msg2) ? msg1 : msg1 + "," + msg2
                            )
                        );
                    logger.error("Shard [{}] failed to populate downsample index. Failures: [{}]", indexShard.shardId(), failures);

                    abort.set(true);
                }
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Exception failure) {
                if (failure != null) {
                    long items = request.numberOfActions();
                    task.addNumFailed(items);
                    logger.error(() -> format("Shard [%s] failed to populate downsample index.", indexShard.shardId()), failure);

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
