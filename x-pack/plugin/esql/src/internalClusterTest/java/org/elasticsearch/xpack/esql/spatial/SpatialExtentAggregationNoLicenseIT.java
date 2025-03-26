/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.spatial;

import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.esql.action.EsqlPluginWithNonEnterpriseOrExpiredLicense;
import org.elasticsearch.xpack.spatial.SpatialPlugin;

import java.util.Collection;
import java.util.List;

public class SpatialExtentAggregationNoLicenseIT extends SpatialExtentAggregationTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(SpatialPlugin.class, EsqlPluginWithNonEnterpriseOrExpiredLicense.class);
    }

    @Override
    public void testStExtentAggregationWithShapes() {
        assertStExtentFailsWith("index_geo_shape");
    }
}
