package com.aa.opsco.prism.flink.processors;

import com.aa.opsco.prism.flink.datamodel.AtomicEvent;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent.Flight;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent.Flight.Leg;
import com.aa.opsco.prism.flink.datamodel.FlightStatus;
import com.aa.opsco.prism.flink.enums.DepartureStatus;
import com.aa.opsco.prism.flink.enums.FlightHubEventEnum;
import com.aa.opsco.prism.flink.enums.LegStatus;
import com.aa.opsco.prism.flink.enums.OPSEventType;
import com.aa.opsco.prism.flink.functions.StatusRichFlatMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.joda.time.DateTime;

import java.io.Serializable;
import java.util.*;

import static com.aa.opsco.prism.flink.utils.ReflectionUtils.createAtomicEvent;


@Slf4j
public class StatusEventProcessor implements Serializable {
    private static final String STREAM_TYPE = "STATUS";


    public List<AtomicEvent> determineStatusAtomicEvents(FlightStatus oldObject, FlightStatus newObject) {
        log.info("in StatusEventProcessor::determineStatusAtomicEvents()");
        List<AtomicEvent> atomicEvents = new ArrayList<>();
        if (oldObject == null && newObject == null) {
            return atomicEvents;
        }
        if (oldObject != null && newObject != null) {
            if (oldObject.getSourceTimestamp().before(newObject.getSourceTimestamp()) && !oldObject.equals(newObject)) {
                Optional.ofNullable(newObject.getDepartureStatus())
                        .filter(value -> !Objects.equals(newObject.getDepartureStatus(), oldObject.getDepartureStatus()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.DEPARTURE_STATUS_CHANGE.getValue())));
                Optional.ofNullable(newObject.getArrivalStatus())
                        .filter(value -> !Objects.equals(newObject.getArrivalStatus(), oldObject.getArrivalStatus()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ARRIVAL_STATUS_CHANGE.getValue())));
                Optional.ofNullable(newObject.getLegStatus())
                        .filter(value -> !Objects.equals(newObject.getLegStatus(), oldObject.getLegStatus()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, newObject.getLegStatus(), OPSEventType.LEG_STATUS_CHANGE.getValue())));
                Optional.ofNullable(newObject.getFlightReleaseStatus())
                        .filter(value -> !Objects.equals(newObject.getFlightReleaseStatus(), oldObject.getFlightReleaseStatus()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, newObject.getFlightReleaseStatus(), OPSEventType.FLT_RELEASE_STATUS_CHANGE.getValue())));
                if (Objects.equals(newObject.getLegStatus(), LegStatus.CANCELLED) || Objects.equals(newObject.getLegStatus(), LegStatus.CANCELLED_VIA_XL)) {
                    // don't create cancel event if flight was deleted (dep status cancelled)
                    if (!Objects.equals(newObject.getDepartureStatus(), DepartureStatus.CANCELLED)) {
                        atomicEvents.add(createAtomicEvent(newObject, newObject.getLegStatus(), OPSEventType.CANCEL.getValue()));
                    }
                }
            }
        } else if (oldObject == null && newObject != null) {
            Optional.ofNullable(newObject.getDepartureStatus()).ifPresent(value ->
                    atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.DEPARTURE_STATUS_CHANGE.getValue())));
            Optional.ofNullable(newObject.getArrivalStatus()).ifPresent(value ->
                    atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.ARRIVAL_STATUS_CHANGE.getValue())));
            Optional.ofNullable(newObject.getLegStatus()).ifPresent(value ->
                    atomicEvents.add(createAtomicEvent(newObject, newObject.getLegStatus(), OPSEventType.LEG_STATUS_CHANGE.getValue())));
            Optional.ofNullable(newObject.getFlightReleaseStatus()).ifPresent(value ->
                    atomicEvents.add(createAtomicEvent(newObject, newObject.getFlightReleaseStatus(), OPSEventType.FLT_RELEASE_STATUS_CHANGE.getValue())));
            if (Objects.equals(newObject.getLegStatus(), LegStatus.CANCELLED) || Objects.equals(newObject.getLegStatus(), LegStatus.CANCELLED_VIA_XL)) {
                // don't create cancel event if flight was deleted (dep status cancelled)
                if (!Objects.equals(newObject.getDepartureStatus(), DepartureStatus.CANCELLED)) {
                    atomicEvents.add(createAtomicEvent(newObject, newObject.getLegStatus(), OPSEventType.CANCEL.getValue()));
                }
            }
        }
        // writeLogFile("atomicEventsList: " + StringUtils.join(atomicEvents, ", "));
        log.info("Exit StatusEventProcessor::determineStatusAtomicEvents()");
        return atomicEvents;
    }

    public DataStream<FlightStatus> processStatusEvents(DataStream<FlightRawEvent> flightRawEventStream) {
        DataStream<FlightStatus> flattenStream = null;
        if (flightRawEventStream != null) {
            DataStream<FlightStatus> processedStream = flightRawEventStream.process(new ProcessFunction<FlightRawEvent, FlightStatus>() {
                @Override
                public void processElement(FlightRawEvent value, ProcessFunction<FlightRawEvent, FlightStatus>.Context ctx, Collector<FlightStatus> collector) {
                    FlightStatus transformedObject = transformStatusFromRawEvent(value);
                    if (transformedObject != null)
                        collector.collect(transformedObject);
                }
            }).name("flight-status-process").keyBy(FlightStatus::getFlightKey);

            flattenStream = processedStream.flatMap(new StatusRichFlatMap()).name("flight-status-flatMap");
        }
        return flattenStream;

    }

    public FlightStatus transformStatusFromRawEvent(FlightRawEvent flightRawEvent) {
        log.info("in StatusEventProcessor::transformOPSStatusEvent()");
        FlightStatus flightStatus = null;
        if (flightRawEvent != null) {
            Flight flight = flightRawEvent.getFlight();
            if (flight != null) {
                flightStatus = new FlightStatus();
                Date effectiveTimeStamp = setTimeStamps(flight.getDataTime());
                flightStatus.setEffectiveTimeStamp(effectiveTimeStamp);
                flightStatus.setOpsFlightKey(flightRawEvent.getOpsFlightKey());
                flightStatus.setFlightKey(flightRawEvent.getFlightKey());
                flightStatus.setRawOpshubEvent(flight.getEvent());
                flightStatus.setSourceTimestamp(flight.getDataTime().getSourceTimeStamp());
                flightStatus.setStreamType(STREAM_TYPE);
                Leg leg = flight.getLeg();
                if (leg != null) {
                    String event = flight.getEvent();
                    Flight.Status status = leg.getStatus();

                    if (FlightHubEventEnum.DCS.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.DESK.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.EQLINK.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.EQSUB.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.FUEL.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.GATE.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.GROUNDTIME.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.LOCK.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.PSGRLOAD.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.SKDCHNG.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.UNLOCK.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.SIGNFLTPLAN.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.CREW.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.UNKNOWN.getValue().equalsIgnoreCase(event)) {
                        return flightStatus;
                    }

                    if (FlightHubEventEnum.CANCEL.getValue().equalsIgnoreCase(event)) {
                        Flight.Reason reason = leg.getReason();
                        if (leg.getReason() != null) {
                            flightStatus.setCancelledReason(reason.getInformation());
                            flightStatus.setCancelledCode(reason.getCode());
                            flightStatus.setReasonCode(reason.getCode());
                            flightStatus.setReasonDescription(reason.getInformation());
                        }
                        if (status != null) {
                            flightStatus.setLegStatus(status.getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setDepartureStatus(status.getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                        }

                    }

                    if (FlightHubEventEnum.CONTINUATION.getValue().equalsIgnoreCase(event)) {
                        if (status != null) {
                            flightStatus.setLegStatus(status.getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                            // DepartureStatus
                            if (status.getDep() != null) {
                                flightStatus.setDepartureStatus(status.getDep());
                                flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                            }
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                            if (leg.getStations() != null)
                                flightStatus.setArrivalStation(leg.getStations().getArr());
                        }
                    }
                    if (FlightHubEventEnum.CREATE.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.CYCLE.getValue().equalsIgnoreCase(event)) {
                        setBasicAttributesForStatus(flight, flightStatus);
                        if (leg.getStations() != null)
                            flightStatus.setArrivalStation(leg.getStations().getArr());
                    }
                    if (FlightHubEventEnum.DB.getValue().equalsIgnoreCase(event)) {
                        DateTime modifiedTimeStamp = new DateTime(effectiveTimeStamp);
                        flightStatus.setEffectiveTimeStamp(modifiedTimeStamp.minusMinutes(2).toDate());
                        setBasicAttributesForStatus(flight, flightStatus);
                        if (leg.getReason() != null) {
                            flightStatus.setReasonCode(leg.getReason().getCode());
                            flightStatus.setReasonDescription(leg.getReason().getInformation());
                        }
                        if (leg.getStations() != null)
                            flightStatus.setArrivalStation(leg.getStations().getArr());
                    }
                    if (FlightHubEventEnum.DCSN.getValue().equalsIgnoreCase(event)) {
                        if (status != null) {
                            flightStatus.setLegStatus(status.getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setDepartureStatus(status.getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                        }

                        if (leg.getType() != null && leg.getType().getFltRelStatus() != null) {
                            flightStatus.setFlightReleaseStatus(leg.getType().getFltRelStatus());
                            flightStatus.setFlightReleaseStatus_timestamp(effectiveTimeStamp);
                        }

                        if (leg.getReason() != null && leg.getReason().getInformation() != null) {
                            flightStatus.setDelayReasonCodes(leg.getReason().getInformation());
                            flightStatus.setDelayReasonCodes_timestamp(effectiveTimeStamp);
                        }
                    }

                    if (FlightHubEventEnum.DELETE.getValue().equalsIgnoreCase(event)) {
                        if (status != null) {
                            flightStatus.setLegStatus(status.getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setDepartureStatus(status.getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                        }

                        if (leg.getReason() != null) {
                            flightStatus.setReasonCode(leg.getReason().getCode());
                            flightStatus.setReasonDescription(leg.getReason().getInformation());
                        }

                    }
                    if (FlightHubEventEnum.DIVERSION.getValue().equalsIgnoreCase(event)) {
                        if (status != null) {
                            flightStatus.setLegStatus(status.getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setDepartureStatus(status.getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                        }

                        if (leg.getReason() != null) {
                            flightStatus.setReasonCode(leg.getReason().getCode());
                            flightStatus.setReasonDescription(leg.getReason().getInformation());
                        }

                        if (leg.getStations() != null)
                            flightStatus.setArrivalStation(leg.getStations().getArr());
                    }
                    if (FlightHubEventEnum.EON.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.ETA.getValue().equalsIgnoreCase(event)) {
                        if (status != null) {
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                        }

                    }

                    if (FlightHubEventEnum.ETD.getValue().equalsIgnoreCase(event)) {
                        if (status != null) {
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setDepartureStatus(status.getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                        }

                        if (leg.getType() != null && leg.getType().getStubFltNum() != null) {
                            flightStatus.setStubbedFlightNumber(leg.getType().getStubFltNum());
                            flightStatus.setStubbedFlightNumber_timestamp(effectiveTimeStamp);
                        }
                        if (leg.getType() != null && leg.getType().getFltRelStatus() != null) {
                            flightStatus.setFlightReleaseStatus(leg.getType().getFltRelStatus());
                            flightStatus.setFlightReleaseStatus_timestamp(effectiveTimeStamp);
                        }
                        if (leg.getReason() != null && leg.getReason().getInformation() != null) {
                            flightStatus.setDelayReasonCodes(leg.getReason().getInformation());
                            flightStatus.setDelayReasonCodes_timestamp(effectiveTimeStamp);
                        }
                    }
                    if (FlightHubEventEnum.ETO.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.OFF.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.OUT.getValue().equalsIgnoreCase(event)) {
                        if (status != null) {
                            flightStatus.setDepartureStatus(status.getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                        }

                        if (leg.getType() != null && leg.getType().getFltRelStatus() != null) {
                            flightStatus.setFlightReleaseStatus(leg.getType().getFltRelStatus());
                            flightStatus.setFlightReleaseStatus_timestamp(effectiveTimeStamp);
                        }
                    }
                    if (FlightHubEventEnum.FLTPLN.getValue().equalsIgnoreCase(event)) {
                        //Ignore the event if the fight is FlightKeys Owned flight
                        if (flight.getInfoIndicators() != null && flight.getInfoIndicators().getFltKeysOwns() != null
                                && Boolean.parseBoolean(flight.getInfoIndicators().getFltKeysOwns())) {
                            return flightStatus;
                        } else {
                            if (leg.getType() != null && leg.getType().getFltRelStatus() != null) {
                                flightStatus.setFlightReleaseStatus(leg.getType().getFltRelStatus());
                                flightStatus.setFlightReleaseStatus_timestamp(effectiveTimeStamp);
                            }
                            // Flight plan release count
                            if (leg.getFlightPlanStatus() != null) {
                                flightStatus.setFlightPlanReleaseCount(leg.getFlightPlanStatus().getFlightPlanReleaseCount());

                            }
                            if (status != null) {
                                // ArrivalStatus
                                if (status.getArr() != null) {
                                    flightStatus.setArrivalStatus(status.getArr());
                                    flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                                }
                                // DepartureStatus
                                if (status.getDep() != null) {
                                    flightStatus.setDepartureStatus(status.getDep());
                                    flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                                }
                            }

                        }

                    }

                    if (FlightHubEventEnum.FPUPDT.getValue().equalsIgnoreCase(event)) {
                        //Ignore the event if the fight is FlightKeys Owned flight
                        if (flight.getInfoIndicators() != null && flight.getInfoIndicators().getFltKeysOwns() != null
                                && Boolean.parseBoolean(flight.getInfoIndicators().getFltKeysOwns())) {
                            return flightStatus;
                        } else {
                            if (status != null) {
                                // ArrivalStatus
                                if (status.getArr() != null) {
                                    flightStatus.setArrivalStatus(status.getArr());
                                    flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                                }
                                // DepartureStatus
                                if (status.getDep() != null) {
                                    flightStatus.setDepartureStatus(status.getDep());
                                    flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                                }
                                // LegStatus
                                if (status.getLeg() != null) {
                                    flightStatus.setLegStatus(status.getLeg());
                                    flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                                }
                            }

                        }
                        //Flight Release Status
                        if (leg.getType() != null && leg.getType().getFltRelStatus() != null) {
                            flightStatus.setFlightReleaseStatus(leg.getType().getFltRelStatus());
                            flightStatus.setFlightReleaseStatus_timestamp(effectiveTimeStamp);
                        }
                        //Flight release version count
                        if (leg.getFlightPlanStatus() != null) {
                            flightStatus.setFlightPlanReleaseCount(leg.getFlightPlanStatus().getFlightPlanReleaseCount());
                        }


                    }
                    if (FlightHubEventEnum.IN.getValue().equalsIgnoreCase(event)) {
                        if (status != null && status.getArr() != null) {
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                        }
                    }
                    if (FlightHubEventEnum.LOADPLAN.getValue().equalsIgnoreCase(event)) {
                        if (leg.getType() != null) {
                            if (leg.getType().getFltRelStatus() != null) {
                                flightStatus.setFlightReleaseStatus(leg.getType().getFltRelStatus());
                                flightStatus.setFlightReleaseStatus_timestamp(effectiveTimeStamp);
                            }
                            if (leg.getType().getStubFltNum() != null) {
                                flightStatus.setStubbedFlightNumber(leg.getType().getStubFltNum());
                                flightStatus.setStubbedFlightNumber_timestamp(effectiveTimeStamp);
                            }
                        }
                    }
                    if (FlightHubEventEnum.ON.getValue().equalsIgnoreCase(event)) {
                        if (status != null && status.getArr() != null) {
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                        }
                    }

                    if (FlightHubEventEnum.OVR.getValue().equalsIgnoreCase(event)) {
                        if (status != null && status.getArr() != null) {
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                        }
                        flightStatus.setDepartureStatus(DepartureStatus.OFF.getValue());
                        flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                    }
                    if (FlightHubEventEnum.REINSTATE.getValue().equalsIgnoreCase(event)) {
                        if (status != null && status.getLeg() != null) {
                            flightStatus.setLegStatus(status.getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setDepartureStatus(status.getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                        }
                        if (leg.getType() != null)
                            flightStatus.setStubbedFlightNumber(leg.getType().getStubFltNum());
                        flightStatus.setStubbedFlightNumber_timestamp(effectiveTimeStamp);
                    }
                    if (FlightHubEventEnum.RETURN.getValue().equalsIgnoreCase(event)) {
                        if (status != null && status.getLeg() != null) {
                            flightStatus.setLegStatus(status.getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                        }

                        if (leg.getReason() != null) {
                            flightStatus.setReasonCode(leg.getReason().getCode());
                            flightStatus.setReasonDescription(leg.getReason().getInformation());
                        }
                        if (leg.getStations() != null)
                            flightStatus.setArrivalStation(leg.getStations().getArr());
                    }
                    if (FlightHubEventEnum.RTD.getValue().equalsIgnoreCase(event)) {
                        if (status != null && status.getLeg() != null) {
                            flightStatus.setLegStatus(status.getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                            flightStatus.setDepartureStatus(status.getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                        }
                        if (leg.getStations() != null)
                            flightStatus.setArrivalStation(leg.getStations().getArr());

                    }
                    if (FlightHubEventEnum.STUB.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.STUBSTA.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.UNSTUB.getValue().equalsIgnoreCase(event) ||
                            FlightHubEventEnum.UNSTUBSTA.getValue().equalsIgnoreCase(event)) {
                        if (status != null) {
                            // ArrivalStatus
                            if (status.getArr() != null)
                                flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                            // DepartureStatus
                            flightStatus.setDepartureStatus(status.getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                            // LegStatus
                            flightStatus.setLegStatus(status.getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                        }

                        // Type
                        // FlightReleaseStatus
                        if (leg.getType() != null) {
                            if (leg.getType().getFltRelStatus() != null)
                                flightStatus.setFlightReleaseStatus(leg.getType().getFltRelStatus());
                            flightStatus.setFlightReleaseStatus_timestamp(effectiveTimeStamp);
                            // StubbedFlightNumber
                            flightStatus.setStubbedFlightNumber(leg.getType().getStubFltNum());
                            flightStatus.setStubbedFlightNumber_timestamp(effectiveTimeStamp);
                        }

                    }

                    if (FlightHubEventEnum.UNFLY.getValue().equalsIgnoreCase(event)) {
                        if (status != null && status.getArr() != null) {
                            // ArrivalStatus
                            flightStatus.setArrivalStatus(status.getArr());
                            flightStatus.setArrivalStatus_timestamp(effectiveTimeStamp);
                        }
                        if (leg.getStatus() != null) {
                            // DepartureStatus
                            flightStatus.setDepartureStatus(leg.getStatus().getDep());
                            flightStatus.setDepartureStatus_timestamp(effectiveTimeStamp);
                            // LegStatus
                            flightStatus.setLegStatus(leg.getStatus().getLeg());
                            flightStatus.setLegStatus_timestamp(effectiveTimeStamp);
                        }

                    }
                    if (FlightHubEventEnum.XPE.getValue().equalsIgnoreCase(event)) {
                        // FlightReleaseStatus
                        if (leg.getType() != null && leg.getType().getFltRelStatus() != null)
                            flightStatus.setFlightReleaseStatus(leg.getType().getFltRelStatus());
                        flightStatus.setFlightReleaseStatus_timestamp(effectiveTimeStamp);
                    }
                    if (FlightHubEventEnum.M58.getValue().equalsIgnoreCase(event)) {
                        if (leg.getReason() != null && leg.getReason().getInformation() != null) {
                            flightStatus.setReasonDescription(leg.getReason().getInformation());
                        }
                    }
                }
            }
        }
        log.info("Exit StatusEventProcessor::transformOPSStatusEvent()");
        return flightStatus;
    }

    private void setBasicAttributesForStatus(Flight rawFlight, FlightStatus status) {

        Flight.Status rawStatus = rawFlight.getLeg().getStatus();
        // Status
        if (rawStatus != null) {
            // ArrivalStatus
            if (rawStatus.getArr() != null) {
                status.setArrivalStatus(rawFlight.getLeg().getStatus().getArr());
                status.setArrivalStatus_timestamp(status.getEffectiveTimeStamp());
            }
            // DepartureStatus
            if (rawStatus.getDep() != null) {
                status.setDepartureStatus(rawFlight.getLeg().getStatus().getDep());
                status.setDepartureStatus_timestamp(status.getEffectiveTimeStamp());
            }
            // LegStatus
            if (rawStatus.getLeg() != null) {
                status.setLegStatus(rawFlight.getLeg().getStatus().getLeg());
                status.setLegStatus_timestamp(status.getEffectiveTimeStamp());
            }
        }
        // Type
        if (rawFlight.getLeg().getType() != null) {
            // FlightReleaseStatus
            if (rawFlight.getLeg().getType().getFltRelStatus() != null) {
                status.setFlightReleaseStatus(rawFlight.getLeg().getType().getFltRelStatus());
                status.setFlightReleaseStatus_timestamp(status.getEffectiveTimeStamp());
            }
            // StubbedFlightNumber
            if (rawFlight.getLeg().getType().getStubFltNum() != null) {
                status.setStubbedFlightNumber(rawFlight.getLeg().getType().getStubFltNum());
                status.setStubbedFlightNumber_timestamp(status.getEffectiveTimeStamp());
            }
        }

        //delayCodes
        if (rawFlight.getLeg() != null && rawFlight.getLeg().getReason() != null && rawFlight.getLeg().getReason().getInformation() != null) {
            status.setDelayReasonCodes(rawFlight.getLeg().getReason().getInformation());
        }

    }

    private Date setTimeStamps(Date sourceTime, Date flightHubTime) {
        // Set effective time stamp depending on whether source exists or not
        // if both don't exist, create our own time stamp for when we
        // received the event
        // use flightHubTime as effective time if source ts doesn't exist
        // use source time as effectiveTime if exists
        return Objects.requireNonNullElseGet(sourceTime, () -> Objects.requireNonNullElseGet(flightHubTime, Date::new));
    }

    private Date setTimeStamps(Flight.DataTime dataTime) {
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
        return setTimeStamps(sourceTime, fltHTime);
    }
}