/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.fielddata;

import org.apache.lucene.index.SortedNumericDocValues;

import java.io.IOException;

/**
 * Extention of {@link SortedNumericDoubleValues} that allows to iterate over double values.
 */
public class IterableSortedNumericLongValues extends SortedNumericLongValues {

    private final SortedNumericDocValues docValues;
    private final ValueProcessor valueProcessor;

    public IterableSortedNumericLongValues(SortedNumericDocValues docValues) {
        this(docValues, null);
    }

    public IterableSortedNumericLongValues(SortedNumericDocValues docValues, ValueProcessor processor) {
        this.docValues = docValues;
        this.valueProcessor = processor;
    }

    /** Advance the iterator to exactly {@code target} and return whether
     *  {@code target} has a value.
     *  {@code target} must be greater than or equal to the current
     *  doc ID and must be a valid doc ID, ie. &ge; 0 and
     *  &lt; {@code maxDoc}.*/
    public boolean advanceExact(int target) throws IOException {
        return docValues.advanceExact(target);
    }

    /**
     * Iterates to the next value in the current document. Do not call this more than
     * {@link #docValueCount} times for the document.
     */
    public long nextValue() throws IOException {
        if (valueProcessor == null) {
            return docValues.nextValue();
        }
        return valueProcessor.process(docValues.nextValue());
    }

    /**
     * Retrieves the number of values for the current document.  This must always
     * be greater than zero.
     * It is illegal to call this method after {@link #advanceExact(int)}
     * returned {@code false}.
     */
    public int docValueCount() {
        return docValues.docValueCount();
    };

    /**
     * Advances to the first beyond the current whose document number is greater than or equal to
     * {@code target}, and returns the document number itself. Exhausts the iterator and returns {@link
     * org.apache.lucene.search.DocIdSetIterator#NO_MORE_DOCS} if {@code target} is greater than the
     * highest document number in the set.
     *
     * <p><b>NOTE:</b>The behavior of this method is <b>undefined</b> when called with {@code target}
     * is less or equal than the current doc id, or after the iterator has exhausted. Both cases may
     * result in unpredicted behavior.
     */
    public int advance(int target) throws IOException {
        return docValues.advance(target);
    }

    /**
     * Returns the following
     *  {@code -1}, if {@link #nextValue()} ()}, {@link #advanceExact(int)} or {@link #advance(int)} were not called yet.
     *  {@link org.apache.lucene.search.DocIdSetIterator#NO_MORE_DOCS} if the iterator has exhausted.
     *  otherwise, the doc ID it is currently on.
     */
    public int docID() {
        return docValues.docID();
    }

    public IterableSortedNumericDoubleValues castAsDoubles() {
        return new IterableSortedNumericDoubleValues(docValues, v -> valueProcessor == null ? v : valueProcessor.process(v));
    }

    public IterableSortedNumericDoubleValues convertToDoubles(IterableSortedNumericDoubleValues.LongToDouble toDouble) {
        return new IterableSortedNumericDoubleValues(
            docValues,
            v -> valueProcessor == null ? toDouble.convert(v) : toDouble.convert(valueProcessor.process(v))
        );
    }

    public interface ValueProcessor {
        long process(long x);
    }
}
