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
package com.guavasoft.agentcompass.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Singleton row of runtime-editable Ollama connection overrides, id always {@code 1}. Either column
 * can be null independently — a null field means "no override, fall back to
 * {@code OllamaProperties}", not "override with null".
 */
@Entity
@Getter
@Setter
@Table(name = "ollama_settings")
public class OllamaSettingsEntity {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id = SINGLETON_ID;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "model")
    private String model;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
