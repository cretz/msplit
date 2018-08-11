package msplit;

public class SplitPoint {
  public final int[] localsRead;
  public final int[] localsWritten;
  public final Object[] neededFromStackAtStart;
  public final Object[] putOnStackAtEnd;
  public final int start;
  public final int length;

  public SplitPoint(int[] localsRead, int[] localsWritten,
      Object[] neededFromStackAtStart, Object[] putOnStackAtEnd, int start, int length) {
    this.localsRead = localsRead;
    this.localsWritten = localsWritten;
    this.neededFromStackAtStart = neededFromStackAtStart;
    this.putOnStackAtEnd = putOnStackAtEnd;
    this.start = start;
    this.length = length;
  }
}
