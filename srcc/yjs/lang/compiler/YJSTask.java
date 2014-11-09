/**
 * YJS ant task
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

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.script.ScriptException;

import yjs.lang.compiler.*;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Path;

public class YJSTask extends MatchingTask {
	private java.io.File srcDir;
	private List sources = new ArrayList();// list<FileSet>
	private String target;
	private Path classPath;
	private boolean run;

	public void setSrc(String src) {
		FileSet fs = new FileSet();
		fs.setFile(new File(src));
		this.addSrc(fs);
	}

	public void addSrc(FileSet src) {
		this.sources.add(src);
	}

	public void setRun(boolean r) {
		this.run = r;
	}

	public void setSrcDir(String dir) {
		this.srcDir = new java.io.File(dir);
	}

	public void setDestDir(String dir) {
		if (dir.length() != 0) {
			dir += '/';
		}
		target = dir;
	}

	public Path createClasspath() {
		if (classPath == null) {
			classPath = new Path(getProject());
		}
		return classPath;
	}

	public void execute() {
		if (!fileset.hasPatterns())
			setIncludes("*.yjs");
		List scanners = new ArrayList(); // list<scanner>
		if (this.sources.isEmpty() || this.srcDir != null) {
			if (this.srcDir == null)
				this.srcDir = getProject().getBaseDir();
			scanners.add(this.getDirectoryScanner(this.srcDir));
		}
		Iterator it = this.sources.iterator();
		while (it.hasNext()) {
			FileSet s = (FileSet) it.next();
			if (!s.hasPatterns()) {
				s.setIncludes("*.yjs");
			}
			scanners.add(s.getDirectoryScanner(this.getProject()));
		}

		List files = new ArrayList(); // list<string>
		List srcDirs = new ArrayList(); // list<string>
		it = scanners.iterator();
		while (it.hasNext()) {
			DirectoryScanner sc = (DirectoryScanner) it.next();
			String[] fs = sc.getIncludedFiles();
			for (int i = 0; i < fs.length; i++) {
				files.add(new File(sc.getBasedir(), fs[i]).getPath());
			}
			srcDirs.add(sc.getBasedir().getPath());
		}

		try {
			String[] srcDirsA = (String[]) srcDirs.toArray(new String[srcDirs
					.size()]);
			File outDir = new File(this.target);
			it = files.iterator();
			int count = 1;
			while (it.hasNext()) {
				YJSMain yjs = new YJSMain();
				yjs.sourcePathes = srcDirsA;
				yjs.outDir = outDir;
				yjs.run = yjs.printType = this.run;
				yjs.source = (String) it.next();
				this.log("Compiling/running " + (count++) + " of "
						+ (files.size()) + ": " + yjs.source);
				yjs.run();
			}
		} catch (CompileException ex) {
			throw new BuildException(ex.getMessage());
		} catch (ScriptException ex) {
			throw new BuildException(ex.getMessage());
		} catch (BuildException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			ex.printStackTrace();
			throw ex;
		} catch (Exception ex) {
			throw new BuildException(ex);
		}
	}
}
