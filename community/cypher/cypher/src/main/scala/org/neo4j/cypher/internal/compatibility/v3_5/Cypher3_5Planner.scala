/*
 * Copyright (c) 2002-2019 "Neo4j,"
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
package org.neo4j.cypher.internal.compatibility.v3_5

import java.time.Clock
import java.util.function.BiFunction

import org.neo4j.cypher.exceptionHandler.runSafely
import org.neo4j.cypher.internal.compatibility._
import org.neo4j.cypher.internal.compatibility.notification.LogicalPlanNotifications
import org.neo4j.cypher.internal.compiler.phases.PlannerContext
import org.neo4j.cypher.internal.compiler.planner.logical.{CachedMetricsFactory, SimpleMetricsFactory, simpleExpressionEvaluator}
import org.neo4j.cypher.internal.compiler.{CypherPlanner => _, _}
import org.neo4j.cypher.internal.logical.plans.{LoadCSV, LogicalPlan}
import org.neo4j.cypher.internal.runtime.interpreted._
import org.neo4j.cypher.internal.spi.{ExceptionTranslatingPlanContext, TransactionBoundPlanContext}
import org.neo4j.cypher.internal.v4_0.ast.Statement
import org.neo4j.cypher.internal.v4_0.expressions.Parameter
import org.neo4j.cypher.internal.v4_0.frontend.PlannerName
import org.neo4j.cypher.internal.v4_0.frontend.phases.{BaseState, CompilationPhaseTracer, InternalNotificationLogger, RecordingNotificationLogger}
import org.neo4j.cypher.internal.v4_0.rewriting.rewriters.{GeneratingNamer, InnerVariableNamer}
import org.neo4j.cypher.internal.v4_0.util.InputPosition
import org.neo4j.cypher.internal.v4_0.util.attribution.SequentialIdGen
import org.neo4j.cypher.internal.{compiler, _}
import org.neo4j.cypher.{CypherPlannerOption, CypherUpdateStrategy}
import org.neo4j.internal.helpers.collection.Pair
import org.neo4j.kernel.impl.query.TransactionalContext
import org.neo4j.logging.Log
import org.neo4j.monitoring.Monitors
import org.neo4j.values.AnyValue
import org.neo4j.values.virtual.MapValue

case class Cypher3_5Planner(config: CypherPlannerConfiguration,
                            clock: Clock,
                            kernelMonitors: Monitors,
                            log: Log,
                            plannerOption: CypherPlannerOption,
                            updateStrategy: CypherUpdateStrategy,
                            txIdProvider: () => Long)
  extends BasePlanner[Statement, BaseState](config, clock, kernelMonitors, log, plannerOption, updateStrategy, txIdProvider) with CypherPlanner {

  monitors.addMonitorListener(logStalePlanRemovalMonitor(logger), "cypher4.0")//cypher3.5?

  override def parseAndPlan(preParsedQuery: PreParsedQuery,
                            tracer: CompilationPhaseTracer,
                            transactionalContext: TransactionalContext,
                            params: MapValue,
                            runtime: CypherRuntime[_]
                           ): LogicalPlanResult = {

    // TODO use 3.5 specific parser

    runSafely {
      val notificationLogger = new RecordingNotificationLogger(Some(preParsedQuery.offset))
      val innerVariableNamer = new GeneratingNamer

      val syntacticQuery =
        getOrParse(preParsedQuery, new Parser3_5(planner, notificationLogger, preParsedQuery.offset, tracer, innerVariableNamer))

      val transactionalContextWrapper = TransactionalContextWrapper(transactionalContext)
      // Context used for db communication during planning
      val planContext = new ExceptionTranslatingPlanContext(TransactionBoundPlanContext(
        transactionalContextWrapper, notificationLogger))

      // Context used to create logical plans
      val logicalPlanIdGen = new SequentialIdGen()
      val context = contextCreator.create(tracer,
        notificationLogger,
        planContext,
        syntacticQuery.queryText,
        preParsedQuery.debugOptions,
        Some(preParsedQuery.offset),
        monitors,
        CachedMetricsFactory(SimpleMetricsFactory),
        createQueryGraphSolver(),
        config,
        maybeUpdateStrategy.getOrElse(defaultUpdateStrategy),
        clock,
        logicalPlanIdGen,
        simpleExpressionEvaluator,
        innerVariableNamer)

      // Prepare query for caching
      val preparedQuery = planner.normalizeQuery(syntacticQuery, context)
      val queryParamNames: Seq[String] = preparedQuery.statement().findByAllClass[Parameter].map(x => x.name).distinct
      checkForSchemaChanges(transactionalContextWrapper)

      // If the query is not cached we do full planning + creating of executable plan
      def createPlan(shouldBeCached: Boolean, missingParameterNames: Seq[String] = Seq.empty): CacheableLogicalPlan = {
        val logicalPlanStateOld = planner.planPreparedQuery(preparedQuery, context)
        val hasLoadCsv = logicalPlanStateOld.logicalPlan.treeFind[LogicalPlan] {
          case _: LoadCSV => true
        }.nonEmpty
        val logicalPlanState = logicalPlanStateOld.copy(hasLoadCSV = hasLoadCsv)
        LogicalPlanNotifications
          .checkForNotifications(logicalPlanState.maybeLogicalPlan.get, planContext, config)
          .foreach(notificationLogger.log)
        if (missingParameterNames.nonEmpty) {
          notificationLogger.log(MissingParametersNotification(missingParameterNames))
        }

        val reusabilityState = if (ProcedureCallOrSchemaCommandRuntime.isApplicable(logicalPlanState))
          FineToReuse
        else {
          val fingerprint = PlanFingerprint.take(clock, planContext.txIdProvider, planContext.statistics)
          val fingerprintReference = new PlanFingerprintReference(fingerprint)
          MaybeReusable(fingerprintReference)
        }
        CacheableLogicalPlan(logicalPlanState, reusabilityState, notificationLogger.notifications, shouldBeCached)
      }

      val autoExtractParams = ValueConversion.asValues(preparedQuery.extractedParams()) // only extracted ones
      // Filter the parameters to retain only those that are actually used in the query (or a subset of them, if not enough
      // parameters where given in the first place)
      val filteredParams: MapValue = params.updatedWith(autoExtractParams).filter(new BiFunction[String, AnyValue, java.lang.Boolean] {
        override def apply(name: String, value: AnyValue): java.lang.Boolean = queryParamNames.contains(name)
      })

      val enoughParametersSupplied = queryParamNames.size == filteredParams.size // this is relevant if the query has parameters

      val cacheableLogicalPlan =
        // We don't want to cache any query without enough given parameters (although EXPLAIN queries will succeed)
        if (preParsedQuery.debugOptions.isEmpty && (queryParamNames.isEmpty || enoughParametersSupplied))
          planCache.computeIfAbsentOrStale(Pair.of(syntacticQuery.statement(), QueryCache.extractParameterTypeMap(filteredParams)),
            transactionalContext,
            () => createPlan(shouldBeCached = true),
            _ => None,
            syntacticQuery.queryText).executableQuery

        else if (!enoughParametersSupplied)
          createPlan(shouldBeCached = false, missingParameterNames = queryParamNames.filterNot(filteredParams.containsKey))
        else
          createPlan(shouldBeCached = false)

      LogicalPlanResult(
        cacheableLogicalPlan.logicalPlanState,
        queryParamNames,
        autoExtractParams,
        cacheableLogicalPlan.reusability,
        context,
        cacheableLogicalPlan.notifications,
        cacheableLogicalPlan.shouldBeCached)
    }
  }

  override val name: PlannerName = plannerName
}

private[v3_5] class Parser3_5(planner: compiler.CypherPlanner[PlannerContext],
                              notificationLogger: InternalNotificationLogger,
                              offset: InputPosition,
                              tracer: CompilationPhaseTracer,
                              innerVariableNamer: InnerVariableNamer
                             ) extends Parser[BaseState] {
  // TODO use 3.5 specific things?
  override def parse(preParsedQuery: PreParsedQuery): BaseState = {
    planner.parseQuery(preParsedQuery.statement,
      preParsedQuery.rawStatement,
      notificationLogger,
      preParsedQuery.planner.name,
      preParsedQuery.debugOptions,
      Some(offset),
      tracer,
      innerVariableNamer)
  }
}