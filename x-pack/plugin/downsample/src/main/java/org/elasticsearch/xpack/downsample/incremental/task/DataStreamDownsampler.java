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
import org.elasticsearch.action.datastreams.downsampling.DataStreamDownsamplerParams;
import org.elasticsearch.action.support.RefCountingListener;
import org.elasticsearch.action.support.SubscribableListener;
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
import org.elasticsearch.xpack.downsample.incremental.PocHelper;

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
 * - Using the previous layer
 * - Stopping gracefully
 * - Tracking progress
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
        PocHelper pocHelper
    ) {
        super(id, type, action, description, parentTask, headers);
        this.projectId = projectId;
        this.dataStreamName = dataStreamName;
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.pollIntervalSupplier = pollIntervalSupplier;
        this.pocHelper = pocHelper;
        this.nowSupplier = System::currentTimeMillis;
    }

    void runDataStreamDownsampler() {

        if (isCancelled() || isCompleted()) {
            return;
        }
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
                if (sourceDataStream == null) {
                    logger.error(
                        "Source data stream [{}] does not exist",
                        DataStream.getDefaultDownsampleLayerIndexName(dataStreamName, previousLayer.interval())
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
                subscribableListener.addListener(ActionListener.runAfter(new ActionListener<>() {
                    @Override
                    public void onResponse(Void unused) {
                        logger.info("Data stream [{}] is downsampled", dataStreamName);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        logger.info("Downsampling data stream [{}] failed, {}", dataStreamName, e);
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
        logger.info("Going to downsample the data stream [{}] for the interval [{}]", dataStreamName, layer.interval());
        SubscribableListener.<Void>newForked(
            l -> pocHelper.maybeCreateDownsampleLayer(projectMetadata, dataStreamName, layer.interval(), l)
        )
            .<Void>andThen(l -> downsampleLayer(clusterState, projectMetadata, sourceLayer, previousLayer, now, layer, l))
            .addListener(listener);
    }

    private void downsampleLayer(
        ClusterState clusterState,
        ProjectMetadata projectMetadata,
        DataStream sourceDataStream,
        DataStreamDownsampling.DownsampledLayer previousLayer,
        Long now,
        DataStreamDownsampling.DownsampledLayer layer,
        ActionListener<Void> listener
    ) {
        if (previousLayer != null && lastDownsampledTime.containsKey(previousLayer.interval().toString()) == false) {
            logger.info("Previous downsampling layer [{}] hasn't started yet for {}", previousLayer.interval(), dataStreamName);
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
        try (RefCountingListener refCountingListener = new RefCountingListener(listener.safeMap(ignored -> {
            lastDownsampledTime.put(layer.interval().toString(), endTime);
            logger.info("Layer [{}-{}] is updated to {}", dataStreamName, layer.interval(), Instant.ofEpochMilli(endTime));
            return null;
        }))) {
            for (int i = sourceDataStream.getIndices().size() - 1; i >= 0; i--) {
                Index sourceIndex = sourceDataStream.getIndices().get(i);
                IndexMetadata indexMetadata = projectMetadata.index(sourceIndex);
                long indexStartTime = indexMetadata.getTimeSeriesStart().toEpochMilli();
                long indexEndTime = indexMetadata.getTimeSeriesEnd().toEpochMilli();
                if (timeOverlap(indexStartTime, indexEndTime, startTime, endTime) == false) {
                    logger.info("Skipping index {} no overlap", sourceIndex);
                    continue;
                }
                // We need to ensure the downsampling range is within the index bounds
                var downsampleStartTime = startTime == null ? indexStartTime : Math.max(indexStartTime, startTime);
                var downsampleEndTime = Math.min(endTime, indexEndTime);
                downsampleIndexForLayer(
                    clusterState,
                    layer,
                    sourceIndex,
                    downsampleStartTime,
                    downsampleEndTime,
                    refCountingListener.acquire()
                );
            }
        }
    }

    private void downsampleIndexForLayer(
        ClusterState clusterState,
        DataStreamDownsampling.DownsampledLayer layer,
        Index sourceIndex,
        Long startTime,
        Long endTime,
        ActionListener<Void> listener
    ) {
        try {
            clusterState.routingTable(projectId).index(sourceIndex).allShards().forEach(shard -> {
                if (shard.hasSearchShards() == false) {
                    throw new RuntimeException("shard [" + shard.shardId() + "] has no search shards");
                }
            });
        } catch (RuntimeException e) {
            listener.onFailure(e);
            logger.error("downsample failed for {}: {}", sourceIndex.getName(), e);
            return;
        }
        logger.info(
            "{} downsampled index {} for timeframe {} - {}",
            layer.interval(),
            sourceIndex.getName(),
            startTime == null ? "start of time" : Instant.ofEpochMilli(startTime),
            Instant.ofEpochMilli(endTime)
        );
        listener.onResponse(null);
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
