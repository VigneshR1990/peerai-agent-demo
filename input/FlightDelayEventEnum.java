package com.aa.opsco.prism.flink.enums;

import java.io.Serializable;

public enum FlightDelayEventEnum implements Serializable {
    DEP_DLY_EVENT,
    ARR_DLY_EVENT,
    GROUND_TIME_CHANGE,
    CREW_CABIN_DLY_EVENT,
    CREW_COCKPIT_DLY_EVENT,
    EQUIP_PREVLEG_CHANGE,
    ACTUAL_OFF_CHANGE,
    ACTUAL_ON_CHANGE,
    ACTUAL_OUT_CHANGE,
    ACTUAL_IN_CHANGE,
}
