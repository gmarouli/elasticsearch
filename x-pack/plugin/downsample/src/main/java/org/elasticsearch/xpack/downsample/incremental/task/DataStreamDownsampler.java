/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental.task;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.datastreams.downsampling.DataStreamDownsamplerParams;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamDownsampling;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Map;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.downsample.Downsample.DOWNSAMPLE_TASK_THREAD_POOL_NAME;

public class DataStreamDownsampler extends AllocatedPersistentTask {
    private static final Logger logger = LogManager.getLogger(DataStreamDownsampler.class);

    public static final String TASK_NAME = DataStreamDownsamplerParams.NAME;
    private final ProjectId projectId;
    private final String dataStreamName;
    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final Supplier<TimeValue> pollIntervalSupplier;
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
        Map<String, String> headers
    ) {
        super(id, type, action, description, parentTask, headers);
        this.projectId = projectId;
        this.dataStreamName = dataStreamName;
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.pollIntervalSupplier = pollIntervalSupplier;
    }

    void runDataStreamDownsampler() {

        if (isCancelled() || isCompleted()) {
            return;
        }
        try {
            DataStream dataStream = clusterService.state().projectState(projectId).metadata().dataStreams().get(dataStreamName);
            if (dataStream == null) {
                logger.error("Data stream [{}] does not exist", dataStreamName);
                // Probably should add some kind of state about why it's cancelled
                markAsCancelled();
                return;
            }
            if (dataStream.getDataStreamOptions() == null || dataStream.getDataStreamOptions().dataStreamDownsampling() == null) {
                logger.info("Data stream [{}] does not have downsampling configuration anymore", dataStreamName);
                // Probably should add some kind of state about why it's cancelled
                markAsCancelled();
                return;
            }
            for (DataStreamDownsampling.DownsampledLayer layer : dataStream.getDataStreamOptions()
                .dataStreamDownsampling()
                .downsampledLayers()) {
                logger.info("Going to downsample the data stream [{}] for the interval [{}]", dataStreamName, layer.interval());
            }
        } catch (Exception e) {
            logger.error("exception during running downsampling task for {}: {}", dataStreamName, e);
        }
        scheduleNextRun(pollIntervalSupplier.get());
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
