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

import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.model.Span;

import java.util.List;

@Mapper(componentModel = "spring")
public interface SpanMapper {

  // costUsd has no source field on SpanEntity -- it is filled in afterwards by
  // TraceService#spansForTrace from one grouped per-trace cost query, not read
  // per-span off the entity. See SpanRepository#findSpanCostsForTrace.
  @Mapping(target = "costUsd", ignore = true)
  // effort has no source field on SpanEntity either -- it is filled in afterwards
  // by TraceService#spansForTrace from one grouped per-trace effort query. See
  // SpanRepository#findSpanEffortsForTrace.
  @Mapping(target = "effort", ignore = true)
  Span toSpan(SpanEntity entity);

  List<Span> toSpans(List<SpanEntity> entities);
}
