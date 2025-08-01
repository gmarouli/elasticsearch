/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.admin.cluster.stats.MappingVisitor;
import org.elasticsearch.action.admin.indices.create.CreateIndexClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.template.put.TransportPutComposableIndexTemplateAction;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.ShardsAcknowledgedResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamLifecycle;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.common.time.DateFormatters;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.TimeSeriesParams;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.aggregatemetric.mapper.AggregateMetricDoubleFieldMapper;
import org.elasticsearch.xpack.downsample.TimeseriesFieldTypeHelper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.index.mapper.TimeSeriesParams.TIME_SERIES_METRIC_PARAM;
import static org.elasticsearch.xpack.downsample.incremental.IncrementalDownsamplingService.DOWNSAMPLE_CONFIG;
import static org.elasticsearch.xpack.downsample.incremental.IncrementalDownsamplingService.DOWNSAMPLING_INTERVAL;

/**
 * This service will track and manage the incremental downsampling of data streams.
 * // TODO-ID: error tracking
 */
public class PocIndexHelper {

    private static final DateFormatter TIMESTAMP_FORMATTER = DateFormatter.forPattern(
        "strict_date_optional_time_nanos||strict_date_optional_time||epoch_millis"
    );

    record FieldsPerType(List<String> dimensions, List<String> metrics, List<String> labels) {}

    // public for testing
    public record AggregateMetricDoubleFieldSupportedMetrics(String defaultMetric, List<String> supportedMetrics) {}

    private final Client client;
    private final IndicesService indicesService;
    MetadataCreateIndexService createIndexService;

    public PocIndexHelper(IndicesService indicesService, MetadataCreateIndexService createIndexService, Client client) {
        this.client = client;
        this.indicesService = indicesService;
        this.createIndexService = createIndexService;
    }

    public void maybeCreateDownsampleLayer(
        ProjectMetadata project,
        String dataStreamName,
        DownsampleConfig downsampleConfig,
        Index sourceIndex,
        ActionListener<Boolean> listener
    ) {
        if (project.hasIndex(DataStream.getDefaultDownsampleLayerIndexName(dataStreamName, downsampleConfig.getInterval()))) {
            listener.onResponse(true);
            return;
        }
        IndexMetadata sourceIndexMetadata = project.index(sourceIndex);
        SubscribableListener.<GetMappingsResponse>newForked(
            l -> client.admin().indices().getMappings(new GetMappingsRequest(TimeValue.THIRTY_SECONDS).indices(sourceIndex.getName()), l)
        )
            .<String>andThen((l, response) -> createDownsampleIndexMapping(project, sourceIndexMetadata, response, l))
            .<ShardsAcknowledgedResponse>andThen(
                (l, mapping) -> createDownsamplingIndex(project.id(), dataStreamName, sourceIndexMetadata, mapping, downsampleConfig, l)
            )
            .andThenApply(ShardsAcknowledgedResponse::isShardsAcknowledged)
            .addListener(listener);
    }

    public void maybeCreateDownsampleTemplate(
        ProjectMetadata project,
        String dataStreamName,
        Index sourceIndex,
        ActionListener<Boolean> listener
    ) {
        String templateName = dataStreamName + "-downsample-template";
        if (project.templatesV2().containsKey(templateName)) {
            listener.onResponse(true);
            return;
        }
        IndexMetadata sourceIndexMetadata = project.index(sourceIndex);
        SubscribableListener.<GetMappingsResponse>newForked(
            l -> client.admin().indices().getMappings(new GetMappingsRequest(TimeValue.THIRTY_SECONDS).indices(sourceIndex.getName()), l)
        )
            .<String>andThen((l, response) -> createDownsampleIndexMapping(project, sourceIndexMetadata, response, l))
            .<AcknowledgedResponse>andThen(
                (l, mapping) -> createDownsamplingTemplate(templateName, dataStreamName, sourceIndexMetadata, mapping, l)
            )
            .andThenApply(AcknowledgedResponse::isAcknowledged)
            .addListener(listener);
    }

    public void refreshDownsampleLayer(
        String dataStreamName,
        DownsampleConfig downsampleConfig,
        ActionListener<BroadcastResponse> listener
    ) {
        client.admin()
            .indices()
            .refresh(
                new RefreshRequest(DataStream.getDefaultDownsampleLayerIndexName(dataStreamName, downsampleConfig.getInterval())),
                listener
            );
    }

    private void createDownsamplingIndex(
        ProjectId projectId,
        String dataStreamName,
        IndexMetadata sourceIndexMetadata,
        String mapping,
        DownsampleConfig downsampleConfig,
        ActionListener<ShardsAcknowledgedResponse> listener
    ) {
        Settings.Builder builder = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_HIDDEN, true)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, sourceIndexMetadata.getNumberOfShards())
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, sourceIndexMetadata.getNumberOfReplicas())
            .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), "-1")
            .put(IndexMetadata.INDEX_DOWNSAMPLE_STATUS.getKey(), IndexMetadata.DownsampleTaskStatus.STARTED)
            .put(IndexMetadata.INDEX_DOWNSAMPLE_INTERVAL.getKey(), DOWNSAMPLING_INTERVAL.toString())
            .put(IndexSettings.MODE.getKey(), sourceIndexMetadata.getIndexMode())
            .putList(IndexMetadata.INDEX_ROUTING_PATH.getKey(), sourceIndexMetadata.getRoutingPaths())
            .put(
                IndexSettings.TIME_SERIES_START_TIME.getKey(),
                sourceIndexMetadata.getSettings().get(IndexSettings.TIME_SERIES_START_TIME.getKey())
            )
            .put(
                IndexSettings.TIME_SERIES_END_TIME.getKey(),
                sourceIndexMetadata.getSettings().get(IndexSettings.TIME_SERIES_END_TIME.getKey())
            );
        if (sourceIndexMetadata.getSettings().hasValue(MapperService.INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING.getKey())) {
            builder.put(
                MapperService.INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING.getKey(),
                sourceIndexMetadata.getSettings().get(MapperService.INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING.getKey())
            );
        }
        if (sourceIndexMetadata.getSettings().hasValue(FieldMapper.IGNORE_MALFORMED_SETTING.getKey())) {
            builder.put(
                FieldMapper.IGNORE_MALFORMED_SETTING.getKey(),
                sourceIndexMetadata.getSettings().get(FieldMapper.IGNORE_MALFORMED_SETTING.getKey())
            );
        }

        String downsampleIndexName = DataStream.getDefaultDownsampleLayerIndexName(dataStreamName, downsampleConfig.getInterval());
        CreateIndexClusterStateUpdateRequest createIndexClusterStateUpdateRequest = new CreateIndexClusterStateUpdateRequest(
            "downsample",
            projectId,
            downsampleIndexName,
            downsampleIndexName
        ).settings(builder.build()).mappings(mapping).waitForActiveShards(ActiveShardCount.ONE);
        createIndexService.createIndex(
            TimeValue.THIRTY_SECONDS,
            TimeValue.THIRTY_SECONDS,
            TimeValue.ONE_MINUTE,
            createIndexClusterStateUpdateRequest,
            listener
        );
    }

    private void createDownsamplingTemplate(
        String templateName,
        String dataStreamName,
        IndexMetadata sourceIndexMetadata,
        String mapping,
        ActionListener<AcknowledgedResponse> listener
    ) {
        Settings.Builder builder = Settings.builder()
            .put(IndexMetadata.SETTING_INDEX_HIDDEN, true)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, sourceIndexMetadata.getNumberOfShards())
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, sourceIndexMetadata.getNumberOfReplicas())
            .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), "-1")
            .put(IndexMetadata.INDEX_DOWNSAMPLE_STATUS.getKey(), IndexMetadata.DownsampleTaskStatus.STARTED)
            .put(IndexMetadata.INDEX_DOWNSAMPLE_INTERVAL.getKey(), DOWNSAMPLING_INTERVAL.toString())
            .put(IndexSettings.MODE.getKey(), sourceIndexMetadata.getIndexMode())
            .putList(IndexMetadata.INDEX_ROUTING_PATH.getKey(), sourceIndexMetadata.getRoutingPaths())
            .put("index.look_back_time", TimeValue.timeValueDays(1).getStringRep());
        if (sourceIndexMetadata.getSettings().hasValue(MapperService.INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING.getKey())) {
            builder.put(
                MapperService.INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING.getKey(),
                sourceIndexMetadata.getSettings().get(MapperService.INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING.getKey())
            );
        }
        if (sourceIndexMetadata.getSettings().hasValue(FieldMapper.IGNORE_MALFORMED_SETTING.getKey())) {
            builder.put(
                FieldMapper.IGNORE_MALFORMED_SETTING.getKey(),
                sourceIndexMetadata.getSettings().get(FieldMapper.IGNORE_MALFORMED_SETTING.getKey())
            );
        }

        try {
            TransportPutComposableIndexTemplateAction.Request request = new TransportPutComposableIndexTemplateAction.Request(templateName);
            request.indexTemplate(
                ComposableIndexTemplate.builder()
                    .indexPatterns(List.of(".downsampled-*-" + dataStreamName))
                    .template(
                        Template.builder()
                            .settings(builder)
                            .mappings(new CompressedXContent(mapping))
                            .lifecycle(DataStreamLifecycle.Template.DATA_DEFAULT)
                    )
                    .dataStreamTemplate(new ComposableIndexTemplate.DataStreamTemplate())
                    .build()
            );
            client.execute(TransportPutComposableIndexTemplateAction.TYPE, request, listener);
        } catch (IOException e) {
            listener.onFailure(e);
        }
    }

    public void createDownsampleIndexMapping(
        ProjectMetadata project,
        IndexMetadata sourceIndexMetadata,
        GetMappingsResponse response,
        ActionListener<String> listener
    ) {
        try {
            String sourceIndexName = sourceIndexMetadata.getIndex().getName();
            final MappingMetadata sourceIndexMappingMetadata = response.mappings().get(sourceIndexName);
            if (sourceIndexMappingMetadata == null) {
                throw new IllegalArgumentException("No mapping found for downsample source index [" + sourceIndexName + "]");
            }
            Map<String, Object> sourceIndexMappings = sourceIndexMappingMetadata.getSourceAsMap();

            final MapperService mapperService = indicesService.createIndexMapperServiceForValidation(project.index(sourceIndexName));
            final CompressedXContent sourceIndexCompressedXContent = new CompressedXContent(sourceIndexMappings);
            mapperService.merge(MapperService.SINGLE_MAPPING_NAME, sourceIndexCompressedXContent, MapperService.MergeReason.INDEX_TEMPLATE);
            final TimeseriesFieldTypeHelper helper = new TimeseriesFieldTypeHelper.Builder(mapperService).build(
                DOWNSAMPLE_CONFIG.getTimestampField()
            );

            validateDownsamplingInterval(mapperService, DOWNSAMPLE_CONFIG);
            FieldsPerType fields = getFieldsPerType(sourceIndexMappings, helper);

            ActionRequestValidationException validationException = new ActionRequestValidationException();
            if (fields.dimensions.isEmpty()) {
                validationException.addValidationError("Index [" + sourceIndexName + "] does not contain any dimension fields");
            }

            if (validationException.validationErrors().isEmpty() == false) {
                listener.onFailure(validationException);
                return;
            }
            DownsampleConfig downsampleConfig = new DownsampleConfig(DOWNSAMPLING_INTERVAL);
            listener.onResponse(createDownsampleIndexMapping(helper, downsampleConfig, mapperService, sourceIndexMappings));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * This method creates the mapping for the downsample index, based on the
     * mapping (dimensions and metrics) from the source index, as well as the
     * downsample configuration.
     *
     * @param config the downsample configuration
     * @param sourceIndexMappings a map with the source index mapping
     * @return the mapping of the downsample index
     */
    public static String createDownsampleIndexMapping(
        final TimeseriesFieldTypeHelper helper,
        final DownsampleConfig config,
        final MapperService mapperService,
        final Map<String, Object> sourceIndexMappings
    ) throws IOException {
        final XContentBuilder builder = XContentFactory.jsonBuilder().startObject();

        addDynamicTemplates(builder);

        builder.startObject("properties");

        addTimestampField(config, sourceIndexMappings, builder);
        addMetricFields(helper, sourceIndexMappings, builder);

        builder.endObject(); // match initial startObject
        builder.endObject(); // match startObject("properties")

        final CompressedXContent mappingDiffXContent = CompressedXContent.fromJSON(
            XContentHelper.convertToJson(BytesReference.bytes(builder), false, XContentType.JSON)
        );
        return mapperService.merge(MapperService.SINGLE_MAPPING_NAME, mappingDiffXContent, MapperService.MergeReason.INDEX_TEMPLATE)
            .mappingSource()
            .uncompressed()
            .utf8ToString();
    }

    private static void addMetricFields(
        final TimeseriesFieldTypeHelper helper,
        final Map<String, Object> sourceIndexMappings,
        final XContentBuilder builder
    ) {
        MappingVisitor.visitMapping(sourceIndexMappings, (field, mapping) -> {
            if (helper.isTimeSeriesMetric(field, mapping)) {
                try {
                    addMetricFieldMapping(builder, field, mapping);
                } catch (IOException e) {
                    throw new ElasticsearchException("Error while adding metric for field [" + field + "]");
                }
            }
        });
    }

    private static void addMetricFieldMapping(final XContentBuilder builder, final String field, final Map<String, ?> fieldProperties)
        throws IOException {
        final TimeSeriesParams.MetricType metricType = TimeSeriesParams.MetricType.fromString(
            fieldProperties.get(TIME_SERIES_METRIC_PARAM).toString()
        );
        builder.startObject(field);
        if (metricType == TimeSeriesParams.MetricType.COUNTER) {
            // For counters, we keep the same field type, because they store
            // only one value (the last value of the counter)
            for (String fieldProperty : fieldProperties.keySet()) {
                builder.field(fieldProperty, fieldProperties.get(fieldProperty));
            }
        } else {
            var supported = getSupportedMetrics(metricType, fieldProperties);

            builder.field("type", AggregateMetricDoubleFieldMapper.CONTENT_TYPE)
                .stringListField(AggregateMetricDoubleFieldMapper.Names.METRICS, supported.supportedMetrics())
                .field(AggregateMetricDoubleFieldMapper.Names.DEFAULT_METRIC, supported.defaultMetric())
                .field(TIME_SERIES_METRIC_PARAM, metricType);
        }
        builder.endObject();
    }

    private static void addTimestampField(
        final DownsampleConfig config,
        Map<String, Object> sourceIndexMappings,
        final XContentBuilder builder
    ) throws IOException {
        final String timestampField = config.getTimestampField();
        final String dateIntervalType = config.getIntervalType();
        final String dateInterval = config.getInterval().toString();
        final String timezone = config.getTimeZone();
        builder.startObject(timestampField);

        MappingVisitor.visitMapping(sourceIndexMappings, (field, mapping) -> {
            try {
                if (timestampField.equals(field)) {
                    final String timestampType = String.valueOf(mapping.get("type"));
                    builder.field("type", timestampType != null ? timestampType : DateFieldMapper.CONTENT_TYPE);
                    if (mapping.get("format") != null) {
                        builder.field("format", mapping.get("format"));
                    }
                    if (mapping.get("ignore_malformed") != null) {
                        builder.field("ignore_malformed", mapping.get("ignore_malformed"));
                    }
                }
            } catch (IOException e) {
                throw new ElasticsearchException("Unable to create timestamp field mapping for field [" + timestampField + "]", e);
            }
        });

        builder.startObject("meta")
            .field(dateIntervalType, dateInterval)
            .field(DownsampleConfig.TIME_ZONE, timezone)
            .endObject()
            .endObject();
    }

    // public for testing
    public static AggregateMetricDoubleFieldSupportedMetrics getSupportedMetrics(
        final TimeSeriesParams.MetricType metricType,
        final Map<String, ?> fieldProperties
    ) {
        boolean sourceIsAggregate = fieldProperties.get("type").equals(AggregateMetricDoubleFieldMapper.CONTENT_TYPE);
        List<String> supportedAggs = List.of(metricType.supportedAggs());

        if (sourceIsAggregate) {
            @SuppressWarnings("unchecked")
            List<String> currentAggs = (List<String>) fieldProperties.get(AggregateMetricDoubleFieldMapper.Names.METRICS);
            supportedAggs = supportedAggs.stream().filter(currentAggs::contains).toList();
        }

        assert supportedAggs.size() > 0;

        String defaultMetric = "max";
        if (supportedAggs.contains(defaultMetric) == false) {
            defaultMetric = supportedAggs.get(0);
        }
        if (sourceIsAggregate) {
            defaultMetric = Objects.requireNonNullElse(
                (String) fieldProperties.get(AggregateMetricDoubleFieldMapper.Names.DEFAULT_METRIC),
                defaultMetric
            );
        }

        return new AggregateMetricDoubleFieldSupportedMetrics(defaultMetric, supportedAggs);
    }

    /**
     * Configure the dynamic templates to always map strings to the keyword field type.
     */
    private static void addDynamicTemplates(final XContentBuilder builder) throws IOException {
        builder.startArray("dynamic_templates")
            .startObject()
            .startObject("strings")
            .field("match_mapping_type", "string")
            .startObject("mapping")
            .field("type", "keyword")
            .endObject()
            .endObject()
            .endObject()
            .endArray();
    }

    FieldsPerType getFieldsPerType(DownsampleConfig downsampleConfig, IndexMetadata indexMetadata, GetMappingsResponse getMappingsResponse)
        throws IOException {
        Index index = indexMetadata.getIndex();
        final Map<String, Object> sourceIndexMappings = getMappingsResponse.mappings().get(index.getName()).getSourceAsMap();
        if (sourceIndexMappings == null) {
            throw new IllegalArgumentException("No mapping found for downsample source index [" + index.getName() + "]");
        }
        final MapperService mapperService = indicesService.createIndexMapperServiceForValidation(indexMetadata);
        final CompressedXContent sourceIndexCompressedXContent = new CompressedXContent(sourceIndexMappings);
        mapperService.merge(MapperService.SINGLE_MAPPING_NAME, sourceIndexCompressedXContent, MapperService.MergeReason.INDEX_TEMPLATE);
        final TimeseriesFieldTypeHelper helper = new TimeseriesFieldTypeHelper.Builder(mapperService).build(
            downsampleConfig.getTimestampField()
        );
        return getFieldsPerType(sourceIndexMappings, helper);
    }

    private FieldsPerType getFieldsPerType(Map<String, Object> sourceIndexMappings, TimeseriesFieldTypeHelper helper) {
        final List<String> dimensions = new ArrayList<>();
        final List<String> metrics = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        MappingVisitor.visitMapping(sourceIndexMappings, (field, mapping) -> {
            var flattenedDimensions = helper.extractFlattenedDimensions(field, mapping);
            if (flattenedDimensions != null) {
                dimensions.addAll(flattenedDimensions);
            } else if (helper.isTimeSeriesDimension(field, mapping)) {
                dimensions.add(field);
            } else if (helper.isTimeSeriesMetric(field, mapping)) {
                metrics.add(field);
            } else if (helper.isTimeSeriesLabel(field, mapping)) {
                labels.add(field);
            }
        });
        return new FieldsPerType(dimensions, metrics, labels);
    }

    static Instant getTimeStampFromRaw(Object rawTimestamp) {
        try {
            if (rawTimestamp instanceof Long lTimestamp) {
                return Instant.ofEpochMilli(lTimestamp);
            } else if (rawTimestamp instanceof String sTimestamp) {
                return DateFormatters.from(TIMESTAMP_FORMATTER.parse(sTimestamp), TIMESTAMP_FORMATTER.locale()).toInstant();
            } else {
                throw new DataStream.TimestampError("timestamp [" + rawTimestamp + "] type [" + rawTimestamp.getClass() + "] error");
            }
        } catch (Exception e) {
            throw new DataStream.TimestampError("Error get data stream timestamp field: " + e.getMessage(), e);
        }
    }

    private static void validateDownsamplingInterval(MapperService mapperService, DownsampleConfig config) {
        MappedFieldType timestampFieldType = mapperService.fieldType(config.getTimestampField());
        assert timestampFieldType != null : "Cannot find timestamp field [" + config.getTimestampField() + "] in the mapping";
        ActionRequestValidationException e = new ActionRequestValidationException();

        Map<String, String> meta = timestampFieldType.meta();
        if (meta.isEmpty() == false) {
            String interval = meta.get(config.getIntervalType());
            if (interval != null) {
                try {
                    DownsampleConfig sourceConfig = new DownsampleConfig(new DateHistogramInterval(interval));
                    DownsampleConfig.validateSourceAndTargetIntervals(sourceConfig, config);
                } catch (IllegalArgumentException exception) {
                    e.addValidationError("Source index is a downsampled index. " + exception.getMessage());
                }
            }

            // Validate that timezones match
            String sourceTimezone = meta.get(DownsampleConfig.TIME_ZONE);
            if (sourceTimezone != null && sourceTimezone.equals(config.getTimeZone()) == false) {
                e.addValidationError(
                    "Source index is a downsampled index. Downsampling timezone ["
                        + config.getTimeZone()
                        + "] cannot be different than the source index timezone ["
                        + sourceTimezone
                        + "]."
                );
            }

            if (e.validationErrors().isEmpty() == false) {
                throw e;
            }
        }
    }
}
