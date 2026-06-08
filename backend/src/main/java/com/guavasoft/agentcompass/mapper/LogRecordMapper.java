package com.guavasoft.agentcompass.mapper;

import org.mapstruct.Mapper;

import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.model.LogRecord;

import java.util.List;

@Mapper(componentModel = "spring")
public interface LogRecordMapper {

  LogRecord toLogRecord(LogRecordEntity entity);

  List<LogRecord> toLogRecords(List<LogRecordEntity> entities);
}
