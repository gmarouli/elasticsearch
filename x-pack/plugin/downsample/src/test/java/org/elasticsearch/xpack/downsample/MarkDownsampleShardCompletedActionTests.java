/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xpack.core.LocalStateCompositeXPackPlugin;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

public class MarkDownsampleShardCompletedActionTests extends ESSingleNodeTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return List.of(LocalStateCompositeXPackPlugin.class, Downsample.class);
    }

    public void testRecordsCompletedShards() {
        final String index = randomIdentifier();
        createIndex(index, Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 3).build());

        markCompleted(index, 2);
        assertThat(completedShards(index), equalTo(Set.of(2)));

        markCompleted(index, 0);
        // Recording the same shard again is a no-op
        markCompleted(index, 2);
        assertThat(completedShards(index), equalTo(Set.of(0, 2)));
    }

    public void testMissingIndexFails() {
        expectThrows(IndexNotFoundException.class, () -> markCompleted(randomIdentifier(), 0));
    }

    private void markCompleted(String index, int shardId) {
        assertAcked(
            client().execute(
                MarkDownsampleShardCompletedAction.INSTANCE,
                new MarkDownsampleShardCompletedAction.Request(TEST_REQUEST_TIMEOUT, index, shardId)
            )
        );
    }

    private Set<Integer> completedShards(String index) {
        return DownsampleCompletedShards.completedShards(
            getInstanceFromNode(ClusterService.class).state().metadata().getProject().index(index)
        );
    }
}
