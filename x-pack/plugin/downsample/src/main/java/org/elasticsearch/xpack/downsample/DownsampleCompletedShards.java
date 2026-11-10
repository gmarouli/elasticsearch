/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Persistent record of the shards whose downsampling has completed successfully.
 * <p>
 * The persistent task of a shard is removed from the cluster state when it completes, so afterwards nothing distinguishes a completed
 * shard from one that never ran. Without this record a later downsample request for the same target index would downsample the shard
 * again.
 */
final class DownsampleCompletedShards {

    /** The custom metadata type under which downsample keeps its state in the target index metadata. */
    static final String CUSTOM_METADATA_KEY = "downsample";
    static final String COMPLETED_SHARDS_KEY = "completed_shards";

    private DownsampleCompletedShards() {}

    /**
     * @return the ids of the shards of {@code targetIndexMetadata} that have completed downsampling, empty if none has been recorded
     */
    static Set<Integer> completedShards(IndexMetadata targetIndexMetadata) {
        final Map<String, String> custom = targetIndexMetadata.getCustomData(CUSTOM_METADATA_KEY);
        return custom == null ? Set.of() : parse(custom.get(COMPLETED_SHARDS_KEY));
    }

    /**
     * Records that {@code shardId} has completed. Any other custom metadata stored under {@link #CUSTOM_METADATA_KEY} is preserved.
     *
     * @return {@code targetIndexMetadata} itself when the shard was already recorded, so the caller can avoid a cluster state change,
     * otherwise a copy with the shard added
     */
    static IndexMetadata withCompletedShard(IndexMetadata targetIndexMetadata, int shardId) {
        Map<String, String> custom = targetIndexMetadata.getCustomData(CUSTOM_METADATA_KEY);
        Set<Integer> alreadyCompletedShards = custom == null ? Set.of() : parse(custom.get(COMPLETED_SHARDS_KEY));
        if (alreadyCompletedShards.contains(shardId)) {
            return targetIndexMetadata;
        }
        final SortedSet<Integer> newCompletedSet = new TreeSet<>(alreadyCompletedShards);
        newCompletedSet.add(shardId);
        custom = custom == null ? new HashMap<>(1) : new HashMap<>(custom);
        custom.put(COMPLETED_SHARDS_KEY, Strings.collectionToCommaDelimitedString(newCompletedSet));
        return IndexMetadata.builder(targetIndexMetadata).putCustom(CUSTOM_METADATA_KEY, custom).build();
    }

    private static Set<Integer> parse(String value) {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        final Set<Integer> shards = new HashSet<>();
        for (String shard : Strings.splitStringByCommaToArray(value)) {
            shards.add(Integer.parseInt(shard));
        }
        return shards;
    }
}
