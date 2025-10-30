package com.aa.opsco.prism.flink.processors;

import com.aa.opsco.prism.flink.datamodel.AtomicEvent;
import com.aa.opsco.prism.flink.datamodel.FlightEquipment;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent.Flight;
import com.aa.opsco.prism.flink.datamodel.FlightRawEvent.Flight.Leg;
import com.aa.opsco.prism.flink.datamodel.FlightTimes;
import com.aa.opsco.prism.flink.enums.EquipmentEvents;
import com.aa.opsco.prism.flink.enums.OPSEventType;
import com.aa.opsco.prism.flink.functions.EquipmentRichFlatMap;
import com.aa.opsco.prism.flink.helpers.FlightHelper;
import com.aa.opsco.prism.flink.utils.FlightProcessorMongoCollections;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.connector.mongodb.source.MongoSource;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamUtils;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import javax.xml.datatype.XMLGregorianCalendar;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.aa.opsco.prism.flink.datamodel.FlightRawEvent.OpsFlightKey.createFlightKeyFromOpsFlightKey;
import static com.aa.opsco.prism.flink.utils.ProcessorDateTimeUtils.ddMMMyy;
import static com.aa.opsco.prism.flink.utils.ProcessorDateTimeUtils.formatXMLCalendar;
import static com.aa.opsco.prism.flink.utils.ProcessorDateTimeUtils.xmlGregorianCalendarToDateTime;
import static com.aa.opsco.prism.flink.utils.ProcessorDateTimeUtils.xmlGregorianCalendarfromString;
import static com.aa.opsco.prism.flink.utils.ReflectionUtils.createAtomicEvent;


@Slf4j
public class EquipmentEventProcessor implements Serializable {
    private final String STREAM_TYPE = "EQUIPMENT";
    private final FlightHelper flightHelper = new FlightHelper();
    ObjectMapper mapper = new ObjectMapper();
    private Date sourceTimeStamp;

    // Process Equipment Events
    public static List<AtomicEvent> determineEquipmentAtomicEvents(FlightEquipment oldObject, FlightEquipment newObject) {
        log.info("in EquipmentEventProcessor::determineEquipmentAtomicEvents()");
        List<AtomicEvent> atomicEvents = new ArrayList<>();
        if (oldObject != null && newObject != null) {
            if (oldObject.getSourceTimestamp().before(newObject.getSourceTimestamp()) && !oldObject.equals(newObject)) {
                Optional.ofNullable(newObject.getTailNumber())
                        .filter(value -> !Objects.equals(newObject.getTailNumber(), oldObject.getTailNumber()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.TAIL.getValue())));
                Optional.ofNullable(newObject.getPrevLeg())
                        .filter(value -> !Objects.equals(newObject.getPrevLeg(), oldObject.getPrevLeg()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.EQUIP_PREVLEG_CHANGE.getValue())));
                Optional.ofNullable(newObject.getNextLeg())
                        .filter(value -> !Objects.equals(newObject.getNextLeg(), oldObject.getNextLeg()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.EQUIP_NEXTLEG_CHANGE.getValue())));
                Optional.ofNullable(newObject.getAssignedEquipmentType())
                        .filter(value -> !Objects.equals(newObject.getAssignedEquipmentType(), oldObject.getAssignedEquipmentType()))
                        .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.EQUIP_TYPE_CHANGE.getValue())));
            }
        } else if (oldObject == null && newObject != null) {
            Optional.ofNullable(newObject.getTailNumber())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.TAIL.getValue())));
            Optional.ofNullable(newObject.getPrevLeg())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.EQUIP_PREVLEG_CHANGE.getValue())));
            Optional.ofNullable(newObject.getNextLeg())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.EQUIP_NEXTLEG_CHANGE.getValue())));
            Optional.ofNullable(newObject.getAssignedEquipmentType())
                    .ifPresent(value -> atomicEvents.add(createAtomicEvent(newObject, value, OPSEventType.EQUIP_TYPE_CHANGE.getValue())));
        }
        log.info("Exit EquipmentEventProcessor::determineEquipmentAtomicEvents()");
        return atomicEvents;
    }

    private static void defineNextLeg(FlightEquipment flightEquipment, Flight flight) {
        // construct LKAFlightKey for nextLeg
        DateTimeFormatter fmt = DateTimeFormat.forPattern("ddMMMyy");
        if (flight.getLeg().getLinkage() != null && flight.getLeg().getLinkage().getNextLegOrgDate() != null) {
            XMLGregorianCalendar xmlGregorianCalendar = xmlGregorianCalendarfromString(flight.getLeg().getLinkage().getNextLegOrgDate());
            DateTime nextDepDate = new DateTime(xmlGregorianCalendarToDateTime(xmlGregorianCalendar));
            FlightRawEvent.OpsFlightKey nextLeg = new FlightRawEvent.OpsFlightKey(flight.getKey().getAirlineCode().getIATA(), flight.getLeg().getLinkage().getNextLegFltNum(),
                    nextDepDate.toString(fmt).toUpperCase(), flight.getLeg().getStations().getArr());
            nextLeg.setDupDepCode(flight.getLeg().getLinkage().getNextLegFltDupCode());
            flightEquipment.setNextLeg(nextLeg);
            flightEquipment.setNextLegFlightKey(createFlightKeyFromOpsFlightKey(nextLeg));
        }
    }

    private static boolean flightsFilterCriteria(String json, FlightEquipment flightEquipment) {
        boolean matched = false;
        BsonDocument doc = BsonDocument.parse(json);
        BsonValue opsFlightKey = doc.get("opsFlightKey");
        String arrivalStation = doc.getString("arrivalStation").getValue();
        if (flightEquipment != null) {
            if (opsFlightKey != null && flightEquipment.getOpsFlightKey() != null) {
                String airlineCode = opsFlightKey.asDocument().getString("airlineCode").getValue();
                String flightDate = opsFlightKey.asDocument().getString("flightDate").getValue();
                String flightNumber = opsFlightKey.asDocument().getString("flightNumber").getValue();
                String snapshotId = opsFlightKey.asDocument().getString("snapshotId").getValue();
                matched = airlineCode.equalsIgnoreCase(flightEquipment.getOpsFlightKey().getAirlineCode())
                        && flightDate.equalsIgnoreCase(flightEquipment.getPreviousFlightDate_ddMMMyy())
                        && flightNumber.equalsIgnoreCase(flightEquipment.getPreviousFlightNumber())
                        && arrivalStation.equalsIgnoreCase(flightEquipment.getOpsFlightKey().getDepartureStation())
                        && snapshotId.equalsIgnoreCase(flightEquipment.getOpsFlightKey().getSnapshotId());
            }
        }
        return matched;
    }

    private static boolean equipmentsFilterCriteria(String json, HashSet<FlightRawEvent.OpsFlightKey> possiblePreviousLegs) {
        boolean res = false;
        BsonDocument doc = BsonDocument.parse(json);
        BsonValue opsFlightKey = doc.get("opsFlightKey");
        if (opsFlightKey != null && possiblePreviousLegs != null) {
            FlightRawEvent.OpsFlightKey opsFlightKeyObject = new FlightRawEvent.OpsFlightKey(
                    opsFlightKey.asDocument().getString("airlineCode").getValue(),
                    opsFlightKey.asDocument().getString("flightNumber").getValue(),
                    opsFlightKey.asDocument().getString("flightDate").getValue(),
                    opsFlightKey.asDocument().getString("departureStation").getValue(),
                    opsFlightKey.asDocument().getString("dupDepCode").getValue(),
                    opsFlightKey.asDocument().getString("snapshotId").getValue()
            );
            res = possiblePreviousLegs.stream().anyMatch(key -> key.equals(opsFlightKeyObject));
        }
        return res;
    }

    private static boolean timesFilterCriteria(String json, String equipmentFlightKey) {
        BsonDocument doc = BsonDocument.parse(json);
        String flightKey = doc.getString("flightKey").getValue();
        if (flightKey != null && equipmentFlightKey != null) {
            return equipmentFlightKey.equalsIgnoreCase(flightKey);
        }
        return false;
    }

    private static boolean flightTimesFilterCriteria(String json, FlightTimes currentTimes) {
        boolean matched = false;
        BsonDocument doc = BsonDocument.parse(json);
        long scheduledDepartureTime = doc.getInt64("scheduledDepartureTime").getValue();
        String flightKey = doc.getString("flightKey").getValue();
        if (currentTimes != null) {
            matched = !flightKey.equalsIgnoreCase(currentTimes.getFlightKey())
                    && scheduledDepartureTime <= currentTimes.getScheduledDepartureTime().getTime();
        }
        return matched;
    }

    private static boolean subQueryFlightsFilterCriteria(String json, FlightEquipment flightEquipment) {
        boolean matched = false;
        BsonDocument doc = BsonDocument.parse(json);
        BsonValue opsFlightKey = doc.get("opsFlightKey");
        String arrivalStation = doc.getString("arrivalStation").getValue();
        if (flightEquipment != null) {
            if (opsFlightKey != null && flightEquipment.getOpsFlightKey() != null) {
                String airlineCode = opsFlightKey.asDocument().getString("airlineCode").getValue();
                String flightDate = opsFlightKey.asDocument().getString("flightDate").getValue();
                String flightNumber = opsFlightKey.asDocument().getString("flightNumber").getValue();
                matched = airlineCode.equalsIgnoreCase(flightEquipment.getOpsFlightKey().getAirlineCode())
                        && flightDate.equalsIgnoreCase(flightEquipment.getPreviousFlightDate_ddMMMyy())
                        && flightNumber.equalsIgnoreCase(flightEquipment.getPreviousFlightNumber())
                        && arrivalStation.equalsIgnoreCase(flightEquipment.getOpsFlightKey().getDepartureStation());
            }
        }
        return matched;
    }

    private List<com.aa.opsco.prism.flink.datamodel.Flight> readFlightFilterCriteria1(FlightEquipment flightEquipment) {
        MongoClient mClient = flightHelper.getMongoClient();
        //Get Database
        MongoDatabase database = mClient.getDatabase("prism-flight");
        //Get Collection
        MongoCollection<Document> collection = database.getCollection("Flight");

        Bson filter = Filters.and(
                Filters.eq("opsFlightKey.airlineCode", flightEquipment.getOpsFlightKey().getAirlineCode()),
                Filters.eq("opsFlightKey.flightDate", flightEquipment.getPreviousFlightDate_ddMMMyy()),
                Filters.eq("opsFlightKey.flightNumber", flightEquipment.getPreviousFlightNumber()),
                Filters.eq("opsFlightKey.snapshotId", flightEquipment.getOpsFlightKey().getSnapshotId())
        );
        List<Document> documents = collection.find(filter).into(new ArrayList<>());

        return documents.stream().map(document -> mapper.convertValue(document, com.aa.opsco.prism.flink.datamodel.Flight.class)).collect(Collectors.toList());
    }

    public DataStream<FlightEquipment> processEquipmentEvents(DataStream<FlightRawEvent> flightRawEventStream) {
        DataStream<FlightEquipment> flattenStream = null;
        if (flightRawEventStream != null) {
            DataStream<FlightEquipment> processedStream = flightRawEventStream.process(new ProcessFunction<FlightRawEvent, FlightEquipment>() {
                @Override
                public void processElement(FlightRawEvent value, ProcessFunction<FlightRawEvent, FlightEquipment>.Context ctx, Collector<FlightEquipment> collector) throws Exception {
                    FlightEquipment transformedObject = transformFlightEquipment(value);
                    if (transformedObject != null) {
//                        setPreviousLeg(transformedObject);
                        collector.collect(transformedObject);
                    }
                }
            }).name("flight-equipment-process").keyBy(FlightEquipment::getFlightKey);
            flattenStream = processedStream.flatMap(new EquipmentRichFlatMap()).name("flight-equipment-flatMap");
        }
        return flattenStream;
    }

    public FlightEquipment transformFlightEquipment(FlightRawEvent flightRawEvent) {
        log.info("in EquipmentEventProcessor::transformFlightEquipment()");
        FlightEquipment flightEquipment = null;
        if (flightRawEvent != null) {
            String eventType = flightRawEvent.getFlight().getEvent();
            if (EquipmentEvents.findByEventName(eventType) == null) {
                return flightEquipment;
            }
            Flight flight = flightRawEvent.getFlight();
            //Set attributes for all events
            flightEquipment = new FlightEquipment();
            if (flight != null) {
                flightEquipment.setRawOpshubEvent(flight.getEvent());

                flightEquipment.setStreamType(STREAM_TYPE);
                flightEquipment.setOpsFlightKey(flightRawEvent.getOpsFlightKey());
                flightEquipment.setFlightKey(flightRawEvent.getFlightKey());
                if (flight.getDataTime() != null) {
                    sourceTimeStamp = flight.getDataTime().getSourceTimeStamp();
                    flightEquipment.setSourceTimestamp(sourceTimeStamp);
                }

                Leg leg = flight.getLeg();
                if (leg != null) {
                    if (leg.getEquipment() != null && leg.getEquipment().getAssignedTail() != null) {
                        flightEquipment.setTailNumber(leg.getEquipment().getAssignedTail());
                        flightEquipment.setTailNumber_timestamp(sourceTimeStamp);
                    }

                    //Set attributes for specific event types
                    switch (EquipmentEvents.findByEventName(eventType)) {
                        case CREATE:
                        case CYCLE:
                        case DB:
                            determineEquipment(leg, flightEquipment, flight);
                            break;
                        case CONTINUATION:
                            setEquipAssignedEquipmentType(flightEquipment, leg);
                            flightEquipment.setArrivalStation(leg.getStations().getArr());
                            if (leg.getEquipment() != null && leg.getEquipment().getSasEquipCode() != null) {
                                flightEquipment.setAssignedEquipmentCode(leg.getEquipment().getSasEquipCode());
                                flightEquipment.setAssignedEquipmentCode_timestamp(sourceTimeStamp);
                            }
                            setEquipNextLeg(flightEquipment, flight);
                            setEquipPrevLeg(flightEquipment, leg);
                            break;
                        case DIVERSION:
                            if (leg.getStations() != null) {
                                flightEquipment.setArrivalStation(leg.getStations().getArr());
                            }
                            setEquipNextLeg(flightEquipment, flight);
                            break;
                        case EQLINK:
                            setEquipAssignedEquipmentType(flightEquipment, leg);
                            setEquipRegistrationNumber(flightEquipment, leg);
                            setEquipNextLeg(flightEquipment, flight);
                            setEquipPrevLeg(flightEquipment, leg);
                            break;
                        case EQSUB:
                            setEquipAssignedEquipmentType(flightEquipment, leg);
                            setEquipRegistrationNumber(flightEquipment, leg);
                            setEquipNextLeg(flightEquipment, flight);
                            setEquipPrevLeg(flightEquipment, leg);
                            //setting anti-ice field
                            if (leg.getType() != null && leg.getType().getAntiIceInd() != null) {
                                flightEquipment.setAntiIce(Boolean.valueOf(leg.getType().getAntiIceInd()));
                            }
                            break;
                        case FLTPLN:
                            if (leg.getEquipment() != null) {
                                flightEquipment.setTailNumberONFlightPlan(leg.getEquipment().getAssignedTail());
                            }
                            break;
                        case RETURN:
                            setEquipNextLeg(flightEquipment, flight);
                            break;
                        case REINSTATE:
                            setEquipAssignedEquipmentType(flightEquipment, leg);
                            //TBD
                            //flightEquipment.setScheduledEquipmentCode(leg.getEquipment().getSkdSASEquip());
                            flightEquipment.setScheduledEquipmentCode_timestamp(sourceTimeStamp);
                            setEquipNextLeg(flightEquipment, flight);
                            setEquipPrevLeg(flightEquipment, leg);
                            break;
                        case RTD:
                            setEquipAssignedEquipmentType(flightEquipment, leg);
                            flightEquipment.setScheduledEquipmentCode(leg.getEquipment().getSkdEquipType());
                            flightEquipment.setScheduledEquipmentCode_timestamp(sourceTimeStamp);
                            setEquipNextLeg(flightEquipment, flight);
                            //Previous Flight
                            if (leg.getLinkage().getPriorLegOrgDate() != null) {
                                setEquipPreviousFlight(flightEquipment, leg);
                            }
                            break;
                        case XPE:
                        case STUB:
                        case UNSTUB:
                        case STUBSTA:
                        case UNSTUBSTA:
                            setEquipAssignedEquipmentType(flightEquipment, leg);
                            setEquipPrevLeg(flightEquipment, leg);
                            break;
                        case LOADPLAN:
                            //setting anti-ice field
                            if (leg.getPerformance() != null) {
                                flightEquipment.setAntiIce(leg.getPerformance().isAntiIce());
                            }
                            //Update the tailNumber from LOADPLAN
                            if (leg.getEquipment() != null && leg.getEquipment().getAssignedTail() != null) {
                                flightEquipment.setTailNumberONFlightPlan(leg.getEquipment().getAssignedTail());
                            }
                            break;
                    }
                }
            }
        }

        log.info("Exit EquipmentEventProcessor::transformFlightEquipment()");
        return flightEquipment;
    }

    void setPreviousLeg(FlightEquipment equipmentToWrite) throws Exception {
        if (equipmentToWrite.getPreviousFlightDate_ddMMMyy() == null || equipmentToWrite
                .getPreviousFlightDepartureAttemptCount() == null
                || equipmentToWrite.getPreviousFlightNumber() == null) {
            equipmentToWrite.setPrevLeg(null);
        } else {
            List<com.aa.opsco.prism.flink.datamodel.Flight> flights = getFlightFromMongoDB(equipmentToWrite);
            log.info("flights form mongo==>" + flights);
            // System.out.println("flights=>" + flights);
            if (flights == null || flights.isEmpty()) {
                equipmentToWrite.setPrevLeg(null);
            } else if (flights.size() == 1) {
                equipmentToWrite.setPrevLeg(flights.get(0).getOpsFlightKey());
                equipmentToWrite.setPrevLeg_timeStamp(equipmentToWrite.getPreviousFlightNumber_timestamp());
            } else {
                //More than one option for previous leg, first let's ensure both have the same tail.
                HashSet<FlightRawEvent.OpsFlightKey> possiblePreviousLegs = new HashSet<>();
                for (com.aa.opsco.prism.flink.datamodel.Flight f : flights) {
                    possiblePreviousLegs.add(f.getOpsFlightKey());
                }
                List<FlightEquipment> _equips = getAllEquipmentsFromMongoDB(possiblePreviousLegs);
                if (!_equips.isEmpty()) {
                    for (FlightEquipment e : _equips) {
                        if (e != null && e.getTailNumber() != null && equipmentToWrite.getTailNumber() != null) {
                            if (!e.getTailNumber().equalsIgnoreCase(equipmentToWrite.getTailNumber())) {
                                possiblePreviousLegs.remove(e.getFlightKey());
                            }
                        }
                    }
                }

                // If after checking tails, there is only one possible previous leg, use that one
                if (possiblePreviousLegs.size() == 1) {
                    equipmentToWrite.setPrevLeg(possiblePreviousLegs.iterator().next());
                }

                // Still more than one after tail comparison, we must compare times.

                else {

                    FlightTimes currentTime = getTimesFromMongoDB(equipmentToWrite.getFlightKey());
                    if (currentTime != null && currentTime.getScheduledArrivalTime() != null) {

                        List<FlightTimes> flightTimesList = getAllFlightTimesFromMongoDB(currentTime);

                        List<com.aa.opsco.prism.flink.datamodel.Flight> flightList = subQueryFlightFromMongoDB(equipmentToWrite);

                        List<FlightTimes> flightTimes = flightTimesList.stream()
                                .filter(times -> flightList.stream()
                                        .anyMatch(flight -> flight.getFlightKey().equals(times.getFlightKey())))
                                .collect(Collectors.toList());
                        if (flightTimes != null) {
                            if (flightTimes.isEmpty()) {
                                equipmentToWrite.setPrevLeg(null);
                            } else {
                                // handling the case circular kind of events
                                if (equipmentToWrite.getFlightKey().equals(flightTimes.get(0).getFlightKey())) {
                                    equipmentToWrite.setPrevLeg(flightTimes.get(0).getOpsFlightKey());
                                } else {
                                    equipmentToWrite.setPrevLeg(flightTimes.get(0).getOpsFlightKey());
                                }
                                equipmentToWrite.setPrevLeg_timeStamp(
                                        equipmentToWrite.getPreviousFlightNumber_timestamp());
                            }
                        } else {
                            System.out.println(
                                    "ERROR - should not be duplicate flights arriving in "
                                            + equipmentToWrite.getOpsFlightKey().getDepartureStation());
                            equipmentToWrite.setPrevLeg(null);
                        }

                    }
                }

            }
        }
        //  System.out.println("equipmentToWrite:" + equipmentToWrite.getFlightKey() + ":" + equipmentToWrite.getPrevLeg());
    }

    private void determineEquipment(Leg leg, FlightEquipment flightEquipment, Flight flight) {
        flightEquipment.setArrivalStation(leg.getStations().getArr());
        // Equipment
        if (leg.getEquipment() != null) {
            // TailNumber
            if (leg.getEquipment().getAssignedTail() != null) {
                flightEquipment.setTailNumber((leg.getEquipment().getAssignedTail()));
                flightEquipment.setTailNumber_timestamp(sourceTimeStamp);
            } else {
                flightEquipment.setIsCreatedWithoutTail(true);
            }
            // AssignedEquipmentCode
            if (leg.getEquipment().getSasEquipCode() != null) {
                flightEquipment.setAssignedEquipmentCode(leg.getEquipment().getSasEquipCode());
                flightEquipment.setAssignedEquipmentCode_timestamp(sourceTimeStamp);
            }
            // ScheduledEquipmentCode
            if (leg.getEquipment().getSkdEquipType() != null) {
                flightEquipment.setScheduledEquipmentCode(leg.getEquipment().getSkdEquipType());
                flightEquipment.setScheduledEquipmentCode_timestamp(sourceTimeStamp);
            }
            // Assigned flightEquipment Type
            if (leg.getEquipment().getAssignedEquipType() != null) {
                flightEquipment.setAssignedEquipmentType(leg.getEquipment().getAssignedEquipType());
                flightEquipment.setAssignedEquipmentType_timestamp(sourceTimeStamp);
            }
            setEquipRegistrationNumber(flightEquipment, leg);
        }
        // Linkage
        if (leg.getLinkage() != null) {
            // NextLeg
            if (leg.getLinkage().getNextLegFltNum().equals("0000")) {
                flightEquipment.setClearNextLinkage(true);
                flightEquipment.setClearNextLinkage_timestamp(sourceTimeStamp);
            }
            if (leg.getLinkage().getNextLegFltNum() != null && !(leg.getLinkage().getNextLegFltNum().equals("0000"))) {
                defineNextLeg(flightEquipment, flight);
            }
            flightEquipment.setNextLeg_timeStamp(sourceTimeStamp);

            // Error getting prevLeg LKAFlightKey because I do not know the
            // departureStation of the previous leg, and FH does not give us
            // this
            if (leg.getLinkage().getPriorLegFltNum().equals("0000")) {
                flightEquipment.setClearPriorLinkage(true);
                flightEquipment.setClearPriorLinkage_timestamp(sourceTimeStamp);
            }

            // PreviousFlightDate_ddMMMyy
            if (leg.getLinkage().getPriorLegOrgDate() != null) {
                flightEquipment.setPreviousFlightDate_ddMMMyy(formatXMLCalendar(leg.getLinkage().getPriorLegOrgDate(), ddMMMyy));
                flightEquipment.setPreviousFlightDate_ddMMMyy_timestamp(sourceTimeStamp);
            }
            // PreviousFlightNumber
            if (leg.getLinkage().getPriorLegFltNum() != null && !(leg.getLinkage().getPriorLegFltNum().equals("0000"))) {
                flightEquipment.setPreviousFlightNumber(leg.getLinkage().getPriorLegFltNum());
                flightEquipment.setPreviousFlightNumber_timestamp(sourceTimeStamp);
            }
            // PreviousFlightDepartureAttemptCount
            if (leg.getLinkage().getPriorLegFltDupCode() != null) {
                flightEquipment.setPreviousFlightDepartureAttemptCount(Integer.parseInt(leg.getLinkage().getPriorLegFltDupCode()));
                flightEquipment.setPreviousFlightDepartureAttemptCount_timestamp(sourceTimeStamp);
            }
            flightEquipment.setPrevLeg_timeStamp(sourceTimeStamp);
            // NextFlightDate_ddMMMyy
            if (leg.getLinkage().getNextLegOrgDate() != null) {
                flightEquipment.setNextFlightDate_ddMMMyy(formatXMLCalendar(leg.getLinkage().getNextLegOrgDate(), ddMMMyy));
                flightEquipment.setNextFlightDate_ddMMMyy_timestamp(sourceTimeStamp);
            }
            // NextFlightNumber
            if (leg.getLinkage().getNextLegFltNum() != null) {
                flightEquipment.setNextFlightNumber(leg.getLinkage().getNextLegFltNum());
                flightEquipment.setNextFlightNumber_timestamp(sourceTimeStamp);
            }
            // NextFlightDepartureAttemptCount
            if (leg.getLinkage().getNextLegFltDupCode() != null) {
                flightEquipment.setNextFlightDepartureAttemptCount(Integer.parseInt(leg.getLinkage().getNextLegFltDupCode()));
                flightEquipment.setNextFlightDepartureAttemptCount_timestamp(sourceTimeStamp);
            }
        }
        //Anti-Ice
        if (leg.getType() != null && leg.getType().getAntiIceInd() != null) {
            flightEquipment.setAntiIce(Boolean.valueOf(leg.getType().getAntiIceInd()));
        }
    }

    private void setEquipAssignedEquipmentType(FlightEquipment flightEquipment, Leg leg) {
        if (leg.getEquipment() != null) {
            flightEquipment.setAssignedEquipmentType(leg.getEquipment().getAssignedEquipType());
            flightEquipment.setAssignedEquipmentType_timestamp(sourceTimeStamp);
        }
    }

    private void setEquipNextLeg(FlightEquipment flightEquipment, Flight flight) {
        //First determine if next flight number is '0000' this is the equivalent of no next leg
        Leg leg = flight.getLeg();
        if (leg != null && leg.getLinkage() != null && leg.getLinkage().getNextLegFltNum().equals("0000")) {
            flightEquipment.setClearNextLinkage(true);
            flightEquipment.setClearNextLinkage_timestamp(sourceTimeStamp);
        } else {
            defineNextLeg(flightEquipment, flight);
            flightEquipment.setNextLeg_timeStamp(sourceTimeStamp);
            flightEquipment.setNextFlightDepartureAttemptCount_timestamp(sourceTimeStamp);
            flightEquipment.setNextFlightDate_ddMMMyy_timestamp(sourceTimeStamp);
            flightEquipment.setNextFlightNumber_timestamp(sourceTimeStamp);
            if (leg != null && leg.getLinkage() != null) {
                if (leg.getLinkage().getNextLegOrgDate() != null)
                    flightEquipment.setNextFlightDate_ddMMMyy(formatXMLCalendar(leg.getLinkage().getNextLegOrgDate(), ddMMMyy));
                if (leg.getLinkage().getNextLegFltDupCode() != null)
                    flightEquipment.setNextFlightDepartureAttemptCount(Integer.parseInt(leg.getLinkage().getNextLegFltDupCode()));
                flightEquipment.setNextFlightNumber(leg.getLinkage().getNextLegFltNum());
            }
        }
        // setting the nextLeg timeStamp always
        flightEquipment.setNextLeg_timeStamp(sourceTimeStamp);
    }

    private void setEquipPrevLeg(FlightEquipment flightEquipment, Leg leg) {
        //First determine if prior flight number is '0000' this is the equivalent of no next leg
        if (leg.getLinkage() != null && leg.getLinkage().getPriorLegFltNum() != null && leg.getLinkage().getPriorLegFltNum().equals("0000")) {
            // clear any linkage associated w/ this flight
            flightEquipment.setClearPriorLinkage(true);
            flightEquipment.setClearPriorLinkage_timestamp(sourceTimeStamp);
        } else {
            setEquipPreviousFlight(flightEquipment, leg);
        }
        flightEquipment.setPrevLeg_timeStamp(sourceTimeStamp);
    }

    private void setEquipRegistrationNumber(FlightEquipment flightEquipment, Leg leg) {
        // Aircraft Registration Number
        if (leg.getEquipment() != null && leg.getEquipment().getAircraftRegistrationNbr() != null) {
            flightEquipment.setRegistrationNumber(leg.getEquipment().getAircraftRegistrationNbr().trim());
            flightEquipment.setRegistrationNumber_timeStamp(sourceTimeStamp);
        }
    }

    private void setEquipPreviousFlight(FlightEquipment flightEquipment, Leg leg) {
        // Prev Flight
        if (leg.getLinkage() != null) {
            if (leg.getLinkage().getPriorLegOrgDate() != null)
                flightEquipment.setPreviousFlightDate_ddMMMyy(formatXMLCalendar(leg.getLinkage().getPriorLegOrgDate(), ddMMMyy));
            flightEquipment.setPreviousFlightDate_ddMMMyy_timestamp(sourceTimeStamp);

            if (leg.getLinkage().getPriorLegFltNum() != null)
                flightEquipment.setPreviousFlightNumber(leg.getLinkage().getPriorLegFltNum());
            flightEquipment.setPreviousFlightNumber_timestamp(sourceTimeStamp);

            if (leg.getLinkage().getPriorLegFltDupCode() != null)
                flightEquipment.setPreviousFlightDepartureAttemptCount(Integer.parseInt(leg.getLinkage().getPriorLegFltDupCode()));
            flightEquipment.setPreviousFlightDepartureAttemptCount_timestamp(sourceTimeStamp);
        }
    }

    public List<com.aa.opsco.prism.flink.datamodel.Flight> getFlightFromMongoDB(FlightEquipment flightEquipment) throws Exception {
        List<com.aa.opsco.prism.flink.datamodel.Flight> flightList = new ArrayList<>();
        if (flightEquipment != null) {
            MongoSource<String> source = flightHelper.createMongoSource(FlightProcessorMongoCollections.FLIGHT_COLLECTION_NAME);

            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);
            env.setParallelism(1);
            env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
            env.getConfig().setRestartStrategy(RestartStrategies.fixedDelayRestart(3, org.apache.flink.api.common.time.Time.seconds(10)));
            //env.getConfig().setRestartStrategy(RestartStrategies.noRestart());
            //env.enableCheckpointing(5000); // setting the checkpoint

            DataStream<String> flightDataStream = env.fromSource(source, WatermarkStrategy.noWatermarks(), "MongoDB-flight-source")
                    .filter(json -> flightsFilterCriteria(json, flightEquipment));
            DataStream<com.aa.opsco.prism.flink.datamodel.Flight> flightStream = flightDataStream.map((MapFunction<String, com.aa.opsco.prism.flink.datamodel.Flight>) value -> mapper.readValue(value, com.aa.opsco.prism.flink.datamodel.Flight.class))
                    .name("getFlightFromMongoDB-filtered-map").keyBy(com.aa.opsco.prism.flink.datamodel.Flight::getFlightKey);

            env.execute("Read from MongoDB-getFlightFromMongoDB");

            Iterator<com.aa.opsco.prism.flink.datamodel.Flight> myOutput = DataStreamUtils.collect(flightStream);
            while (myOutput.hasNext())
                flightList.add(myOutput.next());
        }
        return flightList;
    }

    List<FlightEquipment> getAllEquipmentsFromMongoDB(HashSet<FlightRawEvent.OpsFlightKey> possiblePreviousLegs) throws Exception {
        List<FlightEquipment> _equips = new ArrayList<>();
        if (possiblePreviousLegs != null && !possiblePreviousLegs.isEmpty()) {
            MongoSource<String> source = flightHelper.createMongoSource(FlightProcessorMongoCollections.EQUIPMENT_COLLECTION_NAME);

            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);
            env.setParallelism(1);
            env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
            env.getConfig().setRestartStrategy(RestartStrategies.fixedDelayRestart(3, org.apache.flink.api.common.time.Time.seconds(10)));

            KeyedStream<FlightEquipment, String> equipmentDataStream = env.fromSource(source, WatermarkStrategy.noWatermarks(), "MongoDB-FlightEquipment-source")
                    .filter(json -> equipmentsFilterCriteria(json, possiblePreviousLegs))
                    .map((MapFunction<String, FlightEquipment>) value -> mapper.readValue(value, FlightEquipment.class))
                    .name("getAllEquipmentsFromMongoDB-filtered-map").keyBy(FlightEquipment::getFlightKey);
            env.execute("Read from MongoDB-getAllEquipmentsFromMongoDB");

            Iterator<FlightEquipment> myOutput = DataStreamUtils.collect(equipmentDataStream);
            while (myOutput.hasNext())
                _equips.add(myOutput.next());
        }
        return _equips;
    }

    public FlightTimes getTimesFromMongoDB(String flightKey) throws Exception {
        FlightTimes times = new FlightTimes();
        if (flightKey != null) {
            MongoSource<String> source = flightHelper.createMongoSource(FlightProcessorMongoCollections.TIMES_COLLECTION_NAME);

            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);
            env.setParallelism(1);
            env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
            env.getConfig().setRestartStrategy(RestartStrategies.fixedDelayRestart(3, org.apache.flink.api.common.time.Time.seconds(10)));

            KeyedStream<FlightTimes, String> timesDataStream = env.fromSource(source, WatermarkStrategy.noWatermarks(), "MongoDB-times-source")
                    .filter(json -> timesFilterCriteria(json, flightKey))
                    .map((MapFunction<String, FlightTimes>) value -> mapper.readValue(value, FlightTimes.class))
                    .name("getTimesFromMongoDB-filtered-map").keyBy(FlightTimes::getFlightKey);

            env.execute("Read from MongoDB-getTimesFromMongoDB");

            Iterator<FlightTimes> myOutput = DataStreamUtils.collect(timesDataStream);
            while (myOutput.hasNext())
                times = myOutput.next();
        }
        return times;
    }

    public List<FlightTimes> getAllFlightTimesFromMongoDB(FlightTimes currentTime) throws Exception {
        List<FlightTimes> flightTimesList = new ArrayList<>();
        if (currentTime != null) {
            MongoSource<String> source = flightHelper.createMongoSource(FlightProcessorMongoCollections.TIMES_COLLECTION_NAME);
            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);
            env.setParallelism(1);
            env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
            env.getConfig().setRestartStrategy(RestartStrategies.fixedDelayRestart(3, org.apache.flink.api.common.time.Time.seconds(10)));

            DataStream<String> flightTimesDataStream = env.fromSource(source, WatermarkStrategy.noWatermarks(), "MongoDB-flightTimes-source")
                    .filter(json -> flightTimesFilterCriteria(json, currentTime));

            DataStream<FlightTimes> flightTimesStream = flightTimesDataStream.map((MapFunction<String, FlightTimes>) value -> mapper.readValue(value, FlightTimes.class)).name("getAllFlightTimesFromMongoDB-filtered-map").keyBy(FlightTimes::getFlightKey);

            env.execute("Read from MongoDB-getAllFlightTimesFromMongoDB");

            Iterator<FlightTimes> myOutput = DataStreamUtils.collect(flightTimesStream);
            while (myOutput.hasNext())
                flightTimesList.add(myOutput.next());
        }
        return flightTimesList;
    }

    public List<com.aa.opsco.prism.flink.datamodel.Flight> subQueryFlightFromMongoDB(FlightEquipment flightEquipment) throws Exception {
        List<com.aa.opsco.prism.flink.datamodel.Flight> flightList = new ArrayList<>();
        if (flightEquipment != null) {
            MongoSource<String> source = flightHelper.createMongoSource(FlightProcessorMongoCollections.FLIGHT_COLLECTION_NAME);
            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
            env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);
            env.setParallelism(1);
            env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
            env.getConfig().setRestartStrategy(RestartStrategies.fixedDelayRestart(3, org.apache.flink.api.common.time.Time.seconds(10)));
            //env.getConfig().setRestartStrategy(RestartStrategies.noRestart());
            //env.enableCheckpointing(5000); // setting the checkpoint

            DataStream<String> flightDataStream = env.fromSource(source, WatermarkStrategy.noWatermarks(), "MongoDB-flight-source")
                    .filter(json -> subQueryFlightsFilterCriteria(json, flightEquipment));

            DataStream<com.aa.opsco.prism.flink.datamodel.Flight> flightStream = flightDataStream.map((MapFunction<String, com.aa.opsco.prism.flink.datamodel.Flight>) value -> mapper.readValue(value, com.aa.opsco.prism.flink.datamodel.Flight.class)).name("subQueryFlightFromMongoDB-filtered-map").keyBy(com.aa.opsco.prism.flink.datamodel.Flight::getFlightKey);

            env.execute("Read from MongoDB-subQueryFlightFromMongoDB");

            Iterator<com.aa.opsco.prism.flink.datamodel.Flight> myOutput = DataStreamUtils.collect(flightStream);
            while (myOutput.hasNext())
                flightList.add(myOutput.next());
        }
        return flightList;
    }
}