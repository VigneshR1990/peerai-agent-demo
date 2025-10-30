package com.aa.opsco.prism.flink.processors;

import com.aa.opsco.prism.flink.datamodel.AtomicEvent;
import com.aa.opsco.prism.flink.datamodel.Crew.CrewAssignment;
import com.aa.opsco.prism.flink.datamodel.delay.DelayAuditLog;
import com.aa.opsco.prism.flink.datamodel.delay.FlightDelayEvent;
import com.aa.opsco.prism.flink.datamodel.PEFlight;
import com.aa.opsco.prism.flink.enums.OPSEventType;
import com.aa.opsco.prism.flink.functions.DelayRichFlatMap;
import com.aa.opsco.prism.flink.functions.DelayRichFlatMap_old;
import com.aa.opsco.prism.flink.helpers.DBHelper;
import com.aa.opsco.prism.flink.helpers.KafkaHelper;
import com.aa.opsco.prism.flink.utils.DelayProcessorMongoCollections;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class DelayEventProcessor implements Serializable {

    private static final String STREAM_TYPE = "DELAY";

    public static List<AtomicEvent> determineAtomicEventForCrewDelay(PEFlight updatedLineOfFlying) throws Exception {
        List<AtomicEvent> atomicEvents = new ArrayList<>();

        if (updatedLineOfFlying.getCrewAssignments() == null || updatedLineOfFlying.getCrewAssignments().isEmpty()) {
            return new ArrayList<>();
        }

        updatedLineOfFlying.getCrewAssignments().forEach(crewAssignment -> {
            if (crewAssignment.getNextOpsFlightKey() != null && crewAssignment.getNextFlightKey() == null) {
                String nextFlightKey = crewAssignment.getNextOpsFlightKey().getAirlineCode() + crewAssignment.getNextOpsFlightKey().getFlightNumber() + crewAssignment.getNextOpsFlightKey().getFlightDate() +
                        crewAssignment.getNextOpsFlightKey().getDepartureStation() + "0" +
                        "0";

                crewAssignment.setNextFlightKey(nextFlightKey);
            }
        });
        // Group by crewType and get distinct nextFlightKey for each group
        Map<String, List<String>> groupedDistinctFlightKeys = updatedLineOfFlying.getCrewAssignments().stream()
                .collect(Collectors.groupingBy(
                        ca -> ca.getOpsCrewDutyKey().getCrewType(),
                        Collectors.mapping(CrewAssignment::getNextFlightKey, Collectors.toSet())
                ))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new ArrayList<>(entry.getValue())
                ));

        groupedDistinctFlightKeys.forEach((crewType, flightKeys) -> {
            OPSEventType crewEventType = Objects.equals(crewType, "F") ? OPSEventType.CREW_CABIN_DLY_EVENT : OPSEventType.CREW_COCKPIT_DLY_EVENT;
            if (flightKeys != null && flightKeys.get(0) == null) {
                return;
            }
            flightKeys.forEach(flightKey -> {
                if (!flightKey.isEmpty()) {
                    //System.out.println("Generating KAFKA Atomic Event for Flight Key: " + flightKey + " Crew Event Type " +crewEventType.getValue());
                    Date sourceTimeStamp = new Date();
                    atomicEvents.add(new AtomicEvent(flightKey, "", sourceTimeStamp, Long.toString(updatedLineOfFlying.getProjectedArrivalTime().getTime()), crewEventType.getValue()));
                }
            });
        });
//        DelayHelper.writeDelayEventsToFile(atomicEvents);
        KafkaHelper.produceToKafkaTopic(atomicEvents);
        return atomicEvents;
    }

    public DataStream<PEFlight> processDelayEvents(DataStream<FlightDelayEvent> flightRawDelayEventStream) {
        if (flightRawDelayEventStream == null) {
            throw new IllegalArgumentException("Delay Event Stream cannot be null");
        }
        System.out.println("Processing Delay Events, Received " + flightRawDelayEventStream);
        DataStream<FlightDelayEvent> keyedFlightDelayEventStream = flightRawDelayEventStream.process(new ProcessFunction<FlightDelayEvent, FlightDelayEvent>() {
            @Override
            public void processElement(FlightDelayEvent flightDelayRawEvent, Context context, Collector<FlightDelayEvent> collector) {
                FlightDelayEvent transformedEvent = transformDelayEventFromRawEvent(flightDelayRawEvent);
                if (transformedEvent != null) {
                    collector.collect(transformedEvent);
                }
            }
        }).name("Transform FlightRawDelayEvent to FlightDelayEvent").keyBy(new KeySelector<FlightDelayEvent, String>() {
            @Override
            public String getKey(FlightDelayEvent value) {
                return value.getFlightKey();
            }
        });

        DelayRichFlatMap delayRichFlatMap = new DelayRichFlatMap();

        // Apply the DelayRichFlatMap function to the input stream using the AsyncDataStream method
        DataStream<Pair<List<PEFlight>, DelayAuditLog>> resultStream = null;
        try {
            resultStream = AsyncDataStream.unorderedWait(
                    keyedFlightDelayEventStream,
                    delayRichFlatMap,
                    60,  // Timeout for the asynchronous operation
                    TimeUnit.SECONDS,
                    3000   // Capacity of the async function's input queue
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Sink Delay Audit Log
        DataStream<DelayAuditLog> delayAuditLogStream = resultStream.map(Pair::getRight).name("DelayAuditLog").returns(TypeInformation.of(DelayAuditLog.class));
        delayAuditLogStream.sinkTo(DBHelper.sinkToMongoDBGenericId(DelayProcessorMongoCollections.DELAY_EVENT_AUDIT_LOGS, DelayAuditLog.class));

        // Return the PEFlight Stream
        return resultStream.flatMap((Pair<List<PEFlight>, DelayAuditLog> peFlights, Collector<PEFlight> collector) -> {
            peFlights.getLeft().forEach(collector::collect);
        }).returns(TypeInformation.of(PEFlight.class)).name("DelayRichFlatMap").keyBy(new KeySelector<PEFlight, String>() {
            @Override
            public String getKey(PEFlight value) {
                return value.getFlightKey();
            }
        });
    }

    public FlightDelayEvent transformDelayEventFromRawEvent(FlightDelayEvent flightDelayRawEvent) {
        log.info("Transforming FlightRawEvent to FlightDelayEvent");
        if (flightDelayRawEvent != null) {
            FlightDelayEvent flightDelayEvent = new FlightDelayEvent();
            flightDelayEvent.setEventType(flightDelayRawEvent.getEventType());
            flightDelayEvent.setValue(flightDelayRawEvent.getValue());
            flightDelayEvent.setFlightKey(flightDelayRawEvent.getFlightKey());
            flightDelayEvent.setStreamType(STREAM_TYPE);
            return flightDelayEvent;
        }
        return null;
    }
}
