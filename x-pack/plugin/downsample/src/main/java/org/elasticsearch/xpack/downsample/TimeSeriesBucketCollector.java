/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.internal.hppc.IntArrayList;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.action.bulk.BulkProcessor2;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.Rounding;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.fielddata.FormattedDocValues;
import org.elasticsearch.index.fielddata.SortedNumericDoubleValues;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.index.mapper.DocCountFieldMapper;
import org.elasticsearch.index.mapper.TimeSeriesIdFieldMapper;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.AggregationExecutionContext;
import org.elasticsearch.search.aggregations.BucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.bucket.DocCountProvider;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.stream.Collectors.groupingBy;

/**
 * Calculates the aggregated docs and indexes them using the bulk processor provided.
 */
public class TimeSeriesBucketCollector extends BucketCollector {

    private static final Logger logger = LogManager.getLogger(TimeSeriesBucketCollector.class);
    private static final int DOCID_BUFFER_SIZE = 8096;
    private final BulkProcessor2 bulkProcessor;
    private final DownsampleBucketBuilder downsampleBucketBuilder;
    private final List<LeafDownsampleCollector> leafBucketCollectors = new ArrayList<>();
    private final Rounding.Prepared rounding;
    private final List<FieldValueFetcher> fieldValueFetchers;
    private final Client client;
    private final Runnable cancellationCheck;
    private final AtomicBoolean abort;
    private final String downsampleIndex;
    private final TrackingProgress trackingProgress;
    private final IndexShard indexShard;
    private final SearchExecutionContext searchExecutionContext;
    private final DocValueFormat timestampFormat;
    private final DateFieldMapper.DateFieldType timestampField;
    private long docsProcessed;
    private long bucketsCreated;
    long lastTimestamp = Long.MAX_VALUE;
    long lastHistoTimestamp = Long.MAX_VALUE;

    public TimeSeriesBucketCollector(
        DownsampleConfig config,
        String downsampleIndex,
        IndexShard indexShard,
        BulkProcessor2 bulkProcessor,
        String[] dimensions,
        String[] metrics,
        String[] labels,
        Runnable cancellationCheck,
        AtomicBoolean abort,
        TrackingProgress trackingProgress,
        SearchExecutionContext searchExecutionContext,
        Client client
    ) {
        this.bulkProcessor = bulkProcessor;
        this.downsampleIndex = downsampleIndex;
        rounding = config.createRounding();
        List<FieldValueFetcher> fetchers = new ArrayList<>(metrics.length + labels.length + dimensions.length);
        fetchers.addAll(FieldValueFetcher.create(searchExecutionContext, metrics));
        fetchers.addAll(FieldValueFetcher.create(searchExecutionContext, labels));
        fetchers.addAll(DimensionFieldValueFetcher.create(searchExecutionContext, dimensions));
        this.fieldValueFetchers = Collections.unmodifiableList(fetchers);
        this.client = client;
        this.indexShard = indexShard;
        this.cancellationCheck = cancellationCheck;
        this.abort = abort;
        this.trackingProgress = trackingProgress;
        this.searchExecutionContext = searchExecutionContext;
        this.timestampField = (DateFieldMapper.DateFieldType) searchExecutionContext.getFieldType(config.getTimestampField());
        this.timestampFormat = timestampField.docValueFormat(null, null);
        AbstractDownsampleFieldProducer[] fieldProducers = fieldValueFetchers.stream()
            .map(FieldValueFetcher::fieldProducer)
            .toArray(AbstractDownsampleFieldProducer[]::new);
        this.downsampleBucketBuilder = new DownsampleBucketBuilder(fieldProducers, dimensions);
    }

    @Override
    public LeafBucketCollector getLeafCollector(final AggregationExecutionContext aggCtx) throws IOException {
        final LeafReaderContext ctx = aggCtx.getLeafReaderContext();
        final DocCountProvider docCountProvider = new DocCountProvider();
        docCountProvider.setLeafReaderContext(ctx);

        // For each field, return a tuple with the downsample field producer and the field value leaf
        final List<AbstractDownsampleFieldProducer> nonMetricProducers = new ArrayList<>();
        final List<FormattedDocValues> formattedDocValues = new ArrayList<>();

        final List<MetricFieldProducer> metricProducers = new ArrayList<>();
        final List<SortedNumericDoubleValues> numericDocValues = new ArrayList<>();
        for (var fieldValueFetcher : fieldValueFetchers) {
            var fieldProducer = fieldValueFetcher.fieldProducer();
            if (fieldProducer instanceof MetricFieldProducer metricFieldProducer) {
                metricProducers.add(metricFieldProducer);
                numericDocValues.add(fieldValueFetcher.getNumericLeaf(ctx));
            } else {
                nonMetricProducers.add(fieldProducer);
                formattedDocValues.add(fieldValueFetcher.getLeaf(ctx));
            }
        }

        var leafBucketCollector = new LeafDownsampleCollector(
            aggCtx,
            docCountProvider,
            nonMetricProducers.toArray(new AbstractDownsampleFieldProducer[0]),
            formattedDocValues.toArray(new FormattedDocValues[0]),
            metricProducers.toArray(new MetricFieldProducer[0]),
            numericDocValues.toArray(new SortedNumericDoubleValues[0])
        );
        leafBucketCollectors.add(leafBucketCollector);
        return leafBucketCollector;
    }

    void bulkCollection() throws IOException {
        // The leaf bucket collectors with newer timestamp go first, to correctly capture the last value for counters and labels.
        leafBucketCollectors.sort((o1, o2) -> -Long.compare(o1.firstTimeStampForBulkCollection, o2.firstTimeStampForBulkCollection));
        for (LeafDownsampleCollector leafBucketCollector : leafBucketCollectors) {
            leafBucketCollector.leafBulkCollection();
        }
    }

    class LeafDownsampleCollector extends LeafBucketCollector {

        final AggregationExecutionContext aggCtx;
        final DocCountProvider docCountProvider;
        final FormattedDocValues[] formattedDocValues;
        final AbstractDownsampleFieldProducer[] nonMetricProducers;

        final MetricFieldProducer[] metricProducers;
        final SortedNumericDoubleValues[] numericDocValues;

        // Capture the first timestamp in order to determine which leaf collector's leafBulkCollection() is invoked first.
        long firstTimeStampForBulkCollection;
        final IntArrayList docIdBuffer = new IntArrayList(DOCID_BUFFER_SIZE);
        final long timestampBoundStartTime = searchExecutionContext.getIndexSettings().getTimestampBounds().startTime();

        LeafDownsampleCollector(
            AggregationExecutionContext aggCtx,
            DocCountProvider docCountProvider,
            AbstractDownsampleFieldProducer[] nonMetricProducers,
            FormattedDocValues[] formattedDocValues,
            MetricFieldProducer[] metricProducers,
            SortedNumericDoubleValues[] numericDocValues
        ) {
            assert nonMetricProducers.length == formattedDocValues.length;
            assert metricProducers.length == numericDocValues.length;

            this.aggCtx = aggCtx;
            this.docCountProvider = docCountProvider;
            this.nonMetricProducers = nonMetricProducers;
            this.formattedDocValues = formattedDocValues;
            this.metricProducers = metricProducers;
            this.numericDocValues = numericDocValues;
        }

        @Override
        public void collect(int docId, long owningBucketOrd) throws IOException {
            trackingProgress.addNumReceived(1);
            final BytesRef tsidHash = aggCtx.getTsidHash();
            assert tsidHash != null : "Document without [" + TimeSeriesIdFieldMapper.NAME + "] field was found.";
            final int tsidHashOrd = aggCtx.getTsidHashOrd();
            final long timestamp = timestampField.resolution().roundDownToMillis(aggCtx.getTimestamp());

            boolean tsidChanged = tsidHashOrd != downsampleBucketBuilder.tsidOrd();
            if (tsidChanged || timestamp < lastHistoTimestamp) {
                lastHistoTimestamp = Math.max(rounding.round(timestamp), timestampBoundStartTime);
            }
            trackingProgress.setLastSourceTimestamp(timestamp);
            trackingProgress.setLastTargetTimestamp(lastHistoTimestamp);

            if (logger.isTraceEnabled()) {
                logger.trace(
                    "Doc: [{}] - _tsid: [{}], @timestamp: [{}] -> downsample bucket ts: [{}]",
                    docId,
                    DocValueFormat.TIME_SERIES_ID.format(tsidHash),
                    timestampFormat.format(timestamp),
                    timestampFormat.format(lastHistoTimestamp)
                );
            }

            assert assertTsidAndTimestamp(tsidHash, timestamp);
            lastTimestamp = timestamp;

            if (tsidChanged || downsampleBucketBuilder.timestamp() != lastHistoTimestamp) {
                bulkCollection();
                // Flush downsample doc if not empty
                if (downsampleBucketBuilder.isEmpty() == false) {
                    XContentBuilder doc = downsampleBucketBuilder.buildDownsampleDocument();
                    indexBucket(doc);
                }

                // Create new downsample bucket
                if (tsidChanged) {
                    downsampleBucketBuilder.resetTsid(tsidHash, tsidHashOrd, lastHistoTimestamp);
                } else {
                    downsampleBucketBuilder.resetTimestamp(lastHistoTimestamp);
                }
                bucketsCreated++;
            }

            if (docIdBuffer.isEmpty()) {
                firstTimeStampForBulkCollection = aggCtx.getTimestamp();
            }
            // buffer.add() always delegates to system.arraycopy() and checks buffer size for resizing purposes:
            docIdBuffer.buffer[docIdBuffer.elementsCount++] = docId;
            if (docIdBuffer.size() == DOCID_BUFFER_SIZE) {
                bulkCollection();
            }
        }

        void leafBulkCollection() throws IOException {
            if (docIdBuffer.isEmpty()) {
                return;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("buffered {} docids", docIdBuffer.size());
            }

            downsampleBucketBuilder.collectDocCount(docIdBuffer, docCountProvider);
            // Iterate over all field values and collect the doc_values for this docId
            for (int i = 0; i < nonMetricProducers.length; i++) {
                AbstractDownsampleFieldProducer fieldProducer = nonMetricProducers[i];
                FormattedDocValues docValues = formattedDocValues[i];
                fieldProducer.collect(docValues, docIdBuffer);
            }
            for (int i = 0; i < metricProducers.length; i++) {
                MetricFieldProducer metricFieldProducer = metricProducers[i];
                SortedNumericDoubleValues numericDoubleValues = numericDocValues[i];
                metricFieldProducer.collect(numericDoubleValues, docIdBuffer);
            }

            docsProcessed += docIdBuffer.size();
            trackingProgress.setDocsProcessed(docsProcessed);

            // buffer.clean() also overwrites all slots with zeros
            docIdBuffer.elementsCount = 0;
        }

        /**
         * Sanity checks to ensure that we receive documents in the correct order
         * - _tsid must be sorted in ascending order
         * - @timestamp must be sorted in descending order within the same _tsid
         */
        boolean assertTsidAndTimestamp(BytesRef tsidHash, long timestamp) {
            BytesRef lastTsid = downsampleBucketBuilder.tsid();
            assert lastTsid == null || lastTsid.compareTo(tsidHash) <= 0
                : "_tsid is not sorted in ascending order: ["
                    + DocValueFormat.TIME_SERIES_ID.format(lastTsid)
                    + "] -> ["
                    + DocValueFormat.TIME_SERIES_ID.format(tsidHash)
                    + "]";
            assert tsidHash.equals(lastTsid) == false || lastTimestamp >= timestamp
                : "@timestamp is not sorted in descending order: ["
                    + timestampFormat.format(lastTimestamp)
                    + "] -> ["
                    + timestampFormat.format(timestamp)
                    + "]";
            return true;
        }
    }

    private void indexBucket(XContentBuilder doc) {
        IndexRequestBuilder request = client.prepareIndex(downsampleIndex);
        request.setSource(doc);
        if (logger.isTraceEnabled()) {
            logger.trace("Indexing downsample doc: [{}]", Strings.toString(doc));
        }
        IndexRequest indexRequest = request.request();
        trackingProgress.setLastIndexingTimestamp(System.currentTimeMillis());
        bulkProcessor.addWithBackpressure(indexRequest, abort::get);
    }

    @Override
    public void preCollection() {
        // check cancel when start running
        cancellationCheck.run();
    }

    @Override
    public void postCollection() throws IOException {
        // Flush downsample doc if not empty
        bulkCollection();
        if (downsampleBucketBuilder.isEmpty() == false) {
            XContentBuilder doc = downsampleBucketBuilder.buildDownsampleDocument();
            indexBucket(doc);
        }

        // check cancel after the flush all data
        cancellationCheck.run();

        logger.info("Shard {} processed [{}] docs, created [{}] downsample buckets", indexShard.shardId(), docsProcessed, bucketsCreated);
    }

    @Override
    public ScoreMode scoreMode() {
        return ScoreMode.COMPLETE_NO_SCORES;
    }

    public interface TrackingProgress {
        void setLastIndexingTimestamp(long timestamp);

        void setDocsProcessed(long docsProcessed);

        void setLastTargetTimestamp(long timestamp);

        void setLastSourceTimestamp(long timestamp);

        void addNumReceived(long count);
    }

    private class DownsampleBucketBuilder {
        private BytesRef tsid;
        private int tsidOrd = -1;
        private long timestamp;
        private int docCount;
        private final AbstractDownsampleFieldProducer[] fieldProducers;
        private final DownsampleFieldSerializer[] groupedProducers;
        private final String[] dimensions;

        DownsampleBucketBuilder(AbstractDownsampleFieldProducer[] fieldProducers, String[] dimensions) {
            this.fieldProducers = fieldProducers;
            this.dimensions = dimensions;
            /*
             * The downsample field producers for aggregate_metric_double all share the same name (this is
             * the name they will be serialized in the target index). We group all field producers by
             * name. If grouping yields multiple downsample field producers, we delegate serialization to
             * the AggregateMetricFieldSerializer class.
             */
            groupedProducers = Arrays.stream(fieldProducers)
                .collect(groupingBy(AbstractDownsampleFieldProducer::name))
                .entrySet()
                .stream()
                .map(e -> {
                    if (e.getValue().size() == 1) {
                        return e.getValue().get(0);
                    } else {
                        return new AggregateMetricFieldSerializer(e.getKey(), e.getValue());
                    }
                })
                .toArray(DownsampleFieldSerializer[]::new);
        }

        /**
         * tsid changed, reset tsid and timestamp
         */
        public void resetTsid(BytesRef tsid, int tsidOrd, long timestamp) {
            this.tsid = BytesRef.deepCopyOf(tsid);
            this.tsidOrd = tsidOrd;
            resetTimestamp(timestamp);
        }

        /**
         * timestamp change, reset builder
         */
        public void resetTimestamp(long timestamp) {
            this.timestamp = timestamp;
            this.docCount = 0;
            for (AbstractDownsampleFieldProducer producer : fieldProducers) {
                producer.reset();
            }
            if (logger.isTraceEnabled()) {
                logger.trace(
                    "New bucket for _tsid: [{}], @timestamp: [{}]",
                    DocValueFormat.TIME_SERIES_ID.format(tsid),
                    timestampFormat.format(timestamp)
                );
            }
        }

        public void collectDocCount(IntArrayList buffer, DocCountProvider docCountProvider) throws IOException {
            if (docCountProvider.alwaysOne()) {
                this.docCount += buffer.size();
            } else {
                for (int i = 0; i < buffer.size(); i++) {
                    int docId = buffer.get(i);
                    this.docCount += docCountProvider.getDocCount(docId);
                }
            }
        }

        public XContentBuilder buildDownsampleDocument() throws IOException {
            XContentBuilder builder = XContentFactory.contentBuilder(XContentType.SMILE);
            builder.startObject();
            if (isEmpty()) {
                builder.endObject();
                return builder;
            }
            builder.field(timestampField.name(), timestampFormat.format(timestamp));
            builder.field(DocCountFieldMapper.NAME, docCount);

            // Serialize fields
            for (DownsampleFieldSerializer fieldProducer : groupedProducers) {
                fieldProducer.write(builder);
            }

            if (dimensions.length == 0) {
                logger.debug("extracting dimensions from legacy tsid");
                Map<?, ?> dimensions = (Map<?, ?>) DocValueFormat.TIME_SERIES_ID.format(tsid);
                for (Map.Entry<?, ?> e : dimensions.entrySet()) {
                    assert e.getValue() != null;
                    builder.field((String) e.getKey(), e.getValue());
                }
            }

            builder.endObject();
            return builder;
        }

        public long timestamp() {
            return timestamp;
        }

        public BytesRef tsid() {
            return tsid;
        }

        public int tsidOrd() {
            return tsidOrd;
        }

        public int docCount() {
            return docCount;
        }

        public boolean isEmpty() {
            return tsid() == null || timestamp() == 0 || docCount() == 0;
        }

    }
}
