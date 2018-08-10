package msplit;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public class Splitter implements Iterable<SplitPoint> {
  public final Config config;

  public Splitter(Config config) { this.config = config; }

  @Override
  public Iterator<SplitPoint> iterator() { return new Iter(config); }

  protected static class Iter implements Iterator<SplitPoint> {
    protected final Config config;
    protected final AbstractInsnNode[] insns;
    protected int currIndex = -1;
    protected boolean peeked;
    protected SplitPoint peekedValue;

    public Iter(Config config) {
      this.config = config;
      this.insns = config.method.instructions.toArray();
    }

    @Override
    public boolean hasNext() {
      if (!peeked) {
        peeked = true;
        peekedValue = nextOrNull();
      }
      return peekedValue != null;
    }

    @Override
    public SplitPoint next() {
      // If we've peeked in hasNext, use that
      SplitPoint ret;
      if (peeked) {
        peeked = false;
        ret = peekedValue;
      } else {
        ret = nextOrNull();
      }
      if (ret == null) throw new NoSuchElementException();
      return ret;
    }

    public SplitPoint nextOrNull() {
      // Try for each index
      while (++currIndex + config.minSize <= insns.length) {
        SplitPoint longest = longestForCurrIndex();
        if (longest != null) return longest;
      }
      return null;
    }

    public SplitPoint longestForCurrIndex() {
      // As a special case, if the previous insn was a line number, that was good enough
      if (currIndex - 1 >- 0 && insns[currIndex - 1] instanceof LineNumberNode) return null;

      InsnTraverseInfo info = new InsnTraverseInfo();
      info.startIndex = currIndex;
      info.endIndex = Math.min(currIndex + config.maxSize - 1, insns.length - 1);

      // Reduce the end based on try/catch blocks the start is in or that jump to
      constrainEndByTryCatchBlocks(info);
      // Reduce the end based on any jumps within
      constrainEndByInternalJumps(info);
      // Reduce the end based on any jumps into
      constrainEndByExternalJumps(info);
      // Make sure we didn't reduce the end too far
      if (info.getSize() < config.minSize) return null;

      // Now that we have our largest range from the start index, we can go over each updating the local refs and stack
      // For the stack, we are going to use the
      // TODO: go over each affecting the stack and local refs
      // TODO: final checks - can't be jumped into from outside!
      return null;
    }

    public void constrainEndByTryCatchBlocks(InsnTraverseInfo info) {
      // If there are try blocks that the start is in, we can only go to the earliest block end
      for (TryCatchBlockNode block : config.method.tryCatchBlocks) {
        // No matter what, for now, we don't include catch handling
        int handleIndex = config.method.instructions.indexOf(block.handler);
        if (info.startIndex < handleIndex) info.endIndex = Math.min(info.endIndex, handleIndex);
        // Now we can check the try-block range
        int start = config.method.instructions.indexOf(block.start);
        if (info.startIndex < start) continue;
        int end = config.method.instructions.indexOf(block.end);
        if (info.startIndex >= end) continue;
        info.endIndex = Math.min(info.endIndex, end - 1);
      }
    }

    // Returns false if any jumps jump outside of the current range
    public void constrainEndByInternalJumps(InsnTraverseInfo info) {
      for (int i = info.startIndex; i <= info.endIndex; i++) {
        AbstractInsnNode node = insns[i];
        int earliestIndex;
        int furthestIndex;
        if (node instanceof JumpInsnNode) {
          earliestIndex = config.method.instructions.indexOf(((JumpInsnNode) node).label);
          furthestIndex = earliestIndex;
        } else if (node instanceof TableSwitchInsnNode) {
          earliestIndex = config.method.instructions.indexOf(((TableSwitchInsnNode) node).dflt);
          furthestIndex = earliestIndex;
          for (LabelNode label : ((TableSwitchInsnNode) node).labels) {
            int index = config.method.instructions.indexOf(label);
            earliestIndex = Math.min(earliestIndex, index);
            furthestIndex = Math.max(furthestIndex, index);
          }
        } else if (node instanceof LookupSwitchInsnNode) {
          earliestIndex = config.method.instructions.indexOf(((LookupSwitchInsnNode) node).dflt);
          furthestIndex = earliestIndex;
          for (LabelNode label : ((LookupSwitchInsnNode) node).labels) {
            int index = config.method.instructions.indexOf(label);
            earliestIndex = Math.min(earliestIndex, index);
            furthestIndex = Math.max(furthestIndex, index);
          }
        } else continue;
        // Stop here if any indexes are out of range, otherwise, change end
        if (earliestIndex < info.startIndex || furthestIndex > info.endIndex) {
          info.endIndex = i - 1;
          return;
        }
        info.endIndex = Math.max(info.endIndex, furthestIndex);
      }
    }

    public void constrainEndByExternalJumps(InsnTraverseInfo info) {
      // Basically, if any external jumps jump into our range, that can't be included in the range
      for (int i = 0; i < insns.length; i++) {
        if (i >= info.startIndex && i <= info.endIndex) continue;
        AbstractInsnNode node = insns[i];
        if (node instanceof JumpInsnNode) {
          int index = config.method.instructions.indexOf(((JumpInsnNode) node).label);
          if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
        } else if (node instanceof TableSwitchInsnNode) {
          int index = config.method.instructions.indexOf(((TableSwitchInsnNode) node).dflt);
          if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          for (LabelNode label : ((TableSwitchInsnNode) node).labels) {
            index = config.method.instructions.indexOf(label);
            if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          }
        } else if (node instanceof LookupSwitchInsnNode) {
          int index = config.method.instructions.indexOf(((LookupSwitchInsnNode) node).dflt);
          if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          for (LabelNode label : ((LookupSwitchInsnNode) node).labels) {
            index = config.method.instructions.indexOf(label);
            if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          }
        }
      }
    }

    public void splitPointFromInfo(InsnTraverseInfo info) {
      // We're going to use the analyzer adapter and run it for the up until the end, a step at a time
      StackAndLocalTrackingAdapter adapter = new StackAndLocalTrackingAdapter(config);
      // Visit all of the insns up our start.
      // XXX: I checked the source of AnalyzerAdapter to confirm I don't need any of the surrounding stuff
      for (int i = 0; i < info.startIndex; i++) insns[i].accept(adapter);
      // Take the stack at the start and copy it off
      Object[] stackAtStart = adapter.stack.toArray();
      // Reset some adapter state
      adapter.lowestStackSize = stackAtStart.length;
      adapter.localsRead.clear();
      adapter.localsWritten.clear();
      // Now go over the remaining range
      for (int i = info.startIndex; i <= info.endIndex; i++) insns[i].accept(adapter);
      // Build the split point
    }

    public static class InsnTraverseInfo {
      public int startIndex;
      // Can only shrink, never increase in size
      public int endIndex;

      public int getSize() { return endIndex - startIndex + 1; }
    }

    public static class StackAndLocalTrackingAdapter extends AnalyzerAdapter {
      public int lowestStackSize = 0;
      public final Set<Integer> localsRead = new TreeSet<>();
      public final Set<Integer> localsWritten = new TreeSet<>();

      protected StackAndLocalTrackingAdapter(Config config) {
        super(config.api, config.owner, config.method.access, config.method.name, config.method.desc, null);
      }

      @Override
      protected void onPop() { lowestStackSize = Math.min(lowestStackSize, stack.size() - 1); }

      @Override
      protected Object get(int local) {
        localsRead.add(local);
        return super.get(local);
      }

      @Override
      protected void set(int local, Object type) {
        localsWritten.add(local);
        super.set(local, type);
      }
    }
  }

  public static class Config {
    public final int api;
    public final String owner;
    public final MethodNode method;
    public final int minSize;
    public final int maxSize;

    public Config(int api, String owner, MethodNode method, int minSize, int maxSize) {
      this.api = api;
      this.owner = owner;
      this.method = method;
      this.minSize = minSize;
      this.maxSize = maxSize;
    }
  }
}
