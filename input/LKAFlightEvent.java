/**
 * LookAhead events that are associated with a particular Flight. These events must have an associated LKAFlightKey.
 *
 * @Version 1.0
 */
package com.aa.lookahead.dataobjects.event;

import com.aa.lookahead.dataobjects.LKAFlightKey;
import com.aa.lookahead.dataobjects.SeverityLevel;
import com.aa.lookahead.dataobjects.event.enums.EventStatus;
import com.aa.lookahead.dataobjects.event.payloads.EventPayload;
import com.aa.lookahead.utils.common.MirrorConstants;
import com.aa.lookahead.utils.common.annotations.MirrorColumn;

import java.util.Date;
import java.util.HashMap;
import java.util.Objects;

public abstract class LKAFlightEvent extends LKAEvent {


    private static final long serialVersionUID = 7812641090633641845L;
    @MirrorColumn(name = MirrorConstants.FLIGHT_KEY, type = MirrorConstants.NVARCHAR, precision = MirrorConstants.NVARCHAR_PRECISION)
    private LKAFlightKey flightKey;
    private EventPayload eventPayload = null;
    private String arrivalStation;
    private LKAFlightKey previousFlightKey;

    public LKAFlightEvent() {
        super();
    }

    //Constructors

    public LKAFlightEvent(LKAEventType eventType, Date timeStamp, LKAFlightKey flightKey) {
        super(timeStamp, eventType, flightKey);
        this.flightKey = flightKey;
        this.eventStatusDetails.setEventStatus(EventStatus.NEW);
    }

    public LKAFlightEvent(LKAEventType eventType, Date timeStamp, LKAFlightKey flightKey, Object routeID) {
        super(timeStamp, eventType, routeID);
        this.flightKey = flightKey;
        this.eventStatusDetails.setEventStatus(EventStatus.NEW);
    }
    
    public LKAFlightEvent(LKAEventType eventType, Date timeStamp, LKAFlightKey flightKey, Object routeID,RequestType requestType, Object requestID) {
        super(timeStamp, eventType, routeID,requestType,requestID);
        this.flightKey = flightKey;
        this.eventStatusDetails.setEventStatus(EventStatus.NEW);
    }
    

    public LKAFlightEvent(LKAEventType eventType, Date timeStamp, LKAFlightKey flightKey, Object routeID, String arrivalStation) {
        super(timeStamp, eventType, routeID);
        this.flightKey = flightKey;
        this.eventStatusDetails.setEventStatus(EventStatus.NEW);
        this.arrivalStation = arrivalStation;
    }

    public LKAFlightEvent(LKAEventType eventType, Date timeStamp, LKAFlightEvent lkaFlightEvent) {
        super(timeStamp, eventType, lkaFlightEvent.getRouteID(), lkaFlightEvent.getRequestType(), lkaFlightEvent.getRequestID(),
                lkaFlightEvent.getBatchId(), lkaFlightEvent.preProcessorId);
        this.flightKey = lkaFlightEvent.getFlightKey();
        this.eventStatusDetails.setEventStatus(EventStatus.NEW);
        this.arrivalStation = lkaFlightEvent.getArrivalStation();
    }

    //Override Methods
    @Override
    public String getTupleTypeString() {
        String superTuple = super.getTupleTypeString();
        superTuple = (String) superTuple.subSequence(0, (superTuple.length() - 1)); //remove that ending '>'.
        String attributes = ", ustring airlineCode, ustring flightNumber, ustring flightDate, ustring departureStation, uint32 dupDepCode>";
        return superTuple.concat(attributes);
    }

    @Override
    public HashMap<String, Object> getAttributeMap() {
        HashMap<String, Object> superMap = super.getAttributeMap();
        superMap.put("airlineCode", flightKey.getAirlineCode() == null ? "" : flightKey.getAirlineCode());
        superMap.put("flightDate", flightKey.getFlightDate() == null ? "" : flightKey.getFlightDate());
        superMap.put("departureStation", flightKey.getDepartureStation() == null ? "" : flightKey.getDepartureStation());
        superMap.put("flightNumber", flightKey.getFlightNumber() == null ? "" : flightKey.getFlightNumber());
        superMap.put("dupDepCode", flightKey.getDupDepCode() == null ? 0 : flightKey.getDupDepCode());
        superMap.put("eventType", (eventType != null ? eventType.toString() : ""));
        return superMap;
    }

    /**
     * The purpose of this method is to determine if the given LKAEvent is a problem. It should return true if it is:
     *
     * @return true if the flight is a problem, otherwise false
     */
    public Boolean determineIfProblem() {
        //default to false, overwrite at subtype level
        return false;
    }


    @Override
    public String toString() {
        return "LKAFlightEvent ["
                + (flightKey != null ? "flightKey=" + flightKey + ", " : "")
                + (requestID != null ? "requestID=" + requestID + ", " : "")
                + (eventType != null ? "eventType=" + eventType : "") + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LKAFlightEvent)) return false;
        LKAFlightEvent that = (LKAFlightEvent) o;
        return Objects.equals(getFlightKey(), that.getFlightKey()) &&
                Objects.equals(getEventPayload(), that.getEventPayload()) &&
                Objects.equals(getArrivalStation(), that.getArrivalStation()) &&
                Objects.equals(getPreviousFlightKey(), that.getPreviousFlightKey());
    }

    @Override
    public int hashCode() {

        return Objects.hash(getFlightKey(), getEventPayload(), getArrivalStation(), getPreviousFlightKey());
    }

    //Getters and Setters
    //-------------------

    public LKAFlightKey getFlightKey() {
        return flightKey;
    }

    public void setFlightKey(LKAFlightKey flightKey) {
        this.flightKey = flightKey;
    }

    public String getArrivalStation() {
        return arrivalStation;
    }

    public void setArrivalStation(String arrivalStation) {
        this.arrivalStation = arrivalStation;
    }

    public LKAFlightKey getPreviousFlightKey() {
        return previousFlightKey;
    }

    public void setPreviousFlightKey(LKAFlightKey previousFlightKey) {
        this.previousFlightKey = previousFlightKey;
    }


    /**
     * @return the eventPayload
     */
    public <T extends EventPayload> T getEventPayload() {
        return (T) eventPayload;
    }

    /**
     * @return the eventPayload
     */
    public <T extends EventPayload> void setEventPayload(T eventPayload) {
        this.eventPayload = eventPayload;
    }

    public Integer getProblemMagnitude() {
        return 0;
    }

    public Integer getProblemMinutes() {
        return 0;
    }

    public SeverityLevel getSeverityLevel() {
        return SeverityLevel.WARNING;
    }
}
