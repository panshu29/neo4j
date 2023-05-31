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
package org.neo4j.kernel.api.exceptions.schema;

import static java.lang.String.format;

import org.neo4j.common.EntityType;
import org.neo4j.common.TokenNameLookup;
import org.neo4j.internal.kernel.api.exceptions.schema.ConstraintValidationException;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.constraints.TypeConstraintDescriptor;
import org.neo4j.values.storable.Value;

public class PropertyTypeException extends ConstraintValidationException {
    private final long entityId;
    private final TypeConstraintDescriptor descriptor;
    private final Value value;

    public PropertyTypeException(
            TypeConstraintDescriptor descriptor,
            Phase phase,
            long entityId,
            TokenNameLookup tokenNameLookup,
            Value value) {
        super(
                descriptor,
                phase,
                format(descriptor.schema().entityType() == EntityType.NODE ? "Node(%s)" : "Relationship(%s)", entityId),
                tokenNameLookup);
        this.entityId = entityId;
        this.descriptor = descriptor;
        this.value = value;
    }

    @Override
    public String getUserMessage(TokenNameLookup tokenNameLookup) {
        SchemaDescriptor schema = descriptor.schema();
        EntityType entityType = schema.entityType();
        String entityString = entityType == EntityType.NODE ? "Node" : "Relationship";
        String entityTokenType = entityType == EntityType.NODE ? "label" : "type";
        String entityToken = entityType == EntityType.NODE
                ? tokenNameLookup.labelGetName(schema.getLabelId())
                : tokenNameLookup.relationshipTypeGetName(schema.getRelTypeId());
        String propertyKey = tokenNameLookup.propertyKeyGetName(schema.getPropertyId());

        return format(
                "%s(%s) with %s `%s` has property `%s` of wrong type `%s`. Allowed types: %s",
                entityString,
                entityId,
                entityTokenType,
                entityToken,
                propertyKey,
                value.getTypeName(),
                descriptor.propertyType().userDescription());
    }
}
