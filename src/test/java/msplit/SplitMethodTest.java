package msplit;

import org.junit.Assert;
import org.junit.Test;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.Arrays;

import static msplit.TestUtil.*;
import static msplit.Util.*;

public class SplitMethodTest {

  @Test
  public void testSplitMethod() throws Exception {
    // Create a method too large that mutates a local over and over and then returns it
    MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "testMethod",
        Type.getMethodDescriptor(Type.INT_TYPE), null, null);
    intConst(0).accept(method);
    method.visitVarInsn(Opcodes.ISTORE, 0);
    int expected = 0;
    for (int i = 0; i < 13000; i++) {
      expected += i;
      // Load 0, add i, store
      method.visitVarInsn(Opcodes.ILOAD, 0);
      intConst(i).accept(method);
      method.visitInsn(Opcodes.IADD);
      method.visitVarInsn(Opcodes.ISTORE, 0);
    }
    method.visitVarInsn(Opcodes.ILOAD, 0);
    method.visitInsn(Opcodes.IRETURN);
    ClassNode cls = manualClassWithMethods(method);

    // Compile it and make sure it's too large
    try {
      compileMethod(cls, method.name);
      Assert.fail("Expected exception");
    } catch (MethodTooLargeException e) {
      Assert.assertEquals(method.name, e.getMethodName());
    }

    // Split it
    SplitMethod.Result result = new SplitMethod(Opcodes.ASM6).split(cls.name, method);
    if (debug) {
      System.out.println("Orig method insn count: " + method.instructions.size());
      System.out.println("Split off method insn count: " + result.splitOffMethod.instructions.size());
      System.out.println("Trimmed method insn count: " + result.trimmedMethod.instructions.size());
    }
    if (trace) System.out.println("-----ORIG-----\n" + classAsm(cls) + "\n----------------");

    // Replace methods and recalc frames/max
    cls.methods = Arrays.asList(result.splitOffMethod, result.trimmedMethod);
    cls = classWithComputedFramesAndMaxes(cls);
    if (trace) System.out.println("-----NEW-----\n" + classAsm(cls) + "\n----------------");

    // Replace methods and compile
    Method trimmedMethod = compileMethod(cls, method.name);
    Assert.assertEquals(expected, trimmedMethod.invoke(null));
  }
}
