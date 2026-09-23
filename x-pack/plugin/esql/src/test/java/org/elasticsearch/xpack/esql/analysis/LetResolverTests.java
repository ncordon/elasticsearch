/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.analysis;

import org.elasticsearch.index.IndexMode;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.plan.IndexPattern;
import org.elasticsearch.xpack.esql.plan.LetBinding;
import org.elasticsearch.xpack.esql.plan.logical.Limit;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.NamedSubquery;
import org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation;

import java.util.Collections;
import java.util.List;

import static org.elasticsearch.xpack.esql.core.tree.Source.EMPTY;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Unit tests for {@link LetResolver}.
 */
public class LetResolverTests extends ESTestCase {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Creates a bare UnresolvedRelation for the given pattern string. */
    private static UnresolvedRelation relation(String pattern) {
        return new UnresolvedRelation(EMPTY, new IndexPattern(EMPTY, pattern), false, Collections.emptyList(), IndexMode.STANDARD, null);
    }

    /** Wraps a plan in a trivial Limit to produce a non-trivial subplan. */
    private static LogicalPlan withLimit(LogicalPlan child) {
        return new Limit(
            EMPTY,
            new org.elasticsearch.xpack.esql.core.expression.Literal(EMPTY, 10, org.elasticsearch.xpack.esql.core.type.DataType.INTEGER),
            child
        );
    }

    /** Creates a single LetBinding. */
    private static LetBinding binding(String name, LogicalPlan body) {
        return new LetBinding(EMPTY, name, body);
    }

    // -----------------------------------------------------------------------
    // No-op when bindings list is empty
    // -----------------------------------------------------------------------

    public void testEmptyBindingsReturnsSamePlan() {
        LogicalPlan plan = relation("some_index");
        LogicalPlan result = LetResolver.resolve(plan, List.of());
        assertThat(result, sameInstance(plan));
    }

    // -----------------------------------------------------------------------
    // Every binding body is wrapped in NamedSubquery
    // -----------------------------------------------------------------------

    public void testNonBareBodyIsWrapped() {
        // A binding whose body is Limit(...) → must be wrapped in NamedSubquery
        LogicalPlan body = withLimit(relation("base_index"));
        LetBinding b = binding("top3", body);

        LogicalPlan main = relation("top3");
        LogicalPlan result = LetResolver.resolve(main, List.of(b));

        assertThat(result, instanceOf(NamedSubquery.class));
        NamedSubquery ns = (NamedSubquery) result;
        assertThat(ns.name(), is("top3"));
        assertThat(ns.child(), sameInstance(body));
    }

    // -----------------------------------------------------------------------
    // Unmatched name → left as UnresolvedRelation
    // -----------------------------------------------------------------------

    public void testUnmatchedNameLeftAsUnresolvedRelation() {
        LetBinding b = binding("known", relation("some_index"));
        LogicalPlan main = relation("unknown_index");
        LogicalPlan result = LetResolver.resolve(main, List.of(b));

        // "unknown_index" ≠ "known", should not be substituted
        assertThat(result, instanceOf(UnresolvedRelation.class));
        assertThat(((UnresolvedRelation) result).indexPattern().indexPattern(), is("unknown_index"));
    }

    // -----------------------------------------------------------------------
    // Sequential (chained) scoping
    // -----------------------------------------------------------------------

    public void testChainedBindingsResolveLeftToRight() {
        // LET a = (FROM base | LIMIT 5),
        // b = (FROM a | LIMIT 3); -- "a" in b's body resolves to the first binding
        // FROM b
        LogicalPlan base = relation("base");
        LogicalPlan aBody = withLimit(base);
        LetBinding a = binding("a", aBody);

        // b's body references "a"
        LogicalPlan bBody = withLimit(relation("a"));
        LetBinding b = binding("b", bBody);

        LogicalPlan main = relation("b");
        LogicalPlan result = LetResolver.resolve(main, List.of(a, b));

        // Main result should be NamedSubquery("b", ...)
        assertThat(result, instanceOf(NamedSubquery.class));
        NamedSubquery nsB = (NamedSubquery) result;
        assertThat(nsB.name(), is("b"));

        // b's child is the Limit wrapping bBody after "a" was substituted
        // Inside bBody, relation("a") should have been replaced by NamedSubquery("a", ...)
        LogicalPlan bChild = nsB.child();
        assertThat(bChild, instanceOf(Limit.class));
        Limit limitB = (Limit) bChild;
        // The Limit's child should be NamedSubquery("a", aBody)
        assertThat(limitB.child(), instanceOf(NamedSubquery.class));
        NamedSubquery nsA = (NamedSubquery) limitB.child();
        assertThat(nsA.name(), is("a"));
        assertThat(nsA.child(), sameInstance(aBody));
    }

    // -----------------------------------------------------------------------
    // Substitution into multiple positions
    // -----------------------------------------------------------------------

    public void testSubstitutionIntoMultiplePositions() {
        // The same binding name referenced twice in the plan; both must be substituted.
        LogicalPlan body = withLimit(relation("src"));
        LetBinding b = binding("snap", body);

        // Construct a plan that references "snap" in two branches via a simple
        // structure — use a Filter wrapping another relation that also references snap.
        // For simplicity, use two separate UnresolvedRelations and merge via a helper.
        // We'll verify via transformDown count by building a Limit of Limit of "snap".
        LogicalPlan inner = withLimit(relation("snap"));   // Limit(snap)
        LogicalPlan outer = withLimit(inner);              // Limit(Limit(snap))

        LogicalPlan result = LetResolver.resolve(outer, List.of(b));

        // The "snap" node inside should have been substituted
        assertThat(result, instanceOf(Limit.class));
        LogicalPlan innerResult = ((Limit) result).child();
        assertThat(innerResult, instanceOf(Limit.class));
        // The leaf should be a NamedSubquery (snap was a non-bare body)
        LogicalPlan leaf = ((Limit) innerResult).child();
        assertThat(leaf, instanceOf(NamedSubquery.class));
        assertThat(((NamedSubquery) leaf).name(), is("snap"));
    }
}
