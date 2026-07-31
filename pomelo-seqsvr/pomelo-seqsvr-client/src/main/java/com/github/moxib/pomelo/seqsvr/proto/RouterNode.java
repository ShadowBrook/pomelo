package com.github.moxib.pomelo.seqsvr.proto;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由器节点 — 一个 AllocSvr 及其负责的 Section 范围。
 */
public class RouterNode {

  private String nodeId;
  private String ip;
  private int port;
  private List<RangeId> sectionRanges;

  public RouterNode() {
    this.sectionRanges = new ArrayList<>();
  }

  public RouterNode(String nodeId, String ip, int port, List<RangeId> sectionRanges) {
    this.nodeId = nodeId;
    this.ip = ip;
    this.port = port;
    this.sectionRanges = sectionRanges;
  }

  public String getNodeId() { return nodeId; }
  public void setNodeId(String nodeId) { this.nodeId = nodeId; }

  public String getIp() { return ip; }
  public void setIp(String ip) { this.ip = ip; }

  public int getPort() { return port; }
  public void setPort(int port) { this.port = port; }

  public List<RangeId> getSectionRanges() { return sectionRanges; }
  public void setSectionRanges(List<RangeId> sectionRanges) { this.sectionRanges = sectionRanges; }
}
