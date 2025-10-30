package com.aa.lookahead.dataobjects.event.lookahead;

import com.aa.lookahead.dataobjects.LKAFlightKey;
import com.aa.lookahead.dataobjects.event.LKAEventType;
import com.aa.lookahead.dataobjects.event.LKAFlightEvent;
import com.aa.lookahead.dataobjects.event.enums.LegStatus;
import com.aa.lookahead.dataobjects.flight.Status;
import com.ibm.streams.operator.Tuple;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Objects;

public class CancelEvent extends LKAFlightEvent {
    private static final long serialVersionUID = 1L;

    private LegStatus legStatus;
    private LegStatus previousLegStatus;

    public CancelEvent() {

    }

    public CancelEvent(Status status) {
        super(LKAEventType.CANCEL, Calendar.getInstance().getTime(), status.getFlightKey());
        this.legStatus = status.getLegStatus();
        this.previousLegStatus = status.getPreviousLegStatus();
    }

    public CancelEvent(Status status, String requestID) {
        super(LKAEventType.CANCEL, Calendar.getInstance().getTime(), status.getFlightKey());
        this.legStatus = status.getLegStatus(requestID);
        this.previousLegStatus = status.getPreviousLegStatus(requestID);
    }
    
    public CancelEvent(Tuple tuple) {
        super(LKAEventType.CANCEL, Calendar.getInstance().getTime(), new LKAFlightKey(tuple));
        String legStatus = tuple.getString("newValue");
        this.legStatus = LegStatus.getLegStatus(legStatus);
        this.previousLegStatus = LegStatus.getLegStatus(tuple.getString("previousValue"));
    }

    public CancelEvent(CancelEvent event) {
        super(event.getLKAEventType(), new Date(), event);
        this.legStatus = event.getLegStatus();
        this.previousLegStatus = event.getPreviousLegStatus();
    }


    @Override
    public Boolean determineIfProblem() {
        return false;
    }

    //Override Methods
    @Override
    public String getTupleTypeString() {
        String superTuple = super.getTupleTypeString();
        superTuple = (String) superTuple.subSequence(0, (superTuple.length() - 1)); //remove that ending '>'.
        String attributes = ", ustring newValue, ustring previousValue>";
        return superTuple.concat(attributes);
    }

    @Override
    public HashMap<String, Object> getAttributeMap() {
        HashMap<String, Object> superMap = super.getAttributeMap();
        superMap.put("newValue", (legStatus != null ? legStatus.getValue() : ""));
        superMap.put("previousValue", (previousLegStatus != null ? previousLegStatus.toString() : ""));
        return superMap;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CancelEvent)) return false;
        if (!super.equals(o)) return false;
        CancelEvent that = (CancelEvent) o;

        return getLegStatus() == that.getLegStatus() &&
                getPreviousLegStatus() == that.getPreviousLegStatus();
    }

    @Override
    public String toString() {
        return "CancelEvent{" +
                "legStatus=" + legStatus +
                ", previousLegStatus=" + previousLegStatus +
                ", routeID=" + routeID +
                ", requestType=" + requestType +
                ", requestID=" + requestID +
                ", batchId='" + batchId + '\'' +
                ", preProcessorId=" + preProcessorId +
                ", eventStatusDetails=" + eventStatusDetails +
                ", id='" + id + '\'' +
                ", timeStamp=" + timeStamp +
                ", version_id=" + version_id +
                ", app_id='" + app_id + '\'' +
                ", eventType='" + eventType + '\'' +
                "} " + super.toString();
    }

    @Override
    public int hashCode() {

        return Objects.hash(super.hashCode(), getLegStatus(), getPreviousLegStatus());
    }

    //Getters & Setters
    public LegStatus getLegStatus() {
        return legStatus;
    }

    public void setLegStatus(LegStatus legStatus) {
        this.legStatus = legStatus;
    }

    public LegStatus getPreviousLegStatus() {
        return previousLegStatus;
    }

    public void setPreviousLegStatus(LegStatus previousLegStatus) {
        this.previousLegStatus = previousLegStatus;
    }


}
