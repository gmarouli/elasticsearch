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
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.single.shard.TransportSingleShardAction;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;

import static org.elasticsearch.xpack.downsample.Downsample.DOWNSAMPLE_TASK_THREAD_POOL_NAME;

public class TransportDownsampleShardAction extends TransportSingleShardAction<DownsampleShardRequest, DownsampleShardResponse> {

    private final Logger logger = LogManager.getLogger(TransportDownsampleShardAction.class);
    public static final ActionType<DownsampleShardResponse> TYPE = new ActionType<>("indices:data/downsample");
    private final IndicesService indicesService;
    private final Client client;

    @Inject
    public TransportDownsampleShardAction(
        ThreadPool threadPool,
        ClusterService clusterService,
        TransportService transportService,
        IndicesService indicesService,
        ActionFilters actionFilters,
        ProjectResolver projectResolver,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Client client
    ) {
        super(
            TYPE.name(),
            threadPool,
            clusterService,
            transportService,
            actionFilters,
            projectResolver,
            indexNameExpressionResolver,
            DownsampleShardRequest::new,
            threadPool.executor(DOWNSAMPLE_TASK_THREAD_POOL_NAME)
        );
        this.indicesService = indicesService;
        this.client = client;
    }

    @Override
    protected boolean resolveIndex(DownsampleShardRequest request) {
        return false;
    }

    @Override
    protected void asyncShardOperation(DownsampleShardRequest request, ShardId shardId, ActionListener<DownsampleShardResponse> listener) {
        final var downsampleShardIndexer = new DownsampleShardIndexer(
            request.getStartTime(),
            request.getEndTime(),
            client,
            indicesService.indexServiceSafe(shardId.getIndex()),
            shardId,
            request.getTargetLayer()
        );
        try {
            downsampleShardIndexer.execute(
                request.getDownsampleConfig(),
                request.getMetrics(),
                request.getLabels(),
                request.getDimensions(),
                listener.map(downsampledDocs -> new DownsampleShardResponse(true, shardId, downsampledDocs))
            );
        } catch (Exception exception) {
            logger.error(
                "failed to downsample shard {} for interval {}, {}",
                shardId,
                request.getDownsampleConfig().getFixedInterval(),
                exception
            );
            listener.onFailure(exception);
        }
    }

    @Override
    protected DownsampleShardResponse shardOperation(DownsampleShardRequest request, ShardId shardId) throws IOException {
        throw new UnsupportedOperationException("Downsampling is only async");
    }

    @Override
    protected Writeable.Reader<DownsampleShardResponse> getResponseReader() {
        return DownsampleShardResponse::new;
    }

    @Override
    protected ShardIterator shards(ProjectState state, InternalRequest request) {
        return clusterService.operationRouting().getShards(state, request.concreteIndex(), request.request().getShardId().id(), null);
    }
}
