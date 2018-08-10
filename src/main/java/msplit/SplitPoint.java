package msplit;

import org.objectweb.asm.Type;

public class SplitPoint {
  public final int[] localsRead;
  public final int[] localsWritten;
  public final Type[] neededFromStackAtStart;
  public final Type[] putOnStackAtEnd;
  public final int start;
  public final int length;

  public SplitPoint(int[] localsRead, int[] localsWritten,
      Type[] neededFromStackAtStart, Type[] putOnStackAtEnd, int start, int length) {
    this.localsRead = localsRead;
    this.localsWritten = localsWritten;
    this.neededFromStackAtStart = neededFromStackAtStart;
    this.putOnStackAtEnd = putOnStackAtEnd;
    this.start = start;
    this.length = length;
  }
}
