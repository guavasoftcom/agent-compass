/*
 * Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not,
 * see <https://www.gnu.org/licenses/>.
 */
package com.guavasoft.agentcompass.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.guavasoft.agentcompass.entity.TraceAnalysisEntity;
import com.guavasoft.agentcompass.model.TraceAnalysis;

@Mapper(componentModel = "spring")
public interface TraceAnalysisMapper {

  @Mapping(target = "analysis", source = "analysisText")
  @Mapping(target = "analyzedThroughTimestamp", source = "lastSpanEndTimestamp")
  // outdated has no source field on TraceAnalysisEntity -- it is filled in afterwards by
  // TraceAnalysisService from a fresh comparison against the trace's current latest span end
  // timestamp, not stored on the entity itself. See TraceAnalysis#withOutdated.
  @Mapping(target = "outdated", ignore = true)
  TraceAnalysis toTraceAnalysis(TraceAnalysisEntity entity);
}
