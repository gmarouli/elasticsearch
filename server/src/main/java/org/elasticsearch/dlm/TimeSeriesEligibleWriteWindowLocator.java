/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.dlm;

import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamGlobalRetention;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.core.TimeValue;

public interface TimeSeriesEligibleWriteWindowLocator {

    String getEffectiveIlmPolicy(DataStream dataStream, ProjectMetadata projectMetadata);

    long eligibleWriteWindowStartFromPolicy(String policy, ProjectMetadata projectMetadata);

    default long getEligibleWriteWindowStart(
        DataStream dataStream,
        ProjectMetadata projectMetadata,
        DataStreamGlobalRetention globalRetention,
        long requestStartTimestamp
    ) {
        String ilmPolicy = getEffectiveIlmPolicy(dataStream, projectMetadata);

        if (ilmPolicy != null) {
            return eligibleWriteWindowStartFromPolicy(ilmPolicy, projectMetadata);
        }
        TimeValue writeWindow = null;
        if (dataStream.getDataLifecycle() != null && dataStream.getDataLifecycle().enabled()) {
            if (dataStream.getDataLifecycle().downsamplingRounds() != null) {
                writeWindow = dataStream.getDataLifecycle().downsamplingRounds().getFirst().after();
            } else if (dataStream.getDataLifecycle().frozenAfter() != null) {
                writeWindow = dataStream.getDataLifecycle().frozenAfter();
            } else {
                writeWindow = dataStream.getDataLifecycle().getEffectiveDataRetention(globalRetention, dataStream.isInternal());
            }
        }
        return writeWindow == null ? -1 : requestStartTimestamp - writeWindow.getMillis();
    }

    class DlmOnly implements TimeSeriesEligibleWriteWindowLocator {
        @Override
        public String getEffectiveIlmPolicy(DataStream dataStream, ProjectMetadata projectMetadata) {
            return null;
        }

        @Override
        public long eligibleWriteWindowStartFromPolicy(String policy, ProjectMetadata projectMetadata) {
            return -1;
        }
    }
}
