/**
 * YJS javascript code nodes
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import yjs.lang.compiler.JSAnalyzer.JSScope;

import yeti.lang.Core;
import yjs.lang.compiler.YetiParser.Node;

class ScopedCode {
	final JSCode code;
	final JSScope scope;
	public ScopedCode(JSScope sc, JSCode cd) {
		this.code = cd;
		this.scope = sc;
	}
}
class CodeBuilder {

	static final char[] mangle = "jQh$oBz  apCmds          cSlegqt"
			.toCharArray();

	static final String mangle(String s) {
		char[] a = s.toCharArray();
		char[] to = new char[a.length * 2];
		int l = 0;
		for (int i = 0, cnt = a.length; i < cnt; ++i, ++l) {
			char c = a[i];
			if (c > ' ' && c < 'A' && (to[l + 1] = mangle[c - 33]) != ' ') {
			} else if (c == '^') {
				to[l + 1] = 'v';
			} else if (c == '|') {
				to[l + 1] = 'I';
			} else if (c == '~') {
				to[l + 1] = '_';
			} else {
				to[l] = c;
				continue;
			}
			to[l++] = '$';
		}
		return new String(to, 0, l);
	}

	final StringBuilder bd = new StringBuilder();

	int ident = 0;

	CodeBuilder ind(int dif) {
		ident = Math.max(0, ident + dif);
		return this;
	}

	CodeBuilder ind() {
		return ind(1);
	}

	CodeBuilder dnd() {
		return ind(-1);
	}

	CodeBuilder add(String str) {
		bd.append(str);
		return this;
	}

	CodeBuilder addMangled(String str) {
		return this.add(CodeBuilder.mangle(str));
	}

	CodeBuilder add(JSCode cd) {
		cd.code(this);
		return this;
	}

	CodeBuilder addBraces(JSCode cd) {
		return add("(").add(cd).add(")");
	}

	CodeBuilder addAll(JSCode[] cds, String sep) {
		if (cds == null)
			return this;
		boolean first = true;
		for (int i = 0; i < cds.length; i++) {
			if (cds[i] == null)
				continue;
			if (!first) {
				bd.append(sep);
			} else {
				first = false;
			}
			this.add(cds[i]);
		}
		return this;
	}

	CodeBuilder addAll(Collection col, String sep) {
		if (col == null)
			return this;
		JSCode[] cds = new JSCode[col.size()];
		cds = (JSCode[]) col.toArray(cds);
		this.addAll(cds, sep);
		return this;
	}

	CodeBuilder nl() {
		bd.append("\n");
		for (int i = 0; i < ident; i++) {
			bd.append("    ");
		}
		return this;
	}

	CodeBuilder se() {
		bd.append(";");
		return this;
	}

	public String toString() {
		return bd.toString();
	}

	String str() {
		return toString();
	}

}

abstract class JSCode {
	public static final JSExpr TRUE = new JSLitExpr("true", null);
	public static final JSExpr FALSE = new JSLitExpr("false", null);
	public static final JSLitExpr UNDEF = new JSLitExpr("undefined", null);
	public static final JSExpr EMPTY_LIST = new JSLitExpr("[]", null);
	public final static JSLitExpr EMPTY_MAP = new JSLitExpr("{}", null);
	public static final JSSym NO_ARG = JSScope.ROOT.ref("", null);
	public static final JSLitExpr NULL = new JSLitExpr("null", null);
	public static final JSSym UNDEF_STR = JSScope.ROOT.ref("undef_str", null);
	public static final JSSym NaN = JSScope.ROOT.ref("naN", null);
	public static final JSCode CONTINUE = new JSLitExpr("continue", null);
	public static final JSCode BREAK = new JSLitExpr("break", null);

	static JSCode buildIn(String name, JSExpr arg, Node node) {
		return JSApply.create(new JSLitExpr(name,node), arg, node);
	}

	final YetiParser.Node node;

	JSCode(YetiParser.Node node) {
		this.node = node;
	}

	YType getType() {
		if (node == null)
			return null;
		return node.getType();
	}

	abstract JSExpr toExpr();

	abstract JSStat toStat();

	abstract void code(CodeBuilder bd);

}

abstract class JSExpr extends JSCode {
	JSExpr(Node node) {
		super(node);
	}

	final JSExpr toExpr() {
		return this;
	}

	final JSStat toStat() {
		return new JSExprStat(this);
	}

	abstract int precedence();

	static final int PREC_LIT = -1;
	static final int PREC_GROUP = 0;
	static final int PREC_FIELD = 1;
	static final int PREC_APPLY = 1;
	static final int PREC_NOT = 2;
	static final int PREC_BIN = 5;
	static final int PREC_FUN = 10;
	static final int PREC_RL = 15;

	static JSExpr group(JSExpr expr, JSExpr right) {
		if (expr == null
				|| (expr.precedence() != PREC_RL && expr.precedence() <= right
						.precedence()))
			return expr;
		else
			return new JSGroup(expr);
	}
}

abstract class JSStat extends JSCode {
	JSStat(Node node) {
		super(node);
	}

	final JSStat toStat() {
		return this;
	}

	JSExpr toExpr() {
		return new IIFEJSExpr(this);
	}

}

class JSGroup extends JSExpr {

	final JSExpr expr;

	public JSGroup(JSExpr expr) {
		super(expr.node);
		this.expr = expr;
	}

	int precedence() {
		return PREC_GROUP;
	}

	void code(CodeBuilder bd) {
		bd.add("(").add(expr).add(")");
	}

}

class JSAssign extends JSExpr {
	final JSExpr left;
	final JSExpr right;

	public JSAssign(JSExpr left, JSExpr right, Node node) {
		super(node);
		this.left = left;
		this.right = right;
	}

	int precedence() {
		return PREC_RL;
	}

	void code(CodeBuilder bd) {

		bd.add(left).add(" = ").add(right);
	}
}

class JSBlock extends JSStat {
	final List stats = new ArrayList();

	final String kind;

	public JSBlock(String kind, Node node) {
		super(node);
		this.kind = kind;
	}

	public JSBlock(Node node) {
		this(null, node);
	}

	JSBlock copy() {
		JSBlock ret = new JSBlock(kind, node);
		ret.stats.addAll(this.stats);
		return ret;
	}

	JSCode last() {
		if (stats.isEmpty())
			return null;
		return (JSCode) stats.get(stats.size() - 1);
	}

	void replaceLast(JSCode st) {
		stats.set(stats.size() - 1, st);
	}

	void unbracedCode(CodeBuilder bd) {
		Iterator it = stats.iterator();
		while (it.hasNext()) {
			bd.nl().add(((JSCode) it.next()).toStat());
		}
	}

	void code(CodeBuilder bd) {
		bd.add("{").ind(1);
		unbracedCode(bd);
		bd.ind(-1).nl().add("}");
	};

	JSBlock add(JSCode code) {
		JSBlock seq;
		if (code instanceof JSBlock && (seq = (JSBlock) code).kind != null) {
			stats.addAll(seq.stats);
		} else {
			stats.add(code);
		}
		return this;
	}

	JSBlock addFlat(JSCode code) {
		if (code instanceof JSBlock) {
			this.stats.addAll(((JSBlock) code).stats);
		} else {
			add(code);
		}
		return this;
	}

	boolean isBound() {
		return last() instanceof JSStat;
	}

	JSBlock bind(JSExpr s, JSCode expr, Node nd) {
		JSBlock ot = null;
		if (expr instanceof JSBlock && (ot = (JSBlock) expr).kind != null) {
			this.stats.addAll(ot.stats);
			bindLast(s, true, nd);
		} else {
			add(new Bind(s, expr.toExpr(), nd));
		}
		return this;
	}

	void bindLast(JSExpr s, boolean var, Node nd) {
		JSCode l = last();
		if (l instanceof JSExpr) {
			if (var)
				l = new Bind(s, (JSExpr) l, nd);
			else
				l = new JSAssign(s, (JSExpr) l, nd);
			replaceLast(l);
		} else {
			if (var)
				l = new Bind(s, JSCode.UNDEF, nd);
			else
				l = new JSAssign(s, JSCode.UNDEF, nd);
			stats.add(l);
		}
	}

	private static final class Bind extends JSStat {
		final JSExpr var;
		final JSExpr expr;

		Bind(JSExpr var, JSExpr expr, Node nd) {
			super(nd);
			this.var = var;
			this.expr = expr;
		}

		JSExpr toExpr() {
			if (expr instanceof JSFun)
				return expr;
			return super.toExpr();
		}

		void code(CodeBuilder bd) {
			bd.add("var ").add(var).add(" = ").add(expr).se();
		}
	}

}

final class JSList extends JSExpr {
	final List exprsList = new ArrayList();

	JSList(Node node) {
		super(node);
	}

	JSList add(JSExpr expr) {
		List l;
		Object last;
		if (exprsList.isEmpty()
				|| !((last = exprsList.get(exprsList.size() - 1)) instanceof List)) {
			l = new ArrayList();
			exprsList.add(l);
		} else {
			l = (List) last;
		}
		l.add(expr);
		return this;
	}

	void range(final JSExpr from, final JSExpr to, Node node) {
		exprsList.add(JSApply.create(
				JSCode.buildIn("range", from, node).toExpr(), to, node)
				.toExpr());
	}

	void code(CodeBuilder bd) {
		if (exprsList.isEmpty()) {
			bd.add("[]");
			return;
		}
		if (exprsList.size() == 1) {
			Object last = exprsList.get(exprsList.size() - 1);
			singleCode(bd, last);
			return;
		}

		Iterator it = exprsList.iterator();
		boolean first = true;
		while (it.hasNext()) {
			if (!first) {
				bd.add(".concat(");
			}
			singleCode(bd, it.next());
			if (first) {
				first = false;
			} else {
				bd.add(")");
			}
		}
	}

	private void singleCode(CodeBuilder bd, Object last) {
		if (last instanceof List) {
			bd.add("[").addAll((List) last, ", ").add("]");
		} else {
			bd.add((JSExpr) last);
		}
	}

	int precedence() {
		return PREC_LIT;
	}

}

final class JSMapRef extends JSExpr {
	final JSExpr map;
	final JSExpr key;

	public JSMapRef(JSExpr map, JSExpr key) {
		super(map.node);
		this.map = map;
		this.key = key;
	}

	void code(CodeBuilder bd) {
		bd.add(group(map, this)).add("[").add(key).add("]");
	}

	int precedence() {
		return PREC_FIELD;
	}

}

final class JSMap extends JSExpr {
	final JSSym tempVar = new JSSym();
	final JSBlock seq = new JSBlock("jsmap", null);

	class Entry extends JSStat {
		final JSExpr key;
		final JSExpr value;

		Entry(JSExpr key, JSExpr value, Node node) {
			super(node);
			this.key = key;
			this.value = value;
		}

		void code(CodeBuilder bd) {
			bd.add(new JSMapRef(tempVar, key)).add(" = ").add(value).se();
		};
	}

	public JSMap(Node node) {
		super(node);
		seq.bind(tempVar, new JSLitExpr("{}", node), null);
	}

	void add(JSExpr key, JSExpr value) {
		seq.add(new Entry(key, value, value.node));
	}

	void code(CodeBuilder bd) {
		if (seq.stats.size() == 1)
			bd.add(JSCode.EMPTY_MAP);
		else {
			JSBlock seq = this.seq.copy();
			seq.add(tempVar);
			bd.add(seq.toExpr());
		}
	}

	int precedence() {
		return PREC_GROUP;
	}
}

final class JSSym extends JSExpr {
	final static Set reserved = new HashSet(Arrays.asList(new String[] {
			"break", "extends", "switch", "case", "finally", "this", "class",
			"for", "catch", "function", "try", "const", "if", "typeof",
			"continue", "import", "var", "debugger", "in", "void", "default",
			"instanceof", "while", "delete", "let", "with", "do", "new",
			"yield", "else", "return", "export", "super", "enum", "await",
			"implements", "static", "public", "package", "interface",
			"protected", "private", "null", "abstract", "float", "short",
			"boolean", "goto", "synchronized", "byte", "int", "transient",
			"char", "long", "volatile", "double", "native", "final" }));

	private static long counter = 0;
	final String sym;
	private final String code;

	JSSym() {
		super(null);
		this.sym = "_$v" + counter++;
		this.code = sym;
	}

	JSSym(String sym, Node node) {
		super(node);
		this.sym = sym;

		if (reserved.contains(sym) || sym.startsWith("_$")) {
			this.code = "_$" + CodeBuilder.mangle(sym);
		} else {
			this.code = CodeBuilder.mangle(sym);
		}
	}


	String mangled() {
		return code;
	}

	void code(CodeBuilder bd) {
		bd.add(code);
	}

	int precedence() {
		return PREC_LIT;
	}
}

class JSExprStat extends JSStat {
	final JSExpr expr;

	public JSExprStat(JSExpr expr) {
		super(expr.node);
		this.expr = expr;
	}

	JSExpr toExpr() {
		return expr;
	}

	void code(CodeBuilder bd) {
		bd.add(expr).se();
	}
}

class SimpleJSExpr extends JSExpr {
	private final String kind;
	private final String code;
	private final int precedence;

	SimpleJSExpr(String kind, String code, int precedence, Node node) {
		super(node);
		this.kind = kind;
		this.code = code;
		this.precedence = precedence;
	}

	void code(CodeBuilder bd) {
		bd.add(code);
	}

	int precedence() {
		return precedence;
	}

}

class JSLitExpr extends JSExpr {
	private final String code;

	public JSLitExpr(String code, Node node) {
		super(node);
		this.code = code;
	}

	void code(CodeBuilder bd) {
		bd.add(code);
	}

	int precedence() {
		return PREC_LIT;
	}

}

class JSWhile extends JSStat {
	final JSBlock body;
	final JSExpr cond;

	public JSWhile(JSExpr cond, JSCode body, Node node) {
		super(node);
		if (body instanceof JSBlock) {
			this.body = (JSBlock) body;
		} else {
			this.body = new JSBlock(body.node);
			this.body.add(body);
		}
		this.cond = cond;
	}

	void code(CodeBuilder bd) {
		bd.add("while(").add(cond).add(")").add(body);
	}
}

class JSConcatStr extends JSExpr {
	final List exprs = new ArrayList();

	public JSConcatStr(Node node) {
		super(node);
	}

	void add(JSExpr expr) {
		exprs.add(expr);
	}

	void code(CodeBuilder bd) {
		bd.add("\"\"");
		Iterator it = exprs.iterator();
		while (it.hasNext()) {
			bd.add(" + ").add(new JSGroup((JSExpr) it.next()));
		}
	}

	int precedence() {
		return PREC_BIN;
	}

}

interface IJSBlockExpr {

}

class JSTryBuilder {
	final JSBlock body = new JSBlock(null);
	JSSym catchSym = null;
	final JSBlock catz = new JSBlock(null);
	final JSBlock fina = new JSBlock(null);
	final JSSym tv = new JSSym();

	private final Node node;

	JSTryBuilder(Node node) {
		this.node = node;
	}

	JSBlock block() {
		JSBlock rs = new JSBlock("try", null);
		rs.bind(tv, JSCode.UNDEF, null);
		rs.add(new TryStat());
		rs.add(tv);
		return rs;
	}

	class TryStat extends JSStat {
		TryStat() {
			super(JSTryBuilder.this.node);
		}

		void code(CodeBuilder bd) {
			JSBlock body = JSTryBuilder.this.body.copy();
			JSBlock catz = JSTryBuilder.this.catz.copy();
			JSBlock fina = JSTryBuilder.this.fina.copy();

			body.bindLast(tv, false, null);
			bd.add("try").add(body);

			if (!catz.stats.isEmpty()) {
				catz.bindLast(tv, false, null);
				bd.add("catch (").add(catchSym).add(")").add(catz);
			}

			if (!fina.stats.isEmpty()) {
				fina.bindLast(tv, false, null);
				bd.add("finally").add(fina);
			}
		}
	}
}

class JSIfBuilder {
	static class Clause {
		final JSExpr cond;
		final JSBlock body;

		public Clause(JSExpr cond, JSCode body) {
			if (body instanceof JSBlock)
				this.body = (JSBlock) body;
			else {
				this.body = new JSBlock(body.node);
				this.body.add(body);
			}
			this.cond = cond;
		}
	}

	final List clauses = new ArrayList();
	final JSSym var = new JSSym();
	final Node node;

	JSIfBuilder(Node node) {
		this.node = node;
	}

	JSIfBuilder add(JSExpr cond, JSCode body) {
		clauses.add(new Clause(cond, body));
		return this;
	}

	JSBlock block() {
		final JSBlock cd = new JSBlock("if", node);
		cd.bind(var, JSCode.UNDEF, null);

		cd.add(new IfStat());
		cd.add(var);
		return cd;
	}

	class IfStat extends JSStat {
		boolean bound = false; // used by toc to bind

		IfStat() {
			super(JSIfBuilder.this.node);
		}

		JSIfBuilder getBuilder() {
			return JSIfBuilder.this;
		}

		void code(CodeBuilder bd) {
			boolean first = true;
			Iterator it = clauses.iterator();
			while (it.hasNext()) {
				if (first)
					first = false;
				else
					bd.add(" else ");
				Clause cl = (Clause) it.next();
				if (cl.cond != null) {
					bd.add("if (").add(cl.cond).add(") ");
				}
				JSBlock body = cl.body.copy();
				if (!bound)
					body.bindLast(var, false, null);
				bd.add(body);
			}
			bd.add(";");
		}
	}
}

class JSSeqExp extends JSExpr {

	final JSExpr[] exprs;

	JSSeqExp(JSExpr[] exprs, Node node) {
		super(node);
		this.exprs = exprs;
	}

	JSSeqExp(JSExpr expr1, JSExpr expr2, Node node) {
		this(new JSExpr[] { expr1, expr2 }, node);
	}

	JSSeqExp(JSSym v, JSExpr expr1, JSExpr expr2, Node node) {
		this(new JSExpr[] { new JSAssign(v, expr1, node), expr2 }, node);
	}

	int precedence() {
		return PREC_GROUP;
	}

	void code(CodeBuilder bd) {
		bd.add("(").addAll(exprs, ", ").add(")");
	}

}

class JSBinOp extends JSExpr {

	static final Map OPERATORS = new HashMap() {
		{
			// put("==", "==");
			// put("!=", "!=");
			put("<", "<");
			put(">", ">");
			put(">=", ">=");
			put("<=", "<=");
			put("+", "+");
			put("-", "-");
			put("*", "*");
			put("/", "/");
			put("%", "%");
			put("shl", "<<");
			put("shr", ">>");
			put("b_and", "&");
			put("b_or", "|");
			put("xor", "^");
			put("and", "&&");
			put("or", "||");
			put("not", "!");

		}
	};

	private static boolean isSimpleType(JSExpr expr) {
		YType t = expr.getType();
		return t == YetiType.STR_TYPE || t == YetiType.NUM_TYPE
				|| t == YetiType.BOOL_TYPE || t == YetiType.CHAR_TYPE
				|| t == YetiType.UNIT_TYPE;
	}

	static JSCode create(String op, JSExpr left, JSExpr right, Node node,JSScope scope) {
		String opr = (String) OPERATORS.get(op);
		if (op == "==" || op == "!=") {
			if (isSimpleType(right) && isSimpleType(left))
				opr = op + "=";
		}
		if (opr == null) {
			return JSApply.create(
					JSApply.create(scope.ref(op,node), left, node).toExpr(),
					right, node);
		}
		return new JSBinOp(opr, left, right, node);
	}

	private final String op;
	private final JSExpr left;
	private final JSExpr right;

	private static JSExpr groupBin(JSExpr expr, JSExpr right) {
		if (expr == null
				|| (expr.precedence() != PREC_RL && expr.precedence() < right
						.precedence()))
			return expr;
		else
			return new JSGroup(expr);
	}

	JSBinOp(String op, JSExpr left, JSExpr right, Node node) {
		super(node);
		this.op = op.intern();
		this.left = groupBin(left, this);
		this.right = groupBin(right, this);
	}

	void code(CodeBuilder bd) {
		if (left != null)
			bd.add(left).add(" ");
		if (op != "")
			bd.add(op);
		if (right != null)
			bd.add(" ").add(right);
	}

	int precedence() {
		return PREC_BIN;
	}

}

class JSApply extends JSExpr {

	JSExpr fun;
	JSExpr arg;

	static JSCode create(JSExpr fun, JSExpr arg, Node nd) {
		if (fun instanceof JSSym && "throw".equals(((JSSym) fun).sym))
			return new JSExprStat(new JSApply(fun, arg, nd));
		else
			return new JSApply(fun, arg, nd);
	}

	private JSApply(JSExpr fun, JSExpr arg, Node node) {
		super(node);
		if (fun == null)
			throw new NullPointerException();
		if (arg == null)
			throw new NullPointerException();
		this.fun = fun;
		this.arg = arg;
	}

	void code(CodeBuilder bd) {
		bd.add(group(fun, this)).add("(");
		if (arg != JSCode.UNDEF)
			bd.add(arg);
		bd.add(")");
	}

	int precedence() {
		return PREC_APPLY;
	}
}

class JSObjApply extends JSExpr {

	JSExpr fun;
	JSExpr[] args;

	JSObjApply(JSExpr fun, JSExpr[] args, Node nd) {
		super(nd);
		this.fun = fun;
		this.args = args;
	}

	void code(CodeBuilder bd) {
		bd.add(group(fun, this)).add("(").addAll(args, ",").add(")");
	}

	int precedence() {
		return PREC_APPLY;
	}
}

class JSArrRef extends JSExpr {
	JSExpr arr;
	int field;

	public JSArrRef(JSExpr arr, int field, Node node) {
		super(node);
		this.arr = arr;
		this.field = field;
	}

	void code(CodeBuilder bd) {
		bd.add(group(arr, this)).add("[").add("" + field).add("]");
	}

	int precedence() {
		return PREC_FIELD;
	}
}

class JSFieldSelector extends JSExpr {
	static final Pattern FIELD_SYM = Pattern
			.compile("[a-zA-Z_$][0-9a-zA-Z_$]*");
	final String[] fields;

	public JSFieldSelector(String field, Node node) {
		this(new String[] { field }, node);
	}

	public JSFieldSelector(String[] fields, Node node) {
		super(node);
		this.fields = fields;
	}

	void code(CodeBuilder bd) {
		for (int i = 0; i < fields.length; i++) {
			String field = fields[i];
			if (!(JSSym.reserved.contains(field))
					&& FIELD_SYM.matcher(field).matches()) {
				bd.add(".").add(field);
			} else {
				bd.add("[").add(Core.show(field)).add("]");
			}
		}

	}

	int precedence() {
		return PREC_FIELD;
	}
}

class JSFieldRef extends JSExpr {
	JSExpr obj;
	JSFieldSelector field;

	public JSFieldRef(JSExpr obj, String field, Node nd) {
		super(nd);
		this.obj = obj;
		this.field = new JSFieldSelector(field, nd);
	}

	void code(CodeBuilder bd) {
		bd.add(group(obj, this));
		bd.add(field);
	}

	int precedence() {
		return PREC_FIELD;
	}
}

class JSObj {
	static class Field {
		final JSSym name;
		final JSExpr value;
		final boolean hasFun;
		final Node node;

		public Field(JSSym name, JSExpr value, boolean hasFun, Node nd) {
			this.name = name;
			this.value = value;
			this.hasFun = hasFun;
			this.node = nd;
		}

	}

	final List fields = new ArrayList();
	boolean hasFun = false;

	void addField(JSSym name, JSExpr value, boolean hasFun, Node nd) {
		Field fd = new Field(name, value, hasFun, nd);
		if (fd.hasFun)
			this.hasFun = true;
		fields.add(fd);
	}

	JSCode code() {
		if (!hasFun) {
			return objLit();
		}

		JSBlock body = new JSBlock("objbody", null);
		JSSym litSym = new JSSym();
		body.bind(litSym, objLit(), null);

		// now we have to do scoping as well
		JSBlock fnObjBody = new JSBlock("functions", null);
		Iterator it = fields.iterator();
		while (it.hasNext()) {
			Field fl = (Field) it.next();
			JSSym fls = fl.name;
			if (fl.hasFun) {
				fnObjBody.bind(fls, fl.value, fl.node);
				fnObjBody.add(new JSAssign(new JSFieldRef(litSym, fl.name.sym,
						fl.node), fls, fl.node));
			} else {
				fnObjBody.bind(fls, new JSFieldRef(litSym, fl.name.sym, fl.node),
						fl.node);
			}
		}
		body.add(new IIFEJSExpr(litSym, fnObjBody));
		body.add(litSym);
		return body;
	}

	private JSObjLiteral objLit() {
		JSObjLiteral lit = new JSObjLiteral(null);
		Iterator it = fields.iterator();
		while (it.hasNext()) {
			Field fl = (Field) it.next();
			if (!fl.hasFun)
				lit.add(fl.name.sym, fl.value);
		}
		return lit;

	}
}

class JSObjLiteral extends JSExpr {
	class JSFieldValue {
		final String field;
		final JSExpr value;

		JSFieldValue(String field, JSExpr value) {
			this.field = field;
			this.value = value;
		}
	}

	final List fieldValues = new ArrayList();

	public JSObjLiteral(Node nd) {
		super(nd);
	}

	void add(String field, JSExpr value) {
		fieldValues.add(new JSFieldValue(field, value));
	}

	void code(CodeBuilder bd) {
		bd.add("{").ind().nl();
		boolean first = true;
		Iterator it = fieldValues.iterator();
		while (it.hasNext()) {
			JSFieldValue fl = (JSFieldValue) it.next();
			if (first) {
				first = false;
			} else {
				bd.add(", ").nl();
			}
			bd.add(Core.show(fl.field)).add(" : ").add(fl.value);
		}
		bd.dnd().nl().add("}");

	}

	int precedence() {
		return PREC_LIT;
	}
}

class JSFun extends JSExpr {
	final JSBlock body;
	final JSSym arg;
	final JSSym name;
	boolean closed = false;

	public JSFun(JSSym name, JSSym arg, JSCode body, Node nd) {
		super(nd);
		if (body instanceof JSBlock)
			this.body = (JSBlock) body;
		else
			this.body = new JSBlock(body.node).addFlat(body);

		this.arg = arg == null ? NO_ARG : arg;
		this.name = name;
		// this.body.ret(); is now done in Analyzer because of TCO
	}

	void close() {
		if (!closed) {
			closed = true;

			JSCode last = body.last();
			if (last instanceof JSReturn) {
				return;
			}
			if (last instanceof JSExpr) {
				body.replaceLast(new JSReturn((JSExpr) last));
			}
		}
	}

	private void reti() {
	}

	void code(CodeBuilder bd) {
		close();
		bd.add("function");
		if (name != null)
			bd.add(" ").add(name);
		bd.add("(").add(arg).add(")").add(body);
	}

	int precedence() {
		return PREC_FUN;
	}

	static boolean isBodyTCO(final String name, JSSym[] args,
			final JSBlock body, final boolean testOnly, JSSym retVar) {
		List stats = body.stats;
		int lastI = stats.size() - 1;
		JSCode last = (JSCode) stats.get(lastI);
		JSCode blast = null;
		if (last instanceof JSSym
				&& lastI > 0
				&& (blast = (JSCode) stats.get(lastI - 1)) instanceof JSIfBuilder.IfStat) {

			JSIfBuilder.IfStat ifStat = (JSIfBuilder.IfStat) blast;
			Iterator clit = ifStat.getBuilder().clauses.iterator();
			boolean hasElse = false;
			while (clit.hasNext()) {
				JSIfBuilder.Clause cla = (JSIfBuilder.Clause) clit.next();
				hasElse = hasElse || cla.cond == null;
				if (isBodyTCO(name, args, cla.body, testOnly, retVar)
						&& testOnly)
					return true;
			}
			if (!testOnly) {
				if (!hasElse)
					ifStat.getBuilder().add(null, JSCode.BREAK);
				ifStat.bound = true;
			}
			return false;
		}

		if (last instanceof JSApply) {
			JSApply apl = (JSApply) last;
			JSExpr[] aplArgs = new JSExpr[args.length];
			int i = aplArgs.length - 1;
			while (true) {
				if (i < 0) { // more applies than args
					if (!testOnly) {
						body.bindLast(retVar, false, body.node);
						body.add(JSCode.BREAK);
					}
					return false;
				}
				aplArgs[i--] = apl.arg;
				if (apl.fun instanceof JSApply)
					apl = (JSApply) apl.fun;
				else
					break;
			}
			if (apl.fun instanceof JSSym && name.equals(((JSSym) apl.fun).sym)
					&& aplArgs.length == args.length) {
				// now we have tailcall
				if (testOnly)
					return true;
				// remove last from body
				body.stats.remove(lastI--);
				// assign the applyArgs to the original function args
				for (i = 0; i < aplArgs.length; i++) {
					JSSym tv = new JSSym();
					body.bind(tv, aplArgs[i], apl.node);
					aplArgs[i] = tv;
				}
				for (i = 0; i < aplArgs.length; i++) {
					body.add(new JSAssign(args[i], aplArgs[i], apl.node));
				}
				// and add a continue
				body.add(JSCode.CONTINUE);
				return true;
			}
		}
		if (!testOnly) {
			body.bindLast(retVar, false, body.node);
			body.add(JSCode.BREAK);
		}
		return false;
	}

	private static class JSReturn extends JSStat {
		static final JSReturn UNDEF = new JSReturn(JSCode.UNDEF);
		final JSExpr expr;

		private JSReturn(JSExpr expr) {
			super(expr.node);
			this.expr = expr;
		}

		void code(CodeBuilder bd) {
			bd.add("return ").add(expr).se();
		}
	}
}

class IIFEJSExpr extends JSExpr {
	final JSFun fun;

	public IIFEJSExpr(JSSym arg, JSCode body) {
		super(body.node);
		this.fun = new JSFun(null, arg, body, body.node);
	}

	public IIFEJSExpr(JSCode body) {
		this(null, body);
	}

	void code(CodeBuilder bd) {
		bd.add("(");
		fun.code(bd);
		bd.add("(").add(fun.arg).add("))");
	}

	int precedence() {
		return PREC_GROUP;
	}

}
