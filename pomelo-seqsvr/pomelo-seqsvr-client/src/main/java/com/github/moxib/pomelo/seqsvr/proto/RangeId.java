package com.github.moxib.pomelo.seqsvr.proto;

/**
 * ID 范围，对应 Go 的 RangeID。
 * SectionID 和 SetID 都是 RangeId 的 type alias。
 */
public class RangeId {

  private int idBegin;
  private int size;

  public RangeId() {}

  public RangeId(int idBegin, int size) {
    this.idBegin = idBegin;
    this.size = size;
  }

  public int getIdBegin() { return idBegin; }
  public void setIdBegin(int idBegin) { this.idBegin = idBegin; }

  public int getSize() { return size; }
  public void setSize(int size) { this.size = size; }

  /** 计算指定 id 属于该 Range 内的哪个 section */
  public SectionResult calcSectionID(int id) {
    if (id < idBegin || id >= idBegin + size) {
      return SectionResult.notFound();
    }
    int idx = (id - idBegin) / SeqSvrConstants.SECTION_SIZE;
    return SectionResult.found(idx);
  }

  /** 该 Range 内可以分配多少 section */
  public int calcSetSectionSize() {
    int m = size % SeqSvrConstants.SECTION_SIZE;
    int sz = size / SeqSvrConstants.SECTION_SIZE;
    return m == 0 ? sz : sz + 1;
  }

  public static class SectionResult {
    private final boolean found;
    private final int sectionIdx;

    private SectionResult(boolean found, int sectionIdx) {
      this.found = found;
      this.sectionIdx = sectionIdx;
    }

    static SectionResult notFound() { return new SectionResult(false, -1); }
    static SectionResult found(int idx) { return new SectionResult(true, idx); }

    public boolean isFound() { return found; }
    public int getSectionIdx() { return sectionIdx; }
  }
}
