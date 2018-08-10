package msplit;

import org.objectweb.asm.Type;

public class SplitPoint {
  public final int[] localsReferenced;
  public final Type[] neededFromStackAtStart;
  public final Type[] putOnStackAtEnd;
  public final int start;
  public final int length;

  public SplitPoint(int[] localsReferenced, Type[] neededFromStackAtStart,
      Type[] putOnStackAtEnd, int start, int length) {
    this.localsReferenced = localsReferenced;
    this.neededFromStackAtStart = neededFromStackAtStart;
    this.putOnStackAtEnd = putOnStackAtEnd;
    this.start = start;
    this.length = length;
  }
}
