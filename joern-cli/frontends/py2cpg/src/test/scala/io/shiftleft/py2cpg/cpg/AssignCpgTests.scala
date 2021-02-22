package io.shiftleft.py2cpg.cpg

import io.shiftleft.codepropertygraph.generated.{DispatchTypes, Operators, nodes}
import io.shiftleft.py2cpg.Py2CpgTestContext
import io.shiftleft.semanticcpg.language._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AssignCpgTests extends AnyFreeSpec with Matchers {
  "single target assign" - {
    lazy val cpg = Py2CpgTestContext.buildCpg(
      """x = 2""".stripMargin
    )

    "test assignment node properties" in {
      val assignCall = cpg.call.methodFullName(Operators.assignment).head
      assignCall.code shouldBe "x = 2"
      assignCall.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH
      assignCall.lineNumber shouldBe Some(1)
      assignCall.columnNumber shouldBe Some(1)
    }

    "test assignment node ast children" in {
      cpg.call
        .methodFullName(Operators.assignment)
        .astChildren
        .order(1)
        .isIdentifier
        .head
        .code shouldBe "x"
      cpg.call
        .methodFullName(Operators.assignment)
        .astChildren
        .order(2)
        .isLiteral
        .head
        .code shouldBe "2"
    }

    "test assignment node arguments" in {
      cpg.call
        .methodFullName(Operators.assignment)
        .argument
        .argumentIndex(1)
        .isIdentifier
        .head
        .code shouldBe "x"
      cpg.call
        .methodFullName(Operators.assignment)
        .argument
        .argumentIndex(2)
        .isLiteral
        .head
        .code shouldBe "2"
    }
  }

  "nested multi target assign" - {
    // Multi target assign statements get lowered to a block with
    // a local variable for the right hand side and an assignment
    // inside the block for each element in the target list.
    lazy val cpg = Py2CpgTestContext.buildCpg(
      """x, (y, z) = list""".stripMargin
    )

    "test block exists" in {
      cpg.all.collect { case block: nodes.Block => block }.size shouldBe 1
    }

    "test block node properties" in {
      val block = cpg.all.collect { case block: nodes.Block => block }.head
      block.code shouldBe
        """tmp = list
          |x = tmp[0]
          |y = tmp[1][0]
          |z = tmp[1][1]""".stripMargin
      block.lineNumber shouldBe Some(1)
    }

    "test local node" in {
      val block = cpg.all.collect { case block: nodes.Block => block }.head
      block.astChildren.isLocal.head.code shouldBe "tmp"
    }

    "test tmp variable assignment" in {
      val block = cpg.all.collect { case block: nodes.Block => block }.head
      val tmpAssignNode = block.astChildren.isCall.sortBy(_.order).head
      tmpAssignNode.code shouldBe "tmp = list"
      tmpAssignNode.methodFullName shouldBe Operators.assignment
      tmpAssignNode.lineNumber shouldBe Some(1)
    }

    "test assignments to targets" in {
      val block = cpg.all.collect { case block: nodes.Block => block }.head
      val assignNodes = block.astChildren.isCall.sortBy(_.order).tail
      assignNodes.map(_.code) should contain theSameElementsInOrderAs List(
        "x = tmp[0]",
        "y = tmp[1][0]",
        "z = tmp[1][1]"
      )
      assignNodes.map(_.lineNumber.get) should contain theSameElementsInOrderAs List(1, 1, 1)
    }
  }

  "annotated assign" - {
    lazy val cpg = Py2CpgTestContext.buildCpg(
      """x: y = z""".stripMargin
    )

    "test assignment node properties" in {
      val assignCall = cpg.call.methodFullName(Operators.assignment).head
      assignCall.code shouldBe "x = z"
      assignCall.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH
      assignCall.lineNumber shouldBe Some(1)
      assignCall.columnNumber shouldBe Some(1)
    }

    "test assignment node ast children" in {
      cpg.call
        .methodFullName(Operators.assignment)
        .astChildren
        .order(1)
        .isIdentifier
        .head
        .code shouldBe "x"
      cpg.call
        .methodFullName(Operators.assignment)
        .astChildren
        .order(2)
        .isIdentifier
        .head
        .code shouldBe "z"
    }

    "test assignment node arguments" in {
      cpg.call
        .methodFullName(Operators.assignment)
        .argument
        .argumentIndex(1)
        .isIdentifier
        .head
        .code shouldBe "x"
      cpg.call
        .methodFullName(Operators.assignment)
        .argument
        .argumentIndex(2)
        .isIdentifier
        .head
        .code shouldBe "z"
    }
  }

  "annotated assign without value" - {
    lazy val cpg = Py2CpgTestContext.buildCpg(
      """x: y""".stripMargin
    )

    "test target expression node properties" in {
      val assignCall = cpg.identifier.name("x").head
      assignCall.code shouldBe "x"
      assignCall.lineNumber shouldBe Some(1)
      assignCall.columnNumber shouldBe Some(1)
    }
  }

  "augmented assign" - {
    lazy val cpg = Py2CpgTestContext.buildCpg(
      """x += y""".stripMargin
    )

    "test assignment node properties" in {
      val assignCall = cpg.call.methodFullName(Operators.assignmentPlus).head
      assignCall.code shouldBe "x += y"
      assignCall.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH
      assignCall.lineNumber shouldBe Some(1)
      assignCall.columnNumber shouldBe Some(1)
    }

    "test assignment node ast children" in {
      cpg.call
        .methodFullName(Operators.assignmentPlus)
        .astChildren
        .order(1)
        .isIdentifier
        .head
        .code shouldBe "x"
      cpg.call
        .methodFullName(Operators.assignmentPlus)
        .astChildren
        .order(2)
        .isIdentifier
        .head
        .code shouldBe "y"
    }

    "test assignment node arguments" in {
      cpg.call
        .methodFullName(Operators.assignmentPlus)
        .argument
        .argumentIndex(1)
        .isIdentifier
        .head
        .code shouldBe "x"
      cpg.call
        .methodFullName(Operators.assignmentPlus)
        .argument
        .argumentIndex(2)
        .isIdentifier
        .head
        .code shouldBe "y"
    }
  }
}
