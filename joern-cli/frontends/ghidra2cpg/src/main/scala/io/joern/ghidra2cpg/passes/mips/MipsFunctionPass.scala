package io.joern.ghidra2cpg.passes.mips
import ghidra.program.model.listing.{Function, Instruction, Program}
import ghidra.program.model.pcode.{HighFunction, PcodeOpAST}
import io.joern.ghidra2cpg.Decompiler
import io.joern.ghidra2cpg.passes.FunctionPass
import io.joern.ghidra2cpg.processors.MipsProcessor
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.CfgNodeNew

import scala.jdk.CollectionConverters._
import scala.language.implicitConversions

class MipsFunctionPass(
  currentProgram: Program,
  fileName: String,
  functions: List[Function],
  cpg: Cpg,
  decompiler: Decompiler
) extends FunctionPass(new MipsProcessor, currentProgram, fileName, functions, cpg, decompiler) {

  override def addCallArguments(
    diffGraphBuilder: DiffGraphBuilder,
    instruction: Instruction,
    callNode: CfgNodeNew,
    highFunction: HighFunction
  ): Unit = {
    val opCodes: Seq[PcodeOpAST] = highFunction
      .getPcodeOps(instruction.getAddress())
      .asScala
      .toList
    if (opCodes.size < 2) {
      return
    }
    // first input is the address to the called function
    // we know it already
    val arguments = opCodes.head.getInputs.toList.drop(1)
    arguments.zipWithIndex.foreach { case (value, index) =>
      if (value.getDef != null)
        resolveArgument(diffGraphBuilder, instruction, callNode, value.getDef, index)
    }
  }
}
