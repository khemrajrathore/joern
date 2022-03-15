"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.graphSimple = graphSimple;

function _toConsumableArray(arr) { if (Array.isArray(arr)) { for (var i = 0, arr2 = Array(arr.length); i < arr.length; i++) { arr2[i] = arr[i]; } return arr2; } else { return Array.from(arr); } }

var parserHelpers = require('./utils/parserHelpers');
var utils = require('./utils/utils');
var fs = require('fs');
var parser = require('@solidity-parser/parser');
var graphviz = require('graphviz');

var _require = require('c3-linearization'),
    linearize = _require.linearize;

var importer = require('../lib/utils/importer');

var _require2 = require('./utils/colorscheme'),
    defaultColorScheme = _require2.defaultColorScheme,
    defaultColorSchemeDark = _require2.defaultColorSchemeDark;

function graphSimple(files) {
  var options = arguments.length > 1 && arguments[1] !== undefined ? arguments[1] : {};

  if (files.length === 0) {
    throw new Error('\nNo files were specified for analysis in the arguments. Bailing...\n');
  }

  var colorScheme = options.hasOwnProperty('colorScheme') ? options.colorScheme : defaultColorScheme;

  var digraph = graphviz.digraph('G');
  digraph.set('ratio', 'auto');
  digraph.set('page', '100');
  digraph.set('compound', 'true');
  colorScheme.digraph.bgcolor && digraph.set('bgcolor', colorScheme.digraph.bgcolor);
  for (var i in colorScheme.digraph.nodeAttribs) {
    digraph.setNodeAttribut(i, colorScheme.digraph.nodeAttribs[i]);
  }
  for (var _i in colorScheme.digraph.edgeAttribs) {
    digraph.setEdgeAttribut(_i, colorScheme.digraph.edgeAttribs[_i]);
  }

  // make the files array unique by typecasting them to a Set and back
  // this is not needed in case the importer flag is on, because the 
  // importer module already filters the array internally
  if (!options.contentsInFilePath && options.importer) {
    files = importer.importProfiler(files);
  } else {
    files = [].concat(_toConsumableArray(new Set(files)));
  }

  // initialize vars that persist over file parsing loops
  var userDefinedStateVars = {};
  var stateVars = {};
  var dependencies = {};
  var fileASTs = [];
  var functionsPerContract = {};
  var eventsPerContract = {};
  var structsPerContract = {};
  var contractUsingFor = {};
  var contractNames = [];

  var _loop = function _loop(file) {

    var content = void 0;
    if (!options.contentsInFilePath) {
      try {
        content = fs.readFileSync(file).toString('utf-8');
      } catch (e) {
        if (e.code === 'EISDIR') {
          console.error('Skipping directory ' + file);
          return 'continue';
        } else {
          throw e;
        }
      }
    } else {
      content = file;
    }

    var ast = function () {
      try {
        return parser.parse(content);
      } catch (err) {
        if (!options.contentsInFilePath) {
          console.error('\nError found while parsing the following file: ' + file + '\n');
        } else {
          console.error('\nError found while parsing one of the provided files\n');
        }
        throw err;
      }
    }();

    fileASTs.push(ast);

    var contractName = null;
    var contractNode = null; // a digraph node representing a contract

    parser.visit(ast, {
      ContractDefinition: function ContractDefinition(node) {
        contractName = node.name;
        contractNames.push(contractName);

        var kind = "";
        if (node.kind == "interface") {
          kind = "  (iface)";
        } else if (node.kind == "library") {
          kind = "  (lib)";
        }

        userDefinedStateVars[contractName] = {};
        stateVars[contractName] = {};
        functionsPerContract[contractName] = [];
        eventsPerContract[contractName] = [];
        structsPerContract[contractName] = [];
        contractUsingFor[contractName] = {};

        if (!(contractNode = digraph.getNode(contractName))) {
          contractNode = digraph.addNode(contractName);

          contractNode.set('label', contractName);
          contractNode.set('color', colorScheme.contract.defined.color);
          if (colorScheme.contract.defined.fontcolor) {
            contractNode.set('fontcolor', colorScheme.contract.undefined.fontcolor);
          }

          if (colorScheme.contract.defined.style) {
            contractNode.set('style', colorScheme.contract.defined.style || "filled");
            // contractNode.set('bgcolor', colorScheme.contract.defined.color);
          } else {
            contractNode.set('style', 'filled');
          }

          // colorScheme.contract.defined.bgcolor && contractNode.set('bgcolor', colorScheme.contract.defined.bgcolor);
        } else {
          if (colorScheme.contract.defined.style) {
            contractNode.set('style', colorScheme.contract.defined.style);
          } else {
            contractNode.set('style', 'filled');
          }
        }

        dependencies[contractName] = node.baseContracts.map(function (spec) {
          return spec.baseName.namePath;
        });
      },
      StateVariableDeclaration: function StateVariableDeclaration(node) {
        var _iteratorNormalCompletion3 = true;
        var _didIteratorError3 = false;
        var _iteratorError3 = undefined;

        try {
          for (var _iterator3 = node.variables[Symbol.iterator](), _step3; !(_iteratorNormalCompletion3 = (_step3 = _iterator3.next()).done); _iteratorNormalCompletion3 = true) {
            var variable = _step3.value;

            if (parserHelpers.isUserDefinedDeclaration(variable)) {
              userDefinedStateVars[contractName][variable.name] = variable.typeName.namePath;
            } else if (parserHelpers.isElementaryTypeDeclaration(variable)) {
              stateVars[contractName][variable.name] = variable.typeName.name;
            } else if (parserHelpers.isArrayDeclaration(variable)) {
              stateVars[contractName][variable.name] = variable.typeName.baseTypeName.namePath;
            } else if (parserHelpers.isMappingDeclaration(variable)) {
              stateVars[contractName][variable.name] = variable.typeName.valueType.name;
            }
          }
        } catch (err) {
          _didIteratorError3 = true;
          _iteratorError3 = err;
        } finally {
          try {
            if (!_iteratorNormalCompletion3 && _iterator3.return) {
              _iterator3.return();
            }
          } finally {
            if (_didIteratorError3) {
              throw _iteratorError3;
            }
          }
        }
      },
      FunctionDefinition: function FunctionDefinition(node) {
        functionsPerContract[contractName].push(node.name);
      },
      EventDefinition: function EventDefinition(node) {
        eventsPerContract[contractName].push(node.name);
      },
      StructDefinition: function StructDefinition(node) {
        structsPerContract[contractName].push(node.name);
      },
      UsingForDeclaration: function UsingForDeclaration(node) {
        // Check if the using for declaration is targeting a specific type or all types with "*"
        var typeNameName = node.typeName != null ? node.typeName.name : '*';

        if (!contractUsingFor[contractName][typeNameName]) {
          contractUsingFor[contractName][typeNameName] = new Set([]);
        }
        contractUsingFor[contractName][typeNameName].add(node.libraryName);
      }
    });
  };

  var _iteratorNormalCompletion = true;
  var _didIteratorError = false;
  var _iteratorError = undefined;

  try {
    for (var _iterator = files[Symbol.iterator](), _step; !(_iteratorNormalCompletion = (_step = _iterator.next()).done); _iteratorNormalCompletion = true) {
      var file = _step.value;

      var _ret = _loop(file);

      if (_ret === 'continue') continue;
    }
  } catch (err) {
    _didIteratorError = true;
    _iteratorError = err;
  } finally {
    try {
      if (!_iteratorNormalCompletion && _iterator.return) {
        _iterator.return();
      }
    } finally {
      if (_didIteratorError) {
        throw _iteratorError;
      }
    }
  }

  dependencies = linearize(dependencies, { reverse: true });

  var _loop2 = function _loop2(ast) {

    var contractName = null;
    var cluster = null;

    // find all the contracts, and create anode for them
    parser.visit(ast, {
      ContractDefinition: function ContractDefinition(node) {
        if (!(node = digraph.getNode(node.name))) {
          node = digraph.addNode(node.name);
          node.set('label', contractName);
          node.set('color', colorScheme.contract.defined.color);
          if (colorScheme.contract.defined.fontcolor) {
            contranodectNode.set('fontcolor', colorScheme.contract.undefined.fontcolor);
          }

          if (colorScheme.contract.defined.style) {
            node.set('style', colorScheme.contract.defined.style || "filled");
            // contractNode.set('bgcolor', colorScheme.contract.defined.color);
          } else {
            node.set('style', 'filled');
          }
        }
      }
    });

    var callingScope = null;
    var userDefinedLocalVars = {};
    var localVars = {};
    var tempUserDefinedStateVars = {};
    var tempStateVars = {};
    var eventDefinitions = [];

    parser.visit(ast, {
      ContractDefinition: function ContractDefinition(node) {
        contractName = node.name;
        callingScope = contractName;
        var _iteratorNormalCompletion4 = true;
        var _didIteratorError4 = false;
        var _iteratorError4 = undefined;

        try {
          for (var _iterator4 = dependencies[contractName][Symbol.iterator](), _step4; !(_iteratorNormalCompletion4 = (_step4 = _iterator4.next()).done); _iteratorNormalCompletion4 = true) {
            var dep = _step4.value;

            Object.assign(tempUserDefinedStateVars, userDefinedStateVars[dep]);
            Object.assign(tempStateVars, stateVars[dep]);
          }
        } catch (err) {
          _didIteratorError4 = true;
          _iteratorError4 = err;
        } finally {
          try {
            if (!_iteratorNormalCompletion4 && _iterator4.return) {
              _iterator4.return();
            }
          } finally {
            if (_didIteratorError4) {
              throw _iteratorError4;
            }
          }
        }

        Object.assign(tempUserDefinedStateVars, userDefinedStateVars[contractName]);
        Object.assign(tempStateVars, stateVars[contractName]);
      },


      'ContractDefinition:exit': function ContractDefinitionExit(node) {
        contractName = null;
        tempUserDefinedStateVars = {};
        tempStateVars = {};
      },

      FunctionDefinition: function FunctionDefinition(node) {

        callingScope = contractName;
      },


      'FunctionDefinition:exit': function FunctionDefinitionExit(node) {
        callingScope = null;
        userDefinedLocalVars = {};
        localVars = {};
      },

      ModifierDefinition: function ModifierDefinition(node) {
        callingScope = contractName;
      },


      'ModifierDefinition:exit': function ModifierDefinitionExit(node) {
        callingScope = null;
      },

      // not sure what this is doing
      ParameterList: function ParameterList(node) {
        var _iteratorNormalCompletion5 = true;
        var _didIteratorError5 = false;
        var _iteratorError5 = undefined;

        try {
          for (var _iterator5 = node.parameters[Symbol.iterator](), _step5; !(_iteratorNormalCompletion5 = (_step5 = _iterator5.next()).done); _iteratorNormalCompletion5 = true) {
            var parameter = _step5.value;

            if (parameter.name === null) {
              return;
            } else if (parserHelpers.isUserDefinedDeclaration(parameter)) {
              userDefinedLocalVars[parameter.name] = parameter.typeName.namePath;
            } else if (callingScope) {
              localVars[parameter.name] = parameter.typeName.name;
            }
          }
        } catch (err) {
          _didIteratorError5 = true;
          _iteratorError5 = err;
        } finally {
          try {
            if (!_iteratorNormalCompletion5 && _iterator5.return) {
              _iterator5.return();
            }
          } finally {
            if (_didIteratorError5) {
              throw _iteratorError5;
            }
          }
        }
      },


      // not sure what this is doing
      VariableDeclaration: function VariableDeclaration(node) {
        if (callingScope && node.name === null) {
          return;
        } else if (callingScope && parserHelpers.isUserDefinedDeclaration(node)) {
          userDefinedLocalVars[node.name] = node.typeName.namePath;
        } else if (callingScope && parserHelpers.isElementaryTypeDeclaration(node)) {
          localVars[node.name] = node.typeName.name;
        } else if (callingScope && parserHelpers.isArrayDeclaration(node)) {
          localVars[node.name] = node.typeName.baseTypeName.namePath;
        } else if (callingScope && parserHelpers.isMappingDeclaration(node)) {
          localVars[node.name] = node.typeName.valueType.name;
        }
      },


      // ModifierInvocation(node) {
      //   if (options.enableModifierEdges && callingScope) {
      //     digraph.addEdge(callingScope, contractName);
      //   }
      // },

      FunctionCall: function FunctionCall(node) {
        if (!callingScope) {
          // this is a function call outside of functions and modifiers, ignore for now
          return;
        }

        var expr = node.expression;

        var name = void 0;
        var localContractName = contractName;
        var opts = {
          color: colorScheme.call.default
        };

        // Construct an array with the event and struct names in the whole dependencies tree of the current contract
        var eventsOfDependencies = [];
        var structsOfDependencies = [];
        if (dependencies.hasOwnProperty(contractName)) {
          var _iteratorNormalCompletion6 = true;
          var _didIteratorError6 = false;
          var _iteratorError6 = undefined;

          try {
            for (var _iterator6 = dependencies[contractName][Symbol.iterator](), _step6; !(_iteratorNormalCompletion6 = (_step6 = _iterator6.next()).done); _iteratorNormalCompletion6 = true) {
              var dep = _step6.value;

              eventsOfDependencies = eventsOfDependencies.concat(eventsPerContract[dep]);
              structsOfDependencies = structsOfDependencies.concat(structsPerContract[dep]);
            }
          } catch (err) {
            _didIteratorError6 = true;
            _iteratorError6 = err;
          } finally {
            try {
              if (!_iteratorNormalCompletion6 && _iterator6.return) {
                _iterator6.return();
              }
            } finally {
              if (_didIteratorError6) {
                throw _iteratorError6;
              }
            }
          }
        }

        if (parserHelpers.isRegularFunctionCall(node, contractNames, eventsOfDependencies, structsOfDependencies)) {
          opts.color = colorScheme.call.regular;
          name = expr.name;
        } else if (parserHelpers.isMemberAccess(node)) {
          var object = null;
          var variableType = null;

          name = expr.memberName;

          // checking if the member expression is a simple identifier
          if (expr.expression.hasOwnProperty('name')) {
            object = expr.expression.name;

            // checking if it is a member of `address` and pass along it's contents
          } else if (parserHelpers.isMemberAccessOfAddress(node)) {
            if (expr.expression.arguments[0].hasOwnProperty('name')) {
              object = expr.expression.arguments[0].name;
            } else if (expr.expression.arguments[0].type === 'NumberLiteral') {
              object = 'address(' + expr.expression.arguments[0].number + ')';
            } else {
              object = JSON.stringify(expr.expression.arguments).replace(/"/g, "");
            }

            // checking if it is a typecasting to a user-defined contract type
          } else if (parserHelpers.isAContractTypecast(node, contractNames)) {
            object = expr.expression.expression.name;
          }

          // check if member expression is a special var and get its canonical type
          if (parserHelpers.isSpecialVariable(expr.expression)) {
            variableType = parserHelpers.getSpecialVariableType(expr.expression);

            // check if member expression is a typecast for a canonical type
          } else if (parserHelpers.isElementaryTypecast(expr.expression)) {
            variableType = expr.expression.expression.typeName.name;

            // else check for vars in defined the contract
          } else {
            // check if member access is a function of a "using for" declaration
            // START
            if (localVars.hasOwnProperty(object)) {
              /** tin: Bail - ignore usingFor BaseType in simpleGraph
              variableType = localVars[object];
              */
              return;
            } else if (userDefinedLocalVars.hasOwnProperty(object)) {
              variableType = userDefinedLocalVars[object];
            } else if (tempUserDefinedStateVars.hasOwnProperty(object)) {
              variableType = tempUserDefinedStateVars[object];
            } else if (tempStateVars.hasOwnProperty(object)) {
              /** tin: Bail - ignore usingFor BaseType in simpleGraph
              variableType = tempStateVars[object];
              */
              return;
            }
          }

          // convert to canonical elementary type: uint -> uint256
          variableType = variableType === 'uint' ? 'uint256' : variableType;

          // if variable type is not null let's replace "object" for the actual library name
          if (variableType !== null && contractUsingFor[contractName].hasOwnProperty(variableType) && functionsPerContract.hasOwnProperty(contractUsingFor[contractName][variableType]) && functionsPerContract[contractUsingFor[contractName][variableType]].includes(name)) {
            if (!options.libraries) {
              object = contractUsingFor[contractName][variableType];
            } else {
              return;
            }
          }
          // END

          // if we have found nothing so far then create no node
          if (object === null) {
            return;
          } else if (object === 'this') {
            opts.color = colorScheme.call.this;
          } else if (object === 'super') {
            // "super" in this context is gonna be the 2nd element of the dependencies array
            // since the first is the contract itself
            localContractName = dependencies[localContractName][1];
          } else if (tempUserDefinedStateVars[object] !== undefined) {
            localContractName = tempUserDefinedStateVars[object];
          } else if (userDefinedLocalVars[object] !== undefined) {
            localContractName = userDefinedLocalVars[object];
          } else {
            localContractName = object;
          }
        } else {
          return;
        }

        var externalNode = void 0;

        if (!(externalNode = digraph.getNode(localContractName))) {
          externalNode = digraph.addNode(localContractName);

          externalNode.set('label', localContractName);
          externalNode.set('color', colorScheme.contract.undefined.color);
          if (colorScheme.contract.undefined.fontcolor) {
            externalNode.set('fontcolor', colorScheme.contract.undefined.fontcolor);
          }
          if (colorScheme.contract.undefined.style) {
            externalNode.set('style', colorScheme.contract.undefined.style || "filled");
            /* tin: node.bgcolor is not allowed */
            //colorScheme.contract.undefined.bgcolor && externalNode.set('bgcolor', colorScheme.contract.undefined.bgcolor );
          }
        }

        if (!digraph.getNode(localContractName) && externalNode) {
          digraph.addNode(localContractName, { label: name });
        }

        var nodeExists = false;
        var edges = digraph.edges;
        var _iteratorNormalCompletion7 = true;
        var _didIteratorError7 = false;
        var _iteratorError7 = undefined;

        try {
          for (var _iterator7 = edges[Symbol.iterator](), _step7; !(_iteratorNormalCompletion7 = (_step7 = _iterator7.next()).done); _iteratorNormalCompletion7 = true) {
            var edge = _step7.value;

            if (callingScope == edge.nodeOne.id && externalNode.id == edge.nodeTwo.id) {
              nodeExists = true;
              break;
            }
            // debugger;
            // console.log(callingScope, edge.nodeOne.id);
            // console.log(externalNode.id, edge.nodeTwo.id);
            // console.log(nodeExists)
          }
          // digraph.addEdge(callingScope, externalNode, opts);
        } catch (err) {
          _didIteratorError7 = true;
          _iteratorError7 = err;
        } finally {
          try {
            if (!_iteratorNormalCompletion7 && _iterator7.return) {
              _iterator7.return();
            }
          } finally {
            if (_didIteratorError7) {
              throw _iteratorError7;
            }
          }
        }

        if (!nodeExists) {
          // console.log('adding', callingScope, externalNode.id);
          digraph.addEdge(callingScope, externalNode.id, opts);
        }
      }
    });
  };

  var _iteratorNormalCompletion2 = true;
  var _didIteratorError2 = false;
  var _iteratorError2 = undefined;

  try {
    for (var _iterator2 = fileASTs[Symbol.iterator](), _step2; !(_iteratorNormalCompletion2 = (_step2 = _iterator2.next()).done); _iteratorNormalCompletion2 = true) {
      var ast = _step2.value;

      _loop2(ast);
    }

    // This next block's purpose is to create a legend on the lower left corner
    // of the graph with color information.
    // We'll do it in dot, by hand, because it's overkill to do it programatically.
    // 
    // We'll have to paste this subgraph before the last curly bracket of the diagram
  } catch (err) {
    _didIteratorError2 = true;
    _iteratorError2 = err;
  } finally {
    try {
      if (!_iteratorNormalCompletion2 && _iterator2.return) {
        _iterator2.return();
      }
    } finally {
      if (_didIteratorError2) {
        throw _iteratorError2;
      }
    }
  }

  var legendDotString = '\n\nrankdir=LR\nnode [shape=plaintext]\nsubgraph cluster_01 { \nlabel = "Legend";\nkey [label=<<table border="0" cellpadding="2" cellspacing="0" cellborder="0">\n  <tr><td align="right" port="i1">Internal Call</td></tr>\n  <tr><td align="right" port="i2">External Call</td></tr>\n  <tr><td align="right" port="i3">Defined Contract</td></tr>\n  <tr><td align="right" port="i4">Undefined Contract</td></tr>\n  </table>>]\nkey2 [label=<<table border="0" cellpadding="2" cellspacing="0" cellborder="0">\n  <tr><td port="i1">&nbsp;&nbsp;&nbsp;</td></tr>\n  <tr><td port="i2">&nbsp;&nbsp;&nbsp;</td></tr>\n  <tr><td port="i3" bgcolor="' + colorScheme.contract.defined.bgcolor + '">&nbsp;&nbsp;&nbsp;</td></tr>\n  <tr><td port="i4">\n    <table border="1" cellborder="0" cellspacing="0" cellpadding="7" color="' + colorScheme.contract.undefined.color + '">\n      <tr>\n       <td></td>\n      </tr>\n     </table>\n  </td></tr>\n  </table>>]\nkey:i1:e -> key2:i1:w [color="' + colorScheme.call.regular + '"]\nkey:i2:e -> key2:i2:w [color="' + colorScheme.call.default + '"]\n}\n';
  debugger;
  var finalDigraph = utils.insertBeforeLastOccurrence(digraph.to_dot(), '}', legendDotString);

  return finalDigraph;
}