/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter

import org.neo4j.cypher.internal.compiler.helpers.LogicalPlanBuilder
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.logical.plans.Argument
import org.neo4j.cypher.internal.logical.plans.ExpandInto
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.Selection
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class PruningVarExpanderTest extends CypherFunSuite with LogicalPlanningTestSupport {

  test("simplest possible query that can use PruningVarExpand") {
    // Simplest query:
    // match (a)-[*2..3]-(b) return distinct b

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .expand("(from)-[*2..3]-(to)")
      .allNodeScan("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .pruningVarExpand("(from)-[*2..3]-(to)")
      .allNodeScan("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("simplest possible query that can use BFSPruningVarExpand") {
    // Simplest query:
    // match (a)-[*1..3]->(b) return distinct b

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .expand("(from)-[*1..3]->(to)")
      .allNodeScan("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .bfsPruningVarExpand("(from)-[*1..3]->(to)")
      .allNodeScan("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("do use BFSPruningVarExpand for undirected search") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .expand("(from)-[*1..3]-(to)")
      .allNodeScan("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .bfsPruningVarExpand("(from)-[*1..3]-(to)")
      .allNodeScan("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("ordered distinct with pruningVarExpand") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("a"), "a AS a")
      .expandAll("(a)-[*2..3]->(b)")
      .allNodeScan("a")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("a"), "a AS a")
      .pruningVarExpand("(a)-[*2..3]->(b)")
      .allNodeScan("a")
      .build()

    rewrite(before) should equal(after)
  }

  test("ordered distinct with BFSPruningVarExpand") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("a"), "a AS a")
      .expandAll("(a)-[*1..3]->(b)")
      .allNodeScan("a")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .orderedDistinct(Seq("a"), "a AS a")
      .bfsPruningVarExpand("(a)-[*1..3]->(b)")
      .allNodeScan("a")
      .build()

    rewrite(before) should equal(after)
  }

  test("query with distinct aggregation") {
    // Simplest query:
    // match (from)-[2..3]-(to) return count(distinct to)

    val before = new LogicalPlanBuilder(wholePlan = false)
      .aggregation(Seq.empty, Seq("count(DISTINCT to) AS x"))
      .expand("(from)-[*2..3]-(to)")
      .allNodeScan("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .aggregation(Seq.empty, Seq("count(DISTINCT to) AS x"))
      .pruningVarExpand("(from)-[*2..3]-(to)")
      .allNodeScan("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("query with distinct aggregation and BFSPruningVarExpand") {
    // Simplest query:
    // match (from)<-[1..3]-(to) return count(distinct to)

    val before = new LogicalPlanBuilder(wholePlan = false)
      .aggregation(Seq.empty, Seq("count(DISTINCT to) AS x"))
      .expand("(from)<-[*1..3]-(to)")
      .allNodeScan("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .aggregation(Seq.empty, Seq("count(DISTINCT to) AS x"))
      .bfsPruningVarExpand("(from)<-[*1..3]-(to)")
      .allNodeScan("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("ordered grouping aggregation") {
    val before = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(Seq("a AS a"), Seq("count(distinct b) AS c"), Seq("a"))
      .expand("(a)-[*2..3]->(b)")
      .allNodeScan("a")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .orderedAggregation(Seq("a AS a"), Seq("count(distinct b) AS c"), Seq("a"))
      .pruningVarExpand("(a)-[*2..3]->(b)")
      .allNodeScan("a")
      .build()

    rewrite(before) should equal(after)
  }

  test("Simple query that filters between expand and distinct") {
    // Simplest query:
    // match (a)-[*2..3]->(b:X) return distinct b

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .filter("to:X")
      .expand("(from)-[*2..3]->(to)")
      .allNodeScan("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .filter("to:X")
      .pruningVarExpand("(from)-[*2..3]->(to)")
      .allNodeScan("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("Simple query that filters between expand and distinct and BFSPruningVarExpand") {
    // Simplest query:
    // match (a)-[*1..3]->(b:X) return distinct b

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .filter("to:X")
      .expand("(from)-[*1..3]->(to)")
      .allNodeScan("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .filter("to:X")
      .bfsPruningVarExpand("(from)-[*1..3]->(to)")
      .allNodeScan("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("Query that aggregates before making the result DISTINCT") {
    // Simplest query:
    // match (a)-[:R*1..3]-(b) with count(*) as count return distinct count

    val before = new LogicalPlanBuilder(wholePlan = false)
      .aggregation(Seq.empty, Seq("count(*) AS count"))
      .expand("(from)-[*1..3]-(to)")
      .allNodeScan("from")
      .build()

    assertNotRewritten(before)
  }

  test("Double var expand with distinct result") {
    // Simplest query:
    // match (a)-[:R*2..3]-(b)-[:T*2..3]-(c) return distinct c

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .expand("(b)-[:T*2..3]-(c)")
      .expand("(a)-[:R*2..3]-(b)")
      .allNodeScan("a")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .pruningVarExpand("(b)-[:T*2..3]-(c)")
      .pruningVarExpand("(a)-[:R*2..3]-(b)")
      .allNodeScan("a")
      .build()

    rewrite(before) should equal(after)
  }

  test("Double var expand with distinct result with BFSPruningVarExpand") {
    // Simplest query:
    // match (a)-[:R*1..3]->(b)-[:T*1..3]->(c) return distinct c

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .expand("(b)-[:T*1..3]->(c)")
      .expand("(a)-[:R*1..3]->(b)")
      .allNodeScan("a")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .bfsPruningVarExpand("(b)-[:T*1..3]->(c)")
      .bfsPruningVarExpand("(a)-[:R*1..3]->(b)")
      .allNodeScan("a")
      .build()

    rewrite(before) should equal(after)
  }

  test("var expand followed by normal expand") {
    // Simplest query:
    // match (a)-[:R*2..3]-(b)-[:T]-(c) return distinct c

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .expand("(b)-[:T]-(c)")
      .expand("(a)-[:R*2..3]-(b)")
      .allNodeScan("a")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .expand("(b)-[:T]-(c)")
      .pruningVarExpand("(a)-[:R*2..3]-(b)")
      .allNodeScan("a")
      .build()

    rewrite(before) should equal(after)
  }

  test("var expand followed by normal expand with BFSPruningVarExpand") {
    // Simplest query:
    // match (a)<-[:R*..3]-(b)<-[:T]-(c) return distinct c

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .expand("(b)<-[:T]-(c)")
      .expand("(a)<-[:R*1..3]-(b)")
      .allNodeScan("a")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .expand("(b)<-[:T]-(c)")
      .bfsPruningVarExpand("(a)<-[:R*1..3]-(b)")
      .allNodeScan("a")
      .build()

    rewrite(before) should equal(after)
  }

  test("optional match can be solved with PruningVarExpand") {
    // Simplest query:
    // match (a) optional match (a)-[:R*2..3]-(b) return distinct b

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("b AS b")
      .apply()
      .|.optional("a")
      .|.expand("(a)-[:R*2..3]-(b)")
      .|.argument("a")
      .allNodeScan("a")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("b AS b")
      .apply()
      .|.optional("a")
      .|.pruningVarExpand("(a)-[:R*2..3]-(b)")
      .|.argument("a")
      .allNodeScan("a")
      .build()

    rewrite(before) should equal(after)
  }

  test("optional match can be solved with PruningVarExpand with BFSPruningVarExpand") {
    // Simplest query:
    // match (a) optional match (a)-[:R*1..3]->(b) return distinct b

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("b AS b")
      .apply()
      .|.optional("a")
      .|.expand("(a)-[:R*1..3]->(b)")
      .|.argument("a")
      .allNodeScan("a")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("b AS b")
      .apply()
      .|.optional("a")
      .|.bfsPruningVarExpand("(a)-[:R*1..3]->(b)")
      .|.argument("a")
      .allNodeScan("a")
      .build()

    rewrite(before) should equal(after)
  }

  test("should not rewrite when doing non-distinct aggregation") {
    // Should not be rewritten since it's asking for a count of all paths leading to a node
    // match (a)-[*1..3]-(b) return b, count(*)

    val before = new LogicalPlanBuilder(wholePlan = false)
      .aggregation(Seq("to AS to"), Seq("count(*) AS count"))
      .expand("(from)-[*1..3]-(to)")
      .allNodeScan("from")
      .build()

    assertNotRewritten(before)
  }

  test("on longer var-lengths, we also use PruningVarExpand") {
    // Simplest query:
    // match (a)-[*4..5]-(b) return distinct b

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .expand("(from)-[*4..5]-(to)")
      .allNodeScan("from")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .pruningVarExpand("(from)-[*4..5]-(to)")
      .allNodeScan("from")
      .build()

    rewrite(before) should equal(after)
  }

  test("do not use pruning for length=1") {
    // Simplest query:
    // match (a)-[*1..1]-(b) return distinct b

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .expand("(from)-[*1..1]-(to)")
      .allNodeScan("from")
      .build()

    assertNotRewritten(before)
  }

  test("do not use pruning for pathExpressions when path is needed") {
    // Simplest query:
    // match p=(from)-[r*0..2]-(to) with nodes(p) as d return distinct d

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("d AS d")
      .projection("nodes(path) AS d")
      .projection(Map("path" -> path(varFor("a"), varFor("r"), varFor("b"), SemanticDirection.BOTH)))
      .expand("(from)-[r*0..2]-(to)")
      .allNodeScan("from")
      .build()

    assertNotRewritten(before)
  }

  test("do not use pruning-varexpand when both sides of the var-length-relationship are already known") {

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("to AS to")
      .expand("(from)-[*1..3]-(to)", expandMode = ExpandInto)
      .cartesianProduct()
      .|.allNodeScan("to")
      .allNodeScan("from")
      .build()

    assertNotRewritten(before)
  }

  test("should handle insanely long logical plans without running out of stack") {
    val leafPlan: LogicalPlan = Argument(Set("x"))
    var plan = leafPlan
    (1 until 10000) foreach { _ =>
      plan = Selection(Seq(trueLiteral), plan)
    }

    rewrite(plan) // should not throw exception
  }

  test("cartesian product can be solved with BFSPruningVarExpand") {
    // Simplest query:
    // match (a) match (b)-[:R*1..3]-(c) return distinct c

    val before = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .cartesianProduct()
      .|.allNodeScan("a")
      .expand("(b)-[:R*1..3]-(c)")
      .allNodeScan("b")
      .build()

    val after = new LogicalPlanBuilder(wholePlan = false)
      .distinct("c AS c")
      .cartesianProduct()
      .|.allNodeScan("a")
      .bfsPruningVarExpand("(b)-[:R*1..3]-(c)")
      .allNodeScan("b")
      .build()

    rewrite(before) should equal(after)
  }

  test("do not use pruning-varexpand when upper bound < lower bound") {
    val plan = new LogicalPlanBuilder(wholePlan = false)
      .distinct("a AS a")
      .expandAll("(a)-[r*3..2]->(b)")
      .allNodeScan("a")
      .build()

    assertNotRewritten(plan)
  }

  private def assertNotRewritten(p: LogicalPlan): Unit = {
    rewrite(p) should equal(p)
  }

  private def rewrite(p: LogicalPlan): LogicalPlan =
    p.endoRewrite(pruningVarExpander)
}
