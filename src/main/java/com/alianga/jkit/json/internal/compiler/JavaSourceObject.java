package com.alianga.jkit.json.internal.compiler;

/**
 * java to compile code object
 *
 * @time 2024/3/16 22:22
 */
public class JavaSourceObject {
    /**
     * the package name of the java source to compile
     */
    public final String packageName;
    /**
     * the simple class name of the java source to compile
     */
    public final String className;
    /**
     * the canonical class name, built as <code>packageName + "." + className</code>
     */
    public final String canonicalName;
    /**
     * the full java source code to compile
     */
    public final String javaSourceCode;

    /**
     * Creates a java source object and resolves its canonical name from the package and class name.
     *
     * @param packageName    the package name of the source
     * @param className      the simple class name of the source
     * @param javaSourceCode the full java source code
     */
    public JavaSourceObject(String packageName, String className, String javaSourceCode) {
        this.packageName = packageName;
        this.className = className;
        this.javaSourceCode = javaSourceCode;
        this.canonicalName = packageName + "." + className;
    }
}
