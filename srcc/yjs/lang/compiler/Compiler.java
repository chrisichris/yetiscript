// ex: se sts=4 sw=4 expandtab:

/*
 * Yeti language compiler java bytecode generator.
 *
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import yeti.lang.Core;
import yeti.lang.Fun;
import yeti.lang.Struct3;
import yeti.renamed.asm3.ClassWriter;
import yeti.renamed.asm3.Opcodes;
import yjs.lang.compiler.JSAnalyzer.JSScope;

final class Compiler implements Opcodes {
    public static final String EXT = ".yjs";
    static final int CF_RESOLVE_MODULE   = 1;
    static final int CF_PRINT_PARSE_TREE = 2;
    static final int CF_EVAL             = 4;
    static final int CF_EVAL_RESOLVE     = 8;
    static final int CF_EVAL_STORE       = 32;
    static final int CF_EVAL_BIND        = 40;
    static final int CF_EXPECT_MODULE    = 128;
    static final int CF_EXPECT_PROGRAM   = 256;
    // hack to force getting yetidoc on doc generation
    static final int CF_FORCE_COMPILE    = 512;
    // used with CF_RESOLVE_MODULE on preload
    static final int CF_IGNORE_CLASSPATH = 1024;
    
    // global flags
    static final int GF_NO_IMPORT = 16;
    static final int GF_DOC       = 64;

    static final String[] PRELOAD =
        new String[] {"std"};

    static final ThreadLocal currentCompiler = new ThreadLocal();
    private static ClassLoader JAVAC;

    CodeWriter writer;
    String depDestDir; // used to read already compiled classes
    private Map compiled = new HashMap();
    private List warnings = new ArrayList();
    private String currentSrc;
    private Map definedClasses = new HashMap();
    final List postGen = new ArrayList();
    boolean isGCJ;

    String sourceCharset = "UTF-8";
    private String[] sourcePath = {};
    Fun customReader;

    ClassFinder classPath;
    final Map types = new HashMap();
    final Map opaqueTypes = new HashMap();
    final Map javaTypeCache = new HashMap();
    String[] preload = PRELOAD;
    int classWriterFlags = ClassWriter.COMPUTE_FRAMES;
    int globalFlags;
    
    //JSvars
    JSBlock mainJS = new JSBlock(null);
	JSScope rootJSScope;
    

    Compiler() {
        // GCJ bytecode verifier is overly strict about INVOKEINTERFACE
        isGCJ = System.getProperty("java.vm.name").indexOf("gcj") >= 0;
//            isGCJ = true;
    }

    void warn(CompileException ex) {
        ex.fn = currentSrc;
        warnings.add(ex);
    }

    String createClassName(Ctx ctx, String outerClass, String nameBase) {
        boolean anon = nameBase == "" && ctx != null;
        nameBase = outerClass + '$' + nameBase;
        String lower = nameBase.toLowerCase(), name = lower;
        int counter = -1;

        if (anon)
            name = lower + (counter = ctx.constants.anonymousClassCounter);
        while (definedClasses.containsKey(name))
            name = lower + ++counter;
        if (anon)
            ctx.constants.anonymousClassCounter = counter + 1;
        return counter < 0 ? nameBase : nameBase + counter;
    }

    public void enumWarns(Fun f) {
        for (int i = 0, cnt = warnings.size(); i < cnt; ++i)
            f.apply(warnings.get(i));
    }

    private String readInputAsString(InputStream is) throws IOException{
        BufferedReader reader = null;
        StringBuffer text = new StringBuffer();
        try {
            reader = new BufferedReader(new InputStreamReader(is, sourceCharset));
		    for ( String line; (line = reader.readLine()) != null; )
		        text.append( line );
        } finally {
            if (reader != null)
                reader.close();
            else
                is.close();
        }
        return text.toString();
    	
    }
    private String readJSFile(String parent, String fn) throws IOException {
        File f = new File(parent, fn);
        if (parent == null) { // !loadModule
            f = f.getCanonicalFile();
        }
        return readInputAsString(new FileInputStream(f));
    }

    // if loadModule is true, the file is searched from the source path
    String readJSSource(final String name) {
        String fn = name + ".js";
    	try {
            // Search from path. The localName is slashed package name.
            if (sourcePath.length == 0)
                throw new IOException("no source path");
            int sep = fn.lastIndexOf('/');
            for (;;) {
                // search _with_ packageName
                for (int i = 0; i < sourcePath.length; ++i)
                    try {
                        return readJSFile(sourcePath[i], fn);
                    } catch (IOException ex) {
                    }
                //if (sep != -2 && (analyzer.flags & CF_IGNORE_CLASSPATH) == 0
                //    && (analyzer.resolvedType = moduleType(name)) != null)
                //    return null;
                if (sep != -2) {
                	String cfn = name + ".js";
                	InputStream is = this.getClass().getClassLoader().getResourceAsStream(cfn);
                	if(is != null)
                		return readInputAsString(is).trim();
                }
                if (sep <= 0) // no package path, fail
                    throw new CompileException(0, 0, "script " +
                                name + ".js not found");
                fn = fn.substring(sep + 1); // try without package path
                sep = -2; // fail next time, without rechecking classpath
            }
        } catch (IOException e) {
            throw new CompileException(0, 0,
                        fn + ": " + e.getMessage());
        }
    }
    String compileAll(String[] sources, int flags, String[] javaArg)
            throws Exception {
        List java = null;
        int i, yetiCount = 0;
        for (i = 0; i < sources.length; ++i)
            if (sources[i].endsWith(".java")) {
                char[] s = readSourceFile(null, sources[i], new YetiAnalyzer());
                new JavaSource(sources[i], s, classPath.parsed);
                if (java == null) {
                    java = new ArrayList();
                    boolean debug = true;
                    for (int j = 0; j < javaArg.length; ++j) {
                        if (javaArg[j].startsWith("-g"))
                            debug = false;
                        java.add(javaArg[j]);
                    }
                    if (!java.contains("-encoding")) {
                        java.add("-encoding");
                        java.add("utf-8");
                    }
                    if (debug)
                        java.add("-g");
                    if (classPath.pathStr.length() != 0) {
                        java.add("-classpath");
                        String path = classPath.pathStr;
                        if (depDestDir != null)
                            path = path.length() == 0 ? depDestDir
                                    : path + File.pathSeparator + depDestDir;
                        java.add(path);
                    }
                }
                java.add(sources[i]);
            } else {
                sources[yetiCount++] = sources[i];
            }
        String mainClass = null;
        for (i = 0; i < yetiCount; ++i) {
            String className = compile(sources[i], null, flags).name;
            if (!types.containsKey(className))
                mainClass = className;
        }
        if (java != null) {
            javaArg = (String[]) java.toArray(new String[javaArg.length]);
            Class javac = null;
            try {
                javac = Class.forName("com.sun.tools.javac.Main", true,
                                      getClass().getClassLoader());
            } catch (Exception ex) {
            }
            java.lang.reflect.Method m;
            try {
                if (javac == null) { // find javac...
                    synchronized (currentCompiler) {
                        if (JAVAC == null) {
                            File f = new File(System.getProperty("java.home"),
                                             "../lib/tools.jar");
                            if (!f.exists())
                                f = new File(System.getenv("JAVA_HOME"),
                                             "lib/tools.jar");
                            JAVAC = new URLClassLoader(
                                            new URL[] { f.toURI().toURL() });
                        }
                    }
                    javac =
                        Class.forName("com.sun.tools.javac.Main", true, JAVAC);
                }
                m = javac.getMethod("compile", new Class[] { String[].class });
            } catch (Exception ex) {
                throw new CompileException(null, "Couldn't find Java compiler");
            }
            Object o = javac.newInstance();
            if (((Integer) m.invoke(o, new Object[] {javaArg})).intValue() != 0)
                throw new CompileException(null,
                            "Error while compiling Java sources");
        }
        return yetiCount != 0 ? mainClass : "";
    }

    void setSourcePath(String[] path) throws IOException {
        String[] sp = new String[path.length];
        for (int i = 0, j, cnt; i < path.length; ++i) {
            String s = path[i];
            char c = ' '; // check URI
            for (j = 0, cnt = s.length(); j < cnt; ++j)
                if (((c = s.charAt(j)) < 'a' || c > 'z') &&
                    (c < '0' || c > '9')) break;
            sp[i] = j > 1 && c == ':' ? s : new File(s).getCanonicalPath();
        }
        sourcePath = sp;
    }
    private char[] readInput(InputStream stream) throws IOException {
        char[] buf = new char[0x8000];
        Reader reader = null;
        try {
            reader = new java.io.InputStreamReader(stream, sourceCharset);
            int n, l = 0;
            while ((n = reader.read(buf, l, buf.length - l)) >= 0)
                if (buf.length - (l += n) < 0x1000) {
                    char[] tmp = new char[buf.length << 1];
                    System.arraycopy(buf, 0, tmp, 0, l);
                    buf = tmp;
                }
        } finally {
            if (reader != null)
                reader.close();
            else
                stream.close();
        }
        return buf;
    	
    }

    private char[] readSourceFile(String parent, String fn,
                                  YetiAnalyzer analyzer) throws IOException {
        if (customReader != null) {
            Struct3 arg = new Struct3(new String[] { "name", "parent" }, null);
            arg._0 = fn;
            arg._1 = parent == null ? Core.UNDEF_STR : parent;
            String result = (String) customReader.apply(arg);
            if (result != Core.UNDEF_STR) {
                analyzer.canonicalFile = (String) arg._0;
                analyzer.sourceFile = null;
                if (compiled.containsKey(analyzer.canonicalFile))
                    return null;
                return result.toCharArray();
            }
        }
        File f = new File(parent, fn);
        analyzer.sourceFile = f.getName();
        if (parent == null) { // !loadModule
            f = f.getCanonicalFile();
            analyzer.canonicalFile = f.getPath();
            if (compiled.containsKey(analyzer.canonicalFile))
                return null;
        }
        char[] buf = readInput(new FileInputStream(f));
        if (parent != null)
            analyzer.canonicalFile = f.getCanonicalPath();
        analyzer.sourceTime = f.lastModified();
        return buf;
    }

    private void verifyModuleCase(YetiAnalyzer analyzer) {
        int l = analyzer.canonicalFile.length() - analyzer.sourceName.length();
        if (l < 0)
            return;
        String cf = analyzer.canonicalFile.substring(l);
        if (!analyzer.sourceName.equals(cf) &&
            analyzer.sourceName.equalsIgnoreCase(cf))
            throw new CompileException(0, 0,
                "Module file name case doesn't match the requested name");
    }
    // if loadModule is true, the file is searched from the source path
    private char[] readSource(YetiAnalyzer analyzer) {
        try {
            if ((analyzer.flags & CF_RESOLVE_MODULE) == 0)
                return readSourceFile(null, analyzer.sourceName, analyzer);
            // Search from path. The localName is slashed package name.
            final String name = analyzer.sourceName;
            if("std".equals(name)){
            	String cfn = name + EXT;
            	InputStream is = this.getClass().getClassLoader().getResourceAsStream(cfn);
            	if(is != null)
            		return readInput(is);	
            }
            String fn = analyzer.sourceName = name + EXT;
            if (sourcePath.length == 0)
                throw new IOException("no source path");
            int sep = fn.lastIndexOf('/');
            for (;;) {
                // search _with_ packageName
                for (int i = 0; i < sourcePath.length; ++i)
                    try {
                        char[] r = readSourceFile(sourcePath[i], fn, analyzer);
                        analyzer.sourceDir = sourcePath[i];
                        verifyModuleCase(analyzer);
                        return r;
                    } catch (IOException ex) {
                    }
                //if (sep != -2 && (analyzer.flags & CF_IGNORE_CLASSPATH) == 0
                //    && (analyzer.resolvedType = moduleType(name)) != null)
                //    return null;
                if (sep != -2) {
	            	String cfn = name + EXT;
	            	InputStream is = this.getClass().getClassLoader().getResourceAsStream(cfn);
	            	if(is != null)
	            		return readInput(is);	
                }
                if (sep <= 0) // no package path, fail
                    throw new CompileException(0, 0, "Module " +
                                name.replace('/', '.') + " not found");
                fn = fn.substring(sep + 1); // try without package path
                sep = -2; // fail next time, without rechecking classpath
            }
            //try to read from Classpath
        } catch (IOException e) {
            throw new CompileException(0, 0,
                        analyzer.sourceName + ": " + e.getMessage());
        }
    }
/*
    ModuleType moduleType(String name) throws IOException {
        String cname = name.toLowerCase();
        long[] lastModified = { -1 };
        InputStream in = classPath.findClass(cname + ".class", lastModified);
        if (in == null)
            return null;
        ModuleType t = YetiTypeVisitor.readType(this, in);
        if (t != null) {
            t.name = cname;
            t.lastModified = lastModified[0];
            types.put(cname, t);
        }
        return t;
    }
*/
    void deriveName(YetiParser.Parser parser, YetiAnalyzer analyzer) {
        if ((analyzer.flags & (CF_EVAL | CF_RESOLVE_MODULE)) == CF_EVAL) {
            if (parser.moduleName == null)
                parser.moduleName = "code";
            if (sourcePath.length == 0)
                sourcePath = new String[] { new File("").getAbsolutePath() };
            return;
        }
        
        if ("std".equals(analyzer.sourceName)) {
        	if (parser.moduleName == null)
        		parser.moduleName = "std";
            if (sourcePath.length == 0)
                sourcePath = new String[] { new File("").getAbsolutePath() };
            return;
        }
        //System.err.println("Module name before derive: " + parser.moduleName);
        // derive or verify the module name
        String cf = analyzer.canonicalFile, name = null;
        int i, lastlen = -1, l = -1;
        i = cf.length() - EXT.length();
        if (i > 0 && cf.substring(i).equalsIgnoreCase(EXT))
            cf = cf.substring(0, i);
        else if (parser.isModule)
            throw new CompileException(0, 0,
                "Yeti module source file must have a .yjs suffix");
        boolean ok = parser.moduleName == null;
        String shortName = parser.moduleName;
        if (shortName != null) {
            l = shortName.lastIndexOf('/');
            shortName = l > 0 ? shortName.substring(l + 1) : null;
        }
        String[] path = analyzer.sourceDir == null ? sourcePath :
                            new String[] { analyzer.sourceDir };
        for (i = 0; i < path.length; ++i) {
            l = path[i].length();
            if (l <= lastlen || cf.length() <= l ||
                    cf.charAt(l) != File.separatorChar ||
                    !path[i].equals(cf.substring(0, l)))
                continue;
            name = cf.substring(l + 1).replace(File.separatorChar, '/');
            if (!ok && (name.equalsIgnoreCase(parser.moduleName) ||
                        name.equalsIgnoreCase(shortName))) {
                ok = true;
                break;
            }
            lastlen = l;
        }
        if (name == null)
            name = new File(cf).getName();
        //System.err.println("SPATH:" + java.util.Arrays.asList(path) +
        //    "; cf:" + cf + "; name:" + name + "; shortName:" + shortName +
        //    "; lastlen:" + lastlen);
        if (!ok && (lastlen != -1 || !name.equalsIgnoreCase(shortName) &&
                                     !name.equalsIgnoreCase(parser.moduleName)))
            throw new CompileException(parser.moduleNameLine, 0,
                        (parser.isModule ? "module " : "program ") +
                        parser.moduleName.replace('/', '.') +
                        " is not allowed to be in file named '" +
                        analyzer.canonicalFile + "'");
        if (parser.moduleName != null)
            name = parser.moduleName;
        parser.moduleName = parser.isModule ? name.toLowerCase() : name;
        //System.err.println("Derived module name: " + parser.moduleName);
        
        /* Derive the source path IMPLICITLY as a single directory:
         * + If the the canonical path ends with /foo/bar/baz(.yjs) matching
         *   the module/program NAME foo.bar.baz (case insensitive),
         *   the SOURCEPATH is the preceding part of the canonical path.
         * + Otherwise the SOURCEPATH is the directory of source file. */
        if (sourcePath.length == 0) {
            l = cf.length() - (name.length() + 1);
            if (l >= 0) {
                name = cf.substring(l)
                         .replace(File.separatorChar, '/');
                if (l == 0)
                    l = 1;
                if (name.charAt(0) != '/' ||
                    !name.substring(1).equalsIgnoreCase(parser.moduleName))
                    l = -1;
            }
            name = l < 0 ? new File(cf).getParent() : cf.substring(0, l);
            if (name == null)
                name = new File("").getAbsolutePath();
            sourcePath = new String[] { name  };
        }

        name = parser.moduleName.toLowerCase();
        if (definedClasses.containsKey(name))
            throw new CompileException(0, 0, (definedClasses.get(name) == null
                ? "Circular module dependency: "
                : "Duplicate module name: ") + name.replace('/', '.'));
        if (depDestDir != null && (analyzer.flags & CF_FORCE_COMPILE) == 0) {
            analyzer.targetFile =
                new File(depDestDir, parser.moduleName.concat(".class"));
            analyzer.targetTime = analyzer.targetFile.lastModified();
        }
    }

     ModuleType getType(YetiParser.Node node,
                              String name) {
        final String cname = name.toLowerCase();
        ModuleType t = (ModuleType) this.types.get(cname);
        if (t != null)
            return t;
        try {
            int flags = Compiler.CF_RESOLVE_MODULE;
            if (node == null) {
                flags |= Compiler.CF_IGNORE_CLASSPATH;
            }
            t = (ModuleType) this.types.get(this.compile(name, null,
                    flags | Compiler.CF_EXPECT_MODULE).name);
            if (t == null)
                throw new CompileException(node,
                            "Could not compile `" + name + "' to a module");
            if (!cname.equals(t.name))
                throw new CompileException(node, "Found " +
                            t.name.replace('/', '.') +
                            " instead of " + name.replace('/', '.'));
            if (!t.directFields)
                this.warn(new CompileException(node, "The `" +
                    t.name.replace('/', '.') + "' module is compiled " +
                    "with pre-0.9.8 version\n    of Yeti compiler and " +
                    "might not work with newer standard library."));
            return t;
        } catch (CompileException ex) {
            if (ex.line == 0)
                if (node != null) {
                    ex.line = node.line;
                    ex.col = node.col;
                }
            throw ex;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CompileException(node, ex.getMessage());
        }
    }
     
    ModuleType compile(String sourceName, char[] code, int flags)
            throws Exception {
        YetiAnalyzer anal = new YetiAnalyzer();
        anal.flags = flags;
        anal.compiler = this;
        anal.sourceName = sourceName;
        if (code == null) {
            code = readSource(anal);
            if (code == null)
                return anal.resolvedType != null ? anal.resolvedType :
                    (ModuleType) compiled.get(anal.canonicalFile);
        }
        RootClosure codeTree;
        Object oldCompiler = currentCompiler.get();
        currentCompiler.set(this);
        String oldCurrentSrc = currentSrc;
        currentSrc = anal.sourceName;
        try {
            try {
                anal.preload = preload;
                codeTree = anal.toCode(code);
                if (codeTree == null) {
                	ModuleType t = anal.resolvedType;
                    if (t == null) { // module, type from class
                        throw new 
                        	CompileException
                        		(0,0,"Not type found for source "+currentSrc);
                    }
                    t.lastModified = anal.targetTime;
                    t.hasSource = true;
                    compiled.put(anal.canonicalFile, t);
                    //System.err.println(t.name + " already compiled.");
                    return t;
                }
            } finally {
                currentCompiler.set(oldCompiler);
            }
            final String name = codeTree.moduleType.name;
            if (name == null)
                throw new CompileException(0, 0,
                            "internal error: module/program name undefined");
            ModuleType exists = (ModuleType) types.get(name);
            // If source set has multiple modules with same name (for some
            // crazy reason), it would be useful to use just one.
            if (exists != null && (flags & CF_FORCE_COMPILE) == 0)
                return exists;
            if (codeTree.isModule){
                types.put(name, codeTree.moduleType);
                //add module to jsCode
                ModuleType mt = codeTree.moduleType;
                this.mainJS.bind(mt.jsModuleVar, 
                		new IIFEJSExpr(mt.jsCode),null);
            }
            compiled.put(anal.canonicalFile, codeTree.moduleType);
            classPath.existsCache.clear();
            currentSrc = oldCurrentSrc;
            return codeTree.moduleType;
        } catch (CompileException ex) {
            if (ex.fn == null)
                ex.fn = anal.sourceName;
            throw ex;
        }
    }




    void addClass(String name, Ctx ctx, int line) {
        if (definedClasses.put(name.toLowerCase(), ctx) != null) {
            throw new CompileException(line, 0,
                        "Duplicate class: " + name.replace('/', '.'));
        }
        if (ctx != null)
            ctx.constants.unstoredClasses.add(ctx);
    }

}

final class YClassWriter extends ClassWriter {
    YClassWriter(int flags) {
        super(COMPUTE_MAXS | flags);
    }

    // Overload to avoid using reflection on non-standard-library classes
    protected String getCommonSuperClass(String type1, String type2) {
        if (type1.equals(type2))
            return type1;
        if (type1.startsWith("java/lang/") && type2.startsWith("java/lang/") ||
            type1.startsWith("yeti/lang/") && type2.startsWith("yeti/lang/"))
            return super.getCommonSuperClass(type1, type2);
        return "java/lang/Object";
    }
}
