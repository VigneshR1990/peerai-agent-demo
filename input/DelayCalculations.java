package com.aa.opsco.prism.flink.functions;

import com.aa.opsco.prism.flink.datamodel.delay.FlightDelayEvent;
import com.aa.opsco.prism.flink.datamodel.LineOfFlying.LineOfFlying;
import com.aa.opsco.prism.flink.enums.OPSEventType;
import com.aa.opsco.prism.swap.flightpath.client.model.PeFlight;
import org.apache.commons.lang3.tuple.Pair;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static com.aa.opsco.prism.flink.utils.commonUtils.getMaxTimesValue;

public class DelayCalculations implements Serializable {

    static List<LineOfFlying> _receivedLineOfFlying = new ArrayList<>();
    static List<LineOfFlying> updatedLineOfFlying;
    static String delayEventType;

    public static List<LineOfFlying> applyDelay(FlightDelayEvent flightDelayEvent, List<LineOfFlying> lineOfFlying, PeFlight prevLegFlight) {
        boolean isFirstFlight = true;
        _receivedLineOfFlying = lineOfFlying;
        delayEventType = flightDelayEvent.getEventType();
        updatedLineOfFlying = new ArrayList<>();

        for (int i = 0; i < lineOfFlying.size(); i++) {
            boolean isValidToPropagate = false;
            OPSEventType eventType = OPSEventType.valueOf(flightDelayEvent.getEventType());
            switch (eventType) {
                case DEP_DLY_EVENT:
                    isValidToPropagate = applyDepartureDelay(lineOfFlying.get(i), isFirstFlight, i);
                    break;
                case ARR_DLY_EVENT:
                    isValidToPropagate = applyArrivalDelay(lineOfFlying.get(i), isFirstFlight, i);
                    break;
                case CREW_CABIN_DLY_EVENT:
                case CREW_COCKPIT_DLY_EVENT:
                    isValidToPropagate = applyCrewDelay(flightDelayEvent, lineOfFlying.get(i), isFirstFlight, i);
                    break;
                case ACTUAL_OFF_CHANGE:
                case ACTUAL_OUT_CHANGE:
                    isValidToPropagate = apply_ActualOff_ActualOut_Change(flightDelayEvent, lineOfFlying.get(i), isFirstFlight, i);
                    break;
                case ACTUAL_ON_CHANGE:
                case ACTUAL_IN_CHANGE:
                    isValidToPropagate = apply_ActualIn_ActualOn_Change(flightDelayEvent, lineOfFlying.get(i), isFirstFlight, i);
                    break;
                case GROUND_TIME_CHANGE:
                case EQUIP_PREVLEG_CHANGE:
                    isValidToPropagate = applyGroundTime_EquipmentPrevLeg_Change(flightDelayEvent, lineOfFlying.get(i), isFirstFlight, i, prevLegFlight);
                    break;
                default:
                    break;
            }
            isFirstFlight = false;
            if (isValidToPropagate) {
                updatedLineOfFlying.add(lineOfFlying.get(i));
            } else {
                break;
            }
        }
        return updatedLineOfFlying;
    }

    private static boolean applyGroundTime_EquipmentPrevLeg_Change(FlightDelayEvent delayEvent, LineOfFlying line, boolean isFirstFlight, int lofLevel, PeFlight prevLegFlight) {
        boolean isValidToPropagate = false;
        if (isFirstFlight) {
            long prevLegProjectedArrivalTime = prevLegFlight.getProjectedArrivalTime();
            long mogtInMilliSeconds = Long.parseLong(line.getMogt()) * 60 * 1000;
            Pair<Boolean, Long> status = calculateSubSequentProjectedDepartureTime(line, prevLegProjectedArrivalTime, mogtInMilliSeconds);
            if (status.getLeft()) {
                return calculateProjectedArrivalTime(line, status.getRight());
            }
        } else {
            long lastFlightProjectedArrivalTime = _receivedLineOfFlying.get(lofLevel - 1).getProjectedArrivalTime();
            long mogtInMilliSeconds = Long.parseLong(line.getMogt()) * 60 * 1000;
            Pair<Boolean, Long> status = calculateSubSequentProjectedDepartureTime(line, lastFlightProjectedArrivalTime, mogtInMilliSeconds);
            if (status.getLeft()) {
                return calculateProjectedArrivalTime(line, status.getRight());
            }
        }
        return isValidToPropagate;
    }

    private static boolean applyDepartureDelay(LineOfFlying lineOfFlying, boolean isFirstFlight, int lofLevel) {
        boolean isValidToPropagateFurther = false;
        // Apply departure delay
        if (isFirstFlight) {
            Pair<Boolean, Long> status = calculateProjectedDepartureTime(lineOfFlying, 0);
            if (status.getLeft()) {
                // Intentionally Not Updated the below code to inline variable. It is done to make the code more readable.
                isValidToPropagateFurther = calculateProjectedArrivalTime(lineOfFlying, status.getRight());
            }
        } else {
            long lastFlightProjectedArrivalTime = _receivedLineOfFlying.get(lofLevel - 1).getProjectedArrivalTime();
            long mogtInMilliSeconds = Long.parseLong(lineOfFlying.getMogt()) * 60 * 1000;
            Pair<Boolean, Long> status = calculateSubSequentProjectedDepartureTime(lineOfFlying, lastFlightProjectedArrivalTime, mogtInMilliSeconds);
            if (status.getLeft()) {
                isValidToPropagateFurther = calculateProjectedArrivalTime(lineOfFlying, status.getRight());
            }
        }
        return isValidToPropagateFurther;
    }

    private static boolean applyArrivalDelay(LineOfFlying lineOfFlying, boolean isFirstFlight, int lofLevel) {
        boolean isValidToPropagateFurther = false;
        if (isFirstFlight) {
            isValidToPropagateFurther = calculateProjectedArrivalTime(lineOfFlying, 0);
        } else {
            long lastFlightProjectedArrivalTime = _receivedLineOfFlying.get(lofLevel - 1).getProjectedArrivalTime();
            long mogtInMilliSeconds = Long.parseLong(lineOfFlying.getMogt()) * 60 * 1000;
            Pair<Boolean, Long> status = calculateSubSequentProjectedDepartureTime(lineOfFlying, lastFlightProjectedArrivalTime, mogtInMilliSeconds);
            if (status.getLeft()) {
                isValidToPropagateFurther = calculateProjectedArrivalTime(lineOfFlying, status.getRight());
            }
        }
        return isValidToPropagateFurther;
    }

    /*
     * Below Method is useful for both Cabin Crew  and Cockpit Crew Delay Events
     * */
    private static boolean applyCrewDelay(FlightDelayEvent delayEvent, LineOfFlying line, boolean isFirstFlight, int lofLevel) {
        long receivedProjectedArrivalTimeFromDelayEvent = 0;
        if (isFirstFlight) {
            receivedProjectedArrivalTimeFromDelayEvent = Long.parseLong(delayEvent.getValue());
        } else {
            receivedProjectedArrivalTimeFromDelayEvent = _receivedLineOfFlying.get(lofLevel - 1).getProjectedArrivalTime();
        }
        long mogtInMilliSeconds = Long.parseLong(line.getMogt()) * 60 * 1000;
        Pair<Boolean, Long> status = calculateSubSequentProjectedDepartureTime(line, receivedProjectedArrivalTimeFromDelayEvent, mogtInMilliSeconds);
        if (status.getLeft()) {
            return calculateProjectedArrivalTime(line, status.getRight());
        } else {
            return false;
        }
    }

    private static boolean apply_ActualOff_ActualOut_Change(FlightDelayEvent delayEvent, LineOfFlying line, boolean isFirstFlight, int lofLevel) {
        long actualOffTime = Long.parseLong(delayEvent.getValue());
        boolean isValidToPropagateFurther = false;
        if (isFirstFlight) {
            line.setProjectedDepartureTime(actualOffTime);
            line.setEquipmentResourceReadyTime(actualOffTime);
            isValidToPropagateFurther = calculateProjectedArrivalTime(line, 0);
        } else {
            long lastFlightProjectedArrivalTime = _receivedLineOfFlying.get(lofLevel - 1).getProjectedArrivalTime();
            Pair<Boolean, Long> status = calculateSubSequentProjectedDepartureTime(line, lastFlightProjectedArrivalTime, 0);
            if (status.getLeft()) {
                isValidToPropagateFurther = calculateProjectedArrivalTime(line, status.getRight());
            }
        }
        return isValidToPropagateFurther;
    }

    private static boolean apply_ActualIn_ActualOn_Change(FlightDelayEvent delayEvent, LineOfFlying line, boolean isFirstFlight, int lofLevel) {
        long actual_In_On_Time = Long.parseLong(delayEvent.getValue());
        boolean isValidToPropagateFurther = false;
        if (isFirstFlight) {
            long existingPTA = line.getProjectedArrivalTime();
            long timeDifference = actual_In_On_Time - existingPTA;
            if (timeDifference != 0) {
                isValidToPropagateFurther = true;
                line.setProjectedArrivalTime(actual_In_On_Time);
            }
        } else {
            long lastFlightProjectedArrivalTime = _receivedLineOfFlying.get(lofLevel - 1).getProjectedArrivalTime();
            Pair<Boolean, Long> status = calculateSubSequentProjectedDepartureTime(line, lastFlightProjectedArrivalTime, 0);
            if (status.getLeft()) {
                isValidToPropagateFurther = calculateProjectedArrivalTime(line, status.getRight());
            }
        }
        return isValidToPropagateFurther;
    }

    private static Pair<Boolean, Long> calculateSubSequentProjectedDepartureTime(LineOfFlying line, long lastFlightProjectedArrivalTime, long delayTime) {
        long currFlightProjectedDepartureTime = line.getProjectedDepartureTime();
        long resourceReadyTime = lastFlightProjectedArrivalTime + delayTime;
        long derivedProjectedDepartureTime = setResourceReadyTimesAndDepartureTime(line, resourceReadyTime);
        long timeDifference = derivedProjectedDepartureTime - currFlightProjectedDepartureTime;
        if (timeDifference != 0) {
            line.setProjectedDepartureTime(derivedProjectedDepartureTime);
            return Pair.of(true, timeDifference);
        } else {
            return Pair.of(false, timeDifference);
        }
    }

    private static long setResourceReadyTimesAndDepartureTime(LineOfFlying line, long resourceReadyTime) {
        long derivedProjectedDepartureTime = 0;
        OPSEventType eventType = OPSEventType.valueOf(delayEventType);
        switch (eventType) {
            case CREW_CABIN_DLY_EVENT:
                derivedProjectedDepartureTime = getMaxTimesValue(line.getLatestDepartureTime(), line.getEquipmentResourceReadyTime(), resourceReadyTime, line.getCabinResourceReadyTime());
                line.setCabinResourceReadyTime(resourceReadyTime);
                break;
            case CREW_COCKPIT_DLY_EVENT:
                derivedProjectedDepartureTime = getMaxTimesValue(line.getLatestDepartureTime(), line.getEquipmentResourceReadyTime(), line.getCabinResourceReadyTime(), resourceReadyTime);
                line.setCockpitResourceReadyTime(resourceReadyTime);
                break;
            default:
                derivedProjectedDepartureTime = getMaxTimesValue(line.getLatestDepartureTime(), resourceReadyTime, line.getCabinResourceReadyTime(), line.getCabinResourceReadyTime());
                line.setEquipmentResourceReadyTime(resourceReadyTime);
        }
        return derivedProjectedDepartureTime;
    }

    private static Pair<Boolean, Long> calculateProjectedDepartureTime(LineOfFlying line, int lineOfFlyingLevel) {
        long derivedProjectedDepartureTime = getMaxTimesValue(line.getLatestDepartureTime(), line.getEquipmentResourceReadyTime(),
                line.getCockpitResourceReadyTime(), line.getCabinResourceReadyTime());
        long timeDifference = derivedProjectedDepartureTime - line.getProjectedDepartureTime();
        if (timeDifference != 0) {
            line.setProjectedDepartureTime(derivedProjectedDepartureTime);
            if (lineOfFlyingLevel != 0) {
                line.setEquipmentResourceReadyTime(derivedProjectedDepartureTime);
            }
            return Pair.of(true, timeDifference);
        } else {
            return Pair.of(false, timeDifference);
        }
    }

    private static boolean calculateProjectedArrivalTime(LineOfFlying line, long propagatedDelay) {
        long airTime = Long.parseLong(line.getAirTime()) * 60000;
        long derivedProjectedArrivalTime = line.getProjectedDepartureTime() + airTime;
        long newProjectedArrivalTime = getMaxTimesValue(line.getLatestArrivalTime(), derivedProjectedArrivalTime);
        long timeDifference = newProjectedArrivalTime - line.getProjectedArrivalTime();
        if (timeDifference != 0) {
            line.setProjectedArrivalTime(newProjectedArrivalTime);
            return true;
        } else {
            return false;
        }

    }

}
