/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.protocol.common.fsm.transition.authentication;

import org.neo4j.bolt.fsm.Context;
import org.neo4j.bolt.fsm.error.StateMachineException;
import org.neo4j.bolt.fsm.state.StateReference;
import org.neo4j.bolt.fsm.state.transition.AbstractStateTransition;
import org.neo4j.bolt.protocol.common.fsm.States;
import org.neo4j.bolt.protocol.common.fsm.response.ResponseHandler;
import org.neo4j.bolt.protocol.common.message.request.authentication.LogoffMessage;

/**
 * Handles de-authentication for the purposes of user switching within supported protocol versions.
 * <p />
 * Transitions to {@link States#AUTHENTICATION} when successfully executed.
 */
public final class LogoffStateTransition extends AbstractStateTransition<LogoffMessage> {
    private static final LogoffStateTransition INSTANCE = new LogoffStateTransition();

    private LogoffStateTransition() {
        super(LogoffMessage.class);
    }

    public static LogoffStateTransition getInstance() {
        return INSTANCE;
    }

    @Override
    public StateReference process(Context ctx, LogoffMessage message, ResponseHandler handler)
            throws StateMachineException {
        ctx.connection().logoff();

        // return the default state to authentication in order to prevent clients from accessing
        // protected resources while the connection does not have a login context
        ctx.defaultState(States.AUTHENTICATION);
        return States.AUTHENTICATION;
    }
}
