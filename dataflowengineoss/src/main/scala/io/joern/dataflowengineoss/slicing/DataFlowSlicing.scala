package io.joern.dataflowengineoss.slicing

import io.joern.dataflowengineoss.language._
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.PropertyNames
import io.shiftleft.codepropertygraph.generated.nodes._
import io.shiftleft.semanticcpg.language._

object DataFlowSlicing {

  def calculateDataFlowSlice(cpg: Cpg, config: DataFlowConfig): Option[DataFlowSlice] = {
    (config.fileFilter match {
      case Some(fileName) => cpg.file.nameExact(fileName).ast.isCall
      case None           => cpg.call
    }).toBuffer
      .map { (c: Call) =>
        val sinks = c.argument.l

        val sliceNodes = sinks.iterator.repeat(_.ddgIn)(_.maxDepth(config.sliceDepth).emit).dedup.l
        val sliceEdges = sliceNodes
          .flatMap(_.outE)
          .filter(x => sliceNodes.contains(x.inNode()))
          .map { e => SliceEdge(e.outNode().id(), e.inNode().id(), e.label()) }
          .toSet
        val methodToNodes = sliceNodes.groupBy(_.method).map { case (m, ns) => m.fullName -> ns.map(_.id()).toSet }
        DataFlowSlice(sliceNodes.map(cfgNodeToSliceNode).toSet, sliceEdges, methodToNodes)
      }
      .reduceOption { (a, b) =>
        val methodToChildNode = (a.methodToChildNode.keys ++ b.methodToChildNode.keys)
          .map(k => k -> (a.methodToChildNode.getOrElse(k, Set.empty) ++ b.methodToChildNode.getOrElse(k, Set.empty)))
        DataFlowSlice(a.nodes ++ b.nodes, a.edges ++ b.edges, methodToChildNode.toMap)
      }
  }

  private def cfgNodeToSliceNode(cfgNode: CfgNode): SliceNode = {
    val sliceNode = SliceNode(
      cfgNode.id(),
      cfgNode.label,
      code = cfgNode.code,
      lineNumber = cfgNode.lineNumber.getOrElse(-1),
      columnNumber = cfgNode.columnNumber.getOrElse(-1)
    )
    cfgNode match {
      case n: Method    => sliceNode.copy(name = n.name, typeFullName = n.methodReturn.typeFullName)
      case n: Return    => sliceNode.copy(name = "RET", typeFullName = n.method.methodReturn.typeFullName)
      case n: MethodRef => sliceNode.copy(name = n.methodFullName, code = n.code)
      case n: TypeRef   => sliceNode.copy(name = n.typeFullName, code = n.code)
      case n =>
        sliceNode.copy(
          name = n.property(PropertyNames.NAME, ""),
          typeFullName = n.property(PropertyNames.TYPE_FULL_NAME, "")
        )
    }
  }

}
