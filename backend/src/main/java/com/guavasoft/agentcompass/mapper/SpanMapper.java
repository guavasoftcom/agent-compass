package com.guavasoft.agentcompass.mapper;

import org.mapstruct.Mapper;

import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.model.Span;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SpanMapper {

  Span toSpan(SpanEntity entity);

  List<Span> toSpans(List<SpanEntity> entities);
}
