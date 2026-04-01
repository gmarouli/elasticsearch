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
 * Clone of {@link SortedNumericDocValues} that allows to iterate over double and long values.
 */
public interface IterableSortedNumericValues {

    /** Advance the iterator to exactly {@code target} and return whether
     *  {@code target} has a value.
     *  {@code target} must be greater than or equal to the current
     *  doc ID and must be a valid doc ID, ie. &ge; 0 and
     *  &lt; {@code maxDoc}.*/
    boolean advanceExact(int target) throws IOException;

    /**
     * Iterates to the next value in the current document. Do not call this more than
     * {@link #docValueCount} times for the document.
     */
    double nextDoubleValue() throws IOException;

    /**
     * Iterates to the next value in the current document. Do not call this more than
     * {@link #docValueCount} times for the document.
     */
    long nextLongValue() throws IOException;

    /**
     * Retrieves the number of values for the current document.  This must always
     * be greater than zero.
     * It is illegal to call this method after {@link #advanceExact(int)}
     * returned {@code false}.
     */
    int docValueCount();

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
    int advance(int target) throws IOException;

    /**
     * Returns the following
     *  {@code -1}, if {@link #nextDoubleValue()}, {@link #nextLongValue()}, {@link #advanceExact(int)}
     *  or {@link #advance(int)} were not called yet.
     *  {@link org.apache.lucene.search.DocIdSetIterator#NO_MORE_DOCS} if the iterator has exhausted.
     *  otherwise, the doc ID it is currently on.
     */
    int docID();
}
