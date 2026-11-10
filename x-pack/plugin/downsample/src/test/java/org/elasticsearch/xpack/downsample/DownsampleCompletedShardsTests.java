/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.test.ESTestCase;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

public class DownsampleCompletedShardsTests extends ESTestCase {

    private static IndexMetadata.Builder targetIndex() {
        return IndexMetadata.builder("target")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current()))
            .numberOfShards(5)
            .numberOfReplicas(0);
    }

    public void testNothingRecordedByDefault() {
        IndexMetadata index = targetIndex().build();
        assertThat(DownsampleCompletedShards.completedShards(index), equalTo(Set.of()));
    }

    public void testRecordedShards() {
        Set<Integer> expectedShards = Set.of(0, 3, 12);
        IndexMetadata indexMetadata = targetIndex().build();
        for (int shardId : expectedShards) {
            indexMetadata = DownsampleCompletedShards.withCompletedShard(indexMetadata, shardId);
        }
        assertThat(DownsampleCompletedShards.completedShards(indexMetadata), equalTo(Set.of(0, 3, 12)));
        assertThat(
            indexMetadata.getCustomData(DownsampleCompletedShards.CUSTOM_METADATA_KEY),
            hasEntry(DownsampleCompletedShards.COMPLETED_SHARDS_KEY, "0,3,12")
        );
    }

    public void testRecordingAnAlreadyCompletedShardIsANoOp() {
        IndexMetadata index = DownsampleCompletedShards.withCompletedShard(targetIndex().build(), 2);
        assertThat(DownsampleCompletedShards.withCompletedShard(index, 2), sameInstance(index));
    }

    public void testOtherCustomMetadataIsPreserved() {
        IndexMetadata index = targetIndex().putCustom(DownsampleCompletedShards.CUSTOM_METADATA_KEY, Map.of("other", "value")).build();
        index = DownsampleCompletedShards.withCompletedShard(index, 1);

        assertThat(index.getCustomData(DownsampleCompletedShards.CUSTOM_METADATA_KEY), hasEntry("other", "value"));
        assertThat(DownsampleCompletedShards.completedShards(index), equalTo(Set.of(1)));
    }
}
