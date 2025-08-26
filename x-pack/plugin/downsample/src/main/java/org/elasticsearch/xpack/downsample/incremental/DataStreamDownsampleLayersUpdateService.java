/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.AckedBatchedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateAckListener;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.SimpleBatchedAckListenerTaskExecutor;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;

import java.util.Map;

/**
 * Handles data stream modification requests.
 */
public class DataStreamDownsampleLayersUpdateService {
    private final MasterServiceTaskQueue<UpdateDownsamplingLayersTask> updateDownsamplingLayersTaskQueue;

    public DataStreamDownsampleLayersUpdateService(ClusterService clusterService) {
        ClusterStateTaskExecutor<UpdateDownsamplingLayersTask> updateDownsamplingLayersExecutor =
            new SimpleBatchedAckListenerTaskExecutor<>() {

                @Override
                public Tuple<ClusterState, ClusterStateAckListener> executeTask(
                    UpdateDownsamplingLayersTask updateDownsamplingLayersTask,
                    ClusterState clusterState
                ) {
                    return new Tuple<>(
                        ClusterState.builder(clusterState)
                            .putProjectMetadata(
                                updateDownsampleLayers(
                                    clusterState.metadata().getProject(updateDownsamplingLayersTask.projectId),
                                    updateDownsamplingLayersTask.getDataStreamName(),
                                    updateDownsamplingLayersTask.getLatestDownsampledTimestamp()
                                )
                            )
                            .build(),
                        updateDownsamplingLayersTask
                    );
                }
            };
        this.updateDownsamplingLayersTaskQueue = clusterService.createTaskQueue(
            "modify-data-stream-downsamplingLayers",
            Priority.NORMAL,
            updateDownsamplingLayersExecutor
        );

    }

    /**
     * Submits the task to set the provided data stream options to the requested data streams.
     */
    public void setDownsamplingLayersLastTimestamp(
        final ProjectId projectId,
        final String dataStreamName,
        Map<String, Long> lastDownsampledTimestamps,
        TimeValue ackTimeout,
        TimeValue masterTimeout,
        final ActionListener<AcknowledgedResponse> listener
    ) {
        updateDownsamplingLayersTaskQueue.submitTask(
            "set-last-downsampling-timestamps",
            new UpdateDownsamplingLayersTask(projectId, dataStreamName, lastDownsampledTimestamps, ackTimeout, listener),
            masterTimeout
        );
    }

    /**
     * Creates an updated cluster state in which the requested data stream has the latest downsampled timestamps updated.
     * Visible for testing.
     */
    ProjectMetadata updateDownsampleLayers(ProjectMetadata project, String dataStreamName, Map<String, Long> latestDownsampledTimestamp) {
        ProjectMetadata.Builder builder = ProjectMetadata.builder(project);
        DataStream updatedDataStream = project.dataStreams()
            .get(dataStreamName)
            .copy()
            .setLastDownsampledTimestamp(latestDownsampledTimestamp)
            .build();
        builder.put(updatedDataStream);
        return builder.build();
    }

    /**
     * A cluster state update task that consists of the cluster state request and the listeners that need to be notified upon completion.
     */
    static class UpdateDownsamplingLayersTask extends AckedBatchedClusterStateUpdateTask {
        ProjectId projectId;
        private final String dataStreamName;
        private final Map<String, Long> latestDownsampledTimestamp;

        UpdateDownsamplingLayersTask(
            ProjectId projectId,
            String dataStreamName,
            Map<String, Long> latestDownsampledTimestamp,
            TimeValue ackTimeout,
            ActionListener<AcknowledgedResponse> listener
        ) {
            super(ackTimeout, listener);
            this.projectId = projectId;
            this.dataStreamName = dataStreamName;
            this.latestDownsampledTimestamp = latestDownsampledTimestamp;
        }

        public ProjectId getProjectId() {
            return projectId;
        }

        public String getDataStreamName() {
            return dataStreamName;
        }

        public Map<String, Long> getLatestDownsampledTimestamp() {
            return latestDownsampledTimestamp;
        }
    }
}
