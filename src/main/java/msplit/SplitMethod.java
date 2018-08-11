package msplit;


import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    for (int localRead : splitPoint.localsRead) {
      // Add the arg
      args.add(Type.getType(method.localVariables.get(localRead).desc));
      // Add the local map
      localsMap.put(localRead, args.size() - 1);
    }
    // Create the new instructions...

    MethodNode newMethod = new MethodNode(api, Opcodes.ACC_PRIVATE & Opcodes.ACC_SYNTHETIC, method.name + "$split",
        Type.getMethodDescriptor(Type.getType("[Ljava/lang/Object;"), args.toArray(new Type[0])), null, null);

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
