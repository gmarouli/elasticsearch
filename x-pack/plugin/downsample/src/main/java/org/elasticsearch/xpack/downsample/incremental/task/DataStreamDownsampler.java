/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.datastreams.downsampling.DataStreamDownsamplerParams;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.action.support.RefCountingListener;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamDownsampling;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Rounding;
import org.elasticsearch.common.util.concurrent.RunOnce;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.Index;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.downsample.incremental.DownsampleLayersUpdateStateService;
import org.elasticsearch.xpack.downsample.incremental.PocHelper;
import org.elasticsearch.xpack.downsample.incremental.ShardDownsampleRequest;
import org.elasticsearch.xpack.downsample.incremental.TransportShardDownsampleAction;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.downsample.Downsample.DOWNSAMPLE_TASK_THREAD_POOL_NAME;

/**
 * This persistent task runs periodically and triggers the downsampling operation for all the layers of a data stream
 * for the relevant time ranges.
 * TODO:
 * - Error handling
 * - Stopping gracefully
 * - Persist progress tracking
 */
public class DataStreamDownsampler extends AllocatedPersistentTask {
    private static final Logger logger = LogManager.getLogger(DataStreamDownsampler.class);
    private static final long GRACE_PERIOD = TimeValue.timeValueMinutes(30).millis();

    public static final String TASK_NAME = DataStreamDownsamplerParams.NAME;
    private final ProjectId projectId;
    private final String dataStreamName;
    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final Supplier<TimeValue> pollIntervalSupplier;
    private final ConcurrentMap<String, Rounding> roundings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastDownsampledTime = new ConcurrentHashMap<>();
    private final PocHelper pocHelper;
    private final Supplier<Long> nowSupplier;
    private final Client client;
    private final DownsampleLayersUpdateStateService downsampleLayersUpdateStateService;
    private volatile Scheduler.ScheduledCancellable scheduled;

    public DataStreamDownsampler(
        ProjectId projectId,
        String dataStreamName,
        ClusterService clusterService,
        ThreadPool threadPool,
        Supplier<TimeValue> pollIntervalSupplier,
        long id,
        String type,
        String action,
        String description,
        TaskId parentTask,
        Map<String, String> headers,
        PocHelper pocHelper,
        Client client,
        DownsampleLayersUpdateStateService downsampleLayersUpdateStateService
    ) {
        super(id, type, action, description, parentTask, headers);
        this.projectId = projectId;
        this.dataStreamName = dataStreamName;
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.pollIntervalSupplier = pollIntervalSupplier;
        this.pocHelper = pocHelper;
        this.nowSupplier = System::currentTimeMillis;
        this.client = client;
        this.downsampleLayersUpdateStateService = downsampleLayersUpdateStateService;
        lastDownsampledTime.putAll(
            clusterService.state().projectState(projectId).metadata().dataStreams().get(dataStreamName).getLastDownsampledTimestamp()
        );
    }

    void runDataStreamDownsampler() {

        if (isCancelled() || isCompleted()) {
            return;
        }
        // TODO: We should have a health indicator to ensure it runs promptly
        RunOnce scheduleOnce = new RunOnce(() -> scheduleNextRun(pollIntervalSupplier.get()));
        try {
            ClusterState clusterState = clusterService.state();
            ProjectMetadata projectMetadata = clusterState.projectState(projectId).metadata();
            DataStream dataStream = projectMetadata.dataStreams().get(dataStreamName);
            if (dataStream == null) {
                logger.error("Data stream [{}] does not exist", dataStreamName);
                // Probably should add some kind of state about why it's cancelled
                markAsCancelled();
                return;
            }
            if (dataStream.hasIncrementalDownsamplingEnabled() == false) {
                logger.info("Data stream [{}] does not have downsampling configuration anymore", dataStreamName);
                // Probably should add some info about why it's cancelled in a state
                markAsCancelled();
                return;
            }
            var now = nowSupplier.get();
            DataStreamDownsampling.DownsampledLayer previousLayer = null;
            SubscribableListener<Void> subscribableListener = null;
            for (DataStreamDownsampling.DownsampledLayer layer : dataStream.getDataStreamOptions()
                .dataStreamDownsampling()
                .downsampledLayers()) {
                DataStream sourceDataStream = previousLayer == null
                    ? dataStream
                    : projectMetadata.dataStreams()
                        .get(DataStream.getDefaultDownsampleLayerIndexName(dataStreamName, previousLayer.interval()));
                // TODO if the "previous" layer does not have the data we need, we should fallback to the layer before that.
                if (sourceDataStream == null) {
                    logger.debug(
                        "Source data stream [{}] does not exist, downsampling layer [{}] is skipped.",
                        DataStream.getDefaultDownsampleLayerIndexName(dataStreamName, previousLayer.interval()),
                        layer.interval()
                    );
                    continue;
                }
                final var localPreviousLayer = previousLayer;
                CheckedConsumer<ActionListener<Void>, Exception> downsampleLayer = l -> maybeCreateAndDownsampleLayer(
                    sourceDataStream,
                    localPreviousLayer,
                    clusterState,
                    projectMetadata,
                    layer,
                    now,
                    l
                );
                if (subscribableListener == null) {
                    subscribableListener = SubscribableListener.newForked(downsampleLayer);
                } else {
                    subscribableListener.andThen(downsampleLayer);
                }
                previousLayer = layer;
            }
            if (subscribableListener != null) {
                subscribableListener.<AcknowledgedResponse>andThen(
                    l -> downsampleLayersUpdateStateService.setDownsamplingLayersLastTimestamp(
                        projectId,
                        dataStreamName,
                        lastDownsampledTime,
                        TimeValue.THIRTY_SECONDS,
                        TimeValue.THIRTY_SECONDS,
                        l
                    )
                ).addListener(ActionListener.runAfter(new ActionListener<>() {
                    @Override
                    public void onResponse(AcknowledgedResponse unused) {
                        logger.info("Data stream [{}] is downsampled", dataStreamName);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        logger.error("Downsampling data stream [{}] failed, {}", dataStreamName, e);
                    }
                }, scheduleOnce));
            }
        } catch (Exception e) {
            logger.error("exception during running downsampling task for {}: {}", dataStreamName, e);
            scheduleOnce.run();
        }
    }

    private void maybeCreateAndDownsampleLayer(
        DataStream sourceLayer,
        DataStreamDownsampling.DownsampledLayer previousLayer,
        ClusterState clusterState,
        ProjectMetadata projectMetadata,
        DataStreamDownsampling.DownsampledLayer layer,
        long now,
        ActionListener<Void> listener
    ) {
        logger.info("Downsampling data stream [{}] for the interval [{}]", dataStreamName, layer.interval());
        SubscribableListener.<Void>newForked(
            l -> pocHelper.maybeCreateDownsampleLayer(projectMetadata, dataStreamName, layer.interval(), l)
        )
            .<Void>andThen(
                l -> downsampleLayer(
                    clusterState,
                    projectMetadata,
                    sourceLayer,
                    DataStream.getDefaultDownsampleLayerIndexName(dataStreamName, layer.interval()),
                    previousLayer,
                    now,
                    layer,
                    l
                )
            )
            .addListener(listener);
    }

    private void downsampleLayer(
        ClusterState clusterState,
        ProjectMetadata projectMetadata,
        DataStream sourceDataStream,
        String targetLayer,
        DataStreamDownsampling.DownsampledLayer previousLayer,
        Long now,
        DataStreamDownsampling.DownsampledLayer layer,
        ActionListener<Void> listener
    ) {
        if (previousLayer != null && lastDownsampledTime.containsKey(previousLayer.interval().toString()) == false) {
            logger.debug("Previous downsampling layer [{}] hasn't completed downsampling {}", previousLayer.interval(), dataStreamName);
            listener.onResponse(null);
            return;
        }
        // For now default to start of time if it's null, probably we want to default to a certain start time.
        var startTime = lastDownsampledTime.getOrDefault(layer.interval().toString(), layer.startTime());
        var endTime = getLastEndTime(
            layer.interval(),
            now,
            previousLayer == null ? null : lastDownsampledTime.get(previousLayer.interval().toString())
        );
        if (startTime != null && endTime - startTime <= 0) {
            logger.info(
                "Nothing to downsample for {} {}, {} - {}, see you later",
                dataStreamName,
                layer.interval(),
                Instant.ofEpochMilli(startTime),
                Instant.ofEpochMilli(endTime)
            );
            listener.onResponse(null);
            return;
        }
        DownsampleConfig downsampleConfig = new DownsampleConfig(layer.interval());
        try (RefCountingListener refCountingListener = new RefCountingListener(listener.safeMap(ignored -> {
            lastDownsampledTime.put(layer.interval().toString(), endTime);
            logger.info("Layer [{}-{}] completed downsampling up to {}", dataStreamName, layer.interval(), Instant.ofEpochMilli(endTime));
            return null;
        }))) {
            for (int i = sourceDataStream.getIndices().size() - 1; i >= 0; i--) {
                Index sourceIndex = sourceDataStream.getIndices().get(i);
                IndexMetadata indexMetadata = projectMetadata.index(sourceIndex);
                long indexStartTime = indexMetadata.getTimeSeriesStart().toEpochMilli();
                long indexEndTime = indexMetadata.getTimeSeriesEnd().toEpochMilli();
                if (timeOverlap(indexStartTime, indexEndTime, startTime, endTime) == false) {
                    logger.debug("Skipping index {} no overlap", sourceIndex);
                    continue;
                }
                // We need to ensure the downsampling range is within the index bounds
                var downsampleStartTime = startTime == null ? indexStartTime : Math.max(indexStartTime, startTime);
                var downsampleEndTime = Math.min(endTime, indexEndTime);
                SubscribableListener.<BroadcastResponse>newForked(
                    l -> client.admin().indices().refresh(new RefreshRequest(sourceIndex.getName()), l)
                )
                    .<GetMappingsResponse>andThen(
                        l -> client.admin()
                            .indices()
                            .getMappings(new GetMappingsRequest(TimeValue.THIRTY_SECONDS).indices(sourceIndex.getName()), l)
                    )
                    .andThenApply(r -> pocHelper.getFieldsPerType(downsampleConfig, indexMetadata, r))
                    .<Void>andThen(
                        (l, fields) -> downsampleIndexForLayer(
                            clusterState,
                            downsampleConfig,
                            sourceIndex,
                            downsampleStartTime,
                            downsampleEndTime,
                            targetLayer,
                            fields.dimensions().toArray(new String[0]),
                            fields.metrics().toArray(new String[0]),
                            fields.labels().toArray(new String[0]),
                            l
                        )
                    )
                    .addListener(refCountingListener.acquire());
            }
        }
    }

    private void downsampleIndexForLayer(
        ClusterState clusterState,
        DownsampleConfig downsampleConfig,
        Index sourceIndex,
        long startTime,
        long endTime,
        String targetLayer,
        String[] dimensions,
        String[] metrics,
        String[] labels,
        ActionListener<Void> listener
    ) {
        try (RefCountingListener refCountingListener = new RefCountingListener(listener)) {
            clusterState.routingTable(projectId).index(sourceIndex).allShards().forEach(shard -> {
                if (shard.hasSearchShards() == false) {
                    logger.error("shard [" + shard.shardId() + "] has no search shards");
                } else {
                    client.execute(
                        TransportShardDownsampleAction.TYPE,
                        new ShardDownsampleRequest(
                            shard.shardId(),
                            downsampleConfig,
                            startTime,
                            endTime,
                            targetLayer,
                            dimensions,
                            metrics,
                            labels
                        ),
                        refCountingListener.acquire(response -> {
                            if (response.isAcknowledged() == false) {
                                logger.error("[{}] failed to acknowledge downsampling shard", shard.shardId());
                            } else {
                                logger.info(
                                    "{} downsampled shard {} for timeframe {} - {}, ({})",
                                    downsampleConfig.getInterval(),
                                    shard.shardId(),
                                    Instant.ofEpochMilli(startTime),
                                    Instant.ofEpochMilli(endTime),
                                    response.getDownsampledDocs()
                                );
                            }
                        })
                    );
                }
            });
        }
    }

    private boolean timeOverlap(long indexStartTime, long indexEndTime, Long startTime, long endTime) {
        if (startTime == null) {
            return indexStartTime < endTime && endTime < indexEndTime;
        }
        return (indexStartTime < startTime && startTime < endTime) || (indexStartTime < endTime && endTime < indexEndTime);
    }

    private long getLastEndTime(DateHistogramInterval interval, long now, Long previousLayerEndTime) {
        var rounding = roundings.computeIfAbsent(
            interval.toString(),
            ignored -> Rounding.builder(TimeValue.parseTimeValue(interval.toString(), dataStreamName + ":incremental-downsampling"))
                .timeZone(ZoneId.of("UTC"))
                .build()
        );
        return rounding.prepareForUnknown().round(previousLayerEndTime != null ? previousLayerEndTime : now - GRACE_PERIOD);
    }

    private void scheduleNextRun(TimeValue time) {
        if (threadPool.scheduler().isShutdown() == false) {
            scheduled = threadPool.schedule(this::runDataStreamDownsampler, time, threadPool.executor(DOWNSAMPLE_TASK_THREAD_POOL_NAME));
        }
    }

    /**
     * This method requests that the downloader be rescheduled to run immediately (presumably because a dynamic property supplied by
     * pollIntervalSupplier or eagerDownloadSupplier has changed, or a pipeline with a geoip processor has been added). This method does
     * nothing if this task is cancelled, completed, or has not yet been scheduled to run for the first time. It cancels any existing
     * scheduled run.
     */
    public void requestReschedule() {
        if (isCancelled() || isCompleted()) {
            return;
        }
        if (scheduled != null && scheduled.cancel()) {
            scheduleNextRun(TimeValue.ZERO);
        }
    }
}
