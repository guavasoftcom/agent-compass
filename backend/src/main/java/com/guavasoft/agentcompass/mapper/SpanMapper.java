package com.guavasoft.agentcompass.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.model.Span;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SpanMapper {

  // costUsd has no source field on SpanEntity -- it is filled in afterwards by
  // TraceService#spansForTrace from one grouped per-trace cost query, not read
  // per-span off the entity. See SpanRepository#findSpanCostsForTrace.
  @Mapping(target = "costUsd", ignore = true)
  Span toSpan(SpanEntity entity);

  List<Span> toSpans(List<SpanEntity> entities);
}
