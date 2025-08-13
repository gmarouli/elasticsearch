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
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.elasticsearch.xpack.downsample.Downsample.DOWNSAMPLE_TASK_THREAD_POOL_NAME;

public class DataStreamDownsamplerTaskExecutor extends PersistentTasksExecutor<DataStreamDownsamplerParams> {

    private static final Logger logger = LogManager.getLogger(DataStreamDownsamplerTaskExecutor.class);

    public static final Setting<TimeValue> POLL_INTERVAL_SETTING = Setting.timeSetting(
        "data_streams.downsampling.poll.interval",
        TimeValue.timeValueMinutes(5),
        TimeValue.timeValueMinutes(5),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    private static final TimeValue TASK_KEEP_ALIVE_TIME = TimeValue.timeValueDays(1);
    private final Client client;
    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final ProjectResolver projectResolver;
    private final ConcurrentMap<String, DataStreamDownsampler> tasksInProgress = new ConcurrentHashMap<>();
    private volatile TimeValue pollInterval;

    public DataStreamDownsamplerTaskExecutor(Client client, ClusterService clusterService, String taskName, ThreadPool threadPool) {
        super(taskName, threadPool.executor(DOWNSAMPLE_TASK_THREAD_POOL_NAME));
        this.client = client;
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        this.projectResolver = client.projectResolver();
        this.pollInterval = POLL_INTERVAL_SETTING.get(clusterService.getSettings());
    }

    /**
     * This method completes the initialization of the GeoIpDownloaderTaskExecutor by registering several listeners.
     */
    public void init() {
        clusterService.getClusterSettings().addSettingsUpdateConsumer(POLL_INTERVAL_SETTING, this::setPollInterval);
    }

    private void setPollInterval(TimeValue pollInterval) {
        if (Objects.equals(this.pollInterval, pollInterval) == false) {
            this.pollInterval = pollInterval;
            for (DataStreamDownsampler task : tasksInProgress.values()) {
                task.requestReschedule();
            }
        }
    }

    @Override
    protected DataStreamDownsampler createTask(
        long id,
        String type,
        String action,
        TaskId parentTaskId,
        PersistentTasksCustomMetadata.PersistentTask<DataStreamDownsamplerParams> taskInProgress,
        Map<String, String> headers
    ) {
        ProjectId projectId = projectResolver.getProjectId();
        DataStreamDownsamplerParams params = taskInProgress.getParams();
        if (params == null) {
            return null;
        }
        return new DataStreamDownsampler(
            projectId,
            params.dataStream(),
            clusterService,
            threadPool,
            () -> pollInterval,
            id,
            type,
            action,
            "Creating data stream downsampling task for " + taskInProgress.getParams().dataStream(),
            parentTaskId,
            headers
        );
    }

    /**
     * Returns the node id from the eligible health nodes
     */
    @Override
    protected PersistentTasksCustomMetadata.Assignment doGetAssignment(
        DataStreamDownsamplerParams params,
        Collection<DiscoveryNode> candidateNodes,
        ClusterState clusterState,
        @Nullable ProjectId projectId
    ) {
        DiscoveryNode discoveryNode = selectLeastLoadedNode(clusterState, candidateNodes, DiscoveryNode::canContainData);
        if (discoveryNode == null) {
            return NO_NODE_FOUND;
        } else {
            return new PersistentTasksCustomMetadata.Assignment(discoveryNode.getId(), "");
        }
    }

    @Override
    protected void nodeOperation(
        AllocatedPersistentTask task,
        DataStreamDownsamplerParams params,
        PersistentTaskState persistentTaskState
    ) {
        DataStreamDownsampler dataStreamDownsampler = (DataStreamDownsampler) task;
        tasksInProgress.put(params.dataStream(), dataStreamDownsampler);
        dataStreamDownsampler.runDataStreamDownsampler();
    }

    private TimeValue getTimeToLive(long completionTimeInMillis) {
        return TimeValue.timeValueMillis(
            TASK_KEEP_ALIVE_TIME.millis() - Math.min(
                TASK_KEEP_ALIVE_TIME.millis(),
                threadPool.absoluteTimeInMillis() - completionTimeInMillis
            )
        );
    }
}
