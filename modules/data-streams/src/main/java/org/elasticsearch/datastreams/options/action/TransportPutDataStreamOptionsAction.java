/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.datastreams.options.action;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.datastreams.DataStreamsActionUtil;
import org.elasticsearch.action.datastreams.PutDataStreamOptionsAction;
import org.elasticsearch.action.datastreams.downsampling.DataStreamDownsamplerParams;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.RefCountingListener;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeProjectAction;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetadataDataStreamsService;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.List;

/**
 * Transport action that resolves the data stream names from the request and sets the data stream lifecycle provided in the request.
 */
public class TransportPutDataStreamOptionsAction extends AcknowledgedTransportMasterNodeProjectAction<PutDataStreamOptionsAction.Request> {

    private final Logger logger = LogManager.getLogger(TransportPutDataStreamOptionsAction.class);

    private final IndexNameExpressionResolver indexNameExpressionResolver;
    private final MetadataDataStreamsService metadataDataStreamsService;
    private final SystemIndices systemIndices;
    private final PersistentTasksService persistentTasksService;

    @Inject
    public TransportPutDataStreamOptionsAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        ProjectResolver projectResolver,
        IndexNameExpressionResolver indexNameExpressionResolver,
        MetadataDataStreamsService metadataDataStreamsService,
        SystemIndices systemIndices,
        PersistentTasksService persistentTasksService
    ) {
        super(
            PutDataStreamOptionsAction.INSTANCE.name(),
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            PutDataStreamOptionsAction.Request::new,
            projectResolver,
            EsExecutors.DIRECT_EXECUTOR_SERVICE
        );
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        this.metadataDataStreamsService = metadataDataStreamsService;
        this.systemIndices = systemIndices;
        this.persistentTasksService = persistentTasksService;
    }

    @Override
    protected void masterOperation(
        Task task,
        PutDataStreamOptionsAction.Request request,
        ProjectState state,
        ActionListener<AcknowledgedResponse> listener
    ) {
        List<String> dataStreamNames = DataStreamsActionUtil.getDataStreamNames(
            indexNameExpressionResolver,
            state.metadata(),
            request.getNames(),
            request.indicesOptions()
        );
        for (String name : dataStreamNames) {
            systemIndices.validateDataStreamAccess(name, threadPool.getThreadContext());
        }
        SubscribableListener.<AcknowledgedResponse>newForked(
            l -> metadataDataStreamsService.setDataStreamOptions(
                state.projectId(),
                dataStreamNames,
                request.getOptions(),
                request.ackTimeout(),
                request.masterNodeTimeout(),
                l
            )
        ).<AcknowledgedResponse>andThen((l, response) -> {
            if (response.isAcknowledged() && request.getOptions().dataStreamDownsampling() != null) {
                long now = System.currentTimeMillis();
                try (RefCountingListener refCountingListener = new RefCountingListener(l.map(ignored -> response))) {
                    for (String dataStreamName : dataStreamNames) {
                        final var persistentTaskId = getPersistentTaskId(dataStreamName);
                        final var persistentTask = PersistentTasksCustomMetadata.getTaskWithId(state.metadata(), persistentTaskId);
                        if (persistentTask == null) {
                            startTask(
                                state.projectId(),
                                persistentTaskId,
                                new DataStreamDownsamplerParams(dataStreamName, now),
                                refCountingListener.acquire(startedTask -> {
                                    logger.info(
                                        "Starting data stream downsampler task for [{}] with id [{}]",
                                        dataStreamName,
                                        startedTask.getId()
                                    );
                                })
                            );
                        } else {
                            logger.debug("Downsampler has already been started for [{}] with id [{}]", dataStreamName, persistentTaskId);
                        }
                    }
                }
            } else {
                l.onResponse(response);
            }
        }).addListener(listener);

    }

    @Override
    protected ClusterBlockException checkBlock(PutDataStreamOptionsAction.Request request, ProjectState state) {
        return state.blocks().globalBlockedException(state.projectId(), ClusterBlockLevel.METADATA_WRITE);
    }

    private void startTask(
        ProjectId projectId,
        String persistentTaskId,
        DataStreamDownsamplerParams params,
        ActionListener<PersistentTasksCustomMetadata.PersistentTask<DataStreamDownsamplerParams>> listener
    ) {
        persistentTasksService.sendProjectStartRequest(
            projectId,
            persistentTaskId,
            DataStreamDownsamplerParams.NAME,
            params,
            TimeValue.THIRTY_SECONDS /* TODO should this be configurable? longer by default? infinite? */,
            listener
        );
    }

    private String getPersistentTaskId(String dataStreamName) throws ResourceAlreadyExistsException {
        return DataStreamDownsamplerParams.NAME + ":" + dataStreamName;
    }
}
