// ex: se sts=4 sw=4 expandtab:

/*
 * Yeti language compiler java bytecode generator.
 *
 * Copyright (c) 2007-2013 Madis Janson
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;

public interface CodeWriter {
    void writeClass(String name, byte[] code) throws Exception;
}

class ToFile implements CodeWriter {
    private String target;

    ToFile(String target) {
        this.target = target;
    }

    public void writeClass(String name, byte[] code) throws Exception {
        name = target + name;
        int sl = name.lastIndexOf('/');
        if (sl > 0) {
            new File(name.substring(0, sl)).mkdirs();
        }
        FileOutputStream out = new FileOutputStream(name);
        out.write(code);
        out.close();
    }
}

class Loader extends ClassLoader implements CodeWriter {
    private HashMap classes = new HashMap();

    Loader(ClassLoader cl) {
        super(cl != null ? cl : Thread.currentThread().getContextClassLoader());
    }

    public void writeClass(String name, byte[] code) {
        // to a dotted classname used by loadClass
        classes.put(name.substring(0, name.length() - 6).replace('/', '.'),
                    code);
    }

    // override loadClass to ensure loading our own class
    // even when it already exists in current classpath
    protected synchronized Class loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        Class loaded = findLoadedClass(name);
        if (loaded == null) {
            byte[] code = (byte[]) classes.get(name);
            if (code == null) {
                return super.loadClass(name, resolve);
            }
            loaded = defineClass(name, code, 0, code.length);
        }
        if (resolve) {
            resolveClass(loaded);
        }
        return loaded;
    }

    public InputStream getResourceAsStream(String path) {
        if (path.endsWith(".class")) {
            String name =
                path.substring(0, path.length() - 6).replace('.', '/');
            byte[] code = (byte[]) classes.get(name);
            if (code != null)
                return new ByteArrayInputStream(code);
        }
        return super.getResourceAsStream(path);
    }
}
