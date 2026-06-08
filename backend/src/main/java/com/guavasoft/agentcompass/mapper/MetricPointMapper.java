package com.guavasoft.agentcompass.mapper;

import org.mapstruct.Mapper;

import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.model.EventRow;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MetricPointMapper {

  EventRow toEventRow(MetricPointEntity entity);

  List<EventRow> toEventRows(List<MetricPointEntity> entities);
}
