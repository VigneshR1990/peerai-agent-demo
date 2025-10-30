package com.aa.opsco.prism.flink.processors;

import com.aa.opsco.prism.flink.datamodel.AtomicEvent;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent.Flight;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent.Flight.Leg;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent.Flight.Times;
import com.aa.opsco.prism.flink.datamodel.FlightTimes;
import com.aa.opsco.prism.flink.enums.FlightHubEventEnum;
import com.aa.opsco.prism.flink.enums.OPSEventType;
import com.aa.opsco.prism.flink.functions.TimesRichFlatMap;
import com.aa.opsco.prism.flink.utils.ProcessorDateTimeUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.joda.time.DateTime;

import java.io.Serializable;
import java.util.*;

import static com.aa.opsco.prism.flink.utils.ReflectionUtils.createAtomicEvent;

@Getter
@Slf4j
public class TimesEventProcessor implements Serializable {
    private static final String STREAM_TYPE = "TIMES";
    protected Date effectiveTimeStamp;

    public void setEffectiveTimeStamp(Date effectiveTimeStamp) {
        this.effectiveTimeStamp = effectiveTimeStamp;
    }

    public List<AtomicEvent> determineTimesAtomicEvents(FlightTimes oldObject, FlightTimes newObject) {
        log.info("in TimesEventProcessor::determineTimesAtomicEvents()");
        List<AtomicEvent> atomicEvents = new ArrayList<>();
        if (oldObject != null && newObject != null) {
            if (oldObject.getSourceTimestamp().before(newObject.getSourceTimestamp()) && !oldObject.equals(newObject)) {
                Optional.ofNullable(newObject.getLatestArrivalTime())
                        .filter(value -> !Objects.equals(newObject.getLatestArrivalTime(), oldObject.getLatestArrivalTime()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ETA.getValue())));
                Optional.ofNullable(newObject.getLatestDepartureTime())
                        .filter(value -> !Objects.equals(newObject.getLatestDepartureTime(), oldObject.getLatestDepartureTime()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ETD.getValue())));
                Optional.ofNullable(newObject.getEstimatedOnTime())
                        .filter(value -> !Objects.equals(newObject.getEstimatedOnTime(), oldObject.getEstimatedOnTime()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.EON.getValue())));
                Optional.ofNullable(newObject.getEstimatedOffTime())
                        .filter(value -> !Objects.equals(newObject.getEstimatedOffTime(), oldObject.getEstimatedOffTime()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ETO.getValue())));
                Optional.ofNullable(newObject.getOffTime())
                        .filter(value -> !Objects.equals(newObject.getOffTime(), oldObject.getOffTime()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ACTUAL_OFF_CHANGE.getValue())));
                Optional.ofNullable(newObject.getOnTime())
                        .filter(value -> !Objects.equals(newObject.getOnTime(), oldObject.getOnTime()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ACTUAL_ON_CHANGE.getValue())));
                Optional.ofNullable(newObject.getOutTime())
                        .filter(value -> !Objects.equals(newObject.getOutTime(), oldObject.getOutTime()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ACTUAL_OUT_CHANGE.getValue())));
                Optional.ofNullable(newObject.getInTime())
                        .filter(value -> !Objects.equals(newObject.getInTime(), oldObject.getInTime()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ACTUAL_IN_CHANGE.getValue())));
                Optional.ofNullable(newObject.getObjectiveGroundTimeMinutes())
                        .filter(value -> !Objects.equals(newObject.getObjectiveGroundTimeMinutes(), oldObject.getObjectiveGroundTimeMinutes()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.GROUND_TIME_CHANGE.getValue())));
                Optional.ofNullable(newObject.getArr_minutesOfDelay())
                        .filter(value -> !Objects.equals(newObject.getArr_minutesOfDelay(), oldObject.getArr_minutesOfDelay())
                                && newObject.getArr_minutesOfDelay() > 0)
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ARR_DLY_EVENT.getValue())));
                Optional.ofNullable(newObject.getDep_minutesOfDelay())
                        .filter(value -> !Objects.equals(newObject.getDep_minutesOfDelay(), oldObject.getDep_minutesOfDelay())
                                && newObject.getDep_minutesOfDelay() > 0)
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.DEP_DLY_EVENT.getValue())));
            }
        } else if (oldObject == null && newObject != null) {
            Optional.ofNullable(newObject.getLatestArrivalTime())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ETA.getValue())));
            Optional.ofNullable(newObject.getLatestDepartureTime())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ETD.getValue())));
            Optional.ofNullable(newObject.getEstimatedOnTime())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.EON.getValue())));
            Optional.ofNullable(newObject.getEstimatedOffTime())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ETO.getValue())));
            Optional.ofNullable(newObject.getOffTime())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ACTUAL_OFF_CHANGE.getValue())));
            Optional.ofNullable(newObject.getOnTime())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ACTUAL_ON_CHANGE.getValue())));
            Optional.ofNullable(newObject.getOutTime())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ACTUAL_OUT_CHANGE.getValue())));
            Optional.ofNullable(newObject.getInTime())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ACTUAL_IN_CHANGE.getValue())));
            Optional.ofNullable(newObject.getObjectiveGroundTimeMinutes())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.GROUND_TIME_CHANGE.getValue())));
            Optional.ofNullable(newObject.getArr_minutesOfDelay())
                    .filter(value -> value > 0)
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ARR_DLY_EVENT.getValue())));
            Optional.ofNullable(newObject.getDep_minutesOfDelay())
                    .filter(value -> value > 0)
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.DEP_DLY_EVENT.getValue())));
        }
        // writeLogFile("atomicEventsList: " + StringUtils.join(atomicEvents, ", "));
        log.info("Exit TimesEventProcessor::determineTimesAtomicEvents()");
        return atomicEvents;
    }

    public DataStream<FlightTimes> processTimesEvents(DataStream<FlightRawEvent> flightRawEventStream) {
        DataStream<FlightTimes> flattenStream = null;
        if (flightRawEventStream != null) {
            DataStream<FlightTimes> processedStream = flightRawEventStream.process(new ProcessFunction<FlightRawEvent, FlightTimes>() {
                @Override
                public void processElement(FlightRawEvent value, ProcessFunction<FlightRawEvent, FlightTimes>.Context ctx, Collector<FlightTimes> collector) {
                    FlightTimes transformedObject = transformTimesFromRawEvent(value);
                    if (transformedObject != null)
                        collector.collect(transformedObject);
                }
            }).name("flight-times-process").keyBy(FlightTimes::getFlightKey);

            flattenStream = processedStream.flatMap(new TimesRichFlatMap()).name("flight-times-flatMap");
        }
        return flattenStream;
    }

    public FlightTimes transformTimesFromRawEvent(FlightRawEvent flightRawEvent) {
        log.info("in TimesEventProcessor::transformOPSTimesEvent()");
        FlightTimes flightTimes = null;
        if (flightRawEvent != null) {
            Flight flight = flightRawEvent.getFlight();
            if (flight != null) {
                String event = flight.getEvent();
                Leg leg = flight.getLeg();
                if (leg != null) {
                    flightTimes = new FlightTimes();
                    flightTimes.setOpsFlightKey(flightRawEvent.getOpsFlightKey());
                    flightTimes.setFlightKey(flightRawEvent.getFlightKey());
                    flightTimes.setRawOpshubEvent(event);
                    if (flight.getDataTime() != null) {
                        setTimeStamps(flight.getDataTime());
                        Date sourceTimeStamp = flight.getDataTime().getSourceTimeStamp();
                        flightTimes.setSourceTimestamp(sourceTimeStamp);
                    }
                    flightTimes.setStreamType(STREAM_TYPE);
                    Times times = leg.getTimes();
                    if (times != null) {
                        //set the standard core attributes for all the event_types
                        setCoreAttributesForFlightTimes(flightTimes, times);

                        if ((FlightHubEventEnum.FLTPLN.getValue().equalsIgnoreCase(event) || FlightHubEventEnum.FPUPDT.getValue().equalsIgnoreCase(event)) &&
                                flight.getInfoIndicators() != null && flight.getInfoIndicators().getFltKeysOwns() != null
                                && Boolean.parseBoolean(flight.getInfoIndicators().getFltKeysOwns())) {
                            return null;
                        }

                        if (FlightHubEventEnum.CANCEL.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.DELETE.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.DESK.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.FUEL.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.GATE.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.LOADPLAN.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.LOCK.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.PSGRLOAD.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.UNLOCK.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.UNKNOWN.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.SIGNFLTPLAN.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.M58.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.CREW.getValue().equalsIgnoreCase(event)) {
                            return flightTimes;
                        }
                        if (FlightHubEventEnum.CREATE.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.CYCLE.getValue().equalsIgnoreCase(event)) {
                            determineFlightTimes(flightTimes, times);
                            // ArrivalStation
                            if (leg.getStations() != null)
                                flightTimes.setArrivalStation(leg.getStations().getArr());

                        }
                        if (FlightHubEventEnum.DB.getValue().equalsIgnoreCase(event)) {
                            DateTime modifiedTimeStamp = new DateTime(this.getEffectiveTimeStamp());
                            setEffectiveTimeStamp(modifiedTimeStamp.minusMinutes(2).toDate());
                            determineFlightTimes(flightTimes, times);
                            if (leg.getStations() != null) {
                                flightTimes.setOrigArrivalStation(leg.getStations().getOriginalArr());
                                flightTimes.setArrivalStation(leg.getStations().getArr());
                            }
                        }
                        if (FlightHubEventEnum.DCSN.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.ETD.getValue().equalsIgnoreCase(event)) {
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);
                            flightTimes.setProjectedDepartureTime_FOS(times.getPTD());
                            flightTimes.setProjectedDepartureTimeFrom_FOS_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);
                        }

                        if (FlightHubEventEnum.DIVERSION.getValue().equalsIgnoreCase(event)) {
                            setPredectiveTaxiTimes(flightTimes, times);
                            if (leg.getStations() != null)
                                flightTimes.setArrivalStation(leg.getStations().getArr());
                        }
                        if (FlightHubEventEnum.EQLINK.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.GROUNDTIME.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.EQSUB.getValue().equalsIgnoreCase(event)) {
                            if (times.getOGT() != null) {
                                flightTimes.setObjectiveGroundTimeMinutes(ProcessorDateTimeUtils.getMinutesAsString(times.getOGT()));
                            }
                            flightTimes.setObjectiveGroundTimeMinutes_timestamp(effectiveTimeStamp);
                        }
                        if (FlightHubEventEnum.EON.getValue().equalsIgnoreCase(event)) {
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);
                            //EstimatedOnTime
                            flightTimes.setEstimatedOnTime(times.getEON());
                            flightTimes.setEstimatedOnTime_timestamp(effectiveTimeStamp);
                        }

                        if (FlightHubEventEnum.ETO.getValue().equalsIgnoreCase(event)) {
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);

                            //EstimatedOffTime
                            flightTimes.setEstimatedOffTime(times.getETO());
                            flightTimes.setEstimatedOffTime_timestamp(effectiveTimeStamp);

                            //EstimatedOnTime
                            flightTimes.setEstimatedOnTime(times.getEON());
                            flightTimes.setEstimatedOnTime_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);
                        }

                        if (FlightHubEventEnum.ETA.getValue().equalsIgnoreCase(event)) {
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);
                        }
                        if (FlightHubEventEnum.IN.getValue().equalsIgnoreCase(event)) {
                            //OnTime
                            flightTimes.setOnTime(times.getActualOn());
                            flightTimes.setOnTime_timestamp(effectiveTimeStamp);
                            //InTime
                            flightTimes.setInTime(times.getActualIn());
                            flightTimes.setInTime_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);

                            if (flightTimes.getInTime() != null && flightTimes.getOnTime() != null) {
                                long latestTaxiInSeconds = ProcessorDateTimeUtils.getDifferenceInSeconds(flightTimes.getOnTime(), flightTimes.getInTime());
                                flightTimes.setLatestTaxiInTimeSeconds((int) latestTaxiInSeconds);
                                flightTimes.setLatestTaxiInTimeSeconds_timestamp(effectiveTimeStamp);
                            }
                        }
                        if (FlightHubEventEnum.OFF.getValue().equalsIgnoreCase(event)) {
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);

                            // OffTime
                            flightTimes.setOffTime(times.getActualOff());
                            flightTimes.setOffTime_timestamp(effectiveTimeStamp);
                            // OutTime
                            flightTimes.setOutTime(times.getActualOut());
                            flightTimes.setOutTime_timestamp(effectiveTimeStamp);
                            // LTD
                            flightTimes.setLatestDepartureTime(times.getActualOut());
                            flightTimes.setLatestDepartureTime_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);

                            if (flightTimes.getOffTime() != null && flightTimes.getOutTime() != null) {
                                long latestTaxiOutSeconds = ProcessorDateTimeUtils.getDifferenceInSeconds(flightTimes.getOutTime(), flightTimes.getOffTime());
                                flightTimes.setLatestTaxiOutTimeSeconds((int) latestTaxiOutSeconds);
                                flightTimes.setLatestTaxiOutTimeSeconds_timestamp(effectiveTimeStamp);
                            }
                        }

                        if (FlightHubEventEnum.ON.getValue().equalsIgnoreCase(event)) {
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);

                            //OnTime
                            flightTimes.setOnTime(times.getActualOn());
                            flightTimes.setOnTime_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);

                            if (flightTimes.getInTime() != null && flightTimes.getOnTime() != null) {
                                long latestTaxiInSeconds = ProcessorDateTimeUtils.getDifferenceInSeconds(flightTimes.getOnTime(), flightTimes.getInTime());
                                flightTimes.setLatestTaxiInTimeSeconds((int) latestTaxiInSeconds);
                                flightTimes.setLatestTaxiInTimeSeconds_timestamp(effectiveTimeStamp);
                            }
                        }

                        if (FlightHubEventEnum.OUT.getValue().equalsIgnoreCase(event)) {

                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);
                            flightTimes.setProjectedDepartureTime_FOS(times.getPTD());
                            flightTimes.setProjectedDepartureTimeFrom_FOS_timestamp(effectiveTimeStamp);
                            //OutTime
                            flightTimes.setOutTime(times.getActualOut());
                            flightTimes.setOutTime_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);

                            if (flightTimes.getOffTime() != null && flightTimes.getOutTime() != null) {
                                long latestTaxiOutSeconds = ProcessorDateTimeUtils.getDifferenceInSeconds(flightTimes.getOutTime(), flightTimes.getOffTime());
                                flightTimes.setLatestTaxiOutTimeSeconds((int) latestTaxiOutSeconds);
                                flightTimes.setLatestTaxiOutTimeSeconds_timestamp(effectiveTimeStamp);
                            }

                        }
                        if (FlightHubEventEnum.OVR.getValue().equalsIgnoreCase(event)) {

                            // ProjectedArrivalTime_FOS
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);
                            if (times.getEON() != null) {
                                flightTimes.setEstimatedOnTime(times.getEON());
                                flightTimes.setEstimatedOnTime_timestamp(effectiveTimeStamp);
                            }
                        }
                        if (FlightHubEventEnum.REINSTATE.getValue().equalsIgnoreCase(event)) {
                            // ObjectiveGroundTimeMinutes
                            flightTimes.setObjectiveGroundTimeMinutes(ProcessorDateTimeUtils.getMinutesAsString(times.getOGT()));
                            flightTimes.setObjectiveGroundTimeMinutes_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);
                            if (leg.getStations() != null)
                                flightTimes.setArrivalStation(leg.getStations().getArr());
                        }

                        if (FlightHubEventEnum.XPE.getValue().equalsIgnoreCase(event)) {
                            // ObjectiveGroundTimeMinutes
                            flightTimes.setObjectiveGroundTimeMinutes(ProcessorDateTimeUtils.getMinutesAsString(times.getOGT()));
                            flightTimes.setObjectiveGroundTimeMinutes_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);
                        }

                        if (FlightHubEventEnum.RETURN.getValue().equalsIgnoreCase(event)) {
                            // PredictedTaxiInTime
                            if (times.getOGT() != null) {
                                flightTimes.setPredictedTaxiInTime(ProcessorDateTimeUtils.getMinutesAsString(times.getOGT()));
                                flightTimes.setPredictedTaxiInTime_timestamp(effectiveTimeStamp);
                            }
                            if (leg.getStations() != null)
                                flightTimes.setArrivalStation(leg.getStations().getArr());
                        }
                        if (FlightHubEventEnum.RTD.getValue().equalsIgnoreCase(event)) {
                            //ActualOut
                            flightTimes.setOutTime(times.getActualOut());
                            flightTimes.setOutTime_timestamp(effectiveTimeStamp);
                            //ActualIn
                            flightTimes.setInTime(times.getActualIn());
                            flightTimes.setInTime_timestamp(effectiveTimeStamp);
                            if (leg.getStations() != null)
                                flightTimes.setArrivalStation(leg.getStations().getArr());
                        }
                        if (FlightHubEventEnum.SKDCHNG.getValue().equalsIgnoreCase(event)) {
                            // set latest times when flight date is after current date
                            if (flightRawEvent.getOpsFlightKey().getFlightDate() != null) {
                                Date flightDate = ProcessorDateTimeUtils.stringToDate(flightRawEvent.getOpsFlightKey().getFlightDate(), ProcessorDateTimeUtils.ddMMMyy);
                                Date currentDate = ProcessorDateTimeUtils.stringToDate(ProcessorDateTimeUtils.dateToString(new Date(), ProcessorDateTimeUtils.ddMMMyy), ProcessorDateTimeUtils.ddMMMyy);
                                if (flightDate.after(currentDate)) {
                                    flightTimes.setLatestDepartureTime(flightTimes.getScheduledDepartureTime());
                                    flightTimes.setLatestDepartureTime_timestamp(flightTimes.getScheduledDepartureTime_timestamp());
                                    flightTimes.setLatestArrivalTime(flightTimes.getScheduledArrivalTime());
                                    flightTimes.setLatestArrivalTime_timestamp(flightTimes.getScheduledArrivalTime_timestamp());
                                }
                            }
                        }
                        if (FlightHubEventEnum.STUB.getValue().equalsIgnoreCase(event) ||
                                FlightHubEventEnum.UNSTUB.getValue().equalsIgnoreCase(event)) {
                            // ProjectedArrivalTime_FOS
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);
                            // ProjectedDepartureTime_FOS
                            flightTimes.setProjectedDepartureTime_FOS(times.getPTD());
                            flightTimes.setProjectedDepartureTimeFrom_FOS_timestamp(effectiveTimeStamp);
                            // ObjectiveGroundTimeMinutes
                            if (times.getOGT() != null) {
                                flightTimes.setObjectiveGroundTimeMinutes(ProcessorDateTimeUtils.getMinutesAsString(times.getOGT()));
                            }
                            flightTimes.setObjectiveGroundTimeMinutes_timestamp(effectiveTimeStamp);
                        }
                        if (FlightHubEventEnum.STUBSTA.getValue().equalsIgnoreCase(event)) {
                            // ProjectedArrivalTime_FOS
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);
                            // ProjectedDepartureTime_FOS
                            flightTimes.setProjectedDepartureTime_FOS(times.getPTD());
                            flightTimes.setProjectedDepartureTimeFrom_FOS_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);
                        }

                        if (FlightHubEventEnum.UNSTUBSTA.getValue().equalsIgnoreCase(event)) {

                            // ProjectedArrivalTime_FOS
                            flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                            flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);
                            // ProjectedDepartureTime_FOS
                            flightTimes.setProjectedDepartureTime_FOS(times.getPTD());
                            flightTimes.setProjectedDepartureTimeFrom_FOS_timestamp(effectiveTimeStamp);
                            // ObjectiveGroundTimeMinutes
                            flightTimes.setObjectiveGroundTimeMinutes(ProcessorDateTimeUtils.getMinutesAsString(times.getOGT()));
                            flightTimes.setObjectiveGroundTimeMinutes_timestamp(effectiveTimeStamp);

                            setPredectiveTaxiTimes(flightTimes, times);
                        }
                        if (FlightHubEventEnum.UNFLY.getValue().equalsIgnoreCase(event)) {
                            setPredectiveTaxiTimes(flightTimes, times);

                            // as we are resetting OOOI times set the time source time stamp
                            flightTimes.setOnTime_timestamp(effectiveTimeStamp);
                            flightTimes.setInTime_timestamp(effectiveTimeStamp);
                            flightTimes.setOffTime_timestamp(effectiveTimeStamp);
                            flightTimes.setOutTime_timestamp(effectiveTimeStamp);
                        }

                        flightTimes.setDepartureStationGMTOffSet(ProcessorDateTimeUtils.getMinutesAsString(times.getDepGMTAdjustment())); //flight repository
                        flightTimes.setArrivalStationGMTOffSet(ProcessorDateTimeUtils.getMinutesAsString(times.getArrGMTAdjustment()));//flight repository
                        if (times.getLTD() != null && times.getSTD() != null) {
                            flightTimes.setDep_minutesOfDelay(ProcessorDateTimeUtils.getDelayInMinutesAsString(times.getLTD(), times.getSTD())); //flight repository
                        }
                        if (times.getLTA() != null && times.getSTA() != null) {
                            flightTimes.setArr_minutesOfDelay(ProcessorDateTimeUtils.getDelayInMinutesAsString(times.getLTA(), times.getSTA())); //flight repository
                        }

                        if (FlightHubEventEnum.DCS.getValue().equalsIgnoreCase(event)) {
                            flightTimes.setDoorClose(times.getDoorClose());
                            flightTimes.setDoorClose_timeStamp(effectiveTimeStamp);
                            flightTimes.setPaxClose(times.getPaxClose());
                        }

                        if (FlightHubEventEnum.CONTINUATION.getValue().equalsIgnoreCase(event)) {

                            flightTimes.setObjectiveGroundTimeMinutes(ProcessorDateTimeUtils.getMinutesAsString(times.getOGT()));
                            flightTimes.setObjectiveGroundTimeMinutes_timestamp(effectiveTimeStamp);
                            if (leg.getStations() != null)
                                flightTimes.setArrivalStation(leg.getStations().getArr());
                            // standard times
                            applyScheduledFlightTimesAsTheDefault(flightTimes);

                            if (times.getPTA() != null) {
                                flightTimes.setProjectedArrivalTime_FOS(times.getPTA());
                                flightTimes.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);
                            }
                            // ProjectedDepartureTime_FOS
                            if (times.getPTD() != null) {
                                flightTimes.setProjectedDepartureTime_FOS(times.getPTD());
                                flightTimes.setProjectedDepartureTimeFrom_FOS_timestamp(effectiveTimeStamp);
                            }
                        }


                    }
                }
            }
        }
        log.info("Exit TimesEventProcessor::transformOPSTimesEvent()");
        return flightTimes;
    }


    private void setPredectiveTaxiTimes(FlightTimes times, Times rawTimes) {
        // PredictedTaxiInTime
        setPredectiveTaxiInTime(times, rawTimes);
        // PredictedTaxiOutTime
        setPredectiveTaxiOutTime(times, rawTimes);
    }

    private void setPredectiveTaxiOutTime(FlightTimes times, Times rawTimes) {
        if (rawTimes.getLatestTaxiOut() != null) {
            times.setPredictedTaxiOutTime(ProcessorDateTimeUtils.getMinutesAsString(rawTimes.getLatestTaxiOut()));
            times.setPredictedTaxiOutTime_timestamp(effectiveTimeStamp);
            times.setLatestTaxiOutTimeSeconds(ProcessorDateTimeUtils.getSecondsAsString(rawTimes.getLatestTaxiOut()));
            times.setLatestTaxiOutTimeSeconds_timestamp(effectiveTimeStamp);
        } else {
            // use scheduled Taxi out when latest time is not available
            if (rawTimes.getSkdTaxiOut() != null) {
                times.setPredictedTaxiOutTime(ProcessorDateTimeUtils.getMinutesAsString(rawTimes.getSkdTaxiOut()));
                times.setPredictedTaxiOutTime_timestamp(effectiveTimeStamp);
                times.setLatestTaxiOutTimeSeconds(ProcessorDateTimeUtils.getSecondsAsString(rawTimes.getSkdTaxiOut()));
                times.setLatestTaxiOutTimeSeconds_timestamp(effectiveTimeStamp);
            }
        }
    }

    private void setPredectiveTaxiInTime(FlightTimes times, Times rawTimes) {
        if (rawTimes.getLatestTaxiIn() != null) {
            times.setPredictedTaxiInTime(ProcessorDateTimeUtils.getMinutesAsString(rawTimes.getLatestTaxiIn()));
            times.setPredictedTaxiInTime_timestamp(effectiveTimeStamp);
            times.setLatestTaxiInTimeSeconds(ProcessorDateTimeUtils.getSecondsAsString(rawTimes.getLatestTaxiIn()));
            times.setLatestTaxiInTimeSeconds_timestamp(effectiveTimeStamp);
        } else {
            // use scheduled Taxi in when latest time is not available
            if (rawTimes.getSkdTaxiIn() != null) {
                times.setPredictedTaxiInTime(ProcessorDateTimeUtils.getMinutesAsString(rawTimes.getSkdTaxiIn()));
                times.setPredictedTaxiInTime_timestamp(effectiveTimeStamp);
                times.setLatestTaxiInTimeSeconds(ProcessorDateTimeUtils.getSecondsAsString(rawTimes.getSkdTaxiIn()));
                times.setLatestTaxiInTimeSeconds_timestamp(effectiveTimeStamp);
            }
        }
    }

    void applyScheduledFlightTimesAsTheDefault(FlightTimes flightTimes) {
        flightTimes.setProjectedDepartureTime_FOS(flightTimes.getScheduledDepartureTime());
        flightTimes.setProjectedDepartureTimeFrom_FOS_timestamp(flightTimes.getScheduledDepartureTime_timestamp());
        flightTimes.setProjectedArrivalTime_FOS(flightTimes.getScheduledArrivalTime());
        flightTimes.setProjectedArrivalTime_FOS_timestamp(flightTimes.getScheduledArrivalTime_timestamp());
        flightTimes.setLatestDepartureTime(flightTimes.getScheduledDepartureTime());
        flightTimes.setLatestDepartureTime_timestamp(flightTimes.getScheduledDepartureTime_timestamp());
        flightTimes.setLatestArrivalTime(flightTimes.getScheduledArrivalTime());
        flightTimes.setLatestArrivalTime_timestamp(flightTimes.getScheduledArrivalTime_timestamp());
    }

    private void setCoreAttributesForFlightTimes(FlightTimes flightTimes, Times times) {

        if (times.getSTD() != null) {
            flightTimes.setScheduledDepartureTime(times.getSTD());
            flightTimes.setScheduledDepartureTime_timestamp(effectiveTimeStamp);
        }
        // ScheduledArrivalTime
        if (times.getSTA() != null) {
            flightTimes.setScheduledArrivalTime(times.getSTA());
            flightTimes.setScheduledArrivalTime_timestamp(effectiveTimeStamp);
        }
        // LatestDepartureTime
        if (times.getLTD() != null) {
            flightTimes.setLatestDepartureTime(times.getLTD());
            flightTimes.setLatestDepartureTime_timestamp(effectiveTimeStamp);
        }
        // LatestArrivalTime
        if (times.getLTA() != null) {
            flightTimes.setLatestArrivalTime(times.getLTA());
            flightTimes.setLatestArrivalTime_timestamp(effectiveTimeStamp);
        }
    }

    private void determineFlightTimes(FlightTimes times, Times rawTimes) {

        // ProjectedArrivalTime_FOS
        if (rawTimes.getPTA() != null) {
            times.setProjectedArrivalTime_FOS(rawTimes.getPTA());
            times.setProjectedArrivalTime_FOS_timestamp(effectiveTimeStamp);
        }
        // ProjectedDepartureTime_FOS
        if (rawTimes.getPTD() != null) {
            times.setProjectedDepartureTime_FOS(rawTimes.getPTD());
            times.setProjectedDepartureTimeFrom_FOS_timestamp(effectiveTimeStamp);
        }
        // EstimatedOnTime
        if (rawTimes.getEON() != null) {
            times.setEstimatedOnTime(rawTimes.getEON());
            times.setEstimatedOnTime_timestamp(effectiveTimeStamp);
        }
        // EstimatedOffTime
        if (rawTimes.getETO() != null) {
            times.setEstimatedOffTime(rawTimes.getETO());
            times.setEstimatedOffTime_timestamp(effectiveTimeStamp);
        }
        // OnTime
        if (rawTimes.getActualOn() != null) {
            times.setOnTime(rawTimes.getActualOn());
            times.setOnTime_timestamp(effectiveTimeStamp);
        }
        // OffTime
        if (rawTimes.getActualOff() != null) {
            times.setOffTime(rawTimes.getActualOff());
            times.setOffTime_timestamp(effectiveTimeStamp);
        }
        // OutTime
        if (rawTimes.getActualOut() != null) {
            times.setOutTime(rawTimes.getActualOut());
            times.setOutTime_timestamp(effectiveTimeStamp);
        }
        // InTime
        if (rawTimes.getActualIn() != null) {
            times.setInTime(rawTimes.getActualIn());
            times.setInTime_timestamp(effectiveTimeStamp);
        }
        // ObjectiveGroundTimeMinutes
        if (rawTimes.getOGT() != null) {
            times.setObjectiveGroundTimeMinutes(ProcessorDateTimeUtils.getMinutesAsString(rawTimes.getOGT()));
            times.setObjectiveGroundTimeMinutes_timestamp(effectiveTimeStamp);
        }
        // PredictedTaxiInTime
        if (rawTimes.getLatestTaxiIn() != null) {
            times.setPredictedTaxiInTime(ProcessorDateTimeUtils.getMinutesAsString(rawTimes.getLatestTaxiIn()));
            times.setPredictedTaxiInTime_timestamp(effectiveTimeStamp);
        } else {
            // use scheduled Taxi in when latest time is not available
            if (rawTimes.getSkdTaxiIn() != null) {
                times.setPredictedTaxiInTime(ProcessorDateTimeUtils.getMinutesAsString(rawTimes.getSkdTaxiIn()));
                times.setPredictedTaxiInTime_timestamp(effectiveTimeStamp);
            }
        }
        // PredictedTaxiOutTime
        if (rawTimes.getLatestTaxiOut() != null) {
            times.setPredictedTaxiOutTime(ProcessorDateTimeUtils.getMinutesAsString(rawTimes.getLatestTaxiOut()));
            times.setPredictedTaxiOutTime_timestamp(effectiveTimeStamp);
        } else {
            // use scheduled Taxi out when latest time is not available
            if (rawTimes.getSkdTaxiOut() != null) {
                times.setPredictedTaxiOutTime(ProcessorDateTimeUtils.getMinutesAsString(rawTimes.getSkdTaxiOut()));
                times.setPredictedTaxiOutTime_timestamp(effectiveTimeStamp);
            }
        }
        times.setIsOAFlight(false);
    }

    private void setTimeStamps(Date sourceTime, Date flightHubTime) {
        // Set effective time stamp depending on whether source exists or not
        // if both don't exist, create our own time stamp for when we
        // received the event
        // use flightHubTime as effective time if source ts doesn't exist
        // use source time as effectiveTime if exists
        setEffectiveTimeStamp(Objects.requireNonNullElseGet(sourceTime, () -> Objects.requireNonNullElseGet(flightHubTime, Date::new)));
    }

    private void setTimeStamps(Flight.DataTime dataTime) {
        Date sourceTime = null;
        Date fltHTime = null;
        if (dataTime != null) {
            if (dataTime.getSourceTimeStamp() != null) {
                sourceTime = dataTime.getSourceTimeStamp();
            }
            if (dataTime.getFltHubTimeStamp() != null) {
                fltHTime = dataTime.getSourceTimeStamp();
            }
        }
        setTimeStamps(sourceTime, fltHTime);
    }

}