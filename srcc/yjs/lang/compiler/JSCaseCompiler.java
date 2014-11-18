/**
 * YJS case compiler.
 * Copyright (c) 2007-2014 Christian Essl
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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import yeti.lang.Core;
import yjs.lang.compiler.JSAnalyzer.JSScope;

final class JSCaseCompiler extends YetiType 
	implements Comparable<JSCaseCompiler>{
	
	final JSBlock global;
	final JSAnalyzer anal;
	JSAnalyzer.JSScope scope;
	final Set<String> definedVars ;
	
	int prec;
	JSCode body;
	JSExpr condExpr;
	
	JSCaseCompiler(JSAnalyzer anal, JSScope scope, 
			JSBlock global, 
			Set<String> definedVars) {
		this.global = global;
		this.anal = anal;
		this.scope = scope;
		this.definedVars = definedVars;
	}

	private JSSym addVar(String name, Node n) {
		JSSym var;
		if (name == null)
			return new JSSym();
		else{
			if(definedVars.contains(name)) 
				return scope.ref(name, n);
			definedVars.add(name);
			scope = scope.bind(name);
			JSSym sy = scope.decl(n);
			global.bind(sy, JSCode.UNDEF, n);
			return sy;
		}
	}
	public int compareTo(JSCaseCompiler arg0) {
		return prec - arg0.prec;
	}

	void makePattern(final Node node, final JSExpr val) {
		this.condExpr = toPattern(node,val);
	}
	
	private JSExpr toPattern(final Node node, final JSExpr val) {
		if (node instanceof Sym) {
			String name = node.sym();
			if (name == "_" || name == "..."){
				this.prec = 5;
				return JSCode.TRUE;
			}
			this.prec = 0;
			return new JSSeqExp(addVar(name, node), val,
					JSCode.TRUE, node);
		}
		if (node.kind == "()") {
			this.prec = 5;
			return JSCode.TRUE;
		}
		if (node instanceof NumLit || node instanceof Str
				|| node instanceof ObjectRefOp) {
			JSExpr v = anal.analyze(node, scope).toExpr();
			this.prec = 0;
			return new JSBinOp("==", v, val, node).toExpr();
		}
		if (node.kind == "list") {
			Node[] list = ((XNode) node).expr;
			final JSExpr[] items = new JSExpr[list.length];

			for (int i = 0; i < items.length; i++) {
				items[i] = toPattern(list[i], new JSArrRef(val, i, list[i]));
			}

			JSExpr ret = new JSExpr(node) {
				void code(CodeBuilder bd) {
					bd.add("(").add(val).add(".length === ")
							.add("" + items.length).add(" && (")
							.addAll(items, ") && (").add("))");
				};

				int precedence() {
					return PREC_GROUP;
				};

			};
			this.prec = 0;
			return ret;
		}
		if (node instanceof BinOp) {
			final BinOp pat = (BinOp) node;
			if (pat.op == "" && pat.left instanceof Sym) {
				String variant = pat.left.sym();
				JSExpr valPat = toPattern(pat.right, new JSFieldRef(val,
						"value", node));

				JSExpr expr = JSBinOp.create(
						"and",
						new JSBinOp("===", 
								new JSLitExpr(Core.show(variant), pat.left),
								new JSFieldRef(val, "tag", pat.left), node)
								.toExpr(), valPat, node,scope).toExpr();
				expr = JSBinOp.create(
						"and",
						new JSBinOp("instanceof", val, new JSLitExpr("_tag", node),
								node), expr, node,scope).toExpr();
				if ("None".equals(variant)) {
					expr = JSBinOp
							.create("or",
									new JSBinOp("===", val, JSCode.NULL, node)
											.toExpr(), expr, node,scope).toExpr();
					this.prec = -1;
					return expr;
				}
				if ("Some".equals(variant)) {
					expr = JSBinOp.create("or", expr,
							toPattern(pat.right, val), node,scope).toExpr();
					this.prec = 4;
					return expr;
				}
				this.prec = 0;
				return expr;
			}
			if (pat.op == "::") {
				final JSSym tvl = addVar(null, node);
				final JSSym tvr = addVar(null, node);
				final JSSeqExp le = new JSSeqExp(tvl, JSCode.buildIn("head",
						val, node).toExpr(), toPattern(pat.left, tvl),
						node);
				final JSSeqExp re = new JSSeqExp(tvr, JSCode.buildIn("tail",
						val, node).toExpr(), toPattern(pat.right, tvr),
						node);

				JSExpr expr = new JSExpr(node) {
					int precedence() {
						return PREC_GROUP;
					};

					void code(CodeBuilder bd) {
						bd.add("(").add(val).add(".length > 0").add(" && ")
								.add(le).add(" && ").add(re).add(")");
					};
				};
				this.prec = 0;
				return expr;
			}
		}
		if (node.kind == "struct") {
			Node[] fields = ((XNode) node).expr;
			final JSExpr[] patterns = new JSExpr[fields.length + 2];
			final JSSym tv = addVar(null, node);

			patterns[0] = new JSAssign(tv, val, node);
			for (int i = 0; i < fields.length; i++) {
				Bind field = YetiAnalyzer.getField(fields[i]);
				JSExpr stru = new JSFieldRef(tv, field.name, field);
				patterns[i + 1] = toPattern(field.expr, stru);
			}
			patterns[fields.length + 1] = JSExpr.TRUE;

			this.prec = 0;
			return new JSSeqExp(patterns, node);
		}
		throw new CompileException(node, "Bad case pattern: " + node);
	}

	static JSCode caseType(XNode ex, JSAnalyzer anal, JSAnalyzer.JSScope scope) {
		Node[] choices = ex.expr;
		JSBlock global =new JSBlock("case", ex); 
		Set<String> definedVars = new HashSet<String>();
		
		JSSym val = new JSSym();
		global.bind(val, anal.analyze(choices[0], scope).toExpr(), null);
		JSIfBuilder jsif = new JSIfBuilder(ex);

		JSCaseCompiler[] pats = new JSCaseCompiler[choices.length - 1];
		for (int i = 1; i < choices.length; ++i) {
			JSCaseCompiler cc = 
					new JSCaseCompiler(anal, scope, global,definedVars);
			pats[i - 1] = cc;
			XNode choice = (XNode) choices[i];
			cc.makePattern(choice.expr[0], val);
			scope = cc.scope;
			if (choice.expr[0] instanceof Sym
					&& ((Sym) choice.expr[0]).sym == "...")
				cc.body = JSCode.UNDEF;
			else
				cc.body = anal.analyze(choice.expr[1],scope);
			
		}
		Arrays.sort(pats);
		for (int i = 0; i < pats.length; i++) {
			jsif.add(pats[i].condExpr, pats[i].body);
		}
		global.add(jsif.block());
		return global;
	}
}
