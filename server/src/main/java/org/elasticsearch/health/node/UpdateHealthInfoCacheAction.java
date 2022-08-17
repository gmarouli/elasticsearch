/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.health.node;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.health.node.action.TransportHealthNodeAction;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

public class UpdateHealthInfoCacheAction extends ActionType<AcknowledgedResponse> {

    public static class Request extends ActionRequest {
        private final String nodeId;
        private final List<HealthNodeInfo> healthNodeInfoList;

        public Request(String nodeId, HealthNodeInfo... healthNodeInfo) {
            this.nodeId = nodeId;
            this.healthNodeInfoList = List.of(healthNodeInfo);
        }

        public Request(StreamInput in) throws IOException {
            super(in);
            this.nodeId = in.readString();
            this.healthNodeInfoList = in.readList(input -> {
                String infoClass = input.readString();
                try {
                    return (HealthNodeInfo) Class.forName(infoClass).getDeclaredConstructor(StreamInput.class).newInstance(in);
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException
                    | ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        public String getNodeId() {
            return nodeId;
        }

        public DiskHealthInfo getDiskHealthInfo() {
            return (DiskHealthInfo) healthNodeInfoList.stream()
                .filter(x -> DiskHealthInfo.class.equals(x.getClass()))
                .findAny()
                .orElse(null);
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(nodeId);
            out.writeCollection(healthNodeInfoList, (output, healthNodeInfo) -> {
                output.writeString(healthNodeInfo.getClass().getName());
                healthNodeInfo.writeTo(output);
            });
        }
    }

    public static final UpdateHealthInfoCacheAction INSTANCE = new UpdateHealthInfoCacheAction();
    public static final String NAME = "cluster:monitor/update/health/info";

    private UpdateHealthInfoCacheAction() {
        super(NAME, AcknowledgedResponse::readFrom);
    }

    public static class TransportAction extends TransportHealthNodeAction<Request, AcknowledgedResponse> {
        private final HealthInfoCache nodeHealthOverview;

        @Inject
        public TransportAction(
            TransportService transportService,
            ClusterService clusterService,
            ThreadPool threadPool,
            ActionFilters actionFilters,
            HealthInfoCache nodeHealthOverview
        ) {
            super(
                UpdateHealthInfoCacheAction.NAME,
                transportService,
                clusterService,
                threadPool,
                actionFilters,
                UpdateHealthInfoCacheAction.Request::new,
                AcknowledgedResponse::readFrom,
                ThreadPool.Names.MANAGEMENT
            );
            this.nodeHealthOverview = nodeHealthOverview;
        }

        @Override
        protected void healthOperation(
            Task task,
            Request request,
            ClusterState clusterState,
            ActionListener<AcknowledgedResponse> listener
        ) {
            nodeHealthOverview.updateNodeHealth(request.getNodeId(), request.getDiskHealthInfo());
            listener.onResponse(AcknowledgedResponse.of(true));
        }
    }
}
