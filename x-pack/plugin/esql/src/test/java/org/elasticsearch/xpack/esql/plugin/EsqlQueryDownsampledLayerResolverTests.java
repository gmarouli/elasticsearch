/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plugin;

import org.elasticsearch.test.ESTestCase;

import java.util.regex.Matcher;

import static org.elasticsearch.xpack.esql.plugin.EsqlQueryDownsampledLayerResolver.AVERAGE_METRIC_GROUP_ID;
import static org.elasticsearch.xpack.esql.plugin.EsqlQueryDownsampledLayerResolver.BUCKET_SIZE_GROUP_ID;
import static org.elasticsearch.xpack.esql.plugin.EsqlQueryDownsampledLayerResolver.BUCKET_UNIT_GROUP_ID;
import static org.elasticsearch.xpack.esql.plugin.EsqlQueryDownsampledLayerResolver.DATA_STREAM_NAME_GROUP_ID;
import static org.elasticsearch.xpack.esql.plugin.EsqlQueryDownsampledLayerResolver.GROUPS_COUNT;
import static org.elasticsearch.xpack.esql.plugin.EsqlQueryDownsampledLayerResolver.GROUP_BY_DIMENSION_GROUP_ID;
import static org.elasticsearch.xpack.esql.plugin.EsqlQueryDownsampledLayerResolver.SORT_BY_DIMENSION_GROUP_ID;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class EsqlQueryDownsampledLayerResolverTests extends ESTestCase {

    public void testQueryMatcher() {
        String query = "TS my-data-stream"
            + " | STATS metric_avg = AVG(metrics.cpu) BY attributes.host.name, bucket = BUCKET(@timestamp, 1 hour)"
            + " | SORT bucket, attributes.host.name";
        Matcher matcher = EsqlQueryDownsampledLayerResolver.PATTERN.matcher(query);
        assertThat(matcher.matches(), equalTo(true));
        assertThat(matcher.groupCount(), equalTo(GROUPS_COUNT));
        assertThat(matcher.group(DATA_STREAM_NAME_GROUP_ID), equalTo("my-data-stream"));
        assertThat(matcher.group(AVERAGE_METRIC_GROUP_ID), equalTo("metrics.cpu"));
        assertThat(matcher.group(GROUP_BY_DIMENSION_GROUP_ID), equalTo("attributes.host.name, "));
        assertThat(matcher.group(BUCKET_SIZE_GROUP_ID), equalTo("1"));
        assertThat(matcher.group(BUCKET_UNIT_GROUP_ID), equalTo("hour"));
        assertThat(matcher.group(SORT_BY_DIMENSION_GROUP_ID), equalTo(", attributes.host.name"));

        query = "TS my-data-stream" + " | STATS metric_avg = AVG(metrics.cpu) BY bucket = BUCKET(@timestamp, 1 hour)" + " | SORT bucket";
        matcher = EsqlQueryDownsampledLayerResolver.PATTERN.matcher(query);
        assertThat(matcher.matches(), equalTo(true));
        assertThat(matcher.groupCount(), equalTo(GROUPS_COUNT));
        assertThat(matcher.group(DATA_STREAM_NAME_GROUP_ID), equalTo("my-data-stream"));
        assertThat(matcher.group(AVERAGE_METRIC_GROUP_ID), equalTo("metrics.cpu"));
        assertThat(matcher.group(GROUP_BY_DIMENSION_GROUP_ID), nullValue());
        assertThat(matcher.group(BUCKET_SIZE_GROUP_ID), equalTo("1"));
        assertThat(matcher.group(BUCKET_UNIT_GROUP_ID), equalTo("hour"));
        assertThat(matcher.group(SORT_BY_DIMENSION_GROUP_ID), nullValue());
    }

}
