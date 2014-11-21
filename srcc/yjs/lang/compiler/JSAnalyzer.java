// ex: set sts=4 sw=4 expandtab:

/**
 * Yeti code analyzer.
 * Copyright (c) 2007-2014 Madis Janson, Christian Essl
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package yjs.lang.compiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import yeti.lang.Core;

final public class JSAnalyzer extends YetiType {

	static final class JSScope {
		static final JSScope ROOT =  (new JSScope(null,"","",true))
					.bind("undef_str")
					.bind("naN")
					.bind("failWith")
					.bind("true")
					.bind("false")
					.bind("throw");
		
		final JSScope parent;
		final String yName;
		final String jsName;
		private final Set<String> fnScope;
		final Set<String> freeVars;
		List<JSSym> tcoArgs = null; //used by lambda to mark parent fn as tco
		
		private JSScope(JSScope parent, String yName, String jsName, boolean fnScope) {
			this.parent = parent;
			this.yName = yName.intern();
			this.jsName = jsName.intern();
			this.fnScope = fnScope ? new HashSet<String>() : null;
			this.freeVars = fnScope ? new HashSet<String>() : null;
		}
		JSScope fnScope(){
			return new JSScope(this,"","",true);
		}
		
		JSScope findFnScope(){
			JSScope par = this;
			while(par.fnScope == null) par = par.parent;
			return par;
		}
		JSScope bind(String yName) {
			if(yName == null || "".equals(yName))
				throw new IllegalArgumentException("yName is empty");
			yName = yName.intern();
			//find jsName
			JSScope par = findFnScope();
			String jsName = yName;
			for(int i=1;true;i++){
				if(par.fnScope.contains(jsName))
					jsName = yName + i;
				else{
					par.fnScope.add(jsName.intern());
					break;
				}
			}
			return new JSScope(this, yName, jsName,false);
		}
		
		JSSym ref(String name,Node node){
			name = name.intern();
			JSScope par = this;
			while(par != null && par.yName != name){
				if(par.fnScope != null) //go out of scope -> free
					par.freeVars.add(name);
				par = par.parent;
			}
			if(par == null){
				throw new CompileException(node, "Symbol "+name+" not declared");
			}
			return new JSSym(par.jsName, node);
		}
		
		JSSym decl(Node node) {
			return new JSSym(this.jsName,node);
		}
		@Override
		public String toString() {
			JSScope par = this;
			StringBuilder bd = new StringBuilder();
			while(par != null){
				bd.append(par.yName).append(" ");
				par = par.parent;
			}
			return bd.toString();
		}
	}
	private final Compiler compiler;

	private JSAnalyzer(Compiler compiler) {
		this.compiler = compiler;
	}

	static final String NONSENSE_STRUCT = "No sense in empty struct";

	static void unusedBinding(Scope scope, Bind bind) {
		scope.ctx.compiler.warn(new CompileException(bind, "Unused binding: "
				+ bind.name));
	}

	XNode shortLambda(BinOp op) {
		Sym arg;
		Node[] cases;
		if (op.right.kind == "case-of") {
			cases = ((XNode) op.right).expr;
			cases[0] = arg = new Sym(new JSSym().sym);

			// && (cases = ((XNode) op.right).expr)[0].kind != null
			// && ((XNode) cases[0]).expr != null) {
		} else {
			arg = new Sym("_");
		}
		return XNode.lambda(arg.pos(op.line, op.col), op.right, null);
	}

	XNode asLambda(Node node) {
		BinOp op;
		return node.kind == "lambda" ? (XNode) node : node instanceof BinOp
				&& (op = (BinOp) node).op == "\\" ? shortLambda(op) : null;
	}

	JSCode analyze(Node node, JSScope scope) {
		if (node instanceof Sym) {

			String sym = ((Sym) node).sym;
			if ("none" == sym)
				return JSCode.NULL;
			if ("throw" == sym)
				return scope.ref("failWith", node);
			if (Character.isUpperCase(sym.charAt(0))) {
				return JSCode.buildIn("_tagCon", new JSLitExpr(Core.show(sym),
						node), node);
			}
			return scope.ref(sym,node);
		}
		if (node instanceof NumLit)
			return new JSLitExpr(((NumLit) node).str(), node);
		if (node instanceof Str)
			return new JSLitExpr(Core.show(((Str) node).str), node);
		if (node instanceof Seq)
			return (analSeq((Seq) node, scope)).code;
		if (node instanceof Bind) {
			Bind bind = (Bind) node;
			if (bind.expr.kind != "lambda")
				throw new CompileException(bind, "Closed binding must be a"
						+ " function binding.\n    Maybe you meant := or =="
						+ " instead of =, or have missed ; somewhere.");
			return lambda((XNode) bind.expr, scope, bind.var);
			// return new JSSeq.Bind(new JSSym(bind.name,node),
			// lambda((XNode) bind.expr,scope),node);
		}
		String kind = node.kind;
		if (kind != null) {
			if (kind == "listop") {
				ObjectRefOp l = (ObjectRefOp) node;
				if (l.right == null)
					return list(l, l.arguments, scope);
				return new JSMapRef(analyze(l.right, scope).toExpr(), analyze(
						l.arguments[0], scope).toExpr());
			}
			final XNode x = (XNode) node;
			if (kind == "()")
				return JSCode.UNDEF;
			if (kind == "list")
				return list(x, x.expr, scope);
			if (kind == "lambda")
				return lambda(x, scope, false);
			if (kind == "struct")
				return structType(x, scope).code;
			if (kind == "if")
				return cond(x, scope);
			if (kind == "_")
				return analyze(x.expr[0], scope).toStat();
			if (kind == "concat") {
				JSConcatStr ret = new JSConcatStr(x);
				for (int i = 0; i < x.expr.length; ++i)
					ret.add(analyze(x.expr[i], scope).toExpr());
				return ret;
			}
			if (kind == "case-of")
				return JSCaseCompiler.caseType(x, this, scope);
			if (kind == "new") {
				final String name = x.expr[0].sym();
				final JSCode[] args = mapArgs(1, x.expr, scope);

				return new JSExpr(x) {
					void code(CodeBuilder bd) {
						bd.add("(new ").add(new JSLitExpr(name, x.expr[0]))
								.add("(").addAll(args, ", ").add("))");
					}

					int precedence() {
						return PREC_GROUP;
					}
				};
			}
			if (kind == "rsection")
				return rsection(x, scope);
			if (kind == "try")
				return tryCatch(x, scope);
			if (kind == "load") {
				ModuleType mt = (ModuleType) x.expr[1];
				return mt.jsModuleVar;
			}
			if (kind == "new-array")
				return JSCode.EMPTY_LIST;
			if (kind == "classOf") {
				throw new CompileException(node, "classOf is not supported");
			}
			if (kind == "script") {
				Node arg = x.expr[0];
				if (arg instanceof Str) {
					return new SimpleJSExpr("script", ((Str) arg).str.replace(
							"\r", ""), JSExpr.PREC_RL, x);
				} else {
					String name = ((XNode) arg).expr[0].sym();
					String source = compiler.readJSSource(name);
					return new SimpleJSExpr("import", source, JSExpr.PREC_LIT,
							x);
				}
			}
		} else if (node instanceof BinOp) {
			BinOp op = (BinOp) node;
			String opop = op.op;
			if (opop == "") {
				return binApply(op, scope);

			}
			if (opop == FIELD_OP) {
				if (op.right.kind == "listop") {
					return new JSMapRef(analyze(op.left, scope).toExpr(),
							analyze(op.right, scope).toExpr());
				}
				return new JSFieldRef(analyze(op.left, scope).toExpr(),
						getSelectorSym(op, op.right).sym, op);
			}
			if (opop == ":=")
				return new JSAssign(analyze(op.left, scope).toExpr(), analyze(
						op.right, scope).toExpr(), op);
			if (opop == "\\")
				return lambda(shortLambda(op), scope, false);
			if (opop == "is" || opop == "unsafely_as")
				return analyze(op.right, scope);
			if (opop == "as") {
				JSExpr expr = analyze(op.right, scope).toExpr();
				TypeOp tp = (TypeOp) op;
				if (expr == JSExpr.UNDEF && tp.type.name == "string")
					return JSCode.buildIn("isString", expr, op);
				return expr;
			}
			if (opop == "#")
				return objectRef((ObjectRefOp) op, scope);
			if (opop == "loop") {
				JSExpr cond = analyze(op.left != null ? op.left : op.right,
						scope).toExpr();
				JSCode body = op.left == null ? new JSBlock(op) : analyze(
						op.right, scope);
				return new JSWhile(cond, body, op);
			}
			if (opop == "-" && op.left == null)
				return JSBinOp.create("-", null, analyze(op.right, scope)
						.toExpr(), op,scope);
			/*
			 * return new JSApply( new SimpleJSExpr("minus", "-",
			 * JSExpr.PREC_RL), analyze(op.right,scope).toExpr());
			 */
			if (opop == "not")
				return JSBinOp.create("not", null, analyze(op.right, scope)
						.toExpr(), op,scope);

			/*
			 * return new JSApply( new SimpleJSExpr("minus", "!",
			 * JSExpr.PREC_RL), analyze(op.right,scope).toExpr());
			 */
			if (opop == "with")
				return withStruct(op, scope);
			if (opop == "instanceof") {
				throw new CompileException(op,
						"instanceof operator is not supported in JS version");
			}
			if (op.left == null)
				throw new CompileException(op,
						"Internal error (incomplete operator " + op.op + ")");
			if (opop == "^") {
				JSConcatStr ret = new JSConcatStr(op);
				ret.add(analyze(op.left, scope).toExpr());
				ret.add(analyze(op.right, scope).toExpr());
				return ret;
			}
			if (opop == "|>")
				return apply(op, analyze(op.right, scope).toExpr(), op.left,
						scope);
			return JSBinOp.create(opop, analyze(op.left, scope).toExpr(),
					analyze(op.right, scope).toExpr(), op,scope);
		}
		throw new CompileException(node,
				node.kind == "class" ? "Missing ; after class definition"
						: "I think that this " + node + " should not be here.");
	}

	final Object[][] PRIMITIVE_TYPE_MAPPING = { { "()", UNIT_TYPE },
			{ "boolean", BOOL_TYPE }, { "char", CHAR_TYPE },
			{ "number", NUM_TYPE }, { "string", STR_TYPE } };

	JSExpr[] mapArgs(int start, Node[] args, JSScope scope) {
		if (args == null)
			return null;
		JSExpr[] res = new JSExpr[args.length - start];
		for (int i = start; i < args.length; ++i) {
			res[i - start] = analyze(args[i], scope).toExpr();
		}
		return res;
	}

	JSExpr objectRef(ObjectRefOp ref, JSScope scope) {
		if (ref.right instanceof Sym) {
			if (isJSObj(ref.right.sym())) {
				if (ref.arguments == null) {
					return new JSLitExpr(ref.name, ref);
				} else {
					return new JSObjApply(new JSLitExpr(ref.name, ref), mapArgs(0,
							ref.arguments, scope), ref);
				}
			}
		}
		JSExpr rightCode = analyze(ref.right, scope).toExpr();
		JSFieldRef fn = new JSFieldRef(rightCode, ref.name, ref.right);
		if (ref.arguments == null)
			return fn;
		else
			return new JSObjApply(fn, mapArgs(0, ref.arguments, scope), ref);
	}

	JSStat tryCatch(XNode t, JSScope scope) {
		JSTryBuilder tr = new JSTryBuilder(t);
		if (t.expr.length > 3
				|| (t.expr.length == 3 && t.expr[2].kind == "catch"))
			throw new CompileException(t, "Only one catch clause allowed");

		tr.body.add(analyze(t.expr[0], scope));

		Node fin = null;
		if (t.expr[1].kind == "catch") {
			XNode c = (XNode) t.expr[1];
			scope = scope.bind(c.expr[1].sym());
			tr.catchSym = scope.decl(c);
			tr.catz.add(analyze(c.expr[2], scope));
			if (t.expr.length == 3)
				fin = t.expr[2];
		} else
			fin = t.expr[1];

		if (fin != null)
			tr.fina.add(analyze(fin, scope));

		return tr.block();
	}

	private JSExpr createTag(final String variant, Node valueNd, JSScope scope) {
		if ("None".equals(variant)) {
			return JSCode.NULL;
		}
		final JSExpr value = analyze(valueNd, scope).toExpr();
		YType vt = null;
		if ("Some".equals(variant)) {
			if ((vt = valueNd.getType()) != null && vt.type != YetiType.VAR
					&& vt.type != YetiType.VARIANT) {
				return value;
			} else {
				return JSCode.buildIn("_tagS", value, valueNd).toExpr();
			}
		} else {
			return new JSExpr(valueNd) {
				int precedence() {
					return PREC_APPLY;
				}

				void code(CodeBuilder bd) {
					bd.add("new _tag(").add(Core.show(variant)).add(", ")
							.add(value).add(")");
				}

			};
		}
	}

	JSCode binApply(BinOp bfun, JSScope scope) {
		// check for Variant
		String sn = null;
		if (bfun.left instanceof Sym
				&& Character
						.isUpperCase((sn = ((Sym) bfun.left).sym).charAt(0))) {
			return createTag(sn, bfun.right, scope);
		}
		// otherwise throw gets escaped to use it like a function
		if (bfun.left instanceof Sym
				&& ("throw" == (sn = ((Sym) bfun.left).sym) || "failWith" == sn)) {
			return JSApply.create(scope.ref("throw", bfun.left),
					analyze(bfun.right, scope).toExpr(), bfun);
		}
		return JSApply.create(analyze(bfun.left, scope).toExpr(),
				analyze(bfun.right, scope).toExpr(), bfun);
	}

	JSCode apply(Node where, JSExpr fun, Node arg, JSScope scope) {
		return JSApply.create(fun, analyze(arg, scope).toExpr(), where);
	}

	JSExpr rsection(XNode section, JSScope scope) {
		String sym = section.expr[0].sym();
		if (sym == FIELD_OP) {
			LinkedList parts = new LinkedList();
			Node x = section.expr[1];
			for (BinOp op; x instanceof BinOp; x = op.left) {
				op = (BinOp) x;
				if (op.op != FIELD_OP)
					throw new CompileException(op, "Unexpected " + op.op
							+ " in field selector");
				parts.addFirst(getSelectorSym(op, op.right).sym);
			}

			parts.addFirst(getSelectorSym(section, x).sym);
			final String[] fields = (String[]) parts.toArray(new String[parts
					.size()]);

			return selectMemberFun(fields, section);
		}

		JSSym fun = scope.ref(sym, section);
		JSSym tv = new JSSym();
		JSExpr arg = analyze(section.expr[1], scope).toExpr();
		JSCode apl = JSApply.create(JSApply.create(fun, tv, section).toExpr(),
				arg, section);
		return new JSFun(null, tv, apl, section);
	}

	JSExpr selectMemberFun(String[] fields, Node nd) {

		final JSSym tv = new JSSym();
		final JSFieldSelector sel = new JSFieldSelector(fields, nd);

		return new JSFun(null, tv, new JSExpr(nd) {
			void code(CodeBuilder bd) {
				bd.add(tv).add(sel);
			};

			int precedence() {
				return PREC_FIELD;
			}
		}, nd);

	}

	Sym getSelectorSym(Node op, Node sym) {
		if (!(sym instanceof Sym)) {
			if (sym == null)
				throw new CompileException(op, "What's that dot doing here?");
			if (sym.kind != "``")
				throw new CompileException(sym, "Illegal ." + sym);
			sym = ((XNode) sym).expr[0];
		}
		return (Sym) sym;
	}

	JSStat cond(XNode condition, JSScope scope) {
		JSIfBuilder jsif = new JSIfBuilder(condition);

		for (;;) {
			JSExpr cond = analyze(condition.expr[0], scope).toExpr();
			JSCode body = analyze(condition.expr[1], scope);
			jsif.add(cond, body);

			if (condition.expr[2].kind != "if")
				break;

			condition = (XNode) condition.expr[2];
		}
		if (condition.expr[2].kind != "fi")
			jsif.add(null, analyze(condition.expr[2], scope));

		return jsif.block();
	}

	JSStat withStruct(BinOp with, JSScope scope) {

		JSBlock ret = new JSBlock("with", with);
		final JSSym tv = new JSSym();
		final JSSym tvr = new JSSym();
		final JSSym tv1 = new JSSym();
		final JSSym tv2 = new JSSym();

		ret.bind(tvr, JSCode.EMPTY_MAP, with);
		ret.bind(tv1, analyze(with.left, scope), with);
		ret.bind(tv2, analyze(with.right, scope), with);

		ret.add(new JSStat(with) {
			void code(CodeBuilder bd) {
				bd.add("for( var ").add(tv).add(" in ").add(tv1).add(")")
						.add(tvr).add("[").add(tv).add("] = ").add(tv1)
						.add("[").add(tv).add("];");
				bd.nl().add("for( ").add(tv).add(" in ").add(tv2).add(")")
						.add(tvr).add("[").add(tv).add("] = ").add(tv2)
						.add("[").add(tv).add("];");
			}
		});
		ret.add(tvr);
		return ret;
	}

	ScopedCode explodeStruct(Node where, ModuleType m, JSScope scope) {
		JSBlock ret = new JSBlock("moduleVars", where);
		if (m.type.type == STRUCT) {
			Iterator j = m.type.allowedMembers.entrySet().iterator();
			while (j.hasNext()) {
				Map.Entry e = (Map.Entry) j.next();
				String name = ((String) e.getKey()).intern();
				scope = scope.bind(name);
				ret.bind(scope.decl(where), new JSFieldRef(m.jsModuleVar,
						name, where), where);
			}
		} else if (m.type.type != UNIT) {
			throw new CompileException(where,
					"Expected module with struct or unit type here");
		}
		return new ScopedCode(scope,ret);
	}

	ScopedCode bindStruct(JSExpr structCode, XNode st, JSScope scope) {
		Node[] fields = st.expr;
		if (fields.length == 0)
			throw new CompileException(st, NONSENSE_STRUCT);

		JSBlock stat = new JSBlock("structbind", st);

		for (int j = 0; j < fields.length; ++j) {
			if (!(fields[j] instanceof Bind))
				throw new CompileException(fields[j],
						"Expected field pattern, not a " + fields[j]);
			Bind field = (Bind) fields[j];
			if (field.var || field.property)
				throw new CompileException(field, "Structure "
						+ "field pattern may not have modifiers");
			if (!(field.expr instanceof Sym) || (field.expr.sym()) == "_")
				throw new CompileException(field.expr,
						"Binding name expected, not a " + field.expr);

			String name = ((Sym) field.expr).sym();
			scope = scope.bind(name);
			stat.bind(scope.decl(field), new JSFieldRef(structCode,
					field.name, field), field);
		}
		return new ScopedCode(scope,stat);
	}

	ScopedCode analSeq(Seq seq, JSScope scope) {
		Node[] nodes = seq.st;
		JSBlock stat = new JSBlock("seq",seq);

		for (int i = 0; i < nodes.length; ++i) {
			if (nodes[i] instanceof Bind) {
				Bind bind = (Bind) nodes[i];
				XNode lambda;
				JSCode valueCode;
				if ((lambda = asLambda(bind.expr)) != null) {
					bind.expr = lambda;
					scope = scope.bind(bind.name);
					valueCode = lambda(lambda, scope, bind.var);
				} else {
					valueCode = analyze(bind.expr, scope);
					scope = scope.bind(bind.name);
					// if (code instanceof LoadModule)
					// scope = explodeStruct(bind, (LoadModule) code, scope,
					// bind.name.concat("."), depth - 1, false);
				}
				stat.bind(scope.decl(bind), valueCode, bind);
			} else if (nodes[i].kind == "struct-bind") {
				XNode x = (XNode) nodes[i];
				JSSym helperVar = new JSSym();
				stat.bind(helperVar, analyze(x.expr[1], scope), x);
				ScopedCode sc = bindStruct(helperVar, (XNode) x.expr[0], scope);
				scope = sc.scope;
				stat.add(sc.code);

			} else if (nodes[i].kind == "load") {
				ModuleType mt = (ModuleType) ((XNode) nodes[i]).expr[1];
				ScopedCode sc =explodeStruct(nodes[i], mt, scope);
				scope = sc.scope;
				stat.add(sc.code);
			} else if (nodes[i].kind == "import") {
				throw new CompileException(nodes[i],
						"import not supported in yjs");
				// Node[] imports = ((XNode) nodes[i]).expr;
				// for (int j=0; j < imports.length;j++) {
				// String name = imports[j].sym();
				// String source = ((Compiler)Compiler.currentCompiler.get())
				// .readJSSource(name);
				// stat.add(new SimpleJSExpr("import",source,JSExpr.PREC_LIT));
				//
				// }
			} else if (nodes[i] instanceof TypeDef) {
				// nothing
			} else if (nodes[i].kind == "class") {
				throw new CompileException(nodes[i], "No class can be defined");
			} else {
				stat.add(analyze(nodes[i], scope));
			}
		}
		return new ScopedCode(scope,stat);
	}

	JSExpr lambda(XNode lambda, JSScope scope, boolean bindToVar) {
		if (lambda.kind != "lambda")
			throw new CompileException(lambda, "Must be a function");

		//parent lambda scope
		final JSScope pfnScope = scope.findFnScope();
		scope = scope.fnScope();
		final JSScope fnScope = scope; //here we have our free vars

		// add deconstruction of struct-arg in here
		JSBlock varBody = new JSBlock(lambda);

		// last function body
		JSSym firstName = null;
		JSBlock body = null;
		final List<JSSym> argNames = new ArrayList<JSSym>(); // JSSym
		JSFun lastFun = null;
		JSFun firstFun = null;
		JSFun tcoFun = null;
		while (true) {
			body = new JSBlock(lambda);
			
			// name of function
			JSSym name = null;
			if (lambda.expr.length == 3) {
				String na = ((Sym) lambda.expr[2]).sym;
				scope = scope.bind(na);
				name = scope.decl(lambda.expr[2]);
			}
			if(firstName == null)
				firstName = name == null ? JSCode.NaN : name;
			
			// argument
			Node arg = lambda.expr[0];
			Node bodyNode = lambda.expr[1];
			JSSym argName = null;
			if(arg instanceof Sym) {
				scope = scope.bind(((Sym) arg).sym());
				argName = scope.decl(arg);
			}
			if (arg.kind == "()" || (argName != null && argName.sym == "_")) {
				argName = JSCode.NO_ARG;
			} else if (argName == null && arg.kind == "struct") {
				argName = new JSSym();
				ScopedCode sc = bindStruct(argName, (XNode) arg, scope);
				scope = sc.scope;
				varBody.add(sc.code);
			} else if (argName == null) {
				throw new CompileException(arg, "Bad argument: " + arg);
			}


			JSFun cur = new JSFun(name, argName, body, lambda);
			if (firstFun == null) {
				firstFun = cur;
				tcoFun = name == null ? null : firstFun;
			} else {
				lastFun.body.add(cur);
				lastFun.close();// close the body
			}
			if (name != null) {
				tcoFun = cur;
				argNames.clear();
			}
			argNames.add(argName);

			lastFun = cur;

			if ((lambda = asLambda(bodyNode)) == null) {
				//rebind name if var
				if(bindToVar && firstName != null && firstName != JSCode.NaN){
					scope = scope.bind(firstName.sym);
					varBody.bind(scope.decl(bodyNode), firstName, bodyNode);
				}
				lastFun.body.addFlat(varBody); // the destructured args
				lastFun.body.addFlat(analyze(bodyNode, scope)); // the meat
				JSSym[] args = (JSSym[]) argNames.toArray(new JSSym[argNames
						.size()]);
				// Is a tail call
				if (!bindToVar
						&& tcoFun != null
						&& JSFun.isBodyTCO(tcoFun.name.sym, args, lastFun.body,
								true, null)) {
					fnScope.tcoArgs = argNames; //mark our scope as TCO
					JSSym tv = new JSSym();
					JSBlock nBody = new JSBlock(lambda);
					nBody.bind(tv, JSCode.UNDEF, lambda);
					// transform it
					JSFun.isBodyTCO(tcoFun.name.sym, args, lastFun.body, false, tv);
					JSWhile wl = new JSWhile(JSCode.TRUE, lastFun.body.copy(),
							lambda);
					nBody.add(wl);
					nBody.add(tv);
					lastFun.body.stats.clear();
					lastFun.body.stats.addAll(nBody.stats);
				}
				lastFun.close();// close it
				
				final String pName = name == null? "" :name.sym;
				firstFun.capture = new Callable<Set<JSSym>>() {
					@Override
					public Set<JSSym> call() throws Exception {
						if(pfnScope.tcoArgs != null){
							Set<JSSym> ret = new HashSet<JSSym>();
							
							for(String yn:fnScope.freeVars) {
								JSSym s = fnScope.ref(yn, null);
								if(pfnScope.tcoArgs.contains(s))
									ret.add(s);
							}
							return ret;
						}else{
							return Collections.emptySet();
						}
					}
				};
				return firstFun;
			}
			// go on
		}
	}

	Bind getField(Node node) {
		if (!(node instanceof Bind))
			throw new CompileException(node,
					"Unexpected beast in the structure (" + node
							+ "), please give me some field binding.");
		return (Bind) node;
	}

	static void duplicateField(Bind field) {
		throw new CompileException(field, "Duplicate field " + field.name
				+ " in the structure");
	}
	//TODO: check scoping in here
	ScopedCode structType(XNode st, JSScope scope) {
		Node[] nodes = st.expr;
		if (nodes.length == 0)
			throw new CompileException(st, NONSENSE_STRUCT);
		
		Map<String,JSCode> valueMap = new IdentityHashMap(nodes.length);
		
		
		//Split into value and fun fields
		Map<String, Node> fields = new HashMap<>();
		Map<Bind,XNode> funs = new HashMap<>();
		JSScope tScope = scope; //scope to hold all the field names
		//JSObj obj = new JSObj();
		for(int i=0;i < nodes.length;++i){
			Bind field = getField(nodes[i]);
			if(fields.containsKey(field.name) || funs.containsKey(field.name))
				duplicateField(field);
				
			XNode fun = !field.noRec && field.expr.kind == "lambda" ? asLambda(
					field.expr) : null;
			if(fun == null)
				fields.put(field.name, field.expr);
			else
				funs.put(field, fun);
			
			tScope = tScope.bind(field.name);
		}
		
		//create the object literal for all the fields
		JSObjLiteral objLit = new JSObjLiteral(st);
		for(Map.Entry<String, Node> e: fields.entrySet()){
			objLit.add(e.getKey(), analyze(e.getValue(),scope).toExpr());
		}
		if(funs.isEmpty())
			return new ScopedCode(scope,objLit);
		
		//have functions so we have to put all the fields in scope
		scope = tScope;
		
		//the code block and bind the fields
		JSBlock block = new JSBlock("obj",st);
		JSSym tv = new JSSym();
		block.bind(tv, objLit, st);
		
		//all the fields as vars
		for(Map.Entry<String, Node> e: fields.entrySet()){
			block.bind(scope.ref(e.getKey(), e.getValue()), 
					new JSFieldRef(tv,e.getKey(),e.getValue()), 
					e.getValue());
		}
		
		//all the functions as vars and evaluated in full struct scope
		for(Map.Entry<Bind, XNode> e: funs.entrySet()){
			Node nd = e.getKey();
			String name = e.getKey().name;
			JSSym ref = scope.ref(name,e.getValue());
			block.bind(scope.ref(name, e.getValue()), 
					lambda(e.getValue(), scope, e.getKey().var).toExpr(), 
					e.getValue());
			block.add(new JSAssign(
					new JSFieldRef(tv,name,nd), 
					ref, 
					nd));
		}
		block.add(tv);
		return new ScopedCode(scope,block);
	}

	JSExpr list(Node list, Node[] items, JSScope scope) {
		if (items == null)
			return new JSMap(list);

		JSMap map = new JSMap(list);
		JSList ls = new JSList(list);

		YType kind = null;

		BinOp bin;
		XNode keyNode = null;
		int n = 0;
		for (int i = 0; i < items.length; ++i, ++n) {
			if (items[i].kind == ":") {
				if (keyNode != null)
					throw new CompileException(items[i],
							"Expecting , here, not :");
				keyNode = (XNode) items[i];
				if (kind == LIST_TYPE)
					throw new CompileException(
							keyNode,
							"Unexpected : in list"
									+ (i != 1 ? ""
											: " (or the key is missing on the first item?)"));
				--n;
				kind = MAP_TYPE;
				continue;
			}
			if (keyNode != null) {
				JSExpr key = analyze(keyNode.expr[0], scope).toExpr();
				if (kind != MAP_TYPE) {
					kind = MAP_TYPE;
				}
				map.add(key, analyze(items[i], scope).toExpr());
				keyNode = null;
			} else {
				if (kind == MAP_TYPE)
					throw new CompileException(items[i],
							"Map item is missing a key");
				kind = LIST_TYPE;
				if (items[i] instanceof BinOp
						&& (bin = (BinOp) items[i]).op == "..") {
					ls.range(analyze(bin.left, scope).toExpr(),
							analyze(bin.right, scope).toExpr(), items[i]);
				} else {
					ls.add(analyze(items[i], scope).toExpr());
				}
			}
		}
		if (kind == MAP_TYPE)
			return map;
		else
			return ls;

	}

	static JSCode toCode(Compiler ctx, String className, String[] preload,
			Node n) {
		JSAnalyzer anal = new JSAnalyzer(ctx);
		// dirty hack for preloading just in root contxt
		JSScope scope = JSScope.ROOT;
		if("std".equals(className)){
			JSBlock ret = new JSBlock(n);
			ret.addFlat(anal.analyze(n, scope));
			return ret;
		}
		if (ctx.rootJSScope == null) {
			//compiling std first, because YetiAnalyzer loads it through
			//preloads
			ctx.rootJSScope = scope.fnScope();
			for (int i = 0; i < preload.length; i++) {
				String mn = preload[i];
				ModuleType t = ctx.getType(n, mn);
				ScopedCode sc =anal.explodeStruct(n, t, ctx.rootJSScope);
				ctx.rootJSScope = sc.scope;
				ctx.mainJS.add(sc.code);
			}
		}

		scope = ctx.rootJSScope;
		JSBlock ret = new JSBlock(n);

		ret.addFlat(anal.analyze(n, scope));
		return ret;
	}
}
