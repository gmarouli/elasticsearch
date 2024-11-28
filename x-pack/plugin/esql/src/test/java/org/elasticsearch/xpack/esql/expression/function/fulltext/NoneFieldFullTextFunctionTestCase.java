/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.fulltext;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.AbstractFunctionTestCase;
import org.elasticsearch.xpack.esql.expression.function.TestCaseSupplier;
import org.hamcrest.Matcher;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.equalTo;

public abstract class NoneFieldFullTextFunctionTestCase extends AbstractFunctionTestCase {

    public NoneFieldFullTextFunctionTestCase(Supplier<TestCaseSupplier.TestCase> testCaseSupplier) {
        this.testCase = testCaseSupplier.get();
    }

    public final void testFold() {
        Expression expression = buildLiteralExpression(testCase);
        if (testCase.getExpectedTypeError() != null) {
            assertTypeResolutionFailure(expression);
            return;
        }
        assertFalse("expected resolved", expression.typeResolved().unresolved());
    }

    protected static Iterable<Object[]> generateParameters() {
        List<TestCaseSupplier> suppliers = new LinkedList<>();
        for (DataType strType : DataType.stringTypes()) {
            suppliers.add(
                new TestCaseSupplier(
                    "<" + strType + ">",
                    List.of(strType),
                    () -> testCase(strType, randomAlphaOfLengthBetween(1, 10), equalTo(true))
                )
            );
        }
        List<TestCaseSupplier> errorsSuppliers = errorsForCasesWithoutExamples(suppliers, (v, p) -> "string");
        // Don't test null, as it is not allowed but the expected message is not a type error - so we check it separately in VerifierTests
        return parameterSuppliersFromTypedData(errorsSuppliers.stream().filter(s -> s.types().contains(DataType.NULL) == false).toList());
    }

    private static TestCaseSupplier.TestCase testCase(DataType strType, String str, Matcher<Boolean> matcher) {
        return new TestCaseSupplier.TestCase(
            List.of(new TestCaseSupplier.TypedData(new BytesRef(str), strType, "query")),
            "EndsWithEvaluator[str=Attribute[channel=0], suffix=Attribute[channel=1]]",
            DataType.BOOLEAN,
            matcher
        );
    }
}
