/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeAction;
import org.elasticsearch.action.support.master.MasterNodeRequest;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.downsample.TransportDownsampleAction.DownsampleClusterStateUpdateTask;

import java.io.IOException;
import java.util.Objects;

/**
 * Records, in the target index metadata, that the downsampling of a shard has completed (see {@link DownsampleCompletedShards}).
 * <p>
 * The shard-level persistent task sends it right before it completes, because completing removes the task from the cluster state and
 * with it the only other trace of the shard having been downsampled.
 */
public class MarkDownsampleShardCompletedAction extends ActionType<AcknowledgedResponse> {

    public static final MarkDownsampleShardCompletedAction INSTANCE = new MarkDownsampleShardCompletedAction();
    // Not an "internal:" action: the shard task runs it through an origin-setting client, which is only authorized for non-internal
    // actions.
    public static final String NAME = "cluster:admin/xpack/downsample/shard_completed";

    private MarkDownsampleShardCompletedAction() {
        super(NAME);
    }

    public static class Request extends MasterNodeRequest<Request> {
        private final String targetIndex;
        private final int shardId;

        public Request(TimeValue masterNodeTimeout, String targetIndex, int shardId) {
            super(masterNodeTimeout);
            this.targetIndex = Objects.requireNonNull(targetIndex);
            this.shardId = shardId;
        }

        public Request(StreamInput in) throws IOException {
            super(in);
            this.targetIndex = in.readString();
            this.shardId = in.readVInt();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(targetIndex);
            out.writeVInt(shardId);
        }

        @Override
        public ActionRequestValidationException validate() {
            if (shardId < 0) {
                final ActionRequestValidationException e = new ActionRequestValidationException();
                e.addValidationError("shard id must not be negative but was [" + shardId + "]");
                return e;
            }
            return null;
        }

        public String targetIndex() {
            return targetIndex;
        }

        public int shardId() {
            return shardId;
        }
    }

    public static class TA extends AcknowledgedTransportMasterNodeAction<Request> {
        private static final Logger logger = LogManager.getLogger(TA.class);

        private final ProjectResolver projectResolver;
        private final MasterServiceTaskQueue<DownsampleClusterStateUpdateTask> taskQueue;

        @Inject
        public TA(
            TransportService transportService,
            ClusterService clusterService,
            ThreadPool threadPool,
            ActionFilters actionFilters,
            ProjectResolver projectResolver
        ) {
            super(NAME, transportService, clusterService, threadPool, actionFilters, Request::new, EsExecutors.DIRECT_EXECUTOR_SERVICE);
            this.projectResolver = projectResolver;
            this.taskQueue = clusterService.createTaskQueue(
                "downsample-shard-completed",
                Priority.URGENT,
                TransportDownsampleAction.STATE_UPDATE_TASK_EXECUTOR
            );
        }

        @Override
        protected void masterOperation(Task task, Request request, ClusterState state, ActionListener<AcknowledgedResponse> listener) {
            final ProjectId projectId = projectResolver.getProjectId();
            taskQueue.submitTask(
                "mark-downsample-shard-completed [" + request.targetIndex() + "][" + request.shardId() + "]",
                new DownsampleClusterStateUpdateTask(listener) {
                    @Override
                    public ClusterState execute(ClusterState currentState) {
                        final ProjectMetadata project = currentState.metadata().getProject(projectId);
                        final IndexMetadata targetIndex = project.index(request.targetIndex());
                        if (targetIndex == null) {
                            throw new IndexNotFoundException(request.targetIndex());
                        }
                        final IndexMetadata updated = DownsampleCompletedShards.withCompletedShard(targetIndex, request.shardId());
                        if (updated == targetIndex) {
                            logger.debug("shard [{}] of [{}] was already recorded as completed", request.shardId(), request.targetIndex());
                            return currentState;
                        }
                        return ClusterState.builder(currentState)
                            .putProjectMetadata(ProjectMetadata.builder(project).put(updated, true))
                            .build();
                    }
                },
                request.masterNodeTimeout()
            );
        }

        @Override
        protected ClusterBlockException checkBlock(Request request, ClusterState state) {
            return state.blocks().globalBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.METADATA_WRITE);
        }
    }
}
