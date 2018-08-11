package msplit;


import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

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

  public Result fromSplitPoint(MethodNode method, Splitter.SplitPoint splitPoint) {
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
        Opcodes.ACC_STATIC & Opcodes.ACC_PRIVATE & Opcodes.ACC_SYNTHETIC, method.name + "$split",
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
      AbstractInsnNode insn = method.instructions.get(i + splitPoint.start);
      if (insn instanceof VarInsnNode) {
        insn = insn.clone(Collections.emptyMap());
        ((VarInsnNode) insn).var = localsMap.get(((VarInsnNode) insn).var);
      } else if (insn instanceof IincInsnNode) {
        insn = insn.clone(Collections.emptyMap());
        ((VarInsnNode) insn).var = localsMap.get(((VarInsnNode) insn).var);
      }
      newMethod.instructions.add(insn);
    }
    // Final set of instructions is an object array of stack to set and then locals written

    throw new UnsupportedOperationException("TODO: the rest");
  }

  public static class Result {
    public final MethodNode originalAfterSplit;
    public final MethodNode newMethod;

    public Result(MethodNode originalAfterSplit, MethodNode newMethod) {
      this.originalAfterSplit = originalAfterSplit;
      this.newMethod = newMethod;
    }
  }
}
