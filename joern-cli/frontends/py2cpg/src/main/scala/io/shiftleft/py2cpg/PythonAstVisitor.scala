package io.shiftleft.py2cpg

import io.shiftleft.codepropertygraph.generated.nodes.NewNode
import io.shiftleft.codepropertygraph.generated.{DispatchTypes, ModifierTypes, Operators, nodes}
import io.shiftleft.passes.DiffGraph
import io.shiftleft.py2cpg.memop.{
  AstNodeToMemoryOperationMap,
  MemoryOperation,
  MemoryOperationCalculator,
  Store,
  Load,
}
import io.shiftleft.pythonparser.AstVisitor
import io.shiftleft.pythonparser.ast
import io.shiftleft.semanticcpg.language.toMethodForCallGraph

import scala.collection.mutable

class PythonAstVisitor(fileName: String) extends PythonAstVisitorHelpers {

  private val diffGraph = new DiffGraph.Builder()
  protected val nodeBuilder = new NodeBuilder(diffGraph)
  protected val edgeBuilder = new EdgeBuilder(diffGraph)

  protected val contextStack = new ContextStack()

  private var memOpMap: AstNodeToMemoryOperationMap = _

  def getDiffGraph: DiffGraph = {
    diffGraph.build()
  }

  private def createIdentifierLinks(): Unit = {
    contextStack.createIdentifierLinks(
      nodeBuilder.localNode,
      nodeBuilder.closureBindingNode,
      edgeBuilder.astEdge,
      edgeBuilder.refEdge,
      edgeBuilder.captureEdge
    )
  }

  def convert(astNode: ast.iast): NewNode = {
    astNode match {
      case module: ast.Module => convert(module)
    }
  }

  def convert(mod: ast.imod): NewNode = {
    mod match {
      case node: ast.Module => convert(node)
    }
  }

  // Entry method for the visitor.
  def convert(module: ast.Module): NewNode = {
    val memOpCalculator = new MemoryOperationCalculator()
    module.accept(memOpCalculator)
    memOpMap = memOpCalculator.astNodeToMemOp

    val fileNode = nodeBuilder.fileNode(fileName)
    val namespaceBlockNode = nodeBuilder.namespaceBlockNode(fileName)
    edgeBuilder.astEdge(namespaceBlockNode, fileNode, 1)
    contextStack.setFileNamespaceBlock(namespaceBlockNode)

    val methodFullName = calcMethodFullNameFromContext("module")

    val moduleMethodNode =
      createMethod(
        "module",
        methodFullName,
        (_: nodes.NewMethod) => (),
        body = module.stmts,
        decoratorList = Nil,
        returns = None,
        isAsync = false,
        methodRefNode = None,
        LineAndColumn(1, 1)
      )

    createIdentifierLinks()

    moduleMethodNode
  }

  private def unhandled(node: ast.iast with ast.iattributes): NewNode = {
    val unhandledAsUnknown = true
    if (unhandledAsUnknown) {
      nodeBuilder.unknownNode(node.toString, node.getClass.getName, lineAndColOf(node))
    } else {
      throw new NotImplementedError()
    }
  }

  private def convert(stmt: ast.istmt): NewNode = {
    stmt match {
      case node: ast.FunctionDef => convert(node)
      case node: ast.AsyncFunctionDef => convert(node)
      case node: ast.ClassDef => unhandled(node)
      case node: ast.Return => convert(node)
      case node: ast.Delete => convert(node)
      case node: ast.Assign => convert(node)
      case node: ast.AnnAssign => convert(node)
      case node: ast.AugAssign => convert(node)
      case node: ast.For => unhandled(node)
      case node: ast.AsyncFor => unhandled(node)
      case node: ast.While => convert(node)
      case node: ast.If => convert(node)
      case node: ast.With => unhandled(node)
      case node: ast.AsyncWith => unhandled(node)
      case node: ast.Raise => unhandled(node)
      case node: ast.Try => unhandled(node)
      case node: ast.Assert => unhandled(node)
      case node: ast.Import => unhandled(node)
      case node: ast.ImportFrom => unhandled(node)
      case node: ast.Global => convert(node)
      case node: ast.Nonlocal => convert(node)
      case node: ast.Expr => convert(node)
      case node: ast.Pass => convert(node)
      case node: ast.Break => convert(node)
      case node: ast.Continue => convert(node)
      case node: ast.RaiseP2 => unhandled(node)
      case node: ast.ErrorStatement => unhandled(node)
    }
  }

  def convert(functionDef: ast.FunctionDef): NewNode = {
    // TODO create local variable with same name as functionDef and assign the method reference
    // to it.
    createMethodAndMethodRef(
      functionDef.name,
      createParameterProcessingFunction(functionDef.args.asInstanceOf[ast.Arguments]),
      functionDef.body,
      functionDef.decorator_list,
      functionDef.returns,
      isAsync = false,
      lineAndColOf(functionDef)
    )
  }

  def convert(functionDef: ast.AsyncFunctionDef): NewNode = {
    // TODO create local variable with same name as functionDef and assign the method reference
    // to it.
    createMethodAndMethodRef(
      functionDef.name,
      createParameterProcessingFunction(functionDef.args.asInstanceOf[ast.Arguments]),
      functionDef.body,
      functionDef.decorator_list,
      functionDef.returns,
      isAsync = true,
      lineAndColOf(functionDef)
    )
  }

  private def createParameterProcessingFunction(
      parameters: ast.Arguments
  )(methodNode: nodes.NewMethod): Unit = {
    val parameterOrder = if (contextStack.isClassContext) {
      new AutoIncIndex(0)
    } else {
      new AutoIncIndex(1)
    }
    parameters.posonlyargs.map(convert).foreach { parameterNode =>
      contextStack.addParameter(parameterNode.asInstanceOf[nodes.NewMethodParameterIn])
      edgeBuilder.astEdge(parameterNode, methodNode, parameterOrder.getAndInc)
    }
    parameters.args.map(convert).foreach { parameterNode =>
      contextStack.addParameter(parameterNode.asInstanceOf[nodes.NewMethodParameterIn])
      edgeBuilder.astEdge(parameterNode, methodNode, parameterOrder.getAndInc)
    }
    // TODO implement non position arguments and vararg.
  }

  private def createMethodAndMethodRef(
      methodName: String,
      parameterProcessing: nodes.NewMethod => Unit,
      body: Iterable[ast.istmt],
      decoratorList: Iterable[ast.iexpr],
      returns: Option[ast.iexpr],
      isAsync: Boolean,
      lineAndColumn: LineAndColumn
  ): nodes.NewMethodRef = {
    val methodFullName = calcMethodFullNameFromContext(methodName)

    val methodRefNode = nodeBuilder.methodRefNode(methodName, methodFullName, lineAndColumn)

    val methodNode =
      createMethod(
        methodName,
        methodFullName,
        parameterProcessing,
        body,
        decoratorList,
        returns,
        isAsync = true,
        Some(methodRefNode),
        lineAndColumn
      )

    val typeNode = nodeBuilder.typeNode(methodName, methodFullName)
    val typeDeclNode = nodeBuilder.typeDeclNode(methodName, methodFullName)
    edgeBuilder.astEdge(typeDeclNode, contextStack.astParent, contextStack.order.getAndInc)

    createBinding(methodNode, typeDeclNode)

    methodRefNode
  }

  private def createMethod(
      name: String,
      fullName: String,
      parameterProcessing: nodes.NewMethod => Unit,
      body: Iterable[ast.istmt],
      decoratorList: Iterable[ast.iexpr],
      returns: Option[ast.iexpr],
      isAsync: Boolean,
      methodRefNode: Option[nodes.NewMethodRef],
      lineAndColumn: LineAndColumn
  ): nodes.NewMethod = {
    val methodNode = nodeBuilder.methodNode(name, fullName, lineAndColumn)
    edgeBuilder.astEdge(methodNode, contextStack.astParent, contextStack.order.getAndInc)

    val blockNode = nodeBuilder.blockNode("", lineAndColumn)
    edgeBuilder.astEdge(blockNode, methodNode, 1)

    contextStack.pushMethod(name, methodNode, blockNode, methodRefNode)

    val virtualModifierNode = nodeBuilder.modifierNode(ModifierTypes.VIRTUAL)
    edgeBuilder.astEdge(virtualModifierNode, methodNode, 0)

    parameterProcessing(methodNode)

    val methodReturnNode = nodeBuilder.methodReturnNode(lineAndColumn)
    edgeBuilder.astEdge(methodReturnNode, methodNode, 2)

    val bodyOrder = new AutoIncIndex(1)
    body.map(convert).foreach { bodyStmt =>
      edgeBuilder.astEdge(bodyStmt, blockNode, bodyOrder.getAndInc)
    }

    contextStack.pop()
    methodNode
  }

  def convert(classDef: ast.ClassDef): NewNode = ???

  def convert(ret: ast.Return): NewNode = {
    ret.value match {
      case Some(value) =>
        val valueNode = convert(value)
        val code = "return " + codeOf(valueNode)
        val returnNode = nodeBuilder.returnNode(code, lineAndColOf(ret))

        addAstChildrenAsArguments(returnNode, 1, valueNode)
        returnNode
      case None =>
        nodeBuilder.returnNode("return", lineAndColOf(ret))
    }
  }

  def convert(delete: ast.Delete): NewNode = {
    val deleteArgs = delete.targets.map(convert)

    val code = "del " + deleteArgs.map(codeOf).mkString(", ")
    val callNode = nodeBuilder.callNode(
      code,
      "<operator>.delete",
      DispatchTypes.STATIC_DISPATCH,
      lineAndColOf(delete)
    )

    addAstChildrenAsArguments(callNode, 1, deleteArgs)
    callNode
  }

  def convert(assign: ast.Assign): nodes.NewNode = {
    if (assign.targets.size == 1) {
      val target = assign.targets.head
      val targetWithAccessChains = getTargetsWithAccessChains(target)
      if (targetWithAccessChains.size == 1) {
        // Case with single entity one the left hand side.
        // We always have an empty acces chain in this case.
        val valueNode = convert(assign.value)
        val targetNode = convert(target)

        createAssignment(targetNode, valueNode, lineAndColOf(assign))
      } else {
        // Case with a tuple of entities on the left hand side.
        // Lowering of x, (y,z) = a:
        //   {
        //     tmp = a
        //     x = tmp[0]
        //     y = tmp[1][0]
        //     z = tmp[1][1]
        //   }
        val valueNode = convert(assign.value)
        val tmpVariableName = getUnusedName()

        val tmpIdentifierNode =
          createIdentifierNode(tmpVariableName, Store, lineAndColOf(assign))
        val tmpVariableAssignNode =
          createAssignment(tmpIdentifierNode, valueNode, lineAndColOf(assign))

        val targetAssignNodes =
          targetWithAccessChains.map { case (target, accessChain) =>
            val targetNode = convert(target)
            val tmpIdentifierNode =
              createIdentifierNode(tmpVariableName, Load, lineAndColOf(assign))
            val indexTmpIdentifierNode = createIndexAccessChain(
              tmpIdentifierNode,
              accessChain,
              lineAndColOf(assign)
            )

            createAssignment(
              targetNode,
              indexTmpIdentifierNode,
              lineAndColOf(assign)
            )
          }

        val blockNode =
          createBlock(
            tmpVariableAssignNode :: targetAssignNodes.toList,
            lineAndColOf(assign)
          )

        blockNode
      }
    } else {
      throw new RuntimeException("Unexpected assign with more than one target.")
    }
  }

  // TODO for now we ignore the annotation part and just emit the pure
  // assignment.
  def convert(annotatedAssign: ast.AnnAssign): NewNode = {
    val targetNode = convert(annotatedAssign.target)

    annotatedAssign.value match {
      case Some(value) =>
        val valueNode = convert(value)
        createAssignment(targetNode, valueNode, lineAndColOf(annotatedAssign))
      case None =>
        // If there is no value, this is just an expr: annotation and since
        // we for now ignore the annotation we emit just the expr because
        // it may have side effects.
        targetNode
    }
  }

  def convert(augAssign: ast.AugAssign): NewNode = {
    val targetNode = convert(augAssign.target)
    val valueNode = convert(augAssign.value)

    val (operatorCode, operatorFullName) =
      augAssign.op match {
        case ast.Add  => ("+=", Operators.assignmentPlus)
        case ast.Sub  => ("-=", Operators.assignmentMinus)
        case ast.Mult => ("*=", Operators.assignmentMultiplication)
        case ast.MatMult =>
          ("@=", "<operator>.assignmentMatMult") // TODO make this a define and add policy for this
        case ast.Div    => ("/=", Operators.assignmentDivision)
        case ast.Mod    => ("%=", Operators.assignmentModulo)
        case ast.Pow    => ("**=", Operators.assignmentExponentiation)
        case ast.LShift => ("<<=", Operators.assignmentShiftLeft)
        case ast.RShift => ("<<=", Operators.assignmentArithmeticShiftRight)
        case ast.BitOr  => ("|=", Operators.assignmentOr)
        case ast.BitXor => ("^=", Operators.assignmentXor)
        case ast.BitAnd => ("&=", Operators.assignmentAnd)
        case ast.FloorDiv =>
          (
            "//=",
            "<operator>.assignmentFloorDiv"
          ) // TODO make this a define and add policy for this
      }

    createAugAssignment(
      targetNode,
      operatorCode,
      valueNode,
      operatorFullName,
      lineAndColOf(augAssign)
    )
  }

  def convert(forStmt: ast.For): NewNode = ???

  def convert(forStmt: ast.AsyncFor): NewNode = ???

  def convert(astWhile: ast.While): nodes.NewNode = {
    val conditionNode = convert(astWhile.test)
    val bodyStmtNodes = astWhile.body.map(convert)

    val controlStructureNode =
      nodeBuilder.controlStructureNode("while ... : ...", "WhileStatement", lineAndColOf(astWhile))
    edgeBuilder.conditionEdge(conditionNode, controlStructureNode)

    val bodyBlockNode = createBlock(bodyStmtNodes, lineAndColOf(astWhile))
    addAstChildNodes(controlStructureNode, 1, conditionNode, bodyBlockNode)

    if (astWhile.orelse.nonEmpty) {
      val elseStmtNodes = astWhile.orelse.map(convert)
      val elseBlockNode =
        createBlock(elseStmtNodes, lineAndColOf(astWhile.orelse.head))
      addAstChildNodes(controlStructureNode, 3, elseBlockNode)
    }

    controlStructureNode
  }

  def convert(astIf: ast.If): nodes.NewNode = {
    val conditionNode = convert(astIf.test)
    val bodyStmtNodes = astIf.body.map(convert)

    val controlStructureNode =
      nodeBuilder.controlStructureNode("if ... : ...", "IfStatement", lineAndColOf(astIf))
    edgeBuilder.conditionEdge(conditionNode, controlStructureNode)

    val bodyBlockNode = createBlock(bodyStmtNodes, lineAndColOf(astIf))
    addAstChildNodes(controlStructureNode, 1, conditionNode, bodyBlockNode)

    if (astIf.orelse.nonEmpty) {
      val elseStmtNodes = astIf.orelse.map(convert)
      val elseBlockNode = createBlock(elseStmtNodes, lineAndColOf(astIf.orelse.head))
      addAstChildNodes(controlStructureNode, 3, elseBlockNode)
    }

    controlStructureNode
  }

  def convert(withStmt: ast.With): NewNode = ???

  def convert(withStmt: ast.AsyncWith): NewNode = ???

  def convert(raise: ast.Raise): NewNode = ???

  def convert(tryStmt: ast.Try): NewNode = ???

  def convert(assert: ast.Assert): NewNode = ???

  def convert(importStmt: ast.Import): NewNode = ???

  def convert(importFrom: ast.ImportFrom): NewNode = ???

  def convert(global: ast.Global): NewNode = {
    global.names.foreach(contextStack.addGlobalVariable)
    val code = global.names.mkString("global ", ", ", "")
    nodeBuilder.unknownNode(code, global.getClass.getName, lineAndColOf(global))
  }

  def convert(nonLocal: ast.Nonlocal): NewNode = {
    nonLocal.names.foreach(contextStack.addNonLocalVariable)
    val code = nonLocal.names.mkString("nonlocal ", ", ", "")
    nodeBuilder.unknownNode(code, nonLocal.getClass.getName, lineAndColOf(nonLocal))
  }

  def convert(expr: ast.Expr): nodes.NewNode = {
    convert(expr.value)
  }

  def convert(pass: ast.Pass): nodes.NewNode = {
    nodeBuilder.callNode(
      "pass",
      "<operator>.pass",
      DispatchTypes.STATIC_DISPATCH,
      lineAndColOf(pass)
    )
  }

  def convert(astBreak: ast.Break): nodes.NewNode = {
    nodeBuilder.controlStructureNode("break", "BreakStatement", lineAndColOf(astBreak))
  }

  def convert(astContinue: ast.Continue): nodes.NewNode = {
    nodeBuilder.controlStructureNode("continue", "ContinueStatement", lineAndColOf(astContinue))
  }

  def convert(raise: ast.RaiseP2): NewNode = ???

  def convert(errorStatement: ast.ErrorStatement): NewNode = ???

  private def convert(expr: ast.iexpr): NewNode = {
    expr match {
      case node: ast.BoolOp => convert(node)
      case node: ast.NamedExpr => unhandled(node)
      case node: ast.BinOp => convert(node)
      case node: ast.UnaryOp => convert(node)
      case node: ast.Lambda => convert(node)
      case node: ast.IfExp => unhandled(node)
      case node: ast.Dict => unhandled(node)
      case node: ast.Set => unhandled(node)
      case node: ast.ListComp => unhandled(node)
      case node: ast.SetComp => unhandled(node)
      case node: ast.DictComp => unhandled(node)
      case node: ast.GeneratorExp => unhandled(node)
      case node: ast.Await => unhandled(node)
      case node: ast.Yield => unhandled(node)
      case node: ast.YieldFrom => unhandled(node)
      case node: ast.Compare => convert(node)
      case node: ast.Call => convert(node)
      case node: ast.Constant => convert(node)
      case node: ast.Attribute => convert(node)
      case node: ast.Subscript => unhandled(node)
      case node: ast.Starred => unhandled(node)
      case node: ast.Name => convert(node)
      case node: ast.List => convert(node)
      case node: ast.Tuple => unhandled(node)
      case node: ast.Slice => unhandled(node)
      case node: ast.StringExpList => unhandled(node)
    }
  }

  def convert(boolOp: ast.BoolOp): nodes.NewNode = {
    def boolOpToCodeAndFullName(operator: ast.iboolop): () => (String, String) = { () =>
      {
        operator match {
          case ast.And => ("and", Operators.logicalAnd)
          case ast.Or  => ("or", Operators.logicalOr)
        }
      }
    }

    val operandNodes = boolOp.values.map(convert)
    createNAryOperatorCall(boolOpToCodeAndFullName(boolOp.op), operandNodes, lineAndColOf(boolOp))
  }

  def convert(namedExpr: ast.NamedExpr): NewNode = ???

  def convert(binOp: ast.BinOp): nodes.NewNode = {
    val lhsNode = convert(binOp.left)
    val rhsNode = convert(binOp.right)

    val (operatorCode, methodFullName) =
      binOp.op match {
        case ast.Add  => (" + ", Operators.addition)
        case ast.Sub  => (" - ", Operators.subtraction)
        case ast.Mult => (" * ", Operators.multiplication)
        case ast.MatMult =>
          (" @ ", "<operator>.matMult") // TODO make this a define and add policy for this
        case ast.Div    => (" / ", Operators.division)
        case ast.Mod    => (" % ", Operators.modulo)
        case ast.Pow    => (" ** ", Operators.exponentiation)
        case ast.LShift => (" << ", Operators.shiftLeft)
        case ast.RShift => (" << ", Operators.arithmeticShiftRight)
        case ast.BitOr  => (" | ", Operators.or)
        case ast.BitXor => (" ^ ", Operators.xor)
        case ast.BitAnd => (" & ", Operators.and)
        case ast.FloorDiv =>
          (" // ", "<operator>.floorDiv") // TODO make this a define and add policy for this
      }

    val code = codeOf(lhsNode) + operatorCode + codeOf(rhsNode)
    val callNode = nodeBuilder.callNode(
      code,
      methodFullName,
      DispatchTypes.STATIC_DISPATCH,
      lineAndColOf(binOp)
    )

    addAstChildrenAsArguments(callNode, 1, lhsNode, rhsNode)

    callNode
  }

  def convert(unaryOp: ast.UnaryOp): nodes.NewNode = {
    val operandNode = convert(unaryOp.operand)

    val (operatorCode, methodFullName) =
      unaryOp.op match {
        case ast.Invert => ("~", Operators.not)
        case ast.Not    => ("not ", Operators.logicalNot)
        case ast.UAdd   => ("+", Operators.plus)
        case ast.USub   => ("-", Operators.minus)
      }

    val code = operatorCode + codeOf(operandNode)
    val callNode = nodeBuilder.callNode(
      code,
      methodFullName,
      DispatchTypes.STATIC_DISPATCH,
      lineAndColOf(unaryOp)
    )

    addAstChildrenAsArguments(callNode, 1, operandNode)

    callNode
  }

  def convert(lambda: ast.Lambda): NewNode = {
    // TODO test lambda expression.
    createMethodAndMethodRef(
      "lambda",
      createParameterProcessingFunction(lambda.args.asInstanceOf[ast.Arguments]),
      Iterable.single(new ast.Return(lambda.body, lambda.attributeProvider)),
      decoratorList = Nil,
      returns = None,
      isAsync = false,
      lineAndColOf(lambda)
    )
  }

  def convert(ifExp: ast.IfExp): NewNode = ???

  def convert(dict: ast.Dict): NewNode = ???

  def convert(set: ast.Set): NewNode = ???

  def convert(listComp: ast.ListComp): NewNode = ???

  def convert(setComp: ast.SetComp): NewNode = ???

  def convert(dictComp: ast.DictComp): NewNode = ???

  def convert(generatorExp: ast.GeneratorExp): NewNode = ???

  def convert(await: ast.Await): NewNode = ???

  def convert(yieldExpr: ast.Yield): NewNode = ???

  def convert(yieldFrom: ast.YieldFrom): NewNode = ???

  // In case of a single compare operation there is no lowering applied.
  // So e.g. x < y stay untouched.
  // Otherwise the lowering is as follows:
  //  Src AST:
  //    x < y < z < a
  //  Lowering:
  //    {
  //      tmp1 = y
  //      x < tmp1 && {
  //        tmp2 = z
  //        tmp1 < tmp2 && {
  //          tmp2 < a
  //        }
  //      }
  //    }
  def convert(compare: ast.Compare): NewNode = {
    assert(compare.ops.size == compare.comparators.size)
    var lhsNode = convert(compare.left)

    val topLevelExprNodes =
      lowerComparatorChain(lhsNode, compare.ops, compare.comparators, lineAndColOf(compare))
    if (topLevelExprNodes.size > 1) {
      createBlock(topLevelExprNodes, lineAndColOf(compare))
    } else {
      topLevelExprNodes.head
    }
  }

  private def compopToOpCodeAndFullName(compareOp: ast.icompop): () => (String, String) = { () =>
    {
      compareOp match {
        case ast.Eq    => ("==", Operators.equals)
        case ast.NotEq => ("!=", Operators.notEquals)
        case ast.Lt    => ("<", Operators.lessThan)
        case ast.LtE   => ("<=", Operators.lessEqualsThan)
        case ast.Gt    => (">", Operators.greaterThan)
        case ast.GtE   => (">=", Operators.greaterEqualsThan)
        case ast.Is    => ("is", "<operator>.is")
        case ast.IsNot => ("is not", "<operator>.isNot")
        case ast.In    => ("in", "<operator>.in")
        case ast.NotIn => ("not in", "<operator>.notIn")
      }
    }
  }

  def lowerComparatorChain(
      lhsNode: nodes.NewNode,
      compOperators: Iterable[ast.icompop],
      comparators: Iterable[ast.iexpr],
      lineAndColumn: LineAndColumn
  ): Iterable[nodes.NewNode] = {
    val rhsNode = convert(comparators.head)

    if (compOperators.size == 1) {
      val compareNode = createBinaryOperatorCall(
        lhsNode,
        compopToOpCodeAndFullName(compOperators.head),
        rhsNode,
        lineAndColumn
      )
      Iterable.single(compareNode)
    } else {
      val tmpVariableName = getUnusedName()
      val tmpIdentifierAssign = createIdentifierNode(tmpVariableName, Store, lineAndColumn)
      val assignmentNode = createAssignment(tmpIdentifierAssign, rhsNode, lineAndColumn)

      val tmpIdentifierCompare1 = createIdentifierNode(tmpVariableName, Load, lineAndColumn)
      val compareNode = createBinaryOperatorCall(
        lhsNode,
        compopToOpCodeAndFullName(compOperators.head),
        tmpIdentifierCompare1,
        lineAndColumn
      )

      val tmpIdentifierCompare2 = createIdentifierNode(tmpVariableName, Load, lineAndColumn)
      val childNodes = lowerComparatorChain(
        tmpIdentifierCompare2,
        compOperators.tail,
        comparators.tail,
        lineAndColumn
      )

      val blockNode = createBlock(childNodes, lineAndColumn)

      Iterable(
        assignmentNode,
        createBinaryOperatorCall(compareNode, andOpCodeAndFullName(), blockNode, lineAndColumn)
      )
    }
  }

  private def andOpCodeAndFullName(): () => (String, String) = { () =>
    ("and", Operators.logicalAnd)
  }

  /** TODO
    * For now this function compromises on the correctness of the
    * lowering in order to get some data flow tracking going.
    * 1. For constructs like x.func() we assume x to be the
    *    instance which is passed into func. This is not true
    *    since the instance method object gets the instance
    *    already bound/captured during function access.
    *    This becomes relevant for constructs like:
    *    x.func = y.func <- y.func is class method object
    *    x.func()
    *    In this case the instance passed into func is y and
    *    not x. We cannot represent this in th CPG and thus
    *    stick to the assumption that the part before the "."
    *    and the bound/captured instance will be the same.
    *    For reference see:
    *    https://docs.python.org/3/reference/datamodel.html#the-standard-type-hierarchy
    *    search for "Instance methods"
    *
    * 2. Due to the decision in 1. for calls like x.func() the
    *    expression x is part of the call receiver AST and its
    *    instance AST. This would be only ok if x is side effect
    *    free which is not necessarily the case if x == getX().
    *    Currently we ignore this fact and just emit the expression
    *    twice. A fix would mean to emit a tmp variable which holds
    *    the expression result.
    *    Not yet implemented because this gets obsolete if 1. is
    *    fixed.
    * 3. No named parameter support. CPG does not supports this.
    */
  def convert(call: ast.Call): nodes.NewNode = {
    val argumentNodes = call.args.map(convert).toSeq
    val receiverNode = convert(call.func)

    call.func match {
      case attribute: ast.Attribute =>
        val instanceNode = convert(attribute.value)
        createInstanceCall(receiverNode, instanceNode, lineAndColOf(call), argumentNodes: _*)
      case _ =>
        createCall(receiverNode, lineAndColOf(call), argumentNodes: _*)
    }
  }

  def convert(constant: ast.Constant): nodes.NewNode = {
    constant.value match {
      case stringConstant: ast.StringConstant =>
        nodeBuilder.stringLiteralNode(stringConstant.value, lineAndColOf(constant))
      case boolConstant: ast.BoolConstant =>
        val boolStr = if (boolConstant.value) "True" else "False"
        nodeBuilder.stringLiteralNode(boolStr, lineAndColOf(constant))
      case intConstant: ast.IntConstant =>
        nodeBuilder.numberLiteralNode(intConstant.value, lineAndColOf(constant))
      case floatConstant: ast.FloatConstant =>
        nodeBuilder.numberLiteralNode(floatConstant.value, lineAndColOf(constant))
      case imaginaryConstant: ast.ImaginaryConstant =>
        nodeBuilder.numberLiteralNode(imaginaryConstant.value + "j", lineAndColOf(constant))
      case ast.NoneConstant =>
        nodeBuilder.numberLiteralNode("None", lineAndColOf(constant))
      case ast.EllipsisConstant =>
        nodeBuilder.numberLiteralNode("...", lineAndColOf(constant))
    }
  }

  /** TODO
    * We currently ignore possible attribute access provider/interception
    * mechanisms like __getattr__, __getattribute__ and __get__.
    */
  def convert(attribute: ast.Attribute): nodes.NewNode = {
    val baseNode = convert(attribute.value)
    val fieldIdNode = nodeBuilder.fieldIdentifierNode(attribute.attr, lineAndColOf(attribute))

    createFieldAccess(baseNode, fieldIdNode, lineAndColOf(attribute))
  }

  def convert(subscript: ast.Subscript): NewNode = ???

  def convert(starred: ast.Starred): NewNode = ???

  def convert(name: ast.Name): nodes.NewNode = {
    val memoryOperation = memOpMap.get(name).get
    createIdentifierNode(name.id, memoryOperation, lineAndColOf(name))
  }

  /** Lowering of [1, 2]:
    *   {
    *     tmp = list
    *     tmp.append(1)
    *     tmp.append(2)
    *     tmp
    *   }
    */
  def convert(list: ast.List): nodes.NewNode = {
    val tmpVariableName = getUnusedName()

    val listInstanceId = createIdentifierNode(tmpVariableName, Store, lineAndColOf(list))
    val listIdNode = createIdentifierNode("list", Load, lineAndColOf(list))
    val listConstructorCall = createCall(listIdNode, lineAndColOf(list))
    val listInstanceAssignment =
      createAssignment(listInstanceId, listConstructorCall, lineAndColOf(list))

    val appendCallNodes = list.elts.map { listElement =>
      val listInstanceIdForReceiver =
        createIdentifierNode(tmpVariableName, Load, lineAndColOf(list))
      val appendFieldAccessNode =
        createFieldAccess(listInstanceIdForReceiver, "append", lineAndColOf(list))

      val listeInstanceId = createIdentifierNode(tmpVariableName, Load, lineAndColOf(list))
      val elementNode = convert(listElement)
      createInstanceCall(appendFieldAccessNode, listeInstanceId, lineAndColOf(list), elementNode)
    }

    val listInstanceIdForReturn = createIdentifierNode(tmpVariableName, Load, lineAndColOf(list))

    val blockElements = mutable.ArrayBuffer.empty[nodes.NewNode]
    blockElements.append(listInstanceAssignment)
    blockElements.appendAll(appendCallNodes)
    blockElements.append(listInstanceIdForReturn)
    createBlock(blockElements, lineAndColOf(list))
  }

  def convert(tuple: ast.Tuple): NewNode = ???

  def convert(slice: ast.Slice): NewNode = ???

  def convert(stringExpList: ast.StringExpList): NewNode = ???

  def convert(comprehension: ast.Comprehension): NewNode = ???

  def convert(exceptHandler: ast.ExceptHandler): NewNode = ???

  def convert(arguments: ast.Arguments): NewNode = ???

  def convert(arg: ast.Arg): NewNode = {
    nodeBuilder.methodParameterNode(arg.arg, lineAndColOf(arg))
  }

  def convert(keyword: ast.Keyword): NewNode = ???

  def convert(alias: ast.Alias): NewNode = ???

  def convert(withItem: ast.Withitem): NewNode = ???

  def convert(typeIgnore: ast.TypeIgnore): NewNode = ???

  private def calcMethodFullNameFromContext(name: String): String = {
    val contextQualName = contextStack.qualName
    if (contextQualName != "") {
      fileName + ":" + contextQualName + "." + name
    } else {
      fileName + ":" + name
    }
  }
}
