package msplit;


import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class SplitMethod {

  protected final int api;

  public SplitMethod(int api) { this.api = api; }

  public Result split(String owner, MethodNode method) {
    // Between 20% + 1 and 70% + 1 of size
    int insnCount = method.instructions.size();
    int minSize = (int) (insnCount * 0.2) + 1;
    int maxSize = (int) (insnCount * 0.7) + 1;
    return split(owner, method, minSize, maxSize, maxSize);
  }

  public Result split(String owner, MethodNode method, int minSize, int maxSize, int firstAtLeast) {
    // Get the largest split point
    Splitter.SplitPoint largest = null;
    for (Splitter.SplitPoint point : new Splitter(api, owner, method, minSize, maxSize)) {
      if (largest == null || point.length > largest.length) {
        largest = point;
        // Early exit?
        if (firstAtLeast > 0 && largest.length >= firstAtLeast) break;
      }
    }
    if (largest == null) return null;
    return fromSplitPoint(method, largest);
  }

  public Result fromSplitPoint(MethodNode orig, Splitter.SplitPoint splitPoint) {
    MethodNode splitOff = createSplitOffMethod(orig, splitPoint);
    MethodNode trimmed = createTrimmedMethod(orig, splitOff, splitPoint);
    return new Result(trimmed, splitOff);
  }

  protected MethodNode createSplitOffMethod(MethodNode orig, Splitter.SplitPoint splitPoint) {
    // The new method is a static synthetic method named method.name + "$split" that returns an object array
    // Key is previous local index, value is new local index
    Map<Integer, Integer> localsMap = new HashMap<>();
    // The new method's parameters are all stack items + all read locals
    List<Type> args = new ArrayList<>(splitPoint.neededFromStackAtStart);
    splitPoint.localsRead.forEach((index, type) -> {
      args.add(type);
      localsMap.put(index, args.size() - 1);
    });
    // Create the new method
    MethodNode newMethod = new MethodNode(api,
        Opcodes.ACC_STATIC & Opcodes.ACC_PRIVATE & Opcodes.ACC_SYNTHETIC, orig.name + "$split",
        Type.getMethodDescriptor(Type.getType("[Ljava/lang/Object;"), args.toArray(new Type[0])), null, null);
    // Add the written locals to the map that are not already there
    int newLocalIndex = args.size();
    for (Integer key : splitPoint.localsWritten.keySet()) {
      if (!localsMap.containsKey(key)) {
        localsMap.put(key, newLocalIndex);
        newLocalIndex++;
      }
    }
    // First set of instructions is pushing the new stack from the params
    for (int i = 0; i < splitPoint.neededFromStackAtStart.size(); i++) {
      Type item = splitPoint.neededFromStackAtStart.get(i);
      int op;
      if (item == Type.INT_TYPE) op = Opcodes.ILOAD;
      else if (item == Type.LONG_TYPE) op = Opcodes.LLOAD;
      else if (item == Type.FLOAT_TYPE) op = Opcodes.FLOAD;
      else if (item == Type.DOUBLE_TYPE) op = Opcodes.DLOAD;
      else op = Opcodes.ALOAD;
      newMethod.visitVarInsn(op, i);
    }
    // Next set of instructions comes verbatim from the original, but we have to change the local indexes
    for (int i = 0; i < splitPoint.length; i++) {
      AbstractInsnNode insn = orig.instructions.get(i + splitPoint.start);
      // Skip frames
      if (insn instanceof FrameNode) continue;
      // Change the local if needed
      if (insn instanceof VarInsnNode) {
        insn = insn.clone(Collections.emptyMap());
        ((VarInsnNode) insn).var = localsMap.get(((VarInsnNode) insn).var);
      } else if (insn instanceof IincInsnNode) {
        insn = insn.clone(Collections.emptyMap());
        ((VarInsnNode) insn).var = localsMap.get(((VarInsnNode) insn).var);
      }
      insn.accept(newMethod);
    }
    // Final set of instructions is an object array of stack to set and then locals written
    // Create the object array
    int retArrSize = splitPoint.putOnStackAtEnd.size() + splitPoint.localsWritten.size();
    intConst(retArrSize).accept(newMethod);
    newMethod.visitTypeInsn(Opcodes.ANEWARRAY, Type.getInternalName(Object.class));
    // So, we're going to store the arr in the next avail local
    int retArrLocalIndex = newLocalIndex;
    newMethod.visitVarInsn(Opcodes.ASTORE, retArrLocalIndex);
    // Now go over each stack item and load the arr, swap w/ the stack, add the index, swap with the stack, and store
    for (int i = splitPoint.putOnStackAtEnd.size() - 1; i >= 0; i--) {
      Type item = splitPoint.putOnStackAtEnd.get(i);
      // Box the item on the stack if necessary
      boxStackIfNecessary(item, newMethod);
      // Load the array
      newMethod.visitVarInsn(Opcodes.ALOAD, retArrLocalIndex);
      // Swap to put stack back on top
      newMethod.visitInsn(Opcodes.SWAP);
      // Add the index
      intConst(i).accept(newMethod);
      // Swap to put the stack value back on top
      newMethod.visitInsn(Opcodes.SWAP);
      // Now that we have arr, index, value, we can store in the array
      newMethod.visitInsn(Opcodes.AASTORE);
    }
    // Do the same with written locals
    int currIndex = splitPoint.putOnStackAtEnd.size();
    for (Integer index : splitPoint.localsWritten.keySet()) {
      Type item = splitPoint.localsWritten.get(index);
      // Load the array
      newMethod.visitVarInsn(Opcodes.ALOAD, retArrLocalIndex);
      // Add the arr index
      intConst(currIndex).accept(newMethod);
      currIndex++;
      // Load the var
      int op;
      if (item == Type.INT_TYPE) op = Opcodes.ILOAD;
      else if (item == Type.FLOAT_TYPE) op = Opcodes.FLOAD;
      else if (item == Type.LONG_TYPE) op = Opcodes.LLOAD;
      else if (item == Type.DOUBLE_TYPE) op = Opcodes.DLOAD;
      else op = Opcodes.ALOAD;
      newMethod.visitVarInsn(op, localsMap.get(index));
      // Box it if necessary
      boxStackIfNecessary(item, newMethod);
      // Store in array
      newMethod.visitInsn(Opcodes.AASTORE);
    }
    // Load the array out and return it
    newMethod.visitVarInsn(Opcodes.ALOAD, retArrLocalIndex);
    newMethod.visitInsn(Opcodes.ARETURN);
    return newMethod;
  }

  protected MethodNode createTrimmedMethod(MethodNode orig, MethodNode splitOff, Splitter.SplitPoint splitPoint) {
    throw new UnsupportedOperationException("TODO: the rest");
  }

  protected static void boxStackIfNecessary(Type type, MethodNode method) {
    if (type == Type.INT_TYPE) boxCall(Integer.class, type).accept(method);
    else if (type == Type.FLOAT_TYPE) boxCall(Float.class, type).accept(method);
    else if (type == Type.LONG_TYPE) boxCall(Long.class, type).accept(method);
    else if (type == Type.DOUBLE_TYPE) boxCall(Double.class, type).accept(method);
  }

  protected static AbstractInsnNode intConst(int v) {
    switch (v) {
      case -1: return new InsnNode(Opcodes.ICONST_M1);
      case 0: return new InsnNode(Opcodes.ICONST_0);
      case 1: return new InsnNode(Opcodes.ICONST_1);
      case 2: return new InsnNode(Opcodes.ICONST_2);
      case 3: return new InsnNode(Opcodes.ICONST_3);
      case 4: return new InsnNode(Opcodes.ICONST_4);
      case 5: return new InsnNode(Opcodes.ICONST_5);
      default: return new LdcInsnNode(v);
    }
  }

  protected static MethodInsnNode boxCall(Class<?> boxType, Type primType) {
    return new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(boxType),
        "valueOf", Type.getMethodDescriptor(Type.getType(boxType), primType), false);
  }

  public static class Result {
    public final MethodNode trimmedMethod;
    public final MethodNode splitOffMethod;

    public Result(MethodNode trimmedMethod, MethodNode splitOffMethod) {
      this.trimmedMethod = trimmedMethod;
      this.splitOffMethod = splitOffMethod;
    }
  }
}
