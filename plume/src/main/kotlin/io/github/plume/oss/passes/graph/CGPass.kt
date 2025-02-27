/*
 * Copyright 2021 Plume Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.plume.oss.passes.graph

import io.github.plume.oss.domain.model.DeltaGraph
import io.github.plume.oss.drivers.IDriver
import io.github.plume.oss.passes.IUnitGraphPass
import io.github.plume.oss.store.PlumeStorage
import io.github.plume.oss.util.SootToPlumeUtil
import io.shiftleft.codepropertygraph.generated.EdgeTypes.CALL
import io.shiftleft.codepropertygraph.generated.NodeTypes.METHOD
import io.shiftleft.codepropertygraph.generated.PropertyNames.FULL_NAME
import io.shiftleft.codepropertygraph.generated.nodes.NewMethodBuilder
import org.apache.logging.log4j.LogManager
import soot.Scene
import soot.Unit
import soot.jimple.AssignStmt
import soot.jimple.InvokeExpr
import soot.jimple.InvokeStmt
import soot.jimple.toolkits.callgraph.Edge
import soot.toolkits.graph.BriefUnitGraph

/**
 * The [IUnitGraphPass] that constructs the interprocedural call edges.
 *
 * @param g The driver to build the call edges with.
 * @param driver The driver to build the call edges with.
 */
class CGPass(private val g: BriefUnitGraph, private val driver: IDriver) : IUnitGraphPass {

    private val logger = LogManager.getLogger(CGPass::javaClass)
    private val builder = DeltaGraph.Builder()

    override fun runPass(): DeltaGraph {
        try {
            val mtd = g.body.method
            logger.trace("Building call graph edges for ${mtd.declaringClass.name}:${mtd.name}")
            // If this was an updated method, connect call graphs
            val (fullName, _, _) = SootToPlumeUtil.methodToStrings(mtd)
            getMethodHead(fullName)?.let { reconnectPriorCallGraphEdges(it) }
            // Connect all calls to their methods
            this.g.body.units.forEach(this::projectUnit)
        } catch (e: Exception) {
            logger.warn("Unable to complete CGPass on ${g.body.method.name}. Partial changes will be saved.", e)
        }
        return builder.build()
    }

    private fun projectUnit(unit: Unit) {
        // If Soot points to the assignment as the call source then this is most likely from one of the children
        val srcUnit = if (unit is AssignStmt) unit.rightOp else unit
        when (srcUnit) {
            is InvokeExpr -> PlumeStorage.getCall(srcUnit)
            is InvokeStmt -> PlumeStorage.getCall(srcUnit.invokeExpr)
            else -> null
        }?.let { callV ->
            if (!driver.exists(callV)) return
            Scene.v().callGraph.edgesOutOf(unit).forEach { e: Edge ->
                val (fullName, _, _) = SootToPlumeUtil.methodToStrings(e.tgt.method())
                getMethodHead(fullName)?.let { tgtPlumeVertex -> builder.addEdge(callV, tgtPlumeVertex, CALL) }
            }
        }
    }

    private fun getMethodHead(fullName: String): NewMethodBuilder? =
        PlumeStorage.getMethod(fullName)
            ?: (driver.getVerticesByProperty(FULL_NAME, fullName, METHOD).firstOrNull() as NewMethodBuilder?)
                ?.apply { PlumeStorage.addMethod(this) }

    private fun reconnectPriorCallGraphEdges(mtdV: NewMethodBuilder) {
        val mtd = mtdV.build()
        PlumeStorage.getCallsIn(mtd.fullName()).let { incomingVs ->
            if (incomingVs.isNotEmpty()) {
                logger.debug("Saved call graph edges found - reconnecting incoming call graph edges")
                incomingVs.forEach { inV -> if (driver.exists(inV)) builder.addEdge(inV, mtdV, CALL) }
            } else {
                logger.trace("No previous call graph edges were found")
            }
        }
    }
}