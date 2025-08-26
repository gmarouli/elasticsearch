/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.common.Rounding;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.xpack.esql.action.EsqlQueryResponse;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures a query of the format:
 * TS my-data-stream
 * | STATS avg_cpu = AVG(metrics.cpu), count = COUNT(*) BY attributes.host.name, bucket = BUCKET(@timestamp, 10 minutes)
 * | SORT bucket, attributes.host.name
 * the data stream name and the bucket size are configurable, the rest need to be an exact match.
 * When the response is collected by multiple layers then you will notice the count column will be missing;
 * this was done on purpose, to distinguish between a layers response and a normal one.
 */
public class EsqlQueryDownsampledLayerResolver {

    private static final Logger logger = LogManager.getLogger(EsqlQueryDownsampledLayerResolver.class);
    private static final Pattern pattern = Pattern.compile(
        "^TS ([A-Za-z0-9-]+) "
            + "\\| STATS avg_cpu = AVG\\(metrics\\.cpu\\), count = COUNT\\(\\*\\) "
            + "BY attributes\\.host\\.name, bucket = BUCKET\\(@timestamp, ([0-9]+) ([A-Za-z]+)\\) "
            + "\\| SORT bucket, attributes\\.host\\.name$",
        Pattern.CASE_INSENSITIVE
    );
    private static final ConcurrentMap<String, Rounding> roundings = new ConcurrentHashMap<>();

    public static List<String> resolveDownsampleLayers(String query, ProjectState projectState) {
        // Match regex against input
        final Matcher matcher = pattern.matcher(query);
        if (matcher.matches() == false) {
            return List.of(query);
        }
        assert matcher.groupCount() == 3;
        String dataStreamName = matcher.group(1);
        DataStream dataStream = projectState.metadata().dataStreams().get(dataStreamName);
        if (dataStream == null) {
            return List.of(query);
        }
        String bucketSizeStr = matcher.group(2) + " " + matcher.group(3);
        String bucketSizeLabel = matcher.group(2) + matcher.group(3).substring(0, 1);
        TimeValue bucketSize = TimeValue.parseTimeValue(bucketSizeLabel, "ES|QL query");

        List<Tuple<String, Long>> applicableLayers = new ArrayList<>();
        for (String layer : dataStream.getLastDownsampledTimestamp().keySet()) {
            TimeValue interval = TimeValue.parseTimeValue(layer, "data stream layer");
            long bucketMillis = bucketSize.millis();
            long layerMillis = interval.millis();
            if (isLayerApplicable(layerMillis, bucketMillis)) {
                applicableLayers.add(Tuple.tuple(layer, layerMillis));
            }
        }
        if (applicableLayers.isEmpty()) {
            return List.of(query);
        }
        applicableLayers.sort((t1, t2) -> -Long.compare(t1.v2(), t2.v2()));

        Long previousEndTime = null;
        List<String> rewrittenQueries = new ArrayList<>(applicableLayers.size() + 1);
        for (Tuple<String, Long> layer : applicableLayers) {
            Long endTime = roundEndTimeToFullBucket(layer.v1(), bucketSizeLabel, dataStream);
            if (layerContainsAFullBucket(endTime, previousEndTime, bucketSize.millis()) == false) {
                continue;
            }
            rewrittenQueries.add(
                query(DataStream.getDefaultDownsampleLayerIndexName(dataStreamName, layer.v1()), previousEndTime, endTime, bucketSizeStr)
            );
            previousEndTime = endTime;
        }
        rewrittenQueries.add(query(dataStreamName, previousEndTime, null, bucketSizeStr));
        logger.info("Downsampling aware queries: {}", rewrittenQueries);
        return rewrittenQueries;
    }

    private static Long roundEndTimeToFullBucket(String layer, String bucketSizeLabel, DataStream dataStream) {
        var rounding = roundings.computeIfAbsent(
            bucketSizeLabel,
            ignored -> Rounding.builder(TimeValue.parseTimeValue(bucketSizeLabel, "esql-query")).timeZone(ZoneId.of("UTC")).build()
        );
        return rounding.prepareForUnknown().round(dataStream.getLastDownsampledTimestamp().get(layer));
    }

    private static boolean isLayerApplicable(long layerMillis, long bucketMillis) {
        return bucketMillis >= layerMillis && bucketMillis % layerMillis == 0;
    }

    private static boolean layerContainsAFullBucket(long lastTimestamp, Long previousLastTimestamp, long bucketMillis) {
        return previousLastTimestamp == null || lastTimestamp - previousLastTimestamp >= bucketMillis;
    }

    private static String query(String dataStreamName, Long startTime, Long endTime, String interval) {
        StringBuilder sb = new StringBuilder();
        sb.append("TS ").append(dataStreamName).append(" | WHERE ");
        if (startTime != null) {
            sb.append("@timestamp >= \"").append(Instant.ofEpochMilli(startTime)).append("\" ");
        }
        if (endTime != null) {
            if (startTime != null) {
                sb.append(" AND ");
            }
            sb.append("@timestamp < \"").append(Instant.ofEpochMilli(endTime)).append("\" ");
        }
        sb.append(" | STATS avg_cpu = AVG(metrics.cpu) BY attributes.host.name, bucket = BUCKET(@timestamp, " + interval + ")")
            .append(" | SORT bucket, attributes.host.name");
        return sb.toString();
    }

    public static EsqlQueryResponse combineResponse(Collection<EsqlQueryResponse> responses) {
        List<Page> pages = new ArrayList<>();
        long documentsFound = 0;
        long valuesLoaded = 0;
        for (EsqlQueryResponse response : responses) {
            pages.addAll(response.pages());
            documentsFound += response.documentsFound();
            valuesLoaded += response.valuesLoaded();
        }
        pages.sort(Comparator.comparing(page -> {
            if (page.getBlock(2).asVector() instanceof LongVector longVector) {
                return longVector.getLong(0);
            } else {
                throw new IllegalArgumentException("Cannot order response based on timestamp because the third block is not a long");
            }
        }));
        EsqlQueryResponse sample = responses.stream().findFirst().get();
        return new EsqlQueryResponse(
            sample.columns(),
            pages,
            documentsFound,
            valuesLoaded,
            sample.profile(),
            sample.columnar(),
            sample.asyncExecutionId().orElse(null),
            sample.isRunning(),
            sample.isAsync(),
            sample.getExecutionInfo()
        );
    }
}
