/*
 * ============================================================================
 * GNU General Public License
 * ============================================================================
 *
 * Copyright (C) 2015 Infinite Automation Software. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * When signing a commercial license with Infinite Automation Software,
 * the following extension to GPL is made. A special exception to the GPL is
 * included to allow you to distribute a combined work that includes BAcnet4J
 * without being obliged to provide the source code for any proprietary components.
 *
 * See www.infiniteautomation.com for commercial license options.
 * 
 * @author Matthew Lohbihler
 */
package com.serotonin.bacnet4j.service.confirmed;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.exception.BACnetErrorException;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.obj.PropertyTypeDefinition;
import com.serotonin.bacnet4j.service.acknowledgement.AcknowledgementService;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.BACnetArray;
import com.serotonin.bacnet4j.type.constructed.BACnetError;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.ErrorClass;
import com.serotonin.bacnet4j.type.enumerated.ErrorCode;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.error.ChangeListError;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.sero.ByteQueue;

public class RemoveListElementRequest extends ConfirmedRequestService {
    private static final long serialVersionUID = 5047207667417777074L;

    public static final byte TYPE_ID = 9;

    private final ObjectIdentifier objectIdentifier;
    private final PropertyIdentifier propertyIdentifier;
    private final UnsignedInteger propertyArrayIndex;
    private final SequenceOf<? extends Encodable> listOfElements;

    public RemoveListElementRequest(ObjectIdentifier objectIdentifier, PropertyIdentifier propertyIdentifier,
            UnsignedInteger propertyArrayIndex, SequenceOf<? extends Encodable> listOfElements) {
        this.objectIdentifier = objectIdentifier;
        this.propertyIdentifier = propertyIdentifier;
        this.propertyArrayIndex = propertyArrayIndex;
        this.listOfElements = listOfElements;
    }

    @Override
    public byte getChoiceId() {
        return TYPE_ID;
    }

    @Override
    public void write(ByteQueue queue) {
        write(queue, objectIdentifier, 0);
        write(queue, propertyIdentifier, 1);
        writeOptional(queue, propertyArrayIndex, 2);
        write(queue, listOfElements, 3);
    }

    RemoveListElementRequest(ByteQueue queue) throws BACnetException {
        objectIdentifier = read(queue, ObjectIdentifier.class, 0);
        propertyIdentifier = read(queue, PropertyIdentifier.class, 1);
        propertyArrayIndex = readOptional(queue, UnsignedInteger.class, 2);
        PropertyTypeDefinition def = ObjectProperties.getPropertyTypeDefinition(objectIdentifier.getObjectType(),
                propertyIdentifier);
        listOfElements = readSequenceOf(queue, def.getClazz(), 3);
    }

    @SuppressWarnings("unchecked")
    @Override
    public AcknowledgementService handle(LocalDevice localDevice, Address from) throws BACnetException {
        BACnetObject obj = localDevice.getObject(objectIdentifier);
        if (obj == null)
            throw createException(ErrorClass.object, ErrorCode.unknownObject, new UnsignedInteger(0));

        PropertyTypeDefinition def = ObjectProperties.getPropertyTypeDefinition(objectIdentifier.getObjectType(),
                propertyIdentifier);
        if (def == null)
            throw createException(ErrorClass.property, ErrorCode.unknownProperty, new UnsignedInteger(0));

        Encodable e;
        try {
            e = obj.getProperty(propertyIdentifier);
        }
        catch (BACnetServiceException ex) {
            throw createException(ErrorClass.property, ErrorCode.unknownProperty, new UnsignedInteger(0));
        }
        if (e == null)
            throw createException(ErrorClass.property, ErrorCode.unknownProperty, new UnsignedInteger(0));

        PropertyValue pv = new PropertyValue(propertyIdentifier, propertyArrayIndex, listOfElements, null);
        if (!localDevice.getEventHandler().checkAllowPropertyWrite(from, obj, pv))
            throw createException(ErrorClass.property, ErrorCode.writeAccessDenied, new UnsignedInteger(0));

        if (propertyArrayIndex == null) {
            // Expecting a list
            if (!(e instanceof SequenceOf))
                throw createException(ErrorClass.property, ErrorCode.propertyIsNotAList, new UnsignedInteger(0));
            if (e instanceof BACnetArray)
                throw createException(ErrorClass.property, ErrorCode.propertyIsNotAList, new UnsignedInteger(0));

            SequenceOf<Encodable> origList = (SequenceOf<Encodable>) e;
            SequenceOf<Encodable> list = new SequenceOf<Encodable>(origList.getValues());
            for (int i = 0; i < listOfElements.getCount(); i++) {
                Encodable pr = listOfElements.get(i + 1);
                if (!def.getClazz().isAssignableFrom(pr.getClass()))
                    throw createException(ErrorClass.property, ErrorCode.datatypeNotSupported, new UnsignedInteger(
                            i + 1));
                if (!list.contains(pr))
                    throw createException(ErrorClass.services, ErrorCode.listElementNotFound,
                            new UnsignedInteger(i + 1));
                list.remove(pr);
            }

            obj.writeProperty(propertyIdentifier, origList);
        }
        else
            // It doesn't make much sense to me how elements can be removed from an array. 
            throw createException(ErrorClass.property, ErrorCode.writeAccessDenied, new UnsignedInteger(0));

        localDevice.getEventHandler().propertyWritten(from, obj, pv);

        return null;
    }

    private BACnetErrorException createException(ErrorClass errorClass, ErrorCode errorCode,
            UnsignedInteger firstFailedElementNumber) {
        return new BACnetErrorException(new ChangeListError(getChoiceId(), new BACnetError(errorClass, errorCode),
                firstFailedElementNumber));
    }

    @Override
    public int hashCode() {
        final int PRIME = 31;
        int result = 1;
        result = PRIME * result + ((objectIdentifier == null) ? 0 : objectIdentifier.hashCode());
        result = PRIME * result + ((listOfElements == null) ? 0 : listOfElements.hashCode());
        result = PRIME * result + ((propertyArrayIndex == null) ? 0 : propertyArrayIndex.hashCode());
        result = PRIME * result + ((propertyIdentifier == null) ? 0 : propertyIdentifier.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        final RemoveListElementRequest other = (RemoveListElementRequest) obj;
        if (objectIdentifier == null) {
            if (other.objectIdentifier != null)
                return false;
        }
        else if (!objectIdentifier.equals(other.objectIdentifier))
            return false;
        if (listOfElements == null) {
            if (other.listOfElements != null)
                return false;
        }
        else if (!listOfElements.equals(other.listOfElements))
            return false;
        if (propertyArrayIndex == null) {
            if (other.propertyArrayIndex != null)
                return false;
        }
        else if (!propertyArrayIndex.equals(other.propertyArrayIndex))
            return false;
        if (propertyIdentifier == null) {
            if (other.propertyIdentifier != null)
                return false;
        }
        else if (!propertyIdentifier.equals(other.propertyIdentifier))
            return false;
        return true;
    }
}
