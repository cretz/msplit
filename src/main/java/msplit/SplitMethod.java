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
    return fromSplitPoint(owner, method, largest);
  }

  public Result fromSplitPoint(String owner, MethodNode orig, Splitter.SplitPoint splitPoint) {
    MethodNode splitOff = createSplitOffMethod(orig, splitPoint);
    MethodNode trimmed = createTrimmedMethod(owner, orig, splitOff, splitPoint);
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
      newMethod.visitVarInsn(loadOpFromType(item), i);
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
      newMethod.visitVarInsn(loadOpFromType(item), localsMap.get(index));
      // Box it if necessary
      boxStackIfNecessary(item, newMethod);
      // Store in array
      newMethod.visitInsn(Opcodes.AASTORE);
    }
    // Load the array out and return it
    newMethod.visitVarInsn(Opcodes.ALOAD, retArrLocalIndex);
    newMethod.visitInsn(Opcodes.ARETURN);
    // Any try catch blocks that were completely within we re-add here
    // TODO: Add try-catch blocks that were completely inside
    // Reset the labels
    newMethod.instructions.resetLabels();
    return newMethod;
  }

  protected MethodNode createTrimmedMethod(String owner, MethodNode orig,
      MethodNode splitOff, Splitter.SplitPoint splitPoint) {
    // The trimmed method is the same as the original, yet the split area is replaced with a call to the split off
    // portion. Before calling the split-off, we have to add locals to the stack part. Then afterwards, we have to
    // replace the stack and written locals.
    // Effectively clone the orig
    MethodNode newMethod = new MethodNode(api, orig.access, orig.name, orig.desc,
        orig.signature, orig.exceptions.toArray(new String[0]));
    orig.accept(newMethod);
    // Remove all insns, we'll re-add the ones outside the split range
    newMethod.instructions.clear();
    // Remove all try catch blocks, we'll re-add them at the end
    newMethod.tryCatchBlocks.clear();
    // Add the insns before split
    for (int i = 0; i < splitPoint.start; i++) {
      AbstractInsnNode insn = orig.instructions.get(i + splitPoint.start);
      // Skip frames
      if (insn instanceof FrameNode) continue;
      insn.accept(newMethod);
    }
    // Push all the read locals on the stack
    splitPoint.localsRead.forEach((index, type) -> newMethod.visitVarInsn(loadOpFromType(type), index));
    // Invoke the split off method
    newMethod.visitMethodInsn(Opcodes.INVOKESTATIC, owner, splitOff.name, splitOff.desc, false);
    // Now the object array is on the stack which contains stack pieces + written locals
    // Take off the locals
    int localArrIndex = splitPoint.putOnStackAtEnd.size();
    for (Integer index : splitPoint.localsWritten.keySet()) {
      // Dupe the array
      newMethod.visitInsn(Opcodes.DUP);
      // Put the index on the stack
      intConst(localArrIndex).accept(newMethod);
      localArrIndex++;
      // Load the written local
      Type item = splitPoint.localsWritten.get(index);
      newMethod.visitInsn(arrLoadOpFromType(item));
      // Cast to local type
      if (!"java/lang/Object".equals(item.getInternalName())) {
        newMethod.visitTypeInsn(Opcodes.CHECKCAST, boxedTypeIfNecessary(item).getInternalName());
      }
      // Unbox if necessary
      unboxStackIfNecessary(item, newMethod);
      // Store in the local
      newMethod.visitVarInsn(storeOpFromType(item), index);
    }
    // Now just load up the stack
    for (int i = 0; i < splitPoint.putOnStackAtEnd.size(); i++) {
      boolean last = i == splitPoint.putOnStackAtEnd.size() - 1;
      // Since the loop started with the array, we only dupe the array every time but the last
      if (!last) newMethod.visitInsn(Opcodes.DUP);
      // Put the index on the stack
      intConst(i).accept(newMethod);
      // Load the stack item
      Type item = splitPoint.putOnStackAtEnd.get(i);
      newMethod.visitInsn(arrLoadOpFromType(item));
      // Cast to local type
      if (!"java/lang/Object".equals(item.getInternalName())) {
        newMethod.visitTypeInsn(Opcodes.CHECKCAST, boxedTypeIfNecessary(item).getInternalName());
      }
      // Unbox if necessary
      unboxStackIfNecessary(item, newMethod);
      // For all but the last stack item, we need to swap with the arr ref above.
      if (!last) {
        // Note if the stack item takes two slots, we do a form of dup then pop since there's no swap1x2
        if (item == Type.LONG_TYPE || item == Type.DOUBLE_TYPE) {
          newMethod.visitInsn(Opcodes.DUP_X2);
          newMethod.visitInsn(Opcodes.POP);
        } else {
          newMethod.visitInsn(Opcodes.SWAP);
        }
      }
    }
    // Now we have restored all locals and all stack...add the rest of the insns after the split
    for (int i = splitPoint.start + splitPoint.length; i < orig.instructions.size(); i++) {
      AbstractInsnNode insn = orig.instructions.get(i + splitPoint.start);
      // Skip frames
      if (insn instanceof FrameNode) continue;
      insn.accept(newMethod);
    }
    // TODO: Add the try-catch blocks
    // Reset the labels
    newMethod.instructions.resetLabels();
    return newMethod;
  }

  protected static int storeOpFromType(Type type) {
    if (type == Type.INT_TYPE) return Opcodes.ISTORE;
    else if (type == Type.LONG_TYPE) return Opcodes.LSTORE;
    else if (type == Type.FLOAT_TYPE) return Opcodes.FSTORE;
    else if (type == Type.DOUBLE_TYPE) return Opcodes.DSTORE;
    else return Opcodes.ASTORE;
  }

  protected static int loadOpFromType(Type type) {
    if (type == Type.INT_TYPE) return Opcodes.ILOAD;
    else if (type == Type.LONG_TYPE) return Opcodes.LLOAD;
    else if (type == Type.FLOAT_TYPE) return Opcodes.FLOAD;
    else if (type == Type.DOUBLE_TYPE) return Opcodes.DLOAD;
    else return Opcodes.ALOAD;
  }

  protected static int arrLoadOpFromType(Type type) {
    if (type == Type.INT_TYPE) return Opcodes.IALOAD;
    else if (type == Type.LONG_TYPE) return Opcodes.LALOAD;
    else if (type == Type.FLOAT_TYPE) return Opcodes.FALOAD;
    else if (type == Type.DOUBLE_TYPE) return Opcodes.DALOAD;
    else return Opcodes.AALOAD;
  }

  protected static Type boxedTypeIfNecessary(Type type) {
    if (type == Type.INT_TYPE) return Type.getType(Integer.class);
    else if (type == Type.LONG_TYPE) return Type.getType(Long.class);
    else if (type == Type.FLOAT_TYPE) return Type.getType(Float.class);
    else if (type == Type.DOUBLE_TYPE) return Type.getType(Double.class);
    else return type;
  }

  protected static void boxStackIfNecessary(Type type, MethodNode method) {
    if (type == Type.INT_TYPE) boxCall(Integer.class, type).accept(method);
    else if (type == Type.FLOAT_TYPE) boxCall(Float.class, type).accept(method);
    else if (type == Type.LONG_TYPE) boxCall(Long.class, type).accept(method);
    else if (type == Type.DOUBLE_TYPE) boxCall(Double.class, type).accept(method);
  }

  protected static void unboxStackIfNecessary(Type type, MethodNode method) {
    if (type == Type.INT_TYPE) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
        "java/lang/Integer", "intValue", Type.getMethodDescriptor(Type.INT_TYPE), false);
    else if (type == Type.FLOAT_TYPE) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
        "java/lang/Float", "floatValue", Type.getMethodDescriptor(Type.FLOAT_TYPE), false);
    else if (type == Type.LONG_TYPE) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
        "java/lang/Long", "longValue", Type.getMethodDescriptor(Type.LONG_TYPE), false);
    else if (type == Type.DOUBLE_TYPE) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
        "java/lang/Double", "doubleValue", Type.getMethodDescriptor(Type.DOUBLE_TYPE), false);
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
