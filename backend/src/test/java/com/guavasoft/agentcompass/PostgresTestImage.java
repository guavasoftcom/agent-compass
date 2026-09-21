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
package com.guavasoft.agentcompass;

/**
 * The Postgres image every Testcontainers integration test runs against. Keep it in step with the
 * image pinned in {@code docker-compose.yml} and {@code backend/docker-compose.yml}, so a major
 * bump is a one-line change here.
 */
final class PostgresTestImage {

  static final String MAJOR_VERSION = "18";
  static final String NAME = "postgres:" + MAJOR_VERSION;

  private PostgresTestImage() {}
}
