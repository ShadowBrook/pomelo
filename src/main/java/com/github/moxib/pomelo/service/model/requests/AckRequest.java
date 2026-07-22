package com.github.moxib.pomelo.service.model.requests;

import com.github.moxib.pomelo.proto.ack.AckProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ACK 确认请求 DTO（PB AckReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AckRequest(List<Long> messageIds, int ackType) {
  public static AckRequest fromProto(AckProto.AckReq proto) {
    return new AckRequest(
      proto.getMessageIdsList().stream().map(Long::valueOf).toList(),
      proto.getAckTypeValue());
  }
}
