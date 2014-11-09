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

import yeti.lang.Core;

final class JSCaseCompiler extends YetiType {
	final JSBlock global;

	final JSAnalyzer anal;
	final JSAnalyzer.JSScope scope;
	final Node node;

	JSCaseCompiler(JSAnalyzer anal, JSAnalyzer.JSScope scope, Node nd) {
		this.global = new JSBlock("case", nd);
		this.anal = anal;
		this.scope = scope;
		this.node = nd;
	}

	private JSSym addVar(String name, Node n) {
		JSSym var;
		if (name == null)
			var = new JSSym();
		else
			var = new JSSym(name, n);
		if (name == null || !scope.vars.contains(var.sym)) {
			scope.bind(var.sym, n);
			global.bind(var, JSCode.UNDEF, n);
		}
		return var;
	}

	final private static class CPattern implements Comparable {
		static final CPattern TRUE = new CPattern(JSCode.TRUE, 5);
		final JSExpr expr;
		final int prec;
		JSCode body = null;

		CPattern(JSExpr expr, int prec) {
			this.expr = expr;
			this.prec = prec;
		}

		public int compareTo(Object arg0) {
			return prec - ((CPattern) arg0).prec;
		}

	}

	CPattern toPattern(final Node node, final JSExpr val) {
		if (node instanceof Sym) {
			String name = node.sym();
			if (name == "_" || name == "...")
				return CPattern.TRUE;

			return new CPattern(new JSSeqExp(addVar(name, node), val,
					JSCode.TRUE, node), 0);
		}
		if (node.kind == "()") {
			return CPattern.TRUE;
		}
		if (node instanceof NumLit || node instanceof Str
				|| node instanceof ObjectRefOp) {
			JSExpr v = anal.analyze(node, scope).toExpr();
			return new CPattern(new JSBinOp("==", v, val, node).toExpr(), 0);
		}
		if (node.kind == "list") {
			Node[] list = ((XNode) node).expr;
			final JSExpr[] items = new JSExpr[list.length];

			for (int i = 0; i < items.length; i++) {
				items[i] = toPattern(list[i], new JSArrRef(val, i, list[i])).expr;
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
			return new CPattern(ret, 0);
		}
		if (node instanceof BinOp) {
			final BinOp pat = (BinOp) node;
			if (pat.op == "" && pat.left instanceof Sym) {
				String variant = pat.left.sym();
				JSExpr valPat = toPattern(pat.right, new JSFieldRef(val,
						"value", node)).expr;

				JSExpr expr = JSBinOp.create(
						"and",
						new JSBinOp("===", new JSLitExpr(Core.show(variant),
								pat.left),
								new JSFieldRef(val, "tag", pat.left), node)
								.toExpr(), valPat, node).toExpr();
				expr = JSBinOp.create(
						"and",
						new JSBinOp("instanceof", val, new JSSym("_tag", node),
								node), expr, node).toExpr();
				if ("None".equals(variant)) {
					expr = JSBinOp
							.create("or",
									new JSBinOp("===", val, JSCode.NULL, node)
											.toExpr(), expr, node).toExpr();
					return new CPattern(expr, -1);
				}
				if ("Some".equals(variant)) {
					expr = JSBinOp.create("or", expr,
							toPattern(pat.right, val).expr, node).toExpr();
					return new CPattern(expr, 4);
				}
				return new CPattern(expr, 0);
			}
			if (pat.op == "::") {
				final JSSym tvl = addVar(null, node);
				final JSSym tvr = addVar(null, node);
				final JSSeqExp le = new JSSeqExp(tvl, JSCode.buildIn("head",
						val, node).toExpr(), toPattern(pat.left, tvl).expr,
						node);
				final JSSeqExp re = new JSSeqExp(tvr, JSCode.buildIn("tail",
						val, node).toExpr(), toPattern(pat.right, tvr).expr,
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
				return new CPattern(expr, 0);
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
				patterns[i + 1] = toPattern(field.expr, stru).expr;
			}
			patterns[fields.length + 1] = JSExpr.TRUE;

			return new CPattern(new JSSeqExp(patterns, node), 0);
		}
		throw new CompileException(node, "Bad case pattern: " + node);
	}

	static JSCode caseType(XNode ex, JSAnalyzer anal, JSAnalyzer.JSScope scope) {
		Node[] choices = ex.expr;
		JSCaseCompiler cc = new JSCaseCompiler(anal, scope, ex);
		JSSym val = new JSSym();
		cc.global.bind(val, anal.analyze(choices[0], scope).toExpr(), null);
		JSIfBuilder jsif = new JSIfBuilder(ex);

		CPattern[] pats = new CPattern[choices.length - 1];
		for (int i = 1; i < choices.length; ++i) {
			XNode choice = (XNode) choices[i];
			pats[i - 1] = cc.toPattern(choice.expr[0], val);
			if (choice.expr[0] instanceof Sym
					&& ((Sym) choice.expr[0]).sym == "...")
				pats[i - 1].body = JSCode.UNDEF;
			else
				pats[i - 1].body = anal.analyze(choice.expr[1],
						new JSAnalyzer.JSScope(scope, true));
		}
		scope.unite();
		Arrays.sort(pats);
		for (int i = 0; i < pats.length; i++) {
			jsif.add(pats[i].expr, pats[i].body);
		}
		cc.global.add(jsif.block());
		return cc.global;
	}
}
