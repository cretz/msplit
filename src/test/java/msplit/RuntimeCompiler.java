package msplit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.util.Collections;

class RuntimeCompiler {
  private RuntimeCompiler() { }

  static Class<?> compileToClass(String className, String source) {
    return defineClass(className, compileToBytes(className, source));
  }

  static Class<?> defineClass(String className, byte[] compiled) {
    return new AddClassClassLoader().addClass(className, compiled);
  }

  static byte[] compileToBytes(String className, String source) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    // Streams for output and error
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    // Source and target files
    JavaFileObject sourceFile = new SimpleJavaFileObject(
        URI.create("string:///" + className + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
    };
    JavaFileObject targetFile = new SimpleJavaFileObject(
        URI.create("string:///" + className + JavaFileObject.Kind.CLASS.extension), JavaFileObject.Kind.CLASS) {
      @Override
      public OutputStream openOutputStream() { return out; }
    };
    // The file manager, but returning our target file for output
    JavaFileManager fileManager = new ForwardingJavaFileManager<StandardJavaFileManager>(
        compiler.getStandardFileManager(null, null, null)) {
      @Override
      public JavaFileObject getJavaFileForOutput(Location location, String className,
          JavaFileObject.Kind kind, FileObject sibling) {
        return targetFile;
      }
    };
    // Run compiler
    JavaCompiler.CompilationTask task = compiler.getTask(new OutputStreamWriter(err),
        fileManager, null, null, null, Collections.singleton(sourceFile));
    if (!task.call()) throw new RuntimeException("Compilation failed, output:\n" + new String(err.toByteArray()));
    return out.toByteArray();
  }

  static class AddClassClassLoader extends ClassLoader {
    Class<?> addClass(String className, byte[] bytes) { return defineClass(className, bytes, 0, bytes.length); }
  }
}
