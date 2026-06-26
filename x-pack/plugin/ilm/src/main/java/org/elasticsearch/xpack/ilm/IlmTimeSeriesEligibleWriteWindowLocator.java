/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ilm;

import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.MetadataIndexTemplateService;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.dlm.TimeSeriesEligibleWriteWindowLocator;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.xpack.core.ilm.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicyMetadata;
import org.elasticsearch.xpack.core.ilm.LifecycleSettings;
import org.elasticsearch.xpack.core.ilm.Phase;
import org.elasticsearch.xpack.core.ilm.TimeseriesLifecycleType;

public class IlmTimeSeriesEligibleWriteWindowLocator implements TimeSeriesEligibleWriteWindowLocator {

    @Override
    public String getEffectiveIlmPolicy(DataStream dataStream, ProjectMetadata projectMetadata) {
        String indexTemplateName = MetadataIndexTemplateService.findV2Template(projectMetadata, dataStream.getName(), false);
        if (indexTemplateName == null) {
            return null;
        }
        ComposableIndexTemplate indexTemplate = projectMetadata.templatesV2().get(indexTemplateName);
        Settings settings = MetadataIndexTemplateService.resolveSettings(indexTemplate, projectMetadata.componentTemplates());
        final var policyName = LifecycleSettings.LIFECYCLE_NAME_SETTING.get(settings);
        if (Strings.hasText(policyName) == false) {
            return null;
        }
        // If there is only one of the lifecycle features configured, we return the policy name if available otherwise null.
        if (dataStream.getDataLifecycle() == null) {
            return policyName;
        }
        // If both are configured, ILM is in effect only if prefer_ilm is true.
        return IndexSettings.PREFER_ILM_SETTING.get(settings) ? policyName : null;
    }

    @Override
    public long eligibleWriteWindowStartFromPolicy(String policy, ProjectMetadata projectMetadata) {
        IndexLifecycleMetadata metadata = projectMetadata.custom(IndexLifecycleMetadata.TYPE);
        if (metadata == null) {
            return -1;
        }
        LifecyclePolicyMetadata lifecyclePolicyMetadata = metadata.getPolicyMetadatas().get(policy);
        if (lifecyclePolicyMetadata == null) {
            return -1;
        }
        for (String phaseName : TimeseriesLifecycleType.ORDERED_VALID_PHASES) {
            Phase phase = lifecyclePolicyMetadata.getPolicy().getPhases().get(phaseName);
            if (phase == null) {
                continue;
            }
            for (String readOnlyAction : TimeseriesLifecycleType.READ_ONLY_ACTIONS) {
                if (phase.getActions().containsKey(readOnlyAction)) {
                    return phase.getMinimumAge().millis();
                }
            }
        }
        return -1;
    }
}
