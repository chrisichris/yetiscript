/**
 * YJS main entrance
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

import java.awt.Event;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import yjs.lang.compiler.*;
import yjs.lang.compiler.NanoHTTPD.Response.Status;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

public class YJSMain {
	//change version in three places:
	// 1. here
	// 2. package.json
	// 3. in std
	static final String VERSION = "0.1.1";

	static final String YJS_SERVER_HELP =
	"YetiScript Compile Service version: "+VERSION +"\n\n" +
	"api:\n\n" +
	"POST o. GET: /compile?src=sourcecode\n" +
	"   compile the given sourcecode and response with js \n\n" +
	"POST o. GET: /repl?src=scourcecode&session=sessionid&seq=seqNr\n" +
	"   start a new session when no session id is given or compile using the\n" +
	"   started session with the given id. \n" +
	"   Returns the compiled code and sessionid and seqnr as headers\n\n" +
	"GET: /close?session=sessionid\n" +
	"   close the given session\n\n";
	
	static final String HELP = 
			"YetiScript version \""+VERSION+"\"\n"+
			"\nusage: yjs [-flags...] yjsfile\n\n" +
			"       the default behaviour without flags is to print\n" +
			"       the resulting javascript to std.out\n\n" +
			"flags:\n\n"
			+ "  -h             this help\n\n"
			+ "  -e expr        evaluate expr\n\n"
			+ "  -repl          repl using rhino\n\n"
//			+ "  -nrepl          repl using node\n\n"
			+ "  -parse-tree    print the parse tree\n\n"
			+ "  -p             print generated javascript\n\n"
			+ "  -sp path       set source path\n\n"
			+ "  -d [dir]       write javascript to given directory. Directory\n" 
			+ "                 defaults to '.'\n\n"
			+ "  -r             run generated javascript using rhino\n\n"
			+ "  -w [dir]       watches the given directory or the sourcefile\n" 
			+ "                 for changes and reruns\n\n"
			+ "  -server [port] start the httpd res-server on the opt. port\n"
			+ "                 port defaults to 9090 '\n\n"
			+ "  -t             print type";

	public File outDir = null;
	public boolean parseTree = false;
	public String source = null;
	public String expression = null;
	public boolean print = false;
	public boolean run = false;
	public boolean printType = false;
	public String[] sourcePathes;
	public File watchDir = null;

	static void exitErr(String msg) {
		System.err.println(msg);
		System.exit(-1);
	}

	public static void main(String[] args) throws Exception {
		if(args == null || args.length == 0) {
			System.out.println(HELP);
			System.exit(0);
		}
		YJSMain yjs = new YJSMain();
		boolean setWatchDir = false;
		if(args.length == 1) 
			yjs.print = true;
		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			if ("-h".equals(a)) {
				System.out.println(HELP);
				System.exit(0);
			} else if ("-server".equals(a)){
				if(args.length == 1)
					yjs.print = false;
				int port = 9090;
				if(++i < args.length)
					try{
						port = Integer.parseInt(args[i]);
					}catch(NumberFormatException ex){
						exitErr("no valid port argument to -server "+args[i]);
					}
				NanoHTTPD server = yjs.createServer(port);
				server.start();
				while(true)
					Thread.sleep(1000*10);
			} else if ("-repl".equals(a)){
				if(args.length == 1)
					yjs.print = false;
				yjs.startRepl(true);
				return;
/*			} else if ("-nrepl".equals(a)){
				if(args.length == 1)
					yjs.print = false;
				yjs.startRepl(false);
				return;*/
			} else if ("-e".equals(a)) {
				yjs.outDir = null;
				if (++i < args.length && yjs.source == null) {
					yjs.expression = args[i];
				} else
					exitErr("-e must be followed by expression");
				//if just -e exp than print to std.out for piping
				if(args.length == 2)
					yjs.print = true;
				break;
			} else if ("-parse-tree".equals(a)) {
				yjs.parseTree = true;
			} else if ("-t".equals(a)) {
				yjs.printType = true;
			} else if ("-sp".equals(a)) {
				if (++i < args.length) {
					String ps = args[i];
					yjs.sourcePathes = ps.split(File.pathSeparator);
				} else
					exitErr("-sp must be followed by a path");
			} else if ("-p".equals(a)) {
				yjs.print = true;
			} else if ("-d".equals(a)) {
				if( (i+2) < args.length && !args[i+1].startsWith("-")){
					yjs.outDir = new File(args[++i]);
				}else
					yjs.outDir = new File(".");
			} else if ("-w".equals(a)) {
				if( (i+2) < args.length && !args[i+1].startsWith("-")){
					yjs.watchDir = new File(args[++i]);
					if(!yjs.watchDir.exists())
						exitErr("watch dir does not exist: " + args[i]);
				}else
					setWatchDir = true;
			} else if ("-r".equals(a)) {
				yjs.run = true;

			} else {
				yjs.source = a;
				break;
			}
		}

		//watchDir to source if not given
		if(setWatchDir){
			if(yjs.source == null)
				exitErr("watch flag set but no source given");
			yjs.watchDir = new File(yjs.source);
		}
		
		try {
			if(yjs.watchDir == null)
				yjs.run();
			else
				yjs.watch();
			System.exit(0);
		} catch (Exception ex) {
			System.err.println(ex.getMessage());
			System.exit(-1);
		}
	}
	
	public void watch() throws Exception {
		//the wachservice
		WatchService watcher = FileSystems.getDefault().newWatchService();
		Path dir = this.watchDir.isDirectory() ? 
				this.watchDir.toPath() :
				this.watchDir.getParentFile().toPath();
		dir.register(watcher, 
				StandardWatchEventKinds.ENTRY_CREATE,
				StandardWatchEventKinds.ENTRY_DELETE,
				StandardWatchEventKinds.ENTRY_MODIFY);
		while(true){
			try {
				this.run();
			} catch (CompileException e) {
				System.err.print(e.getMessage());
			} catch (ScriptException e) {
				System.err.print(e.getMessage());
			} catch (Exception ex){
				throw ex;
			}
			WatchKey key;
			try{
				key = watcher.take();
				key.pollEvents();
				key.reset();
			}catch(InterruptedException x){
				return;
			}
		}
	}

	private static final class CompileResult {
		final ModuleType type;
		final String jsCode;
		CompileResult(ModuleType type, String jsCode) {
			this.type =type;
			this.jsCode = jsCode;
		}
	}
	private CompileResult compile(int flags, Compiler ctx, String source, String expr)
			throws Exception {
		ModuleType t = ctx.compile(source,
				expr == null ? null : expr.toCharArray(), flags);
		ctx.mainJS.add(new JSComment("--- yjsmain ---",null));
		if (!t.isModule)
			ctx.mainJS.addFlat(t.jsCode);
		else
			ctx.mainJS.add(t.jsModuleVar);

		CodeBuilder bd = new CodeBuilder();
		ctx.mainJS.unbracedCode(bd);
		String code = bd.toString();
		if (print)
			System.out.println(code);
		if (printType && (expression == null || !this.run))
			System.err.println("is " + (t.type));
		
		return new CompileResult(t,code);
	}

	private CompileResult compile(boolean expression, String source) throws Exception{
		return compile(setupFlags(expression),setupCompiler(),
				expression ? null : source,
				expression ? source : null);
	}

	private int setupFlags(boolean expr) {
		int flags = 0;
		if (expr){
			flags = flags | Compiler.CF_EVAL;
		}
		if (parseTree)
			flags = flags | Compiler.CF_PRINT_PARSE_TREE;
		return flags;
	}
	
	private Compiler setupCompiler() throws IOException {
		Compiler ctx = new Compiler();
		ctx.classPath = new ClassFinder(new String[] {}, "");
		ctx.writer = null;
		if (sourcePathes != null)
			ctx.setSourcePath(this.sourcePathes);
		return ctx;
	}
	public void run() throws Exception {
		String expression = this.expression;
		if (expression != null){
			expression = "println ("+expression+")";
		}

		if (this.source == null && this.expression == null) {
			throw new IllegalStateException("You must provide either a source or an expression");
			//repl(flags, ctx);
		} else {
			CompileResult res = this.expression == null ?
					compile(false,this.source)
					: compile(true,this.expression);
			//CompileResult res = compile(flags, ctx, this.source, expression);
			ModuleType t = res.type;
			String code = res.jsCode;
			if (outDir != null) {
				File outFile = new File(outDir, t.name + ".js");
				File parDir = outFile.getParentFile();
				if (parDir != null && !parDir.exists())
					parDir.mkdirs();
				PrintWriter pw = null;
				try {
					pw = new PrintWriter(outFile, "UTF-8");
					pw.append(code);
				} finally {
					if (pw != null)
						pw.close();
				}
			}

			if (run) {
				ScriptEngine engine = new ScriptEngineManager()
						.getEngineByName("JavaScript");
				String fileName = null;
				if (outDir == null)
					fileName = t.name + ".js";
				else
					fileName = (new File(outDir, t.name + ".js")).toString();

				engine.put(ScriptEngine.FILENAME, fileName);
				code = ("var console = {log: function (msg) {" + "   if(!msg)"
						+ "      msg = 'null';"
						+ "	java.lang.System.out.println(msg.toString());}};")
						+ code;

				Object sv = engine.eval(code);
			}
		}
	}

	void startRepl(boolean rhino) throws IOException {
		ReplSession session = new ReplSession();
		
		System.out.println("yjs repl");
		BufferedReader rd = new BufferedReader(new InputStreamReader(System.in));
		ScriptEngine rhinoEng = null;
		SimpleScriptContext rhinoCtxt = null;
		Process nodeProcess = null;
		OutputStreamWriter nodeOut = null;
		
		if(rhino){
			rhinoEng = new ScriptEngineManager()
					.getEngineByName("JavaScript");
			rhinoCtxt = new SimpleScriptContext();
		}else{
			ProcessBuilder pb = new ProcessBuilder("node", "-i");
			pb.redirectError(Redirect.INHERIT);
			pb.redirectOutput(Redirect.INHERIT);
			pb.redirectInput(Redirect.PIPE);
			nodeProcess = pb.start();
			nodeOut = new OutputStreamWriter(nodeProcess.getOutputStream());
		}
		
		
		while (true)
			try {
				System.out.print(">");
				String line = rd.readLine();
				CompileResult cres = session.compile(line,-1);
				if(rhino){
					Object res = rhinoEng.eval(cres.jsCode, rhinoCtxt);
					System.out.println(res + " is " + cres.type.type);
				}else{
					try{
						System.exit(nodeProcess.exitValue());
					}catch(IllegalThreadStateException ex){
					}
					nodeOut.write(cres.jsCode+"\n\u0004");
					System.out.println("is " + cres.type.type);
				}
			} catch (Exception ex) {
				System.err.println(ex.getMessage());
			}
	}
	

	private class ReplSession {
		final String id = UUID.randomUUID().toString();
		final AtomicLong lastUsedTime = 
				new AtomicLong(System.currentTimeMillis());
		final Compiler ctxt;
		final YetiEval evalEnv = new YetiEval();
		final int flags = setupFlags(false)
				| Compiler.CF_EVAL 
				| Compiler.CF_EVAL_RESOLVE
				| Compiler.CF_EVAL_STORE;
		private long counter = 0;
		
		public ReplSession() throws IOException {
			ctxt = setupCompiler();
			JSAnalyzer.JSScope.CHECK_SCOPE = false;
		}
		
		synchronized long getCounter() {
			return counter;
		}
		synchronized CompileResult compile(String code,long clientCounter) throws Exception{
			if(clientCounter != -1 && clientCounter != counter){
				throw new IllegalArgumentException("Wrong Client-Counter: "+clientCounter);
			}
			counter++;
			
			lastUsedTime.set(System.currentTimeMillis());
			
			//taken more or less from eval.yeti evaluteYetiCode
			//List bindings = evalEnv.bindings;
			YetiEval oldContext = YetiEval.set(evalEnv);
			
			try{
				CompileResult res = YJSMain.this.compile(flags, ctxt, null, code);
				//set back already done JSCode
				ctxt.mainJS = new JSBlock(null);
				return res;
			}finally{
				YetiEval.set(oldContext);
			}
			
		}
	}
	
	public NanoHTTPD createServer(int port){
		YJSServer server = new YJSServer(port);
		return server;
	}
	
	private class YJSServer extends NanoHTTPD{
		private final ConcurrentHashMap<String, ReplSession> sessions = 
			new ConcurrentHashMap<>();
	
		private final Timer timer = new Timer(true);
		private final int port;
		public YJSServer(int port) {
			super(port);
			this.port = port;
			timer.scheduleAtFixedRate(new TimerTask() {
				public void run() {
					long cur = System.currentTimeMillis() - 5 * 60 * 1000;
					for(ReplSession entr: sessions.values()){
						if(entr.lastUsedTime.get() < cur){
							sessions.remove(entr.id);
						}
					}
				}
			}, 0, 5 * 60* 1000); //every 5 min
		}
		
		private Response cors(IHTTPSession session, Status status, String mimeType, String txt) {
			Response res = new Response(status, mimeType, txt);
			res.addHeader("Access-Control-Allow-Origin", "*");
			res.addHeader("Access-Cotnrol-Allow-Credentials","true");
			res.addHeader("Access-Control-Allow-Methods","GET, POST, OPTIONS");
			String reqHead = session.getHeaders().get("Access-Control_Request-Headers");
			if(null != reqHead && !reqHead.equals("")){
				res.addHeader("Access-Control-Allow-Headers",reqHead);
			}
			return res;
		}
		private void log(String str){
			System.out.println("YJSServer: "+str);
		}
		@Override
		public Response serve(IHTTPSession session) {
	        final Method method = session.getMethod();
			final String uri = session.getUri();
			if (Method.PUT.equals(method) || Method.POST.equals(method)) {
	            try {
	                session.parseBody(new HashMap<String,String>());
	            } catch (IOException ioe) {
	                return cors(session,Status.INTERNAL_ERROR, MIME_PLAINTEXT, 
	                		"SERVER INTERNAL ERROR: IOException: " 
	                					+ ioe.getMessage());
	            } catch (ResponseException re) {
	                return cors(session,Status.INTERNAL_ERROR, 
	                		MIME_PLAINTEXT, re.getMessage());
	            }
	        }
			Map<String,String> params = session.getParms();
			try{
				if("".equals(uri) || "/".equals(uri)){
					return cors(session,Status.OK, "text/plain",YJS_SERVER_HELP);
				}else if("/compile".equals(uri)){
					String src = params.get("src");
					log("/compile: "+src);
					if(src == null)
						throw new IllegalArgumentException("no source");
					CompileResult cres = compile(true, src);
					log("compiled");
					return cors(session,
						Status.OK,"text/plain",cres.jsCode);
				}else if("/repl".equals(uri)){
					String src = params.get("src");
					if(src == null)
						throw new IllegalArgumentException("no source");
					log("/repl "+src);
					
					String sessId = params.get("session");
					
					ReplSession sess = null;
					long ct = -1L;
					if(sessId == null){
						sess = new ReplSession();
						sessions.put(sess.id, sess);
					}else {
						sess = sessions.get(sessId);
						if(sess == null)
							throw new IllegalArgumentException("no session for id: "+sessId);
						if(params.get("seq") != null)
							try{
								ct = Long.parseLong(params.get("seq"));
							}catch(NumberFormatException ex){
								throw new IllegalArgumentException("No seq-counter given");
							}
					}
					CompileResult res = sess.compile(src, ct);
					Response resp = cors(session,Status.OK,"text/plain",res.jsCode);
					resp.addHeader("x-session",sess.id);
					resp.addHeader("x-seq", ""+sess.getCounter());
					return resp; 
				}else if("/close".equals(uri)){
					sessions.remove(params.get("session"));
					return cors(session,Status.OK,"text/plain","");
				}else{
					return cors(session, Status.NOT_FOUND, "text/plain", uri);
				}
			}catch(CompileException ex){
				return cors(session,Status.BAD_REQUEST, "text/plain", ex.getMessage());
			}catch(IllegalArgumentException ex){
				return cors(session,Status.NOT_FOUND, "text/plain", ex.getMessage());
			}catch(Exception ex){
				ex.printStackTrace();
				return cors(session,Status.INTERNAL_ERROR, "text/plain", ex.getMessage());
			}
		}

		@Override
		public void start() throws IOException {
			System.out.println("\nYJSServer listening at localhost:"+ port+"\n"+YJS_SERVER_HELP);
			super.start();
		}
	}
	
	
	
	
}
