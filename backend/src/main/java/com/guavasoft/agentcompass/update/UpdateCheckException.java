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
package com.guavasoft.agentcompass.update;

/**
 * The latest release could not be determined. The message is written to be shown to the operator
 * as it stands, and is never mapped to an HTTP error: a check that cannot reach GitHub is a normal
 * outcome, reported in {@code UpdateCheckStatus#message}, not a failed request.
 */
public class UpdateCheckException extends RuntimeException {

    public UpdateCheckException(String message) {
        super(message);
    }

    public UpdateCheckException(String message, Throwable cause) {
        super(message, cause);
    }
}
