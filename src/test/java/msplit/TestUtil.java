package msplit;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static msplit.Util.OBJECT_TYPE;

class TestUtil {
  private TestUtil() { }

  static final boolean debug = false;
  static final boolean trace = false;

  static ClassNode manualClassWithMethods(MethodNode... methods) {
    ClassNode cls = new ClassNode();
    cls.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "temp" + UUID.randomUUID().toString().replace("-", ""), null,
        OBJECT_TYPE.getInternalName(), new String[0]);
    cls.methods.addAll(Arrays.asList(methods));
    return cls;
  }

  static ClassNode classWithComputedFramesAndMaxes(ClassNode cls) {
    // Just write with computed and then read again
    byte[] bytes = classNodeToBytes(cls);
    return bytesToClassNode(bytes);
  }

  static ClassNode bytesToClassNode(byte[] compiled) {
    ClassNode node = new ClassNode();
    new ClassReader(compiled).accept(node, 0);
    return node;
  }

  static Method compileMethod(ClassNode cls, String methodName) throws Exception {
    Class<?> javaClass = RuntimeCompiler.defineClass(cls.name, classNodeToBytes(cls));
    for (Method javaMethod : javaClass.getDeclaredMethods()) {
      if (javaMethod.getName().equals(methodName)) return javaMethod;
    }
    throw new NoSuchMethodException();
  }

  static byte[] classNodeToBytes(ClassNode node) {
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    return writer.toByteArray();
  }

  static String methodAsm(MethodNode method) {
    StringWriter string = new StringWriter();
    Textifier printer = new Textifier();
    method.accept(new TraceMethodVisitor(printer));
    printer.print(new PrintWriter(string));
    return string.toString();
  }

  static String classAsm(ClassNode cls) {
    StringWriter string = new StringWriter();
    cls.accept(new TraceClassVisitor(new PrintWriter(string)));
    return string.toString();
  }
}
