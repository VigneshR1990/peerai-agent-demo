/**
 *
 */
package com.aa.lookahead.cache.propagationengine.Job;

import com.aa.lookahead.aggregation.repository.AggregationRepository;
import com.aa.lookahead.cache.propagationengine.dao.PropagationEngineDAO;
import com.aa.lookahead.cache.propagationengine.factories.PE_EventFactory;
import com.aa.lookahead.crew.legality.service.impl.CrewPropertiesUtil;
import com.aa.lookahead.crew.repository.cache.CrewRepository;
import com.aa.lookahead.dataobjects.LKAFlightKey;
import com.aa.lookahead.dataobjects.Tail;
import com.aa.lookahead.dataobjects.adl.AdlKey;
import com.aa.lookahead.dataobjects.aggregation.AggregationKey;
import com.aa.lookahead.dataobjects.aggregation.AggregationTypes;
import com.aa.lookahead.dataobjects.crewsequence.read.CrewFlightDetail;
import com.aa.lookahead.dataobjects.event.EventStatusDetails;
import com.aa.lookahead.dataobjects.event.LKAEventType;
import com.aa.lookahead.dataobjects.event.LKAFlightEvent;
import com.aa.lookahead.dataobjects.event.RequestType;
import com.aa.lookahead.dataobjects.event.enums.ArrivalStatus;
import com.aa.lookahead.dataobjects.event.enums.DepartureStatus;
import com.aa.lookahead.dataobjects.event.enums.EventStatus;
import com.aa.lookahead.dataobjects.event.enums.LegStatus;
import com.aa.lookahead.dataobjects.event.lookahead.propagation.*;
import com.aa.lookahead.dataobjects.flight.Equipment;
import com.aa.lookahead.dataobjects.flight.FlightTimes;
import com.aa.lookahead.dataobjects.flight.Status;
import com.aa.lookahead.dataobjects.legality.FlightOperatingCrew;
import com.aa.lookahead.dataobjects.problemtype.ProblemTypeDescription;
import com.aa.lookahead.dataobjects.problemtype.ProblemTypes;
import com.aa.lookahead.dataobjects.propagation.PropagationCrewDetail;
import com.aa.lookahead.dataobjects.propagation.PropagationEngineCalculationValues;
import com.aa.lookahead.dataobjects.propagation.PropagationManagement_CrewTrigger;
import com.aa.lookahead.dataobjects.propagation.PropagationTrackerRecord;
import com.aa.lookahead.dataobjects.propagation.VO.FlightResourceVO;
import com.aa.lookahead.execute.RunComponents;
import com.aa.lookahead.management.DynamicPropertyBean;
import com.aa.lookahead.problemtype.repository.ProblemTypeRepository;
import com.aa.lookahead.repository.cache.FlightRepository;
import com.aa.lookahead.services.propagation.calculator.CalculatorService;
import com.aa.lookahead.services.propagation.repository.PropagationSpaceRepository;
import com.aa.lookahead.services.propagation.utility.CommonUtility;
import com.aa.lookahead.services.propagation.utility.PropagationEngineErrorCode;
import com.aa.lookahead.utils.DateTimeUtils;
import com.aa.lookahead.utils.common.TimeUtils;
import com.gigaspaces.client.ChangeSet;
import com.j_spaces.core.client.SQLQuery;
import org.apache.commons.lang3.BooleanUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.text.ParseException;
import java.util.*;


/**
 * Performs business logic for the Propagation stage (uses service call to to {@link com.aa.lookahead.services.propagation.calculator.CalculatorService}
 * and repository serivce call to{@link com.aa.lookahead.services.propagation.repository.PropagationSpaceRepository}).
 * <p/>
 * Processes propagation component events {@link com.aa.lookahead.dataobjects.event.lookahead.propagation.PropagationEngineEvent} and changes their status to PROCESSED or FAILED.
 * It also persists input event with status DEBUG or EXCEPTION for audit and integration.
 *
 * @author Anirban
 */
public class PropagationEngineDelayCalculatorJob {

    private static Logger LOG = LoggerFactory.getLogger("PROPAGATION");
    private static Logger LOGDB = LoggerFactory.getLogger("PROPAGATION_DB");
    public static final long twoDaysInmillisecond = 172800000;
    public static final int minute2millisecond = 60000;
    private static final String FLIGHT_TIMES_CONSTANT = "FLIGHT_TIMES";
    private static final String EVENT_CHANGE_CONSTANT = "LKA_EVENTS";
    //leg status projection columns
    private static final String[] PROJECTIONCOLUMNS_LEGSTATUS = {"legStatus", "previousLegStatus",
            "departureStatus", "arrivalStatus"};
    //flight times projection columns
    private static final String[] PROJECTIONCOLUMNS_FLIGHTTIMES = {"flightKey", "altFlightKey",
            "origFlightLeg", "routingFlightKey",
            "scheduledDepartureTime", "scheduledArrivalTime", "latestDepartureTime",
            "previousLatestDepartureTime", "latestArrivalTime", "previousLatestLatestArrivalTime",
            "projectedArrivalTime_FOS", "previousProjectedArrivalTime_FOS",
            "projectedDepartureTime_FOS",
            "previousProjectedDepartureTime_FOS", "onTime", "previousInTime", "outTime",
            "previousOutTime", "offTime", "previousOffTime", "objectiveGroundTimeMinutes",
            "predictedTaxiInTime", "predictedTaxiOutTime",
            "ctaDate", "ctdDate", "previousCtaDate", "previousCtdDate", "predictedEMOGT",
            "predictedAirTime", "projectedArrivalTime", "previousProjectedArrivalTime",
            "projectedArrivalTime_Controlled", "previousProjectedArrivalTime_controlled",
            "projectedDepartureTime", "previousProjectedDepartureTime",
            "projectedDepartureTime_Controlled", "previousProjectedDepartureTime_Controlled",
            "snapshots", "projectedMntcArrivalTime", "projectedMntcDepartureTime","arrivalStation",
            "previousProjectedMntcArrivalTime", "previousProjectedMntcDepartureTime",
            "projectedLatestMntcDepartureTime","projectedLatestMntcArrivalTime","previousProjectedLatestMntcDepartureTime", "previousProjectedLatestMntcArrivalTime", "departureStationGMTOffset","arrivalStationGMTOffset"};
    //equipment projection columns
    private static final String[] PROJECTIONCOLUMNS_EQUIPMENT = {"flightKey", "routingFlightKey",
            "arrivalStation", "tailNumber", "assignedEquipmentType", "nextLeg", "prevLeg",
            "flightCapacity", "snapshots"};
    @Autowired
    private PropagationSpaceRepository propagationSpaceRepository;
    @Autowired
    private CalculatorService calculatorService;
    @Autowired
    private FlightRepository flightRepository;
    @Autowired
    private ProblemTypeRepository problemTypeRepository;
    @Autowired
    private AggregationRepository aggregationRepository;
    @Autowired
    private CrewRepository crewRepository;
    @Autowired
    private RunComponents runProblemType;
    @Autowired
    private RunComponents runAggregation;
    @Value("#{new Boolean('${computationspace.propagation.enable.crewturntime:false}')}")
    private boolean enableNewCrewTurnTimeLogic;
    private DynamicPropertyBean dynamicPropertyBean;
    private PropagationEngineDAO propagationEngineDAO;

    public PropagationEngineDelayCalculatorJob() {
    }

    public PropagationEngineDelayCalculatorJob(DataSource dataSource) {
        propagationEngineDAO = new PropagationEngineDAO();
        propagationEngineDAO.setDataSource(dataSource);
    }

    public void eventHandler(PropagationEngineEvent propagationEngineEvent) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("eventHandler():: Checking if the following event is not null: "
                    + propagationEngineEvent);
        }
        if (propagationEngineEvent != null) {
            startPropagationEvent(propagationEngineEvent, Boolean.FALSE);
        }
    }

    /**
     * This starts propagation engine events handling. It calculates propagated times and check whether next flight is available or not.
     * Exceptions are tracked as statusMessage inside StatusDetails object
     *
     * @param propagationEngineEvent
     */
    public PropagationEngineCalculationValues startPropagationEvent(
            PropagationEngineEvent propagationEngineEvent, Boolean reseedForced) {
        PropagationEngineCalculationValues propagationEngineCalculationValues = null;
        Boolean isCrewExcluded = Boolean.FALSE;
        if (LOG.isInfoEnabled()) {
            LOG.info("startPropagationEvent():: Start propagating the following event: "
                    + propagationEngineEvent
                    + " requestType=" + propagationEngineEvent.getRequestType());
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "startPropagationEvent():: Checking if the flight key for the event is not null: "
                            + propagationEngineEvent);
        }

        if (propagationEngineEvent.getFlightKey() != null) {

            String requestID = "0";
            if (propagationEngineEvent.getRequestID() != null) {
                // if request is coming from ADL, use the snapshot id as the request id, it should always be 0. Request
                // type ADL is live
                if (propagationEngineEvent.getRequestID() instanceof AdlKey) {
                    requestID = ((AdlKey) propagationEngineEvent.getRequestID()).getSnapshotId();
                }
                else{
                    requestID = propagationEngineEvent.getRequestID().toString();
                }

            }
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "startPropagationEvent():: Flight key is not null: " + propagationEngineEvent
                                .getFlightKey() + " and requestId :" + requestID);
            }
            try {
                long start = System.currentTimeMillis();
                LOG.info("Thread[" + Thread.currentThread() + "] waiting for lock to be released");
                synchronized (PropagationSpaceRepository
                        .getFlightKeyToken(propagationEngineEvent.getFlightKey(), requestID)) {
                    LOG.info("Lock is release, thread[" + Thread.currentThread() + "] has lock " + (System.currentTimeMillis() - start));
                    try {
                        //Provide option to exclude crew integration based on boolean flag in cache
                        PropagationManagement_CrewTrigger propagationManagement_CrewTrigger = propagationSpaceRepository
                                .getPropagationManagement_crewExclusion();
                        if (propagationManagement_CrewTrigger != null
                                && propagationManagement_CrewTrigger.getCrewLogicExcluded()
                                == Boolean.TRUE) {
                            isCrewExcluded = Boolean.TRUE;
                        }
                        //Populate propagation payload
                        propagationEngineEvent = populatePropagationPayload(propagationEngineEvent,
                                reseedForced, isCrewExcluded, requestID);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(
                                    "startPropagationEvent():: Using following event status details: "
                                            + propagationEngineEvent.getEventStatusDetails());
                            LOG.debug("startPropagationEvent():: Using following event payload: "
                                    + propagationEngineEvent.getEventPayload() + "requestID :"
                                    + requestID + ", event requestid :" + propagationEngineEvent
                                    .getRequestID());

                        }
                        propagationEngineCalculationValues = calculatePropagatedFlightTimes(
                                propagationEngineEvent, reseedForced, isCrewExcluded, requestID);

                        if (LOG.isDebugEnabled()) {
                            LOG.debug(
                                    "startPropagationEvent():: Done calculating propagated flight times: "
                                            + propagationEngineEvent);
                            LOG.debug(
                                    "startPropagationEvent():: Setting event to " + EventStatus.DEBUG);
                        }
                    } catch (Exception ex) {
                        propagationEngineEvent.getEventStatusDetails()
                                .setEventStatus(EventStatus.DEBUG);
                        if (LOG.isErrorEnabled()) {
                            LOG.error("startPropagationEvent():: ERRORCODE: "
                                    + PropagationEngineErrorCode.ERROR_CODE_1100 + ", Description: "
                                    + PropagationEngineErrorCode.ERROR_CODE_1100_DESC, ex);

                        }
                        CommonUtility
                                .handleLKAException(propagationSpaceRepository.repoConn.getGigaSpace(),
                                        propagationEngineEvent, ex, "Error inside startPropagationEvent");
                    }
                }
            } catch (Exception ex) {

                CommonUtility.handleLKAException(propagationSpaceRepository.repoConn.getGigaSpace(),
                        propagationEngineEvent, ex, "Unable to find lock to process");
            }


        } else {
            if (LOG.isInfoEnabled()) {
                LOG.info("startPropagationEvent():: Flight key is null, cannot proceed with event: "
                        + propagationEngineEvent
                        + " requestType=" + propagationEngineEvent.getRequestType());
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "startPropagationEvent():: Setting event status to " + EventStatus.FAILED);
            }
            throw new IllegalArgumentException("Flight Key can not be empty");
        }
        if (propagationEngineCalculationValues == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("startPropagationEvent():: propagationEngineCalculationValues is null ");

            }
        }
        return propagationEngineCalculationValues;
    }

    public PropagationEngineCalculationValues calculatePropagatedFlightTimes(
            PropagationEngineEvent propagationEngineEvent, boolean reseedEnforced,
            Boolean isCrewExcluded, String requestID) throws Exception {
        PropagationEngineCalculationValues propagationEngineCalculationValues = new PropagationEngineCalculationValues();
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "calculatePropagatedFlightTimes():: Checking if propagation event is not null: "
                            + propagationEngineEvent);
        }
        Boolean processedError = false;
        RequestType requestType = propagationEngineEvent.getRequestType();
        String eventType = propagationEngineEvent.getEventType();
        if (propagationEngineEvent != null) {
            PropagationEngineEventPayload propagationEngineEventPayload = propagationEngineEvent
                    .getEventPayload();
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "calculatePropagatedFlightTimes():: Checking if propagation payload is not null: "
                                + propagationEngineEventPayload);
            }
//			PropagationEngineEventPayload propagationEngineEventPayload = propagationEngineEvent.getPropagationEngineEventPayload();
            LKAFlightKey flightKey = propagationEngineEvent.getFlightKey();
            String batchId = propagationEngineEvent.getBatchId();
            Boolean nextLegPropagtionContinue = Boolean.FALSE;
            LKAFlightKey eventRootFlightKey = null;
            LKAFlightKey lastVisitedFlightKey = null;
            String errorReasonMsg = null;
            boolean logicalExceptionHandled = false;
            boolean originalEvent = false;
            TreeSet<String> visitedFlightKeysSet = new TreeSet<String>();
            List<String> toBeProcessedFlightList = new ArrayList<String>();
            List<String> processedFlightList = new ArrayList<String>();
            PropagationTrackerRecord propagationTrackerRecord = null;
            String eventLinkResourceType = null;
            ChangeSet propagationTrackerRecord_CS = null;
            if (eventType.equalsIgnoreCase(LKAEventType.PE_CONTINUED.name())) {
                eventRootFlightKey = ((PE_ContinutationEvent) propagationEngineEvent).getEventInitiatedFlightKey();
                lastVisitedFlightKey = ((PE_ContinutationEvent) propagationEngineEvent).getLastVisitedFlightKey();
                visitedFlightKeysSet.addAll(((PE_ContinutationEvent) propagationEngineEvent).getVisitedFlightKeysSet());
                eventLinkResourceType = ((PE_ContinutationEvent) propagationEngineEvent).getResourceType();
                if (eventLinkResourceType != null) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(
                                "eventLinkResourceType is " + eventLinkResourceType + " for flightKey :"
                                        + propagationEngineEvent.getFlightKey());
                    }
                    if (FlightEventResourceType.CREW.name()
                            .equalsIgnoreCase(eventLinkResourceType)) {
                        if (propagationEngineEventPayload != null) {
                            propagationEngineEventPayload
                                    .setFlightEventResourceType(FlightEventResourceType.CREW);
                        }

                    } else if (FlightEventResourceType.EQUIPMENT.name()
                            .equalsIgnoreCase(eventLinkResourceType)) {
                        if (propagationEngineEventPayload != null) {
                            propagationEngineEventPayload
                                    .setFlightEventResourceType(FlightEventResourceType.EQUIPMENT);
                        }
                    }
                } else {
                    LOG.warn(
                            "eventLinkResourceType is missing for flightKey :" + propagationEngineEvent
                                    .getFlightKey());
                }
            }
            else if (!reseedEnforced) {
                originalEvent = true;
                //Create propagationTracker object
                propagationTrackerRecord = new PropagationTrackerRecord();
                propagationTrackerRecord.setBatchId(batchId);
                propagationTrackerRecord.setRequestID(propagationEngineEvent.getRequestID());
                propagationTrackerRecord.setRequestType(propagationEngineEvent.getRequestType());
                propagationTrackerRecord.setOriginFlightKey(flightKey);
                propagationTrackerRecord.setInitialRootEvent(propagationEngineEvent);
                propagationTrackerRecord.setFailed(false);
                propagationTrackerRecord.setFinished(false);
                List<String> initialEventList = new ArrayList<String>();
                initialEventList.add(flightKey.generateFlightKeyString()
                        + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER
                        + propagationEngineEvent.getEventType());
                propagationTrackerRecord.setToBeProcessedFlightList(initialEventList);
                propagationTrackerRecord.setProcessedFlightList(new ArrayList<String>());
                propagationTrackerRecord.setPendingChildrenCount(new Integer(1));
                try {
                    propagationSpaceRepository.repoConn.getGigaSpace()
                            .write(propagationTrackerRecord);
                } catch (Exception ex) {
                    if (LOG.isErrorEnabled()) {
                        LOG.error("Unable to write propagationTrackerRecord for :"
                                + propagationEngineEvent.toString() + " with details: ", ex);
                    }
                    CommonUtility
                            .handleLKAException(propagationSpaceRepository.repoConn.getGigaSpace(),
                                    propagationEngineEvent, ex,
                                    "Unable to write propagationTrackerRecord.");
                }
            }
            PE_EventFactory pe_EventFactory = new PE_EventFactory();
            Boolean transactionSuccessful = Boolean.FALSE;
            //Synchronize based on batchId and event origin flight key
            String fltKeyLock =
                    eventRootFlightKey != null ? eventRootFlightKey.generateFlightKeyString().trim()
                            : flightKey.generateFlightKeyString().trim();
            String transactionLock =
                    fltKeyLock + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER + requestID;
            LOG.debug("transactionLock : " + transactionLock + " requestID:" + requestID);
            Object lock = PropagationSpaceRepository.getLock(transactionLock);
            synchronized (lock) {
                if (propagationEngineEventPayload != null
                        && propagationEngineEventPayload.getCurrentFlightKey() != null) {
                    flightKey = propagationEngineEventPayload.getCurrentFlightKey();
                    LKAFlightKey previousEquipmentFlightKey = (
                            propagationEngineEventPayload.getPreviousFlightKey() == null ? null
                                    : propagationEngineEventPayload.getPreviousFlightKey());
                    if (FlightEventResourceType.EQUIPMENT.name()
                            .equalsIgnoreCase(eventLinkResourceType)) {

                        if (visitedFlightKeysSet.contains(flightKey.toString())) {
                            errorReasonMsg =
                                    "Cyclic path detected for flight key " + flightKey.toString();
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(errorReasonMsg);
                            }
                            logicalExceptionHandled = Boolean.TRUE;
                        } else {
                            visitedFlightKeysSet.add(flightKey.toString());
                        }
//						if(LOG.isDebugEnabled())
//						{
//							LOG.debug("calculatePropagatedFlightTimes():: Start working with event payload: "+propagationEngineEventPayload);
//							LOG.debug("calculatePropagatedFlightTimes():: Checking if the event type is a continuation event: "+propagationEngineEvent.getEventType());
//						}

                        //TODO - find connecting resource . Specially Pilot/FirstOfficer ,crew members.
                        //TODO - Consolidate the incoming flights for each resource.
                        //TODO - Aggregate all the list the unique flight keys

                        if (!logicalExceptionHandled && FlightEventResourceType.EQUIPMENT.name()
                                .equalsIgnoreCase(eventLinkResourceType)
                                && previousEquipmentFlightKey != null && lastVisitedFlightKey != null
                                && !previousEquipmentFlightKey.equals(lastVisitedFlightKey)) {
                            errorReasonMsg = "Previous flight key" + previousEquipmentFlightKey
                                    + " does not matching with last processed flight key"
                                    + lastVisitedFlightKey;
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(errorReasonMsg);
                            }
                            logicalExceptionHandled = Boolean.TRUE;
                        }
                    }

                    //Validate no logical Exception to continue the flow
                    if (!logicalExceptionHandled) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("calculatePropagatedFlightTimes():: previousEquipmentFlight: "
                                    + previousEquipmentFlightKey + ", "
                                    + " eventRootKey: " + eventRootFlightKey + " for flightKey: "
                                    + flightKey + " requestID:" + requestID);
                        }
                        FlightTimes flightTimes = propagationEngineEventPayload
                                .getCurrentFlightTimes();
                        FlightTimes operationFlightTimes = null;
                        //propagation handling logic with times and child event publishing
                        try {
                            Boolean isFlightDepartureStatusOut = Boolean.FALSE;
                            Boolean isFlightDepartureStatusOff = Boolean.FALSE;
                            Boolean isLegStatusCancelled = Boolean.FALSE;
                            Boolean isFlightArrivalStatusOn = Boolean.FALSE;
                            Boolean isFlightArrivalStatusIn = Boolean.FALSE;
                            LegStatus legStatus = null;
                            Status flightStatus = null;
                            if (propagationEngineEventPayload.getFlightStatus() != null) {
                                flightStatus = propagationEngineEventPayload.getFlightStatus();
                                //Check departure status
                                if (flightStatus.getDepartureStatus() != null) {
                                    if (DepartureStatus.OUT.name().equalsIgnoreCase(
                                            flightStatus.getDepartureStatus().name())) {
                                        isFlightDepartureStatusOut = Boolean.TRUE;
                                    } else if (DepartureStatus.OFF.name().equalsIgnoreCase(
                                            flightStatus.getDepartureStatus().name())) {
                                        isFlightDepartureStatusOff = Boolean.TRUE;
                                    }
                                }
                                //Check arrival status
                                if (flightStatus.getArrivalStatus() != null) {
                                    if (ArrivalStatus.IN.name()
                                            .equalsIgnoreCase(flightStatus.getArrivalStatus().name())) {
                                        isFlightArrivalStatusIn = Boolean.TRUE;
                                    } else if (ArrivalStatus.ON.name()
                                            .equalsIgnoreCase(flightStatus.getArrivalStatus().name())) {
                                        isFlightArrivalStatusOn = Boolean.TRUE;
                                    }
                                }
                                //Check leg status
                                if (flightStatus.getLegStatus() != null) {
                                    legStatus = flightStatus.getLegStatus();
                                    if (LegStatus.CANCELLED_VIA_XL.equals(legStatus)
                                            || LegStatus.CANCELLED.equals(legStatus)
                                            || LegStatus.CANCELLED_VIA_PLAN.equals(legStatus)) {
                                        isLegStatusCancelled = true;
                                    }

                                }
                            }

                            if (!isLegStatusCancelled) {
                                switch (LKAEventType.valueOf(eventType)) {
                                    case PE_CANCEL:
                                        flightTimes = substituteFlightTimesWithScheduleTimes(
                                                flightTimes, requestID);
                                        operationFlightTimes = copyFlightTimesRequiredValue(
                                                flightTimes, operationFlightTimes, requestID);
                                        break;
                                    case PE_OUT:
                                        if (flightTimes.getOutTime() != null) {
                                            operationFlightTimes = new FlightTimes(flightKey);
                                            flightTimes.setPreviousProjectedDepartureTime(
                                                    flightTimes.getProjectedDepartureTime(requestID),
                                                    requestID);
                                            flightTimes
                                                    .setProjectedDepartureTime(flightTimes.getOutTime(),
                                                            requestID);
                                            flightTimes.setProjectedMntcDepartureTime(flightTimes.getProjectedDepartureTime(requestID),
                                                    requestID);
                                            flightTimes
                                                    .setPreviousProjectedDepartureTime_Controlled(
                                                            flightTimes
                                                                    .getProjectedDepartureTime_Controlled(
                                                                            requestID), requestID);
                                            flightTimes.setProjectedDepartureTime_Controlled(
                                                    flightTimes.getOutTime(), requestID);
                                            flightTimes.setPreviousProjectedLatestMntcDepartureTime(flightTimes.getProjectedLatestMntcDepartureTime(requestID));
                                            flightTimes.setProjectedLatestMntcDepartureTime(flightTimes.getOutTime(),requestID);
                                            flightTimes = calculateFlightProjectedArrivals(
                                                    flightTimes, (isFlightArrivalStatusIn
                                                            || isFlightArrivalStatusOn), legStatus,
                                                    requestID);
                                            operationFlightTimes = copyFlightTimesRequiredValue(
                                                    flightTimes, operationFlightTimes, requestID);
                                        }
                                        break;
                                    case PE_IN:
                                        if (flightTimes.getInTime() != null) {
                                            operationFlightTimes = new FlightTimes(flightKey);
                                            flightTimes.setPreviousProjectedArrivalTime(
                                                    flightTimes.getProjectedArrivalTime(requestID),
                                                    requestID);
                                            flightTimes
                                                    .setProjectedArrivalTime(flightTimes.getInTime(),
                                                            requestID);
                                            flightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedArrivalTime(requestID),
                                                    requestID);
                                            flightTimes.setPreviousProjectedArrivalTime_controlled(
                                                    flightTimes
                                                            .getProjectedArrivalTime_Controlled(requestID),
                                                    requestID);
                                            flightTimes.setProjectedArrivalTime_Controlled(
                                                    flightTimes.getInTime(), requestID);
                                            flightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID));
                                            flightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getInTime(),requestID);
                                            operationFlightTimes.setPreviousProjectedArrivalTime(
                                                    flightTimes
                                                            .getPreviousProjectedArrivalTime(requestID),
                                                    requestID);
                                            operationFlightTimes.setProjectedArrivalTime(
                                                    flightTimes.getProjectedArrivalTime(requestID),
                                                    requestID);
                                            operationFlightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedArrivalTime(requestID),
                                                    requestID);
                                            operationFlightTimes
                                                    .setPreviousProjectedArrivalTime_controlled(
                                                            flightTimes
                                                                    .getPreviousProjectedArrivalTime_controlled(
                                                                            requestID), requestID);
                                            operationFlightTimes.setProjectedArrivalTime_Controlled(
                                                    flightTimes
                                                            .getProjectedArrivalTime_Controlled(requestID),
                                                    requestID);
                                            operationFlightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getPreviousProjectedLatestMntcArrivalTime(requestID));
                                            operationFlightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID),requestID);
                                            operationFlightTimes.setScheduledArrivalTime(
                                                    flightTimes.getScheduledArrivalTime());
                                            operationFlightTimes.setScheduledDepartureTime(
                                                    flightTimes.getScheduledDepartureTime());
                                        }
                                        break;
                                    case PE_OFF:
                                        if (flightTimes.getOffTime() != null) {
                                            //For Off event, PGTD=PTD=LGTD; PGTA=PTA=LGTA.
                                            operationFlightTimes = new FlightTimes(flightKey);
                                            flightTimes
                                                    .setPreviousProjectedDepartureTime_Controlled(
                                                            flightTimes
                                                                    .getProjectedDepartureTime_Controlled(
                                                                            requestID), requestID);
                                            flightTimes.setProjectedDepartureTime_Controlled(
                                                    flightTimes.getLatestDepartureTime(requestID),
                                                    requestID);
                                            flightTimes.setPreviousProjectedDepartureTime(
                                                    flightTimes.getProjectedDepartureTime(requestID),
                                                    requestID);
                                            flightTimes.setProjectedDepartureTime(
                                                    flightTimes.getLatestDepartureTime(requestID),
                                                    requestID);
                                            flightTimes.setProjectedMntcDepartureTime(flightTimes.getProjectedDepartureTime(requestID),
                                                    requestID);
                                            flightTimes.setPreviousProjectedArrivalTime_controlled(
                                                    flightTimes
                                                            .getProjectedArrivalTime_Controlled(requestID),
                                                    requestID);
                                            flightTimes.setProjectedArrivalTime_Controlled(
                                                    flightTimes.getLatestArrivalTime(requestID),
                                                    requestID);
                                            flightTimes.setPreviousProjectedArrivalTime(
                                                    flightTimes.getProjectedArrivalTime(requestID),
                                                    requestID);
                                            flightTimes.setProjectedArrivalTime(
                                                    flightTimes.getLatestArrivalTime(requestID),
                                                    requestID);
                                            flightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedArrivalTime(requestID),
                                                    requestID);
                                            flightTimes.setPreviousProjectedLatestMntcDepartureTime(flightTimes.getProjectedLatestMntcDepartureTime(requestID));
                                            flightTimes.setProjectedLatestMntcDepartureTime(flightTimes.getLatestDepartureTime(requestID),requestID);
                                            flightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID));
                                            flightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getLatestArrivalTime(),requestID);
//			        						int taxiOut=calculatorService.calculateTaxiOutTime(flightKey, flightTimes);
//			        						if(taxiOut !=0)
//			        						{
//												Date computedDeparture_Control = new Date(flightTimes.getOffTime().getTime() - taxiOut*minute2millisecond);
//												flightTimes.setProjectedDepartureTime_Controlled(computedDeparture_Control);
//			        						}
//			        						else
//			        						{
//			        							flightTimes.setProjectedDepartureTime_Controlled(flightTimes.getOffTime());
//			        						}
                                            operationFlightTimes = copyFlightTimesRequiredValue(
                                                    flightTimes, operationFlightTimes, requestID);
                                        }
                                        break;
                                    case PE_ON:
                                        if (flightTimes.getOnTime() != null) {
                                            operationFlightTimes = new FlightTimes(flightKey);
                                            flightTimes.setPreviousProjectedArrivalTime_controlled(
                                                    flightTimes
                                                            .getProjectedArrivalTime_Controlled(requestID),
                                                    requestID);
                                            int taxiIn = calculatorService
                                                    .calculateTaxiInTime(flightKey, flightTimes, requestID);
                                            if (taxiIn != 0) {
                                                Date computedArrival_Control = new Date(
                                                        flightTimes.getOnTime().getTime()
                                                                + taxiIn * minute2millisecond);
                                                flightTimes.setProjectedArrivalTime_Controlled(
                                                        computedArrival_Control, requestID);
                                            } else {
                                                flightTimes.setProjectedArrivalTime_Controlled(
                                                        flightTimes.getOnTime(), requestID);
                                            }
                                            operationFlightTimes
                                                    .setPreviousProjectedArrivalTime_controlled(
                                                            flightTimes
                                                                    .getPreviousProjectedArrivalTime_controlled(
                                                                            requestID), requestID);
                                            operationFlightTimes.setProjectedArrivalTime_Controlled(
                                                    flightTimes
                                                            .getProjectedArrivalTime_Controlled(requestID),
                                                    requestID);
                                            operationFlightTimes.setScheduledArrivalTime(
                                                    flightTimes.getScheduledArrivalTime());
                                            operationFlightTimes.setScheduledDepartureTime(
                                                    flightTimes.getScheduledDepartureTime());
                                        }
                                        break;
                                    default:
                                        //Verify and continue the flow if not already handled
                                        boolean needContinue = true;
                                        //In case of departure status "OUT", pta=eta or ptd=etd.
                                        LOG.debug(" requestID :" + requestID
                                                + " isFlightDepartureStatusOut :"
                                                + isFlightDepartureStatusOut
                                                + " isFlightDepartureStatusOff: "
                                                + isFlightDepartureStatusOff);
                                        if (isFlightDepartureStatusOut
                                                || isFlightDepartureStatusOff) {
                                            if (LKAEventType.valueOf(eventType).name().equals("PE_ETA")) {
                                                operationFlightTimes = new FlightTimes(flightKey);
                                                flightTimes.setPreviousProjectedArrivalTime(
                                                        flightTimes.getProjectedArrivalTime(requestID),
                                                        requestID);
                                                flightTimes.setProjectedArrivalTime(
                                                        flightTimes.getLatestArrivalTime(requestID),
                                                        requestID);
                                                flightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedArrivalTime(requestID),
                                                        requestID);
                                                flightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID));
                                                flightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getLatestArrivalTime(),requestID);
                                                operationFlightTimes
                                                        .setPreviousProjectedArrivalTime(flightTimes
                                                                        .getPreviousProjectedArrivalTime(requestID),
                                                                requestID);
                                                operationFlightTimes.setProjectedArrivalTime(
                                                        flightTimes.getProjectedArrivalTime(requestID),
                                                        requestID);
                                                operationFlightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedArrivalTime(requestID),
                                                        requestID);
                                                operationFlightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID));
                                                operationFlightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getLatestArrivalTime(),requestID);
                                                operationFlightTimes.setScheduledArrivalTime(
                                                        flightTimes.getScheduledArrivalTime());
                                                operationFlightTimes.setScheduledDepartureTime(
                                                        flightTimes.getScheduledDepartureTime());
                                                needContinue = false;
                                            } else if (LKAEventType.valueOf(eventType).name()
                                                    .equals("PE_ETD") || LKAEventType.valueOf(eventType).name().equals("PE_ETR")){
                                                operationFlightTimes = new FlightTimes(flightKey);
                                                flightTimes.setPreviousProjectedDepartureTime(
                                                        flightTimes
                                                                .getProjectedDepartureTime(requestID),
                                                        requestID);
                                                flightTimes.setProjectedDepartureTime(
                                                        flightTimes.getLatestDepartureTime(requestID),
                                                        requestID);
                                                flightTimes.setProjectedMntcDepartureTime(flightTimes.getProjectedDepartureTime(requestID),
                                                        requestID);
                                                flightTimes.setPreviousProjectedArrivalTime(
                                                        flightTimes.getProjectedArrivalTime(requestID),
                                                        requestID);
                                                flightTimes.setProjectedArrivalTime(
                                                        flightTimes.getLatestArrivalTime(requestID),
                                                        requestID);
                                                flightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedArrivalTime(requestID),
                                                        requestID);
                                                flightTimes.setPreviousProjectedLatestMntcDepartureTime(flightTimes.getProjectedLatestMntcDepartureTime(requestID));
                                                flightTimes.setProjectedLatestMntcDepartureTime(flightTimes.getLatestDepartureTime(requestID),requestID);
                                                flightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID));
                                                flightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getLatestArrivalTime(),requestID);
                                                operationFlightTimes
                                                        .setPreviousProjectedDepartureTime(flightTimes
                                                                .getPreviousProjectedDepartureTime(
                                                                        requestID), requestID);
                                                operationFlightTimes.setProjectedDepartureTime(
                                                        flightTimes
                                                                .getProjectedDepartureTime(requestID),
                                                        requestID);
                                                operationFlightTimes.setProjectedMntcDepartureTime(flightTimes.getProjectedDepartureTime(requestID),
                                                        requestID);
                                                operationFlightTimes
                                                        .setPreviousProjectedArrivalTime(flightTimes
                                                                        .getPreviousProjectedArrivalTime(requestID),
                                                                requestID);
                                                operationFlightTimes.setProjectedArrivalTime(
                                                        flightTimes.getProjectedArrivalTime(requestID),
                                                        requestID);
                                                operationFlightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedArrivalTime(requestID),
                                                        requestID);
                                                operationFlightTimes.setScheduledArrivalTime(
                                                        flightTimes.getScheduledArrivalTime());
                                                operationFlightTimes.setScheduledDepartureTime(
                                                        flightTimes.getScheduledDepartureTime());
                                                operationFlightTimes.setPreviousProjectedLatestMntcDepartureTime(flightTimes.getProjectedLatestMntcDepartureTime(requestID));
                                                operationFlightTimes.setProjectedLatestMntcDepartureTime(flightTimes.getLatestDepartureTime(requestID),requestID);
                                                operationFlightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID));
                                                operationFlightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getLatestArrivalTime(),requestID);
                                                needContinue = false;
                                            }
                                        }
                                        if (needContinue) {
                                            //Calculate PTD = max(max(ready times of inbound flights for all resources), ETD)
                                            List<FlightResourceVO> calculatedAllFlightResourcesReadyTimesList = calculateAllResourceReadyTimes(
                                                    propagationEngineEventPayload, requestID, LKAEventType.getLKAEventEnumFromString(eventType), propagationEngineEvent.getRequestType());
                                            if (calculatedAllFlightResourcesReadyTimesList != null
                                                    && !calculatedAllFlightResourcesReadyTimesList
                                                    .isEmpty()) {
                                                propagationEngineCalculationValues
                                                        .setResourceReadyTimes(
                                                                calculatedAllFlightResourcesReadyTimesList);
                                                try {
                                                    propagationSpaceRepository
                                                            .deleteFlightResourceVOByFlightKey(
                                                                    flightKey);
                                                    propagationSpaceRepository
                                                            .insertFlightResourceVOList(
                                                                    calculatedAllFlightResourcesReadyTimesList);
                                                } catch (Exception ex) {
                                                    LOG.error(
                                                            "Error occurred while updated calculatedAllFlightResourcesReadyTimesList for flightKey :"
                                                                    + flightKey);
                                                }
                                            }
                                            LOG.debug(" requestID :" + requestID + " - " + flightKey.toShortString()
                                                    + " before Calculating projectedTime :"
                                                    + flightTimes);
                                            flightTimes = calculateFlightProjectedDepartures(
                                                    flightTimes,
                                                    calculatedAllFlightResourcesReadyTimesList,
                                                    (isFlightDepartureStatusOut
                                                            || isFlightDepartureStatusOff), legStatus,
                                                    requestID);
                                            flightTimes = calculateFlightProjectedArrivals(
                                                    flightTimes, (isFlightArrivalStatusIn
                                                            || isFlightArrivalStatusOn), legStatus,
                                                    requestID);
                                            operationFlightTimes = copyFlightTimesRequiredValue(
                                                    flightTimes, operationFlightTimes, requestID);
                                            LOG.debug(" requestID :" + requestID + " - " + flightKey.toShortString()
                                                    + " after Calculating, operationFlightTimes :"
                                                    + operationFlightTimes);
                                            if (LOG.isDebugEnabled()) {
                                                LOG.debug(" requestID :" + requestID + " - " + flightKey.toShortString()
                                                        + " calculatePropagatedFlightTimes():: Using flightTimes: "
                                                        + flightTimes);
                                            }
                                        }
                                        break;
                                }
                            } else {
                                flightTimes = substituteFlightTimesWithScheduleTimes(flightTimes, requestID);
                                operationFlightTimes = copyFlightTimesRequiredValue(flightTimes,
                                        operationFlightTimes, requestID);
                            }

                            //Create Problem Type for projected times only if run set to true
                            if (runProblemType.convertBoolean(runProblemType.getRunProblemType())) {
                                boolean departure = false;
                                boolean departureControlled = false;
                                boolean arrival = false;
                                boolean arrivalControlled = false;

                                if (flightTimes.getProjectedDepartureTime(requestID) != null) {
                                    if (reseedEnforced || (!TimeUtils
                                            .equalDate(flightTimes.getProjectedDepartureTime(requestID),
                                                    flightTimes
                                                            .getPreviousProjectedDepartureTime(requestID)))) {
                                        departure = true;
                                    }
                                }
                                if (flightTimes.getProjectedDepartureTime_Controlled(requestID)
                                        != null) {
                                    if (reseedEnforced || (!TimeUtils.equalDate(
                                            flightTimes.getProjectedDepartureTime_Controlled(requestID),
                                            flightTimes.getPreviousProjectedDepartureTime_Controlled(
                                                    requestID)))) {
                                        departureControlled = true;
                                    }
                                }
                                // create problem type as inactive/active depending of flight departure status
                                handleProblemType(flightTimes, departure, departureControlled, true,
                                        flightStatus);

                                // create problem type as inactive/active depending of flight arrival status
                                if (flightTimes.getProjectedArrivalTime(requestID) != null) {
                                    if (reseedEnforced || (!TimeUtils
                                            .equalDate(flightTimes.getProjectedArrivalTime(requestID),
                                                    flightTimes
                                                            .getPreviousProjectedArrivalTime(requestID)))) {
                                        arrival = true;
                                    }
                                }
                                if (flightTimes.getProjectedArrivalTime_Controlled(requestID)
                                        != null) {
                                    if (reseedEnforced || (!TimeUtils.equalDate(
                                            flightTimes.getProjectedArrivalTime_Controlled(requestID),
                                            flightTimes.getPreviousProjectedArrivalTime_controlled(
                                                    requestID)))) {
                                        arrivalControlled = true;
                                    }
                                }
                                // create problem type as inactive/active depending of flight arrival status
                                handleProblemType(flightTimes, arrival, arrivalControlled, false,
                                        flightStatus);
                            }

                            LOG.debug(" requestID :" + requestID + " - " + flightKey.toShortString()
                                    + " operationFlightTimes about to be saved to cache :"
                                    + operationFlightTimes);
                            if(RequestType.SWAP_WHATIF.equals(requestType)){
                                if(requestID.equals("0"))
                                {
                                    LOG.debug(" requestID :" + requestID + " - " + flightKey.toShortString()
                                            + " operationFlightTimes about to be saved to cache before :"
                                            + operationFlightTimes+ " "+operationFlightTimes.getFlightKey());
                                }
                                else if(operationFlightTimes.getSnapshots()!=null && operationFlightTimes.getSnapshots().get(requestID)!=null){
                                    LOG.debug(" requestID :" + requestID + " - " + flightKey.toShortString()
                                            + " operationFlightTimes about to be saved to cache after :"
                                            + operationFlightTimes.getSnapshots().get(requestID) +" "+operationFlightTimes.getFlightKey());
                                }
                                else{
                                    LOG.debug(" requestID :" + requestID + " - " + flightKey.toShortString()
                                            + " operationFlightTimes about to be saved to cache after snapshot map for requestId is null :"
                                            + operationFlightTimes.getFlightKey());
                                }
                            }

                            Map<String, Object> returnMapValue = computeChangeSetFromFlightTimes(
                                    operationFlightTimes, reseedEnforced, requestID,requestType);
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(" requestID :" + requestID + " - " + flightKey.toShortString()
                                        + " calculatePropagatedFlightTimes():: Done calculating PTD and PTA");
                                LOG.debug(" requestID :" + requestID + flightKey.toShortString()
                                        + " calculatePropagatedFlightTimes():: Checking if data from changeset is provided: "
                                        + (returnMapValue != null
                                        && returnMapValue.get(EVENT_CHANGE_CONSTANT) != null));
                            }

                            if (returnMapValue != null
                                    && returnMapValue.get(EVENT_CHANGE_CONSTANT) != null) {

                                if (LOG.isDebugEnabled()) {
                                    LOG.debug(" requestID :" + requestID + " - " + flightKey.toShortString() +
                                            " calculatePropagatedFlightTimes():: Attempting to publish projected times");
                                }
                                if (returnMapValue.get(FLIGHT_TIMES_CONSTANT) != null) {
                                    publishFlightTime(flightTimes,
                                            (ChangeSet) returnMapValue.get(FLIGHT_TIMES_CONSTANT),
                                            calculatorService);
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debug(" requestID :" + requestID
                                                + "calculatePropagatedFlightTimes():: Done publishing projected times: "
                                                + flightTimes);

                                        if(RequestType.SWAP_WHATIF.equals(requestType)) {
                                            if (requestID.equals("0")) {
                                                LOG.debug(" requestID :" + requestID
                                                        + "calculatePropagatedFlightTimes():: Done publishing projected times: before "
                                                        + flightTimes+" "+ flightTimes.getFlightKey());
                                            }
                                            else if(flightTimes.getSnapshots()!=null && flightTimes.getSnapshots().get(requestID)!=null){
                                                LOG.debug(" requestID :" + requestID
                                                        + "calculatePropagatedFlightTimes():: Done publishing projected times: after "
                                                        + flightTimes.getSnapshots().get(requestID)+" "+ flightTimes.getFlightKey());
                                            }
                                            else{
                                               LOG.debug("requestID :" + requestID
                                                        + "calculatePropagatedFlightTimes():: Done publishing projected times: after snapshot map for requestId is null "
                                                       + flightTimes.getFlightKey());
                                            }
                                        }
                                    }
                                }

                                if (eventRootFlightKey == null) {
                                    eventRootFlightKey = flightKey;
                                }
                                if (!reseedEnforced) {
                                    publishEvents(operationFlightTimes,
                                            (List<LKAEventType>) returnMapValue
                                                    .get(EVENT_CHANGE_CONSTANT), calculatorService, null,
                                            propagationEngineEvent,
                                            propagationEngineEventPayload.getPreviousFlightKey());
                                    nextLegPropagtionContinue = checkPEContinuedforNextFlight(
                                            operationFlightTimes, eventRootFlightKey,
                                            propagationEngineEventPayload.getNextLegFlightKeyList(),requestID,requestType
                                            ,propagationEngineEventPayload.getPreviousFlightKey(),propagationEngineEventPayload.getFlightTimesMap());
                                }

                                if (nextLegPropagtionContinue) {

                                    if (propagationEngineEventPayload.getNextLegFlightKeyList()
                                            != null && !propagationEngineEventPayload
                                            .getNextLegFlightKeyList().isEmpty()) {
                                        int childrenCount = 1;
                                        for (String nextLegFlightKey_Resource : propagationEngineEventPayload
                                                .getNextLegFlightKeyList()) {
                                            if (nextLegFlightKey_Resource != null) {

//												 synchronized (transLockValue) {
                                                if (LOG.isDebugEnabled()) {
                                                    LOG.debug(" requestID :" + requestID
                                                            + "PE_CONTINUE writing start for batchId,eventRootFlightKey,nextflightKey"
                                                            + batchId + "," + fltKeyLock + ","
                                                            + nextLegFlightKey_Resource);
                                                }

//													 boolean operationStatus=propagationSpaceRepository.processFlightEventCounterUpdateChangeSet(batchId,eventRootFlightKey, originFlightKey_Str);
//												 if(operationStatus)
//												 {

                                                String[] splitedValues = nextLegFlightKey_Resource
                                                        .split(
                                                                PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER);
                                                LKAFlightKey lkaFlightKey_nextLeg = new LKAFlightKey(
                                                        splitedValues[0]);
                                                String resourcetype = splitedValues[1];
                                                String[] linkedResourceValue = new String[1];
                                                if (FlightEventResourceType.EQUIPMENT.name()
                                                        .equalsIgnoreCase(resourcetype)) {
                                                    linkedResourceValue[0] = FlightEventResourceType.EQUIPMENT
                                                            .name();
                                                } else if (FlightEventResourceType.CREW.name()
                                                        .equalsIgnoreCase(resourcetype)) {
                                                    linkedResourceValue[0] = FlightEventResourceType.CREW
                                                            .name();
                                                }
                                                LKAFlightEvent propagationEngineFollowupEvent = pe_EventFactory
                                                        .createEvents(LKAEventType.PE_CONTINUED,
                                                                lkaFlightKey_nextLeg, eventRootFlightKey,
                                                                propagationEngineEvent,
                                                                linkedResourceValue);
                                                if (propagationEngineFollowupEvent != null) {
                                                    ((PE_ContinutationEvent) propagationEngineFollowupEvent).setLastVisitedFlightKey(flightKey);
                                                    ((PE_ContinutationEvent) propagationEngineFollowupEvent).setVisitedFlightKeysSet(visitedFlightKeysSet);
                                                    ((PE_ContinutationEvent) propagationEngineFollowupEvent).setInitiatedEventType(propagationEngineEvent.getEventType());
                                                    if(propagationEngineEvent.getRequestType() != null  &&
                                                            (RequestType.HEATWHATIF.name().equals(propagationEngineEvent.getRequestType().name()) ||
                                                                    RequestType.HEATWHATIF_NOACTION.name().equals(propagationEngineEvent.getRequestType().name()) ||
                                                                    RequestType.SWAP_WHATIF.name().equals(propagationEngineEvent.getRequestType().name()))) {
                                                        propagationEngineFollowupEvent.setRouteID(propagationEngineEvent.getRouteID());
                                                        propagationEngineFollowupEvent.setRequestType(propagationEngineEvent.getRequestType());
                                                        propagationEngineFollowupEvent.setPreProcessorId(propagationEngineEvent.getPreProcessorId());
                                                    }
                                                    if (LOG.isDebugEnabled()) {
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Propagation engine event:");
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event type: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getEventType());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event app_id: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getApp_id());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event batchId: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getBatchId());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event Id: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getId());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event routeId: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getRouteID());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event preProcessorId: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getPreProcessorId());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event flightKey: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getFlightKey());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event previousFlightKey: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getPreviousFlightKey());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event requestID: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getRequestID());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event requestType: "
                                                                        + propagationEngineFollowupEvent
                                                                        .getRequestType());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event LastVisitedFlightKey: "
                                                                        + ((PE_ContinutationEvent) propagationEngineFollowupEvent)
                                                                        .getLastVisitedFlightKey());
                                                        LOG.debug(
                                                                "calculatePropagatedFlightTimes():: Working with event linkedResourceValue: "
                                                                        + ((PE_ContinutationEvent) propagationEngineFollowupEvent)
                                                                        .getResourceType());
                                                        EventStatusDetails eventDetails = propagationEngineFollowupEvent
                                                                .getEventStatusDetails();
                                                        if (eventDetails != null) {
                                                            LOG.debug(
                                                                    "calculatePropagatedFlightTimes():: Working with event.eventStatusDetails message: "
                                                                            + eventDetails.getMessage());
                                                            LOG.debug(
                                                                    "calculatePropagatedFlightTimes():: Working with event.eventStatusDetails status: "
                                                                            + eventDetails
                                                                            .getEventStatus());
                                                        }
                                                    }
                                                    List<LKAFlightEvent> propagationEngineFollowupEventList = new ArrayList<LKAFlightEvent>();
                                                    propagationEngineFollowupEventList
                                                            .add(propagationEngineFollowupEvent);
                                                    boolean pe_continued_Scuccess = calculatorService
                                                            .publishEvents2Cache(
                                                                    propagationEngineFollowupEventList,
                                                                    Boolean.TRUE);

                                                    if (pe_continued_Scuccess) {
                                                        if (propagationTrackerRecord_CS == null) {
                                                            propagationTrackerRecord_CS = new ChangeSet();
                                                        }
                                                        LOG.info("Increasing count for eventRootFlightKey " + eventRootFlightKey + " flightKey " + flightKey);
                                                        propagationTrackerRecord_CS
                                                                .increment("pendingChildrenCount", 1);
                                                        toBeProcessedFlightList.add(
                                                                flightKey.generateFlightKeyString()
                                                                        + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER
                                                                        + nextLegFlightKey_Resource +
                                                                        PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER
                                                                        + childrenCount);
                                                        childrenCount++;
                                                    } else {
                                                        if (LOG.isErrorEnabled()) {
                                                            LOG.error(
                                                                    "Unable to write PE_CONTINUE for "
                                                                            + propagationEngineFollowupEvent
                                                                            .toString());
                                                        }
                                                    }
//											 }
                                                }
//										 }
//												}											 
                                            }
                                        }

                                        //TODO: Perform changeset on flightProcessedMap of PropagationTracker object based on eventRootFlightKey
                                        // Insert flightKey in map if does not exist.
                                        //

                                    }
                                }

                                if (LOG.isDebugEnabled()) {
                                    LOG.debug(" requestID :" + requestID
                                            + "calculatePropagatedFlightTimes():: Done publishing events");
                                }
                            }
                            //Change for "PE_CALCULATED" event to be created for ETD_Post
                            if(propagationEngineEvent.getRequestType() != null  &&
                                    (RequestType.HEATWHATIF.name().equals(propagationEngineEvent.getRequestType().name()) ||
                                            RequestType.HEATWHATIF_NOACTION.name().equals(propagationEngineEvent.getRequestType().name())
                                    || RequestType.SWAP_WHATIF.name().equals(propagationEngineEvent.getRequestType().name()))) {
                                LOG.debug("Skipping the PE_CALCULATED event process for HEAT,SWAP WHAT IF workflow!");
                            }else {
                                try {
                                    LKAFlightEvent pe_CalculatedEvent = pe_EventFactory.createEvents(LKAEventType.PE_CALCULATED, propagationEngineEvent.getFlightKey(), eventRootFlightKey, propagationEngineEvent, propagationEngineEventPayload.getPreviousFlightKey());
                                    List<LKAFlightEvent> propagationEngineFollowupEventList = new ArrayList<LKAFlightEvent>();
                                    propagationEngineFollowupEventList.add(pe_CalculatedEvent);
                                    boolean pe_calculated_Scuccess = calculatorService.publishEvents2Cache(propagationEngineFollowupEventList, Boolean.FALSE);
                                    if(LOG.isDebugEnabled())
                                    {
                                        LOG.debug("Status of the PE_CALCULATED event write is :"+pe_calculated_Scuccess +" for event : "+ pe_CalculatedEvent);

                                    }
                                }
                                catch(Exception ex) {
                                    LOG.warn("Unable to write PE_CALCULATED_event for flightKey : "+propagationEngineEvent.getFlightKey());
                                }
                            }
                            transactionSuccessful = Boolean.TRUE;
                        } catch (Exception ex) {
                            if (LOG.isErrorEnabled()) {
                                LOG.error(" requestID :" + requestID
                                        + "Propagation event handling error for event:"
                                        + propagationEngineEvent.toString() + " with details: ", ex);
                            }
                            processedError = true;
                            propagationEngineEvent.getEventStatusDetails()
                                    .setMessage(CommonUtility.getExceptionStackTrace(ex));
                            CommonUtility.handleLKAException(
                                    propagationSpaceRepository.repoConn.getGigaSpace(),
                                    propagationEngineEvent, ex,
                                    "Error inside calculatePropagatedFlightTimes method");
                        }

                        propagationEngineEvent.getEventStatusDetails()
                                .setEventStatus(EventStatus.DEBUG);
                    } else {
                        propagationEngineEvent.getEventStatusDetails().setMessage(errorReasonMsg);
                        processedError = true;
                    }

                } else {
                    propagationEngineEvent.getEventStatusDetails()
                            .setMessage("Event payload is null");
                }

                if (!reseedEnforced) {
                    if (eventRootFlightKey == null) {
                        eventRootFlightKey = flightKey;
                    }
                    if (eventLinkResourceType != null) {
                        processedFlightList.add((lastVisitedFlightKey != null ? lastVisitedFlightKey
                                .generateFlightKeyString() : "")
                                + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER + flightKey
                                .generateFlightKeyString()
                                + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER
                                + eventLinkResourceType);
                    } else {
                        processedFlightList.add((lastVisitedFlightKey != null ? lastVisitedFlightKey
                                .generateFlightKeyString() : "")
                                + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER + flightKey
                                .generateFlightKeyString()
                                + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER
                                + propagationEngineEvent.getEventType());
                    }
                    propagationSpaceRepository
                            .updateChildrenEventsPropagationTracker(batchId, eventRootFlightKey,
                                    toBeProcessedFlightList, processedFlightList, processedError);
                    if (propagationTrackerRecord_CS == null) {
                        propagationTrackerRecord_CS = new ChangeSet();
                    }
                    LOG.info("Decreasing count for eventRootFlightKey " + eventRootFlightKey + " flightKey " + flightKey);
                    propagationTrackerRecord_CS.decrement("pendingChildrenCount", 1);
                    propagationSpaceRepository.repoConn.getGigaSpace().change(
                            new SQLQuery<PropagationTrackerRecord>(PropagationTrackerRecord.class,
                                    " batchId=? and originFlightKey=?")
                                    .setParameter(1, batchId)
                                    .setParameter(2, eventRootFlightKey), propagationTrackerRecord_CS);

//						}
                    //This updates arraylist for tracker record

//					}
                }

//				if(nextFlightKey == null && !reseedEnforced) {
//					if(LOG.isDebugEnabled()) {
//						LOG.debug("Next flight is " + nextFlightKey);
//					}				
//					
//					
//				}
                if (!transactionSuccessful) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(" requestID :" + requestID + "Transaction not successful: "
                                + eventRootFlightKey + " for flightKey: " + flightKey + ". "
                                + propagationEngineEvent);
                    }
                    propagationEngineEvent.getEventStatusDetails()
                            .setEventStatus(EventStatus.EXCEPTION);
                }
                propagationEngineCalculationValues
                        .setPreProcessorId(propagationEngineEvent.getPreProcessorId());
                propagationEngineCalculationValues.setPayload(propagationEngineEventPayload);
                propagationEngineCalculationValues.setEventType(LKAEventType.valueOf(eventType));
                propagationEngineCalculationValues
                        .setRequestType(propagationEngineEvent.getRequestType());
                propagationEngineCalculationValues.setRouteId(propagationEngineEvent.getRouteID());
                propagationEngineCalculationValues.setBatchId(batchId);
                propagationEngineCalculationValues
                        .setRequestId(propagationEngineEvent.getRequestID());
                propagationEngineCalculationValues.setOriginFlightKey(eventRootFlightKey);
                propagationEngineCalculationValues
                        .setEventStatusDetails(propagationEngineEvent.getEventStatusDetails());
                propagationEngineCalculationValues.setTimeStamp(new Date());
                propagationEngineCalculationValues.setReseedForce(reseedEnforced);
                savePropagationDebug(propagationEngineCalculationValues);
                //propagationSpaceRepository.repoConn.getGigaSpace().write(propagationEngineCalculationValues);
            }

        }
        return propagationEngineCalculationValues;
    }

    private boolean publishFlightTime(FlightTimes flightTimes, ChangeSet changeSet,
                                      CalculatorService calculatorService) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("publishFlightTime():: " + calculatorService);
        }
        return calculatorService.updateFlightTimesChangeSet(flightTimes, changeSet);
    }

    private boolean publishEvents(FlightTimes flightTimes, List<LKAEventType> evenTypeChangeList,
                                  CalculatorService calculatorService,
                                  List<LKAFlightEvent> propagationContinueEventList,
                                  PropagationEngineEvent propagationEngineEvent, LKAFlightKey previousEquipmentFlightKey) {
        boolean statusSuccess = true;
        List<LKAFlightEvent> flightTimesChangeEventList = new ArrayList<LKAFlightEvent>();

        if (flightTimes != null && flightTimes.getFlightKey() != null) {
            String flightKey_current = flightTimes.getFlightKey().toString();
            if (evenTypeChangeList != null && !evenTypeChangeList.isEmpty()) {
                try {

                    LKAEventType event;
                    Iterator<LKAEventType> changeListIterator = evenTypeChangeList.iterator();
                    PE_EventFactory pe_EventFactory = new PE_EventFactory();
                    while (changeListIterator.hasNext()) {
                        event = changeListIterator.next();
                        if (LOG.isInfoEnabled()) {
                            LOG.info("publishEvents():: event: " + event + " requestType="
                                    + propagationEngineEvent.getRequestType()
                                    + " for flightKey: " + flightKey_current);
                        }
                        switch (event) {
                            case PTA:
                                flightTimesChangeEventList.add(pe_EventFactory
                                        .createEvents(LKAEventType.PTA, flightTimes,
                                                propagationEngineEvent));
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("publishEvents():: for PTA executed successfully.");
                                }
                                break;
                            case PTD:
                                LKAFlightEvent ptdEvent = pe_EventFactory
                                        .createEvents(LKAEventType.PTD, flightTimes,
                                                propagationEngineEvent);
                                ptdEvent.setPreviousFlightKey(previousEquipmentFlightKey);
                                flightTimesChangeEventList.add(ptdEvent);
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("publishEvents():: for PTD executed successfully.");
                                }
                                break;
                            case PTA_CONTROL:
                                flightTimesChangeEventList.add(pe_EventFactory
                                        .createEvents(LKAEventType.PTA_CONTROL, flightTimes,
                                                propagationEngineEvent));
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug(
                                            "publishEvents():: for PTA_CONTROL executed successfully.");
                                }
                                break;
                            case PTD_CONTROL:
                                flightTimesChangeEventList.add(pe_EventFactory
                                        .createEvents(LKAEventType.PTD_CONTROL, flightTimes,
                                                propagationEngineEvent));
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug(
                                            "publishEvents():: for PTD_CONTROL executed successfully.");
                                }
                            case PMTD:
                                LKAFlightEvent etrEvent = pe_EventFactory
                                        .createEvents(LKAEventType.PMTD, flightTimes,
                                                propagationEngineEvent);
                                etrEvent.setPreviousFlightKey(previousEquipmentFlightKey);
                                flightTimesChangeEventList.add(etrEvent);
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("publishEvents():: for ETR executed successfully.");
                                }
                                break;
                            case PLMTD:
                                LKAFlightEvent etrEventByPlmtd = pe_EventFactory
                                        .createEvents(LKAEventType.PLMTD, flightTimes,
                                                propagationEngineEvent);
                                etrEventByPlmtd.setPreviousFlightKey(previousEquipmentFlightKey);
                                flightTimesChangeEventList.add(etrEventByPlmtd);
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("publishEvents():: for PLMTD executed successfully.");
                                }
                                break;
                            case PLMTA:
                                flightTimesChangeEventList.add(pe_EventFactory
                                        .createEvents(LKAEventType.PLMTA, flightTimes,
                                                propagationEngineEvent));
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("publishEvents():: for PLMTA executed successfully.");
                                }
                                break;
                            default:
                                break;
                        }
                    }
                    //Write Times event separate
                    if (!flightTimesChangeEventList.isEmpty()) {
                        statusSuccess = calculatorService
                                .publishEvents2Cache(flightTimesChangeEventList);
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("publishEvents():: done publishing flight times.");
                        }
                    }
                } catch (Exception ex) {
                    statusSuccess = false;
                    if (LOG.isErrorEnabled()) {
                        LOG.error("publishEvents():: ERRORCODE: "
                                + PropagationEngineErrorCode.ERROR_CODE_1100 + " Description: "
                                + PropagationEngineErrorCode.ERROR_CODE_1100_DESC + flightKey_current);
                    }
                }
            }
        }
        //Write continue/complete event.
        else if (propagationContinueEventList != null && !propagationContinueEventList.isEmpty()) {
            flightTimesChangeEventList.clear();
            flightTimesChangeEventList.addAll(propagationContinueEventList);
            try {
                statusSuccess = calculatorService
                        .publishEvents2Cache(flightTimesChangeEventList, Boolean.TRUE);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("publishEvents():: done publishing data, continue for event.");
                }
            } catch (Exception ex) {
                statusSuccess = false;
                if (LOG.isErrorEnabled()) {
                    LOG.error(
                            "publishEvents():: ERRORCODE: " + PropagationEngineErrorCode.ERROR_CODE_1100
                                    + " Description: " + PropagationEngineErrorCode.ERROR_CODE_1100_DESC
                                    + " " + propagationContinueEventList);
                }
            }
        }

        return statusSuccess;
    }

    private List<FlightResourceVO> calculateAllResourceReadyTimes(
            PropagationEngineEventPayload propagationEngineEventPayload, String requestID, LKAEventType eventType, RequestType requestType) {
        FlightTimes inboundflightTimes = null;
        List<FlightResourceVO> flightResourceVOList = new ArrayList<FlightResourceVO>();
        if (LOG.isDebugEnabled()) {
            if (propagationEngineEventPayload != null) {
                LOG.debug("calculateAllResourceReadyTimes():: Checking for previous flight key"
                        + propagationEngineEventPayload.getPreviousFlightKey());
            }
        }
        LKAFlightKey equipmentPreviousFlightKey = propagationEngineEventPayload
                .getPreviousFlightKey();

        Map<LKAFlightKey, FlightTimes> fltTimeMap = propagationEngineEventPayload
                .getFlightTimesMap();
        if (fltTimeMap != null & !fltTimeMap.isEmpty()) {
            inboundflightTimes = fltTimeMap.get(equipmentPreviousFlightKey);
        }
//        if(LKAEventType.PE_ETR.equals(eventType)){
//            FlightTimes flightTimes = propagationEngineEventPayload.getCurrentFlightTimes();
//            int airCraftTurnTime = (flightTimes
//                    .getObjectiveGroundTimeMinutes(requestID) != null)
//                    ? flightTimes
//                    .getObjectiveGroundTimeMinutes(requestID) : 0;
//            long mntcReadyTime = flightTimes.getProjectedMntcDepartureTime().getTime() + airCraftTurnTime * minute2millisecond;
//            LOG.debug("calculateAllResourceReadyTimes for PE_ETR for flightkey:{}",propagationEngineEventPayload.getCurrentFlightKey());
//            flightResourceVOList.add(getETRFlightResource(propagationEngineEventPayload, mntcReadyTime));
//            //return flightResourceVOList;
//        }
        //logic for handling non ots to ots changes and non ots to ots changes for etr propagation
        LOG.info("Request Type:{} , event type:{}",requestType,eventType);
//        if(LKAEventType.PE_TAIL.equals(eventType) || RequestType.SWAP_WHATIF.equals(requestType) && LKAEventType.PE_ETD.equals(eventType)){
//            String tailNumber = propagationEngineEventPayload.getTailNumber();
//            String airlineCode = propagationEngineEventPayload.getCurrentFlightKey().getAirlineCode();
//            String snapshotId=propagationEngineEventPayload.getCurrentFlightKey().getSnapshotId();
//            FlightTimes currentFlightTimes = propagationEngineEventPayload.getCurrentFlightTimes();
//            LKAFlightKey currentFlightKey = propagationEngineEventPayload.getCurrentFlightKey();
//
//            String[] TAIL_PROJECTION_COLUMNS={"airlineCode","tailNumber","snapshotId","otsInd","otsSta","estimatedReturnToServiceTime","returnToServiceTime"};
//            LOG.debug("Getting the tail object for airlineCode:{} tailNumber:{} snapshotId:{}",airlineCode,tailNumber,snapshotId);
//            try {
//                Tail tail = flightRepository.getTail(tailNumber, airlineCode, snapshotId, TAIL_PROJECTION_COLUMNS);
//                if(tail!=null && BooleanUtils.isTrue(tail.getOtsInd()) && inboundflightTimes!=null){
//                    if(tail.getEstimatedReturnToServiceTime()!=null || tail.getReturnToServiceTime()!=null){
//                        //check for ots sta , inbound flight arrival station , current flight departure station
//                        String otsSta = tail.getOtsSta();
//                        if(tail.getOtsSta()!=null) {
//                            if (currentFlightKey.getDepartureStation().equals(inboundflightTimes.getArrivalStation()) &&
//                                    otsSta.equals(inboundflightTimes.getArrivalStation())) {
//                                if(inboundflightTimes.getProjectedArrivalTime(requestID)!=null && inboundflightTimes.getProjectedArrivalTime(requestID).before(tail.getEstimatedReturnToServiceTime())){
//                                    int airCraftTurnTime = (currentFlightTimes
//                                            .getObjectiveGroundTimeMinutes(requestID) != null)
//                                            ? currentFlightTimes
//                                            .getObjectiveGroundTimeMinutes(requestID) : 0;
//                                    long mntcReadyTime = (tail.getReturnToServiceTime()!=null?tail.getReturnToServiceTime():tail.getEstimatedReturnToServiceTime()).getTime()+ airCraftTurnTime * minute2millisecond;
//                                    flightResourceVOList.add(getETRFlightResource(propagationEngineEventPayload, mntcReadyTime));
//                                }
//                            }
//                        }
//
//                    }
//                }
//            } catch (Exception e) {
//                LOG.error("Error occurred while fetching tail object for airlineCode:{} tailNumber:{} snapshotId:{}",airlineCode,tailNumber,snapshotId);
//            }
//            //return flightResourceVOList;
//
//        }
        FlightTimes currentFlightTimes = propagationEngineEventPayload.getCurrentFlightTimes();
        boolean eventCheck = LKAEventType.PE_CONTINUED.equals(eventType);
        long resourceReadyTimeTail=0L;
        Tail currentTail = null;
        String tailNumber = propagationEngineEventPayload.getTailNumber();
        String airlineCode = propagationEngineEventPayload.getCurrentFlightKey().getAirlineCode();
        String snapshotId=propagationEngineEventPayload.getCurrentFlightKey().getSnapshotId();
        LKAFlightKey currentFlightKey = propagationEngineEventPayload.getCurrentFlightKey();
        int airCraftTurnTime = (currentFlightTimes
                .getObjectiveGroundTimeMinutes(requestID) != null)
                ? propagationEngineEventPayload.getCurrentFlightTimes()
                .getObjectiveGroundTimeMinutes(requestID) : 0;
        //etr change
        if(RequestType.SWAP_WHATIF.equals(requestType) && LKAEventType.PE_TAIL.equals(eventType)){
            LOG.info("Received PE_TAIL for Swap What If "+currentFlightKey+" for tail Number "+tailNumber);
            try {
                currentTail = flightRepository.getTailForPE(airlineCode, tailNumber, snapshotId,
                        new String[]{"airlineCode", "tailNumber", "snapshotId", "otsInd", "otsSta", "estimatedReturnToServiceTime", "returnToServiceTime", "isDecisionETR"});

            } catch (Exception e) {
                LOG.error("Error occurred while fetching tail object for airlineCode:{} tailNumber:{} snapshotId:{}", airlineCode, tailNumber, snapshotId);
            }
            resourceReadyTimeTail = calculateResourceReadyTimeByTail(currentFlightKey,currentTail,airCraftTurnTime,tailNumber,requestID);
        }


        if (inboundflightTimes != null) {

            int airCraftTurnTime_calculated = calculatorService
                    .calculateTurnTime(propagationEngineEventPayload);

            long equipmentReadyTime = calculateResourceReadyTime(inboundflightTimes,
                    airCraftTurnTime, Boolean.FALSE, requestID, false);
            long equipmentMntcReadyTime = calculateResourceReadyTime(inboundflightTimes,
                    airCraftTurnTime, Boolean.FALSE, requestID, true);
            long equipmentReadyTimeWithControl = calculateResourceReadyTime(inboundflightTimes,
                    airCraftTurnTime, Boolean.TRUE, requestID, false);
            long equipmentReadyTimeWithLatest=calculateResourceReadyTime(inboundflightTimes,
                    airCraftTurnTime,requestID,requestType,eventCheck);
//			FlightResourceVO flightResourceVO = new FlightResourceVO(inboundflightTimes.getFlightKey().toString(),FlightEventResourceType.EQUIPMENT,equipmentReadyTime,equipmentReadyTimeWithControl);
            FlightResourceVO flightResourceVO = new FlightResourceVO(
                    propagationEngineEventPayload.getCurrentFlightKey().generateNonSnapshotFlightKey(),
                    inboundflightTimes.getFlightKey().generateNonSnapshotFlightKey(),
                    inboundflightTimes.getFlightKey().toString(), FlightEventResourceType.EQUIPMENT,
                    equipmentReadyTime, equipmentReadyTimeWithControl, "AC",
                    propagationEngineEventPayload.getTailNumber());
            flightResourceVO.setCalculatedEMOGT(airCraftTurnTime);
            flightResourceVO.setResourceMntcReadyTime(equipmentMntcReadyTime);
            //check to update resource Ready Values for ETR
            if(RequestType.SWAP_WHATIF.equals(requestType)) {
                LOG.info("current flight Key "+currentFlightKey+" resource Ready Time based on Tail "+ resourceReadyTimeTail+" resource Ready Based On Latest "
                        +equipmentReadyTimeWithLatest+" tail number: "+tailNumber+" event Type: "+eventType);
            }
            if(resourceReadyTimeTail>equipmentReadyTimeWithLatest){
                equipmentReadyTimeWithLatest = resourceReadyTimeTail;
            }
            equipmentReadyTimeWithLatest = (RequestType.SWAP_WHATIF.equals(requestType))?equipmentReadyTimeWithLatest:0;
            flightResourceVO.setResourceReadyTimeWithLatest(equipmentReadyTimeWithLatest);
            flightResourceVOList.add(flightResourceVO);
            //handle the case for ots tails receiving etd post mntnc ready time is populated
            /* Will Review the projected maintenance after SWAP GO LIVE ,05/15/2025
            if(TimeUtils.equalDate(inboundflightTimes.getProjectedDepartureTime(requestID), inboundflightTimes.getProjectedMntcDepartureTime(requestID))) {
                Tail currentTail = null;
                String tailNumber = propagationEngineEventPayload.getTailNumber();
                String airlineCode = propagationEngineEventPayload.getCurrentFlightKey().getAirlineCode();
                String snapshotId = propagationEngineEventPayload.getCurrentFlightKey().getSnapshotId();
                try {
                    currentTail = flightRepository.getTailForPE(airlineCode, tailNumber, snapshotId,
                            new String[]{"airlineCode", "tailNumber", "snapshotId", "otsInd", "otsSta", "estimatedReturnToServiceTime", "returnToServiceTime"});
                } catch (Exception e) {
                    LOG.error("Error occurred while fetching tail object for airlineCode:{} tailNumber:{} snapshotId:{}", airlineCode, tailNumber, snapshotId);
                }



                if (!(LKAEventType.PE_ETR.equals(eventType) || LKAEventType.PE_TAIL.equals(eventType) || (RequestType.SWAP_WHATIF.equals(requestType) && LKAEventType.PE_ETD.equals(eventType)))) {
                    if (currentFlightTimes != null && currentFlightTimes.getProjectedMntcDepartureTime(requestID) != null && currentFlightTimes.getProjectedDepartureTime(requestID) != null &&
                            currentFlightTimes.getProjectedMntcDepartureTime(requestID).after(currentFlightTimes.getProjectedDepartureTime(requestID))) {
                        //Add ETR Resource Ready time when its available
                        long mntcReadyTime = currentFlightTimes.getProjectedMntcDepartureTime().getTime();
                        if (currentTail != null && BooleanUtils.isTrue(currentTail.getOtsInd())) {
                            flightResourceVOList.add(getETRFlightResource(propagationEngineEventPayload, mntcReadyTime));
                        }
                    }
                } else if (LKAEventType.PE_ETR.equals(eventType)) {
                    FlightTimes flightTimes = propagationEngineEventPayload.getCurrentFlightTimes();
                    long mntcReadyTime = flightTimes.getProjectedMntcDepartureTime().getTime() + airCraftTurnTime * minute2millisecond;
                    LOG.debug("calculateAllResourceReadyTimes for PE_ETR for flightkey:{}", propagationEngineEventPayload.getCurrentFlightKey());
                    flightResourceVOList.add(getETRFlightResource(propagationEngineEventPayload, mntcReadyTime));
                }

                else if(LKAEventType.PE_TAIL.equals(eventType) || (RequestType.SWAP_WHATIF.equals(requestType) && LKAEventType.PE_ETD.equals(eventType))){
                    if(currentTail!=null && BooleanUtils.isTrue(currentTail.getOtsInd())){
                        if(currentTail.getEstimatedReturnToServiceTime()!=null || currentTail.getReturnToServiceTime()!=null){
                            //check for ots sta , inbound flight arrival station , current flight departure station
                            String otsSta = currentTail.getOtsSta();
                            if(currentTail.getOtsSta()!=null) {
                                if (currentFlightKey.getDepartureStation().equals(inboundflightTimes.getArrivalStation()) &&
                                        otsSta.equals(inboundflightTimes.getArrivalStation())) {
                                    if(inboundflightTimes.getProjectedArrivalTime(requestID)!=null && inboundflightTimes.getProjectedArrivalTime(requestID).before(currentTail.getEstimatedReturnToServiceTime())){
                                        long mntcReadyTime = (currentTail.getReturnToServiceTime()!=null?currentTail.getReturnToServiceTime():currentTail.getEstimatedReturnToServiceTime()).getTime()+ airCraftTurnTime * minute2millisecond;
                                        flightResourceVOList.add(getETRFlightResource(propagationEngineEventPayload, mntcReadyTime));
                                    }
                                }
                            }

                        }
                    }
                }
            }
            */
            LOG.info("calculateAllResourceReadyTimes():: Calculated aircraft turn time: "
                    + airCraftTurnTime_calculated + ", FOS MOGT :" + airCraftTurnTime
                    + ",` using the following data: " + propagationEngineEventPayload);
            if (LOG.isDebugEnabled()) {
                LOG.debug("calculateAllResourceReadyTimes():: Calculated equipment ready time: "
                        + equipmentReadyTime
                        + ", using inbound flight times: [LTA: " + inboundflightTimes
                        .getLatestArrivalTime(requestID) + ","
                        + "PCTA: " + inboundflightTimes.getProjectedArrivalTime_Controlled(requestID)
                        + ","
                        + "Prev PTA: " + inboundflightTimes.getProjectedArrivalTime(requestID) + "]"
                        + " and aircraft turn time: " + airCraftTurnTime);
                LOG.debug(
                        "calculateAllResourceReadyTimes():: Calculated equipment ready time with control: "
                                + equipmentReadyTimeWithControl + ", using inbound flight time: [LTA: "
                                + inboundflightTimes.getLatestArrivalTime(requestID) + ","
                                + "PCTA: " + inboundflightTimes
                                .getProjectedArrivalTime_Controlled(requestID) + ","
                                + "Prev PTA: " + inboundflightTimes.getProjectedArrivalTime(requestID) + "]"
                                + " and aircraft turn time: " + airCraftTurnTime);

                LOG.debug("calculateAllResourceReadyTimes():: Calculated equipment ready time for flightKey:"+ currentFlightKey.toShortString() +" with latest: "
                         +equipmentReadyTimeWithLatest+", using inbound flight time: [LTA: "
                         +inboundflightTimes.getLatestArrivalTime(requestID)+" , "
                         +" PLTA: "+ inboundflightTimes.getProjectedLatestMntcArrivalTime(requestID) +" ], "
                         +" Tail ETR: "+ ((currentTail!=null && BooleanUtils.isTrue(currentTail.getOtsInd()))?currentTail.getEstimatedReturnToServiceTime():" Not Applicable ")
                                +" is a Decision ETR: "+ (currentTail!=null && BooleanUtils.isTrue(currentTail.getIsDecisionETR()))
                         );

                if(RequestType.SWAP_WHATIF.equals(requestType)) {
                    LOG.debug(
                            "calculateAllResourceReadyTimes() when inbound equipment is not null:: Flight resource VO: " + flightResourceVO + " for requestId " + requestID);
                }
            }
        }
        else{
            if(currentTail!=null && BooleanUtils.isTrue(currentTail.getOtsInd())) {
                long equipmentReadyTimeWithLatest =  calculateResourceReadyTimeByTail(currentFlightKey, currentTail,airCraftTurnTime,tailNumber,requestID);
                LOG.debug("calculateAllResourceReadyTimes() for flightKey"+currentFlightKey.toShortString() +" :: Calculated equipment ready time with latest: "
                        + equipmentReadyTimeWithLatest
                        +" Tail ETR: "+ currentTail.getEstimatedReturnToServiceTime()
                        +" is a Decison etr "+BooleanUtils.isTrue(currentTail.getIsDecisionETR())
                );
                FlightResourceVO flightResourceVO = new FlightResourceVO(
                        propagationEngineEventPayload.getCurrentFlightKey().generateNonSnapshotFlightKey(),
                        propagationEngineEventPayload.getCurrentFlightKey().generateNonSnapshotFlightKey(),
                        propagationEngineEventPayload.getCurrentFlightKey().toString(), FlightEventResourceType.EQUIPMENT,
                        0, 0, "AC",
                        propagationEngineEventPayload.getTailNumber());
                equipmentReadyTimeWithLatest = (RequestType.SWAP_WHATIF.equals(requestType))?equipmentReadyTimeWithLatest:0;
                flightResourceVO.setResourceReadyTimeWithLatest(equipmentReadyTimeWithLatest);
                if(RequestType.SWAP_WHATIF.equals(requestType)) {
                    LOG.debug(
                            "calculateAllResourceReadyTimes() when inbound equipment flight is null::  Flight resource VO: " + flightResourceVO + " for requestId " + requestID);
                }
                flightResourceVOList.add(flightResourceVO);
            }
            /* Will fix them after the Swap GO LIVE
            //handle when inbound flight times are not available , etr is calculated
            Tail currentTail = null;
            String tailNumber = propagationEngineEventPayload.getTailNumber();
            String airlineCode = propagationEngineEventPayload.getCurrentFlightKey().getAirlineCode();
            String snapshotId=propagationEngineEventPayload.getCurrentFlightKey().getSnapshotId();
            LKAFlightKey currentFlightKey = propagationEngineEventPayload.getCurrentFlightKey();
            try {
                currentTail = flightRepository.getTailForPE(airlineCode,tailNumber,snapshotId,
                        new String[]{"airlineCode", "tailNumber", "snapshotId", "otsInd", "otsSta", "estimatedReturnToServiceTime", "returnToServiceTime"});
            }catch (Exception e){
                LOG.error("Error occurred while fetching tail object for airlineCode:{} tailNumber:{} snapshotId:{}",airlineCode,tailNumber,snapshotId);
            }
            int airCraftTurnTime = (currentFlightTimes
                    .getObjectiveGroundTimeMinutes(requestID) != null)
                    ? propagationEngineEventPayload.getCurrentFlightTimes()
                    .getObjectiveGroundTimeMinutes(requestID) : 0;
            if(!(LKAEventType.PE_ETR.equals(eventType) || LKAEventType.PE_TAIL.equals(eventType) || (RequestType.SWAP_WHATIF.equals(requestType) && LKAEventType.PE_ETD.equals(eventType)))){
                if(currentFlightTimes != null && currentFlightTimes.getProjectedMntcDepartureTime(requestID) != null && currentFlightTimes.getProjectedDepartureTime(requestID) != null &&
                        currentFlightTimes.getProjectedMntcDepartureTime(requestID).after(currentFlightTimes.getProjectedDepartureTime(requestID))){
                    long mntcReadyTime = currentFlightTimes.getProjectedMntcDepartureTime().getTime();
                    if (currentTail != null && BooleanUtils.isTrue(currentTail.getOtsInd())) {
                        flightResourceVOList.add(getETRFlightResource(propagationEngineEventPayload, mntcReadyTime));
                    }
                }
            }
            else if (LKAEventType.PE_ETR.equals(eventType)) {
                FlightTimes flightTimes = propagationEngineEventPayload.getCurrentFlightTimes();
                long mntcReadyTime = flightTimes.getProjectedMntcDepartureTime().getTime() + airCraftTurnTime * minute2millisecond;
                LOG.debug("calculateAllResourceReadyTimes for PE_ETR for flightkey:{}", propagationEngineEventPayload.getCurrentFlightKey());
                flightResourceVOList.add(getETRFlightResource(propagationEngineEventPayload, mntcReadyTime));
            }
            else if(LKAEventType.PE_TAIL.equals(eventType)||(RequestType.SWAP_WHATIF.equals(requestType) && LKAEventType.PE_ETD.equals(eventType))){
                if(currentTail!=null && BooleanUtils.isTrue(currentTail.getOtsInd())){
                    if(currentTail.getEstimatedReturnToServiceTime()!=null || currentTail.getReturnToServiceTime()!=null){
                        //check for ots sta , inbound flight arrival station , current flight departure station
                        String otsSta = currentTail.getOtsSta();
                        if(currentTail.getOtsSta()!=null) {
                            if (currentFlightKey.getDepartureStation().equals(otsSta)) {
                                long mntcReadyTime = (currentTail.getReturnToServiceTime()!=null?currentTail.getReturnToServiceTime():currentTail.getEstimatedReturnToServiceTime()).getTime()+ airCraftTurnTime * minute2millisecond;
                                flightResourceVOList.add(getETRFlightResource(propagationEngineEventPayload, mntcReadyTime));
                            }
                        }
                    }
                }
            }
             */
        }
        //Commented for crew
        List<CrewFlightDetail> crewFlightDetailList = propagationEngineEventPayload
                .getCrewFlightDetailList();
        Map<String, PropagationCrewDetail> crewTurnTimesMap = propagationEngineEventPayload
                .getCrewTurnTimesMap();
        FlightTimes tempFltTimes = null;
        PropagationCrewDetail propagationCrewDetail = null;
        if (crewFlightDetailList != null && !crewFlightDetailList.isEmpty()) {
            for (CrewFlightDetail crewFlightDetail : crewFlightDetailList) {
                tempFltTimes = fltTimeMap.get(crewFlightDetail.getPreviousFlightKey());
                if (crewTurnTimesMap != null && !crewTurnTimesMap.isEmpty()) {
                    propagationCrewDetail = crewTurnTimesMap
                            .get(crewFlightDetail.getDutyKey().toString());
                }

                if (tempFltTimes != null && propagationCrewDetail != null) {
                    long resourceReadyTime = calculateResourceReadyTime(tempFltTimes,
                            propagationCrewDetail.getTurnTimeMinutes(), Boolean.FALSE, requestID, false);
                    long resourceMntcReadyTime = calculateResourceReadyTime(tempFltTimes,
                            propagationCrewDetail.getTurnTimeMinutes(), Boolean.FALSE, requestID, true);
                    long resourceReadyTimeWithControl = calculateResourceReadyTime(tempFltTimes,
                            propagationCrewDetail.getTurnTimeMinutes(), Boolean.TRUE, requestID, false);
                    long resourceReadyTimeWithLatest = calculateResourceReadyTime(tempFltTimes,
                            propagationCrewDetail.getTurnTimeMinutes(),requestID,requestType,false);
//					FlightResourceVO flightResourceVO = new FlightResourceVO(crewFlightDetail.getDutyKey().toString(),FlightEventResourceType.CREW,resourceReadyTime,resourceReadyTimeWithControl);
                    FlightResourceVO flightResourceVO = new FlightResourceVO(
                            crewFlightDetail.getFlightKey().generateNonSnapshotFlightKey(),
                            crewFlightDetail.getPreviousFlightKey().generateNonSnapshotFlightKey(),
                            crewFlightDetail.getDutyKey().toString(), FlightEventResourceType.CREW
                            , resourceReadyTime, resourceReadyTimeWithControl,
                            crewFlightDetail.getActualSeat(),
                            crewFlightDetail.getDutyKey().getEmployeeNumber());
                    flightResourceVO.setCalculatedEMOGT(propagationCrewDetail.getTurnTimeMinutes());
                    flightResourceVO.setResourceMntcReadyTime(resourceMntcReadyTime);
                    resourceReadyTimeWithLatest = (RequestType.SWAP_WHATIF.equals(requestType))?resourceReadyTimeWithLatest:0;
                    flightResourceVO.setResourceReadyTimeWithLatest(resourceReadyTimeWithLatest);
                    if(RequestType.SWAP_WHATIF.equals(requestType)) {
                        LOG.debug(
                                "calculateAllResourceReadyTimes() when inbound crew flight is not null:: Flight resource VO: "
                                        + flightResourceVO+ " for requestId "+requestID);
                    }
                    flightResourceVOList.add(flightResourceVO);
                }
            }
        }
        return flightResourceVOList;
    }

    private FlightResourceVO getETRFlightResource(PropagationEngineEventPayload propagationEngineEventPayload, long mntcReadyTime) {
        FlightResourceVO flightResourceVO = new FlightResourceVO(
                propagationEngineEventPayload.getCurrentFlightKey().generateNonSnapshotFlightKey(),
                propagationEngineEventPayload.getCurrentFlightKey().generateNonSnapshotFlightKey(),
                propagationEngineEventPayload.getCurrentFlightKey().toString(), FlightEventResourceType.EQUIPMENT,
                0, 0, "AC",
                propagationEngineEventPayload.getTailNumber());

        flightResourceVO.setResourceMntcReadyTime(mntcReadyTime);
        return flightResourceVO;
    }


    //create new resource readyTime based on latest for Swap, distinguish whether the event is current flight or downline
    private long calculateResourceReadyTime(FlightTimes flightTimes, int resourceTurnTime, String requestID,RequestType requestType,boolean currentOrDownline){
        long resourceReadyTime = 0L;
        if(flightTimes!=null) {
            if(RequestType.SWAP_WHATIF.equals(requestType)){
                LOG.info("Calculating the resource ready time for flight: "+flightTimes.toShortString());
                LOG.info(" Inbound LTA "+ flightTimes.getLatestArrivalTime(requestID));
                LOG.info("Resource Turn Time "+resourceTurnTime+" event check "+currentOrDownline);
            }
            if(flightTimes.getLatestArrivalTime(requestID)!=null)
                resourceReadyTime = flightTimes.getLatestArrivalTime(requestID).getTime();
            if (currentOrDownline) {
                if(flightTimes.getProjectedLatestMntcArrivalTime(requestID)!=null)
                    resourceReadyTime = flightTimes.getProjectedLatestMntcArrivalTime(requestID).getTime();
                else{
                    LOG.warn(
                            "ProjectedLatestMntcArrivalTime is missing for flightKey:" + flightTimes.getFlightKey());
                }
            }
            resourceReadyTime = (resourceReadyTime + resourceTurnTime * minute2millisecond);
        }
        else{
            LOG.warn(
                    "LatestArrivalTime is missing for flightKey in :" + flightTimes.getFlightKey());
        }
        return resourceReadyTime;
    }

    private long calculateResourceReadyTimeByTail(LKAFlightKey flightKey,Tail tail, int resourceTurnTime,String tailNumber, String requestID){
        long resourceReadyTime = 0L;
        LOG.info("calculateResourceReadyTime():: Checking for tail object:"+tailNumber+" for flightKey: "+flightKey +" requestID "+requestID);
        if(tail!=null) {
            LOG.info("current tail :"+tail.getTailNumber()+" ETR "+ tail.getEstimatedReturnToServiceTime()+" "+tail.getReturnToServiceTime());
            resourceReadyTime= tail.getOtsInd()?tail.getEstimatedReturnToServiceTime().getTime():tail.getReturnToServiceTime().getTime();
            resourceReadyTime = (resourceReadyTime + resourceTurnTime * minute2millisecond);
        }
        else{
            LOG.warn("Tail object is not present for flightKey:"+flightKey);
        }
        return resourceReadyTime;
    }

    private long calculateResourceReadyTime(FlightTimes flightTimes, int resourceTurnTime,
                                            Boolean contorlTimeIncluded, String requestID, boolean mntcReadyTime) {
        long resourceReadyTime = 0L;
        if (flightTimes != null) {
            if(mntcReadyTime && flightTimes.getProjectedMntcArrivalTime(requestID) != null){
                resourceReadyTime = flightTimes.getProjectedMntcArrivalTime(requestID).getTime();
                resourceReadyTime = (resourceReadyTime + resourceTurnTime * minute2millisecond);
            } else if(mntcReadyTime && flightTimes.getProjectedLatestMntcArrivalTime(requestID) != null){
                resourceReadyTime = flightTimes.getProjectedLatestMntcArrivalTime(requestID).getTime();
                resourceReadyTime = (resourceReadyTime + resourceTurnTime * minute2millisecond);
            }
            else if(flightTimes.getLatestArrivalTime(requestID) != null) {
                resourceReadyTime = flightTimes.getLatestArrivalTime(requestID).getTime();
                if (contorlTimeIncluded) {
                    if (flightTimes.getProjectedArrivalTime_Controlled(requestID) != null) {
                        resourceReadyTime = flightTimes
                                .getProjectedArrivalTime_Controlled(requestID).getTime();
                    }
                } else if (flightTimes.getProjectedArrivalTime(requestID) != null) {
                    resourceReadyTime = flightTimes.getProjectedArrivalTime(requestID).getTime();
                }

                resourceReadyTime = (resourceReadyTime + resourceTurnTime * minute2millisecond);
            } else {
                LOG.warn(
                        "LatestArrivalTime is missing for flightKey:" + flightTimes.getFlightKey());
            }
        }
        return resourceReadyTime;
    }

    /*
     * Calculate and updated projected departure( also with control) time for specified flight key.
     */
    private FlightTimes calculateFlightProjectedDepartures(FlightTimes flightTimes,
                                                           List<FlightResourceVO> flightResourceVOList, Boolean isFlightDepartureStatusOut,
                                                           LegStatus legStatus, String requestID) throws Exception {
        long calculatedPTD = 0;
        long calculatedPTD_Control = 0;
        long calculatedPTDWithLatest =0;
        long maxResourceMntcReadyTime = 0;
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "calculateFlightProjectedDepartures():: Checking if flight times is provided to do projected departure calculation: "
                            + flightTimes);
        }
        if (flightTimes != null) {
            if (flightTimes.getLatestDepartureTime(requestID) != null) {
                //Calculate projected departure values if the flight is not out
                // Once the flight statuas is out, projected departure time can not be chaned.
                if (!isFlightDepartureStatusOut) {
                    calculatedPTD = calculatedPTD_Control = calculatedPTDWithLatest = flightTimes
                            .getLatestDepartureTime(requestID).getTime();
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(
                                "calculateFlightProjectedDepartures():: Using following value as calculatedPTD and calculatedPTD_Control: "
                                        + calculatedPTD);
                        LOG.debug(
                                "calculateFlightProjectedDepartures():: Checking if flightResourceVOList is provided: "
                                        + (flightResourceVOList != null && !flightResourceVOList
                                        .isEmpty()));
                    }

                    LKAFlightKey currentFlightKey = flightTimes.getFlightKey();
                    long maxResourceReadyTime = 0, maxResourceReadyTime_Control = 0, maxResourceReadyTimeWithLatest=0;
                    String resourceType = null;
                    String mntcResourceType = null;
                    String resourceType_Control = null;
                    boolean departures = true;
                    if (flightResourceVOList != null && !flightResourceVOList.isEmpty()) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug(
                                    "calculateFlightProjectedDepartures():: flightResourceVOList is provided");
                        }
                        for (FlightResourceVO flightResourceVO : flightResourceVOList) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(
                                        "calculateFlightProjectedDepartures():: Currently analyzing the following flightResourceVO: "
                                                + flightResourceVO);
                                LOG.debug(
                                        "calculateFlightProjectedDepartures():: Checking if flightResourceVO.resourceReadyTime is provided: "
                                                + flightResourceVO.getResourceReadyTime());
                            }
                            if (flightResourceVO != null
                                    && flightResourceVO.getResourceReadyTime() > maxResourceReadyTime) {
                                maxResourceReadyTime = flightResourceVO.getResourceReadyTime();
                                resourceType = flightResourceVO.getResourceType().toString();
                            }
                            if (flightResourceVO.getResourceReadyTimeWithControl()
                                    > maxResourceReadyTime_Control) {
                                maxResourceReadyTime_Control = flightResourceVO
                                        .getResourceReadyTimeWithControl();
                                resourceType_Control = flightResourceVO.getResourceType()
                                        .toString();
                            }
                            if (flightResourceVO.getResourceMntcReadyTime() > maxResourceMntcReadyTime) {
                                maxResourceMntcReadyTime = flightResourceVO.getResourceMntcReadyTime();
                                mntcResourceType = flightResourceVO.getResourceType().toString();
                            }

                            //check the resource times based on latest

                            if (flightResourceVO.getResourceReadyTimeWithLatest()
                                    > maxResourceReadyTimeWithLatest) {
                                maxResourceReadyTimeWithLatest = flightResourceVO
                                        .getResourceReadyTimeWithLatest();
                            }
                        }
                    }
                    if (maxResourceReadyTime > calculatedPTD) {
                        calculatedPTD = maxResourceReadyTime;
                        flightTimes.setResourceType(resourceType);
                    }
                    if (calculatedPTD > maxResourceMntcReadyTime) {
                        maxResourceMntcReadyTime = calculatedPTD;
                        //flightTimes.setResourceType(resourceType);
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("calculateFlightProjectedDepartures():: Max resource Ready time: "
                                + maxResourceReadyTime);
                        LOG.debug(
                                "calculateFlightProjectedDepartures():: Max resource Ready time with control: "
                                        + maxResourceReadyTime_Control);
                        LOG.debug(
                                "calculateFlightProjectedMntcDepartures():: Max resource Mntc Ready time: "
                                        + maxResourceMntcReadyTime);
                        LOG.debug(
                                "calculateFlightProjectedDepartures():: Max resource Ready time with latest: "
                                        + maxResourceReadyTimeWithLatest +" flightKey "+flightTimes.getFlightKey().toShortString()+" requestID "+requestID);
                    }

                    //flightTimes.setPreviousProjectedDepartureTime_Controlled(flightTimes.getProjectedDepartureTime_Controlled());
                    if (maxResourceReadyTime_Control > calculatedPTD_Control) {
                        calculatedPTD_Control = maxResourceReadyTime_Control;
                        flightTimes.setResourceType_Control(resourceType_Control);
                    }

                    if(maxResourceReadyTimeWithLatest > calculatedPTDWithLatest){
                        calculatedPTDWithLatest = maxResourceReadyTimeWithLatest;
                    }
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(
                                "calculateFlightProjectedDepartures():: Using new flightTimes values: [Prev PTD: "
                                        + flightTimes.getPreviousProjectedDepartureTime(requestID) + ", "
                                        + "PTD: " + flightTimes.getProjectedDepartureTime(requestID)
                                        + ", Prev PCTD: " + flightTimes
                                        .getPreviousProjectedDepartureTime_Controlled(requestID) + ", "
                                        + "PCTD: " + flightTimes
                                        .getProjectedDepartureTime_Controlled(requestID) + "]");
                        LOG.debug(
                                "calculateFlightProjectedDepartures():: Checking if ctd is provided in flightTimes: "
                                        + flightTimes.getCtdDate(requestID));
                    }
                    //Added leg status check to take control impact out for RO in AFP program
                    if (flightTimes.getCtdDate(requestID) != null && !LegStatus.ROUTE_OUT
                            .equals(legStatus)) {
                        int taxiOut = calculatorService
                                .calculateTaxiOutTime(currentFlightKey, flightTimes, requestID);
                        long calulatedDepartureTimewithControl =
                                flightTimes.getCtdDate(requestID).getTime()
                                        + (-1) * taxiOut * minute2millisecond;
                        if (calulatedDepartureTimewithControl > calculatedPTD_Control) {
                            calculatedPTD_Control = calulatedDepartureTimewithControl;

                        }
                    }

                    LOG.debug(String.format("calculateFlightProjectedDepartures():: [%s] - PTD time [%d], PCTD time [%d] to use",
                            currentFlightKey.toShortString(),calculatedPTD,calculatedPTD_Control));
                    LOG.debug(String.format("calculateFlightProjectedMntcDepartures():: [%s] - PTMD time [%d] to use",
                            currentFlightKey.toShortString(),maxResourceMntcReadyTime));
                    LOG.debug(String.format("calculateFlightProjectedLatestMntcDepartures():: [%s] - PLTMD time [%d] to use",
                            currentFlightKey.toShortString(),calculatedPTDWithLatest));
                    LOG.debug("calculateFlightProjectedLatestMntcDepartures():: flightKey :"+currentFlightKey.toShortString()+" "+new Date(calculatedPTDWithLatest) +" requestId "+requestID);
                }


                boolean projected = false;
                boolean projectedControlled = false;

                flightTimes
                        .setPreviousProjectedDepartureTime(flightTimes.getProjectedDepartureTime(),
                                requestID);

                if (calculatedPTD > 0) {
                    flightTimes.setProjectedDepartureTime(new Date(calculatedPTD), requestID);
                } else {
                    flightTimes
                            .setProjectedDepartureTime(flightTimes.getLatestDepartureTime(requestID),
                                    requestID);
                }
                if(maxResourceMntcReadyTime > 0)
                    flightTimes.setProjectedMntcDepartureTime(new Date(maxResourceMntcReadyTime),
                            requestID);
                else
                    flightTimes
                            .setProjectedMntcDepartureTime(flightTimes.getProjectedDepartureTime(requestID),
                                    requestID);

                flightTimes.setPreviousProjectedDepartureTime_Controlled(
                        flightTimes.getProjectedDepartureTime_Controlled(requestID), requestID);

                if (calculatedPTD_Control > 0) {
                    flightTimes
                            .setProjectedDepartureTime_Controlled(new Date(calculatedPTD_Control),
                                    requestID);
                } else {
                    flightTimes.setProjectedDepartureTime_Controlled(
                            flightTimes.getLatestDepartureTime(requestID), requestID);
                }

                flightTimes.setPreviousProjectedLatestMntcDepartureTime(flightTimes.getProjectedLatestMntcDepartureTime(),requestID);
                if(calculatedPTDWithLatest>0){
                    flightTimes.setProjectedLatestMntcDepartureTime(new Date(calculatedPTDWithLatest),requestID);
                }
                else{
                    flightTimes.setProjectedLatestMntcDepartureTime(
                            flightTimes.getLatestDepartureTime(requestID), requestID);
                }


                if (LOG.isDebugEnabled()) {
                    LOG.debug("calculateFlightProjectedDepartures():: New PCTD: " + flightTimes
                            .getProjectedDepartureTime_Controlled(requestID));
                    LOG.debug("calculateFlightProjectedMntcDepartures():: New PMTD: " + flightTimes
                            .getProjectedMntcDepartureTime(requestID));
                    LOG.debug("calculateFlightProjectectedLatestMntcDepartures():: New PLTMD: " + flightTimes
                            .getProjectedLatestMntcDepartureTime(requestID)+" flightKey "+flightTimes.getFlightKey().toShortString()+" requestId "+requestID);
                }
                if (runAggregation.convertBoolean(runAggregation.getRunAggregation())) {
                    if (TimeUtils.getDiffInMinutes(flightTimes.getScheduledDepartureTime(),
                            flightTimes.getProjectedDepartureTime(requestID)) == 0) {
                        aggregationRepository.handleAggregation(flightTimes.getOrigFlightLeg(),
                                new AggregationKey(AggregationTypes.PD0,
                                        flightTimes.getOrigFlightLeg().getFlightDate()), true);
                    } else {
                        aggregationRepository.handleAggregation(flightTimes.getOrigFlightLeg(),
                                new AggregationKey(AggregationTypes.PD0,
                                        flightTimes.getOrigFlightLeg().getFlightDate()), false);
                    }
                }
            } else {
                LOG.warn(
                        "latestDepartureTime is missing for flight :" + flightTimes.getFlightKey());
            }

        }
        return flightTimes;
    }

    /*
     * Calculate and updated projected arrival( also with control) time for specified flight key.
     */
    private FlightTimes calculateFlightProjectedArrivals(FlightTimes flightTimes,
                                                         Boolean isFlightArrivalStatusOn_In, LegStatus legStatus, String requestID)
            throws Exception {
        if (flightTimes != null) {
            LOG.debug(String.format("calculateFlightProjectedArrivals():: start for [%s] on flight [%s]",requestID,flightTimes.getFlightKey().toShortString()));
            long calculatedPTA = 0, calculatedPTA_WithControl = 0,calculatedPTAWithLatest=0;
            long updatedMntcArrivalTime = 0;
            if (flightTimes.getLatestArrivalTime(requestID) != null) {
                calculatedPTA = calculatedPTA_WithControl = calculatedPTAWithLatest= flightTimes
                        .getLatestArrivalTime(requestID).getTime();
                updatedMntcArrivalTime = flightTimes
                        .getLatestArrivalTime(requestID).getTime();

                if (!isFlightArrivalStatusOn_In) {
                    LKAFlightKey currentFlightKey = flightTimes.getFlightKey();
                    boolean departures = false;
                    //Taxi out time calculation
                    int taxiOutTime = calculatorService
                            .calculateTaxiOutTime(currentFlightKey, flightTimes, requestID);
                    // TODO: set flight times once clarification for taxi times is determined
                    // flightTime.setPredictedTaxiOutTime();

                    //Taxi in time calculation
                    int taxiInTime = calculatorService
                            .calculateTaxiInTime(currentFlightKey, flightTimes, requestID);
                    // TODO: set flight times once clarification for taxi times is determined
                    // flightTime.setPredictedTaxiInTime();

                    //Airtime calculation
                    int airTimeValue = calculatorService.calculateAirTime(flightTimes, requestID);
                    flightTimes.setPredictedAirTime(airTimeValue);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug(
                                "calculateFlightProjectedArrivals():: Using following value for taxi out: "
                                        + taxiOutTime);
                        LOG.debug(
                                "calculateFlightProjectedArrivals():: Using following value for taxi in: "
                                        + taxiInTime);
                        LOG.debug(
                                "calculateFlightProjectedArrivals():: Using following value for air time: "
                                        + airTimeValue);
                        LOG.debug(String.format("calculateFlightProjectedArrivals():: [%s] LDT [%s], LAT [%s], PDT [%s]",
                                currentFlightKey.toShortString(),
                                flightTimes.getLatestDepartureTime(requestID),
                                flightTimes.getLatestArrivalTime(requestID),
                                flightTimes.getProjectedDepartureTime(requestID)));
                    }
                    //Modified as airtime calculation includes taxi in & taxi out
                    int total_Taxi_Airtime = airTimeValue; // TODO: taxiOutTime + airTimeValue + taxiInTime;
                    long departureTimeValue = 0;
                    long mntcDepartureTimeValue = 0;
                    long departureTimeValueWithLatest =0;
                    if (flightTimes.getProjectedDepartureTime(requestID) != null) {
                        departureTimeValue = flightTimes.getProjectedDepartureTime(requestID)
                                .getTime();
                    } else if (flightTimes.getLatestDepartureTime(requestID) != null) {
                        departureTimeValue = flightTimes.getLatestDepartureTime(requestID)
                                .getTime();
                    }
                    if(flightTimes.getProjectedMntcDepartureTime(requestID) != null){
                        mntcDepartureTimeValue = flightTimes.getProjectedMntcDepartureTime(requestID)
                                .getTime();
                    }
                    if(flightTimes.getProjectedLatestMntcDepartureTime(requestID)!=null){
                        departureTimeValueWithLatest = flightTimes.getProjectedLatestMntcDepartureTime(requestID).getTime();
                    }

                    long updatedArrivalTime =
                            departureTimeValue + total_Taxi_Airtime * minute2millisecond;
                    updatedMntcArrivalTime =
                            mntcDepartureTimeValue + total_Taxi_Airtime * minute2millisecond;
                    long updatedArrivalTimeWithLatest = departureTimeValueWithLatest+ total_Taxi_Airtime*minute2millisecond;

                    if (updatedArrivalTime > calculatedPTA) {
                        calculatedPTA = updatedArrivalTime;
                        LOG.debug(String.format("calculateFlightProjectedArrivals():: [%s] PTA > LTA updated",
                                currentFlightKey.toShortString()));
                    }
                    if (updatedMntcArrivalTime > calculatedPTA) {
                        LOG.debug(String.format("calculateFlightProjectedMntcArrivals():: [%s] PTMA > LTA updated",
                                currentFlightKey.toShortString()));
                    }
                    else {
                        updatedMntcArrivalTime = calculatedPTA;
                    }
                    if(updatedArrivalTimeWithLatest > calculatedPTAWithLatest){
                        calculatedPTAWithLatest = updatedArrivalTimeWithLatest;
                        LOG.debug(String.format("calculateFlightProjectedLatestMntcArrivals():: [%s] PLTMA > LTA updated",
                                currentFlightKey.toShortString()));
                    }

//					flightTimes.setProjectedArrivalTime(calculatedPTA);
                    //Calculate for control
                    long departureTimewithValueControl = 0;
                    if (flightTimes.getProjectedDepartureTime_Controlled(requestID) != null) {
                        departureTimewithValueControl = flightTimes
                                .getProjectedDepartureTime_Controlled(requestID).getTime();
                    } else if (flightTimes.getLatestDepartureTime(requestID) != null) {
                        departureTimewithValueControl = flightTimes
                                .getLatestDepartureTime(requestID).getTime();
                    }

                    // TODO: remove joda time objects
                    long updatedArrivalTimeControl =
                            departureTimewithValueControl + total_Taxi_Airtime * minute2millisecond;

                    if (updatedArrivalTimeControl > calculatedPTA_WithControl) {
                        calculatedPTA_WithControl = updatedArrivalTimeControl;
                        LOG.debug(String.format("calculateFlightProjectedArrivals():: [%s] PCTA > LTA updated",
                                currentFlightKey.toShortString()));
                    }

                    if (flightTimes.getCtaDate(requestID) != null && !LegStatus.ROUTE_OUT
                            .equals(legStatus)) {
                        long calulatedArrivalTimewithControl =
                                flightTimes.getCtaDate(requestID).getTime()
                                        + taxiInTime * minute2millisecond;
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("calculateFlightProjectedArrivals():: Using ctaValue: "
                                    + flightTimes.getCtaDate(requestID) + " - " + currentFlightKey.toShortString());
                            LOG.debug(
                                    "calculateFlightProjectedArrivals():: Using taxiIn: " + taxiInTime);
                            LOG.debug(
                                    "calculateFlightProjectedArrivals():: Using calulatedArrivalTimewithControl: "
                                            + calulatedArrivalTimewithControl);
                            LOG.debug(
                                    "calculateFlightProjectedArrivals():: Checking if calulatedArrivalTimewithControl: "
                                            + calulatedArrivalTimewithControl
                                            + " is greater than calculatedPTA_WithControl: "
                                            + calculatedPTA_WithControl);
                        }
                        if (calulatedArrivalTimewithControl > calculatedPTA_WithControl) {
                            calculatedPTA_WithControl = calulatedArrivalTimewithControl;
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(
                                        "calculateFlightProjectedArrivals():: Using new calculatedPTA_WithControl: "
                                                + calculatedPTA_WithControl);
                            }
                        }
                    }
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug(
                            "calculateFlightProjectedArrivals():: Using new flightTimes values: [Prev PTA: "
                                    + flightTimes.getPreviousProjectedArrivalTime(requestID) + ", "
                                    + "PTA: " + flightTimes.getProjectedArrivalTime() + ", Prev PCTA: "
                                    + flightTimes.getPreviousProjectedArrivalTime_controlled(requestID)
                                    + ", "
                                    + "PCTA: " + flightTimes.getProjectedArrivalTime_Controlled(requestID)
                                    + "]");
                    LOG.debug(
                            "calculateFlightProjectedArrivals():: Checking if cta is provided in flightTimes: "
                                    + flightTimes.getCtaDate(requestID));
                }

                boolean projected = false;
                boolean projectedControlled = false;
                flightTimes
                        .setPreviousProjectedArrivalTime(flightTimes.getProjectedArrivalTime(requestID),
                                requestID);
                flightTimes.setProjectedArrivalTime(new Date(calculatedPTA), requestID);
                flightTimes.setProjectedMntcArrivalTime(new Date(updatedMntcArrivalTime), requestID);
                flightTimes.setPreviousProjectedArrivalTime_controlled(
                        flightTimes.getProjectedArrivalTime_Controlled(requestID), requestID);
                flightTimes.setProjectedArrivalTime_Controlled(new Date(calculatedPTA_WithControl),
                        requestID);
                flightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID),requestID);
                flightTimes.setProjectedLatestMntcArrivalTime(new Date(calculatedPTAWithLatest),requestID);

                LOG.debug(String.format("calculateFlightProjectedArrivals():: calculated times on flight [%s] - PTA [%s], PCTA [%s], Prev PTA [%s], Prev PCTA [%s] PMTA [%s] PLTMA [%s]",
                        flightTimes.getFlightKey().toShortString(),
                        flightTimes.getProjectedArrivalTime(requestID),
                        flightTimes.getProjectedArrivalTime_Controlled(requestID),
                        flightTimes.getPreviousProjectedArrivalTime(requestID),
                        flightTimes.getPreviousProjectedArrivalTime_controlled(requestID),flightTimes.getProjectedMntcArrivalTime(requestID),flightTimes.getProjectedLatestMntcArrivalTime(requestID)));
                LOG.debug("calculateFlightProjectedArrivals():: calculated times on flight key "
                        +flightTimes.getFlightKey().toShortString() +" for requestId "+requestID+" PLMTA "
                        +flightTimes.getProjectedLatestMntcArrivalTime(requestID) +" prev PLMTA "+flightTimes.getPreviousProjectedLatestMntcArrivalTime(requestID));

                if (runAggregation.convertBoolean(runAggregation.getRunAggregation())) {
                    if (TimeUtils.getDiffInMinutes(flightTimes.getScheduledArrivalTime(),
                            flightTimes.getProjectedArrivalTime()) <= 14) {
                        aggregationRepository.handleAggregation(flightTimes.getOrigFlightLeg(),
                                new AggregationKey(AggregationTypes.PA14,
                                        flightTimes.getOrigFlightLeg().getFlightDate()), true);
                    } else {
                        aggregationRepository.handleAggregation(flightTimes.getOrigFlightLeg(),
                                new AggregationKey(AggregationTypes.PA14,
                                        flightTimes.getOrigFlightLeg().getFlightDate()), false);
                    }
                }
            } else {
                LOG.warn("latestArrivalTime is missing for flight:" + flightTimes.getFlightKey());
            }

        }
        return flightTimes;
    }

    /**
     * This method will determine what problem type to update based on the event type
     *
     * @param flightTimes
     * @param projected
     * @param projectedControlled
     * @param departures
     */
    public void handleProblemType(FlightTimes flightTimes, boolean projected,
                                  boolean projectedControlled, boolean departures, Status flightStatus) throws Exception {
        boolean isInactive = problemTypeRepository
                .isProblemTypeInactive(flightTimes.getFlightKey(), departures, flightStatus);
        ProblemTypeDescription pt = new ProblemTypeDescription();
        pt.setLkaFlightKey(flightTimes.getFlightKey());
        pt.setActive(!isInactive);
        // check for control time posted, if found, create projected control time delay problem type, if not, dont
        // create a projected control time delay
        boolean hasControlDepartureTime = flightTimes.getCtdDate() != null;
        boolean hasControlArrivalTime = flightTimes.getCtaDate() != null;
        if (departures) {
            if (projected) {
                LOG.info("Running projected departure problem type calculation for :" + flightTimes
                        .getFlightKey());
                pt.setProblemType(ProblemTypes.PROJECTED_DEP_DELAY);
                problemTypeRepository
                        .updateDelayProblemType(flightTimes, pt, ProblemTypes.PROJECTED_DEP_DELAY,
                                LKAEventType.PTD);
            }
            if (hasControlDepartureTime && projectedControlled) {
                LOG.info("Running projected control departure problem type calculation for :"
                        + flightTimes.getFlightKey());
                pt.setProblemType(ProblemTypes.PROJECTED_CONTROLLED_DEP_DELAY);
                problemTypeRepository.updateDelayProblemType(flightTimes, pt,
                        ProblemTypes.PROJECTED_CONTROLLED_DEP_DELAY, LKAEventType.PTD_CONTROL);
            }
        } else {
            if (projected) {
                LOG.info("Running projected arrival problem type calculation for :" + flightTimes
                        .getFlightKey());
                pt.setProblemType(ProblemTypes.PROJECTED_ARR_DELAY);
                problemTypeRepository
                        .updateDelayProblemType(flightTimes, pt, ProblemTypes.PROJECTED_ARR_DELAY,
                                LKAEventType.PTA);
            }
            if (hasControlArrivalTime && projectedControlled) {
                LOG.info(
                        "Running projected control arrival problem type calculation for :" + flightTimes
                                .getFlightKey());
                pt.setProblemType(ProblemTypes.PROJECTED_CONTROLLED_ARR_DELAY);
                problemTypeRepository.updateDelayProblemType(flightTimes, pt,
                        ProblemTypes.PROJECTED_CONTROLLED_ARR_DELAY, LKAEventType.PTA_CONTROL);
            }
        }
    }

    /*
     * Determine the flight times changes and generate events
     */

    private synchronized Map<String, Object> computeChangeSetFromFlightTimes(FlightTimes flightTimes, Boolean reseedForced, String requestID, RequestType requestType) {
        List<LKAEventType> eventTypeList = null;
        ChangeSet changeSet = null;
        Map<String, Object> returnMap = null;
        Boolean foundChange = Boolean.FALSE;
        if (flightTimes != null) {
            if (flightTimes.getSnapshots().get(requestID) != null && !requestID.equalsIgnoreCase("0")) {
                if (!foundChange) {
                    changeSet = new ChangeSet();
                    eventTypeList = new ArrayList<LKAEventType>();
                    foundChange = Boolean.TRUE;
                }
                changeSet.putInMap("snapshots", requestID,
                        flightTimes.getSnapshots().get(requestID));
            }

            if (reseedForced || (flightTimes.getProjectedArrivalTime(requestID) != null && !flightTimes
                    .getProjectedArrivalTime(requestID).equals(flightTimes.getPreviousProjectedArrivalTime(requestID)))) {
                if (!foundChange) {
                    changeSet = new ChangeSet();
                    eventTypeList = new ArrayList<LKAEventType>();
                    foundChange = Boolean.TRUE;
                }
                if (com.aa.lookahead.utils.common.CommonUtility.isMasterSnapshot(requestID)) {
                    changeSet.set("projectedArrivalTime",
                            flightTimes.getProjectedArrivalTime());
                    changeSet.set("previousProjectedArrivalTime",
                            flightTimes.getPreviousProjectedArrivalTime());
                    changeSet.set("projectedMntcArrivalTime",
                            flightTimes.getProjectedMntcArrivalTime());
                }
                if(!RequestType.SWAP_WHATIF.equals(requestType)) {
                    eventTypeList.add(LKAEventType.PTA);
                }
            }
            if (reseedForced || (flightTimes.getProjectedArrivalTime_Controlled(requestID) != null
                    && !flightTimes.getProjectedArrivalTime_Controlled(requestID)
                    .equals(flightTimes.getPreviousProjectedArrivalTime_controlled(requestID)))) {
                if (!foundChange) {
                    changeSet = new ChangeSet();
                    eventTypeList = new ArrayList<LKAEventType>();
                    foundChange = Boolean.TRUE;
                }
                if (com.aa.lookahead.utils.common.CommonUtility.isMasterSnapshot(requestID)) {
                    changeSet.set("projectedArrivalTime_Controlled",
                            flightTimes.getProjectedArrivalTime_Controlled());
                    changeSet.set("previousProjectedArrivalTime_controlled",
                            flightTimes.getPreviousProjectedArrivalTime_controlled());
                }
                if(!RequestType.SWAP_WHATIF.equals(requestType)) {
                    eventTypeList.add(LKAEventType.PTA_CONTROL);
                }
            }
            if (reseedForced || (flightTimes.getProjectedDepartureTime(requestID) != null && !flightTimes
                    .getProjectedDepartureTime(requestID)
                    .equals(flightTimes.getPreviousProjectedDepartureTime(requestID)))) {
                if (!foundChange) {
                    changeSet = new ChangeSet();
                    eventTypeList = new ArrayList<LKAEventType>();
                    foundChange = Boolean.TRUE;
                }
                if (com.aa.lookahead.utils.common.CommonUtility.isMasterSnapshot(requestID)) {
                    changeSet
                            .set("projectedDepartureTime", flightTimes.getProjectedDepartureTime());
                    changeSet.set("previousProjectedDepartureTime",
                            flightTimes.getPreviousProjectedDepartureTime());
                    changeSet.set("projectedMntcDepartureTime",
                            flightTimes.getProjectedMntcDepartureTime());
                }
                if(!RequestType.SWAP_WHATIF.equals(requestType)) {
                    eventTypeList.add(LKAEventType.PTD);
                }
            }
            /* commented PMTD, PMTA for Swap Release
//            if (reseedForced || flightTimes.getProjectedMntcDepartureTime(requestID) != null ||
//                    flightTimes.getProjectedMntcArrivalTime(requestID) != null) {
//                if (!foundChange) {
//                    changeSet = new ChangeSet();
//                    eventTypeList = new ArrayList<LKAEventType>();
//                    foundChange = Boolean.TRUE;
//                }
//                if (com.aa.lookahead.utils.common.CommonUtility.isMasterSnapshot(requestID)) {
//                    if(flightTimes.getProjectedMntcDepartureTime(requestID) != null) {
//                        changeSet.set("projectedMntcDepartureTime",
//                                flightTimes.getProjectedMntcDepartureTime());
//                        changeSet.set("projectedMntcArrivalTime",
//                                flightTimes.getProjectedMntcArrivalTime());
//                    }
//                }
////                if((!TimeUtils.equalDate(flightTimes.getProjectedDepartureTime(requestID),
////                        flightTimes.getProjectedMntcDepartureTime(requestID))
////                        && flightTimes.getProjectedDepartureTime(requestID).before(flightTimes.getProjectedMntcDepartureTime(requestID))))
////                    eventTypeList.add(LKAEventType.PMTD);
//            } */
            if (reseedForced || flightTimes.getProjectedLatestMntcDepartureTime(requestID) != null
                   // || !flightTimes.getProjectedLatestMntcDepartureTime(requestID).equals(flightTimes.getPreviousProjectedLatestMntcDepartureTime(requestID))
            ) {
                if (!foundChange) {
                    changeSet = new ChangeSet();
                    eventTypeList = new ArrayList<LKAEventType>();
                    foundChange = Boolean.TRUE;
                }
                if (com.aa.lookahead.utils.common.CommonUtility.isMasterSnapshot(requestID)) {
                        changeSet.set("projectedLatestMntcDepartureTime",
                                flightTimes.getProjectedLatestMntcDepartureTime());
                        changeSet.set("previousProjectedLatestMntcDepartureTime",
                                flightTimes.getPreviousProjectedLatestMntcDepartureTime());
                }
                 if(RequestType.SWAP_WHATIF.equals(requestType)) {
                    // eventTypeList.add(LKAEventType.PLMTD);
                 }
            }
            if (reseedForced || flightTimes.getProjectedLatestMntcArrivalTime(requestID) != null
                    //|| !flightTimes.getProjectedLatestMntcArrivalTime(requestID).equals(flightTimes.getPreviousProjectedLatestMntcArrivalTime(requestID))
            ) {
                if (!foundChange) {
                    changeSet = new ChangeSet();
                    eventTypeList = new ArrayList<LKAEventType>();
                    foundChange = Boolean.TRUE;
                }
                if (com.aa.lookahead.utils.common.CommonUtility.isMasterSnapshot(requestID)) {
                    changeSet.set("projectedLatestMntcArrivalTime",
                            flightTimes.getProjectedLatestMntcArrivalTime());
                    changeSet.set("previousProjectedLatestMntcArrivalTime",
                            flightTimes.getPreviousProjectedLatestMntcArrivalTime());
                }
                if(RequestType.SWAP_WHATIF.equals(requestType)) {
                    eventTypeList.add(LKAEventType.PLMTA);
                }
            }
            if (reseedForced || (flightTimes.getProjectedDepartureTime_Controlled(requestID) != null
                    && !flightTimes.getProjectedDepartureTime_Controlled(requestID)
                    .equals(flightTimes.getPreviousProjectedDepartureTime_Controlled(requestID)))) {
                if (!foundChange) {
                    changeSet = new ChangeSet();
                    eventTypeList = new ArrayList<LKAEventType>();
                    foundChange = Boolean.TRUE;
                }
                if (com.aa.lookahead.utils.common.CommonUtility.isMasterSnapshot(requestID)) {
                    changeSet.set("projectedDepartureTime_Controlled",
                            flightTimes.getProjectedDepartureTime_Controlled());
                    changeSet.set("previousProjectedDepartureTime_Controlled",
                            flightTimes.getPreviousProjectedDepartureTime_Controlled());
                }
                if(!RequestType.SWAP_WHATIF.equals(requestType)) {
                    eventTypeList.add(LKAEventType.PTD_CONTROL);
                }
            }

            if (foundChange) {
                returnMap = new WeakHashMap<String, Object>();
                returnMap.put(EVENT_CHANGE_CONSTANT, eventTypeList);
                returnMap.put(FLIGHT_TIMES_CONSTANT, changeSet);
                LOG.info(flightTimes.getFlightKey() + " EVENT_CHANGE: "+ eventTypeList);
            }
        }

        return returnMap;
    }

    private Boolean checkPEContinuedforNextFlight(FlightTimes flightTimes,
                                                  LKAFlightKey rootEventFlightKey, List<String> nextLegFlightKeyList,
                                                  String requestID, RequestType requestType, LKAFlightKey previousEquipmentFlightKey, Map<LKAFlightKey,FlightTimes> flightKeyFlightTimesMap) throws Exception {

        Boolean returnPropagationContinue = Boolean.FALSE;
        if (flightTimes != null && nextLegFlightKeyList != null && !nextLegFlightKeyList
                .isEmpty()) {
            LKAFlightKey currentFlightKey = flightTimes.getFlightKey();
            LOG.debug("For requestId "+requestID+" for FlightKey "+ flightTimes.getFlightKey().toShortString()+ " PE_CONTINUED Event PMLTA"+
                    flightTimes.getProjectedLatestMntcArrivalTime(requestID) +
                    " prev PLMTA "+flightTimes.getPreviousProjectedLatestMntcArrivalTime(requestID)
                    +" event continue check "+ flightTimes.getProjectedLatestMntcArrivalTime(requestID).equals(flightTimes.getPreviousProjectedLatestMntcArrivalTime(requestID))
                    +"ron Check"+
                    isRonCheck(flightTimes,requestID,previousEquipmentFlightKey,flightKeyFlightTimesMap)
            );
            if (
                   ( RequestType.SWAP_WHATIF.equals(requestType) &&
                   flightTimes.getProjectedLatestMntcArrivalTime(requestID)!=null
                //  !flightTimes.getProjectedLatestMntcArrivalTime(requestID).equals(flightTimes.getPreviousProjectedLatestMntcArrivalTime(requestID)))
                    //!isRonCheck(flightTimes,requestID,previousEquipmentFlightKey,flightKeyFlightTimesMap)
            ) ||
                    ((flightTimes.getProjectedArrivalTime(requestID) != null && !flightTimes
                    .getProjectedArrivalTime(requestID).equals(flightTimes.getPreviousProjectedArrivalTime(requestID)))
                    || (flightTimes.getProjectedArrivalTime_Controlled(requestID) != null && !flightTimes
                    .getProjectedArrivalTime_Controlled(requestID)
                    .equals(flightTimes.getPreviousProjectedArrivalTime_controlled(requestID))))
//                    || (flightTimes.getProjectedArrivalTime(requestID) != null && !flightTimes
//                    .getProjectedArrivalTime(requestID).equals(flightTimes.getProjectedMntcArrivalTime(requestID)))
            // || (flightTimes.getProjectedLatestMntcArrivalTime(requestID)!=null && !flightTimes.getProjectedLatestMntcArrivalTime(requestID).equals(flightTimes.getPreviousProjectedLatestMntcArrivalTime(requestID)))
            )
            {
                try {
                    long delayMilliSecond = TimeUtils.getDiffInMilliSeconds(
                            (flightTimes.getScheduledArrivalTime() != null ? flightTimes
                                    .getScheduledArrivalTime() : flightTimes.getScheduledDepartureTime())
                            , (flightTimes.getProjectedArrivalTime_Controlled(requestID) != null ? flightTimes
                                    .getProjectedArrivalTime_Controlled(requestID)
                                    : flightTimes.getProjectedArrivalTime(requestID)));

                    if (delayMilliSecond < twoDaysInmillisecond) {
                        if (rootEventFlightKey != null && currentFlightKey != null) {
                            try {
                                int noOfDays = TimeUtils
                                        .getDaysInbetween2Days(rootEventFlightKey.getFlightDate(),
                                                currentFlightKey.getFlightDate());
                                LOG.info("For requestId: "+requestID+" rootEventFlightKey: "+rootEventFlightKey+" current Flight Key "+currentFlightKey+" noOfDays "+noOfDays);
//                                if(RequestType.SWAP_WHATIF.equals(requestType)){
//                                    if(noOfDays < 1){
//                                        returnPropagationContinue = Boolean.TRUE;
//                                    }
//                                }
                                //else {
                                    if (noOfDays < 2) {
                                        returnPropagationContinue = Boolean.TRUE;
                                    }
                               // }
                            } catch (ParseException parseEx) {
                                if (LOG.isInfoEnabled()) {
                                    LOG.info(
                                            "checkPEContinuedforNextFlight():: Exception occurred in datetime handling: "
                                                    + parseEx.getMessage(), parseEx);
                                }
                            }

                        } else {
                            returnPropagationContinue = Boolean.TRUE;
                        }
                    }
                } catch (Exception ex) {
                    LOG.info("Error occurred in checkPEContinuedforNextFlight for :" + flightTimes,
                            ex);
                }
            }
        }

        LOG.debug(String.format("%s - %s, has continue P.E. %s",requestID,rootEventFlightKey.toShortString(),returnPropagationContinue));

        return returnPropagationContinue;
    }

    private boolean isRonCheck(FlightTimes currentFlightTimes,String requestID,LKAFlightKey previousFlightKey ,Map<LKAFlightKey,FlightTimes> flightKeyFlightTimesMap){
        int ronHours = 3;
        boolean ronValue=true;
        FlightTimes inboundFlightTimes =null;
        if(currentFlightTimes!=null) {
            LKAFlightKey currentFlightKey = currentFlightTimes.getFlightKey();
            DateTime currentFlight = DateTimeUtils.toDateTime(currentFlightKey.getFlightDate(), DateTimeUtils.ddMMMyy);
            DateTime ronCutOffTime = currentFlight.plusDays(1).plusHours(ronHours);
            Date ronCutOff = TimeUtils.convertLocalToGMTTime(ronCutOffTime.toDate(), currentFlightTimes.getDepartureStationGMTOffset()!=null?
                    currentFlightTimes.getDepartureStationGMTOffset().intValue():-300);
            if(previousFlightKey!=null){
                inboundFlightTimes = flightKeyFlightTimesMap.get(previousFlightKey);
            }
            if(inboundFlightTimes!=null) {
                if(inboundFlightTimes.getProjectedLatestMntcArrivalTime(requestID)!=null) {
                    if (currentFlightTimes.getProjectedLatestMntcDepartureTime(requestID) != null) {
                        if (inboundFlightTimes.getProjectedLatestMntcArrivalTime(requestID).before(ronCutOff)
                                && currentFlightTimes.getProjectedLatestMntcDepartureTime(requestID).after(ronCutOff)) {
                                    ronValue=true;
                        }
                        else{
                            ronValue=false;
                        }
                    }
                }
            }
            else{
                if(currentFlightTimes.getProjectedLatestMntcDepartureTime(requestID)!=null) {
                    if (currentFlightTimes.getProjectedLatestMntcDepartureTime(requestID).after(ronCutOff)) {
                        ronValue = true;
                    }
                    else{
                        ronValue =false;
                    }
                }
            }
        }
        return ronValue;
    }


    /**
     * @return the propagationSpaceRepository
     */
    public PropagationSpaceRepository getPropagationSpaceRepository() {
        return propagationSpaceRepository;
    }

    /**
     * @param propagationSpaceRepository the propagationSpaceRepository to set
     */
    public void setPropagationSpaceRepository(
            PropagationSpaceRepository propagationSpaceRepository) {
        this.propagationSpaceRepository = propagationSpaceRepository;
    }

    public FlightTimes substituteFlightTimesWithScheduleTimes(FlightTimes flightTimes, String requestID) {
        if (flightTimes != null) {
            flightTimes.setPreviousProjectedArrivalTime(flightTimes.getProjectedArrivalTime(requestID), requestID);
            flightTimes.setPreviousProjectedArrivalTime_controlled(
                    flightTimes.getProjectedArrivalTime_Controlled(requestID),requestID);
            flightTimes.setPreviousProjectedDepartureTime(flightTimes.getProjectedDepartureTime(requestID), requestID);
            flightTimes.setPreviousProjectedDepartureTime_Controlled(
                    flightTimes.getProjectedDepartureTime_Controlled(requestID), requestID);
            flightTimes.setProjectedArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
            flightTimes.setProjectedArrivalTime_Controlled(flightTimes.getScheduledArrivalTime(), requestID);
            flightTimes.setProjectedDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
            flightTimes.setPreviousProjectedLatestMntcDepartureTime(flightTimes.getProjectedLatestMntcDepartureTime(requestID),requestID);
            flightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID),requestID);
            flightTimes.setProjectedLatestMntcDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
            flightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
            flightTimes.setProjectedMntcArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
            flightTimes.setProjectedMntcDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
//            if(flightTimes.getProjectedMntcDepartureTime(requestID) == null || flightTimes.getProjectedMntcDepartureTime(requestID).before(flightTimes.getProjectedDepartureTime(requestID))) {
//                flightTimes.setProjectedMntcDepartureTime(flightTimes.getProjectedDepartureTime(requestID), requestID);
//                flightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedArrivalTime(requestID),
//                        requestID);
//            }
//            else {
//                flightTimes.setProjectedMntcDepartureTime(flightTimes.getProjectedMntcDepartureTime(requestID),
//                        requestID);
//                flightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedMntcArrivalTime(requestID),
//                        requestID);
//            }
            flightTimes
                    .setProjectedDepartureTime_Controlled(flightTimes.getScheduledDepartureTime(), requestID);
        }
        return flightTimes;
    }

    public FlightTimes copyFlightTimesRequiredValue(FlightTimes flightTimes,
                                                    FlightTimes operationFlightTimes, String requestID) {
        if (flightTimes != null) {
            if (operationFlightTimes == null) {
                operationFlightTimes = new FlightTimes(flightTimes.getFlightKey());
            }
            operationFlightTimes.setScheduledArrivalTime(flightTimes.getScheduledArrivalTime());
            operationFlightTimes.setScheduledDepartureTime(flightTimes.getScheduledDepartureTime());
            operationFlightTimes
                    .setLatestDepartureTime(flightTimes.getLatestDepartureTime(requestID));
            operationFlightTimes
                    .setPreviousLatestDepartureTime(flightTimes.getPreviousLatestDepartureTime());
            operationFlightTimes.setPreviousProjectedArrivalTime(
                    flightTimes.getPreviousProjectedArrivalTime(requestID), requestID);
            operationFlightTimes.setPreviousProjectedArrivalTime_controlled(
                    flightTimes.getPreviousProjectedArrivalTime_controlled(requestID),requestID);
            operationFlightTimes.setPreviousProjectedDepartureTime(
                    flightTimes.getPreviousProjectedDepartureTime(requestID),requestID);
            operationFlightTimes.setPreviousProjectedDepartureTime_Controlled(
                    flightTimes.getPreviousProjectedDepartureTime_Controlled(requestID),requestID);
            operationFlightTimes
                    .setProjectedArrivalTime(flightTimes.getProjectedArrivalTime(requestID),requestID);
            operationFlightTimes.setProjectedMntcArrivalTime(flightTimes.getProjectedMntcArrivalTime(requestID),
                    requestID);
            operationFlightTimes.setProjectedArrivalTime_Controlled(
                    flightTimes.getProjectedArrivalTime_Controlled(requestID),requestID);
            operationFlightTimes
                    .setProjectedDepartureTime(flightTimes.getProjectedDepartureTime(requestID), requestID);
            operationFlightTimes
                    .setProjectedMntcDepartureTime(flightTimes.getProjectedMntcDepartureTime(requestID), requestID);
            operationFlightTimes.setProjectedLatestMntcDepartureTime(flightTimes.getProjectedLatestMntcDepartureTime(requestID),requestID);
            operationFlightTimes.setPreviousProjectedLatestMntcDepartureTime(flightTimes.getPreviousProjectedLatestMntcDepartureTime(requestID),requestID);
            operationFlightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getPreviousProjectedLatestMntcArrivalTime(requestID),requestID);
            operationFlightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID),requestID);
            LOG.info("For requestId: "+requestID+" "+ "PLMTD "+operationFlightTimes.getProjectedLatestMntcDepartureTime(requestID)
            +" PMLTA "+operationFlightTimes.getProjectedLatestMntcArrivalTime(requestID)
                    +" prev PLMTD "+ operationFlightTimes.getPreviousProjectedLatestMntcDepartureTime(requestID)
                    +" prev PLMTA "+ operationFlightTimes.getPreviousProjectedLatestMntcArrivalTime(requestID) + " "+operationFlightTimes.getFlightKey().toShortString());
            LOG.info("For requestId: "+requestID+" "+flightTimes.getSnapshots() +" "+flightTimes.getFlightKey().toShortString());
            operationFlightTimes.setProjectedDepartureTime_Controlled(
                    flightTimes.getProjectedDepartureTime_Controlled(requestID), requestID);
            operationFlightTimes.setSnapshots(flightTimes.getSnapshots());
            operationFlightTimes.setDepartureStationGMTOffset(flightTimes.getDepartureStationGMTOffset());
            operationFlightTimes.setArrivalStationGMTOffset(flightTimes.getArrivalStationGMTOffset());
        }
        return operationFlightTimes;
    }

    private PropagationEngineEvent populatePropagationPayload(PropagationEngineEvent peFlightEvent,
                                                              Boolean reseedForced, Boolean crewExcluded, String requestID) {
        // Populate EventPayload
        PropagationEngineEventPayload propagationEngineEventPayload = new PropagationEngineEventPayload();
        try {

            LKAFlightKey currentFlightKey = peFlightEvent.getFlightKey();
            //Added crew changes
            //Add logic for flight times and flight equipment look up
            //getlist of crew for flight
            FlightOperatingCrew flightOperatingCrew = null;
            List<CrewFlightDetail> crewFlightDetailList = null;
            List<LKAFlightKey> inboundResourceFltKeys = new ArrayList<LKAFlightKey>();
            List<String> outboundResourceFltKeys = new ArrayList<String>();
            inboundResourceFltKeys.add(currentFlightKey);

            //Perform crew logic exclusion if requested
            if (!crewExcluded) {
                //Read crew list by flight
                //TODO: need to move space query for flight crew list by LKAFlightKey from crew repository to flight repository
                flightOperatingCrew = flightRepository.getFlightOperatingCrewById(currentFlightKey);
                //Populate list of previous flight keys
                if (flightOperatingCrew != null) {
                    //Check assigned cockpit crew
                    if (flightOperatingCrew.getCockPitCrewList() != null && !flightOperatingCrew
                            .getCockPitCrewList().isEmpty()) {
                        crewFlightDetailList = new ArrayList<CrewFlightDetail>(
                                flightOperatingCrew.getCockPitCrewList());
                    } else {
                        //Raise error for no cockpit crew assigned
                    }
                    if (flightOperatingCrew.getCabinCrewList() != null && !flightOperatingCrew
                            .getCabinCrewList().isEmpty()) {
                        if (crewFlightDetailList == null) {
                            crewFlightDetailList = new ArrayList<CrewFlightDetail>(
                                    flightOperatingCrew.getCabinCrewList());
                        } else {
                            crewFlightDetailList.addAll(flightOperatingCrew.getCabinCrewList());
                        }
                    }
                    //Iterating to populate flightkeys list
                    if (crewFlightDetailList != null) {
                        for (CrewFlightDetail crewFltDetail : crewFlightDetailList) {
                            if (crewFltDetail != null) {
                                if (crewFltDetail.getPreviousFlightKey() != null
                                        && !inboundResourceFltKeys
                                        .contains(crewFltDetail.getPreviousFlightKey())) {
                                    inboundResourceFltKeys
                                            .add(crewFltDetail.getPreviousFlightKey());
                                }
                                if (crewFltDetail.getNextFlightKey() != null) {
                                    //Added ResourceType used for propagation event linking
                                    String crewNextFlightKey =
                                            crewFltDetail.getNextFlightKey().generateFlightKeyString()
                                                    + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER
                                                    + FlightEventResourceType.CREW.name();
                                    if (!outboundResourceFltKeys.contains(crewNextFlightKey)) {
                                        outboundResourceFltKeys.add(crewNextFlightKey);
                                    }
                                }
                            }
                        }
                    }
                    //Iterate deadhead list for populating next leg.
                    if (flightOperatingCrew.getDeadHeadCrewList() != null && !flightOperatingCrew
                            .getDeadHeadCrewList().isEmpty()) {
                        for (CrewFlightDetail crewFltDetail : flightOperatingCrew
                                .getDeadHeadCrewList()) {
                            if (crewFltDetail != null && crewFltDetail.getNextFlightKey() != null) {
                                //Added ResourceType used for propagation event linking
                                String crewNextFlightKey =
                                        crewFltDetail.getNextFlightKey().generateFlightKeyString()
                                                + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER
                                                + FlightEventResourceType.CREW.name();
                                if (!outboundResourceFltKeys.contains(crewNextFlightKey)) {
                                    outboundResourceFltKeys.add(crewNextFlightKey);
                                }

                            }
                        }
                    }

                    //Iterate regional deadhead list for populating next leg.
                    if (flightOperatingCrew.getRegionalDHCrewList() != null && !flightOperatingCrew
                            .getRegionalDHCrewList().isEmpty()) {
                        for (CrewFlightDetail crewFltDetail : flightOperatingCrew
                                .getRegionalDHCrewList()) {
                            if (crewFltDetail != null && crewFltDetail.getNextFlightKey() != null) {
                                //Added ResourceType used for propagation event linking
                                String crewNextFlightKey =
                                        crewFltDetail.getNextFlightKey().generateFlightKeyString()
                                                + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER
                                                + FlightEventResourceType.CREW.name();
                                if (!outboundResourceFltKeys.contains(crewNextFlightKey)) {
                                    outboundResourceFltKeys.add(crewNextFlightKey);
                                }
                            }
                        }
                    }

                }
            }
            // Use flightkeys list for readbyIds query
            LKAFlightKey[] lkaFltKeysQueryArray = new LKAFlightKey[inboundResourceFltKeys.size()];
            lkaFltKeysQueryArray = inboundResourceFltKeys.toArray(lkaFltKeysQueryArray);

            //Query equipments read by ids(LKAFlightKey)
            List<Equipment> equipmentList = flightRepository
                    .getEquipmentByIds_LKAFlightKeysList(lkaFltKeysQueryArray,
                            PROJECTIONCOLUMNS_EQUIPMENT);
            Map<LKAFlightKey, Equipment> equipmentMap = new WeakHashMap<LKAFlightKey, Equipment>();
            //Create list of PropagationCrewDetail object for crew turn time calculation
            String departureTailNumber = null;
            String departureStation = currentFlightKey.getDepartureStation();
            PropagationCrewDetail propagationCrewDetail;
            Map<String, PropagationCrewDetail> resourceReadyTimesMap = new HashMap<String, PropagationCrewDetail>();
            // populate departure tail number
            if (equipmentList != null) {
                for (Equipment equipment : equipmentList) {
                    if (equipment != null) {
                        if (currentFlightKey.equals(equipment.getFlightKey())) {
                            departureTailNumber = equipment.getTailNumber();
                            if (equipment.getPrevLeg(requestID) != null) {
                                LKAFlightKey equipmentPrevLeg = equipment.getPrevLeg(requestID);
                                propagationEngineEventPayload
                                        .setPreviousFlightKey(equipmentPrevLeg);
                                if (!inboundResourceFltKeys.contains(equipmentPrevLeg)) {
                                    inboundResourceFltKeys.add(equipmentPrevLeg);
                                }
                            } else {
                                flightRepository.setPreviousFlightKey(equipment);
                                if (equipment.getPrevLeg() != null) {
                                    propagationEngineEventPayload
                                            .setPreviousFlightKey(equipment.getPrevLeg());
                                    if (!inboundResourceFltKeys.contains(equipment.getPrevLeg())) {
                                        inboundResourceFltKeys.add(equipment.getPrevLeg());
                                    }
                                    if (reseedForced) {
                                        flightRepository.getSpace().write(equipment);
                                    }

                                }
                            }

                            if (equipment.getNextLeg(requestID) != null) {
                                LKAFlightKey equipmentlinkageLeg_Next = equipment
                                        .getNextLeg(requestID);
                                propagationEngineEventPayload
                                        .setNextFlightKey(equipmentlinkageLeg_Next);
                                if (!inboundResourceFltKeys.contains(equipmentlinkageLeg_Next)) {
                                    inboundResourceFltKeys.add(equipmentlinkageLeg_Next);
                                }
                                String equipmentNextLeg =
                                        equipmentlinkageLeg_Next.generateFlightKeyString()
                                                + PropagationEngineConstant.FLIGHT_KEY_RESOURCE_DELIMITER
                                                + FlightEventResourceType.EQUIPMENT.name();
                                if (!outboundResourceFltKeys.contains(equipmentNextLeg)) {
                                    outboundResourceFltKeys.add(equipmentNextLeg);
                                }
                            }
                            if (equipment.getAssignedEquipmentType() != null) {
                                propagationEngineEventPayload
                                        .setEquipmentType(equipment.getAssignedEquipmentType());
                            }
                            if (equipment.getTailNumber() != null) {
                                propagationEngineEventPayload
                                        .setTailNumber(equipment.getTailNumber(requestID));
                            }
                        }
                        if (equipment.getFlightKey() != null && !equipmentMap
                                .containsKey(equipment.getFlightKey())) {
                            equipmentMap.put(equipment.getFlightKey(), equipment);
                        }
                    }
                }
                //by pass if crewExcluded flag is set True
                if (!crewExcluded && crewFlightDetailList != null && !crewFlightDetailList
                        .isEmpty()) {
                    propagationEngineEventPayload.setCrewFlightDetailList(crewFlightDetailList);
                    String inboundTailNumber = null;
                    String inboundArrivalStaion = null;
                    String inboundAirlineCode = null;
                    for (CrewFlightDetail crewFltDetail : crewFlightDetailList) {
                        if (crewFltDetail != null) {
                            if (crewFltDetail.getPreviousFlightKey() != null) {
                                inboundAirlineCode = crewFltDetail.getPreviousFlightKey()
                                        .getAirlineCode();
                                if (crewFltDetail.getPreviousLegArrivalStation() != null) {
                                    inboundArrivalStaion = crewFltDetail
                                            .getPreviousLegArrivalStation();
                                }
                                Equipment previousEquipment = equipmentMap
                                        .get(crewFltDetail.getPreviousFlightKey());
                                if (previousEquipment != null) {
                                    inboundTailNumber = previousEquipment.getTailNumber();
                                }

                                propagationCrewDetail = new PropagationCrewDetail(departureStation, inboundArrivalStaion,
                                        departureTailNumber, inboundTailNumber, currentFlightKey.getAirlineCode(), inboundAirlineCode,
                                        crewFltDetail.getDutyKey(),crewFltDetail.getPreviousFlightKey(), crewFltDetail.getFlightKey());propagationCrewDetail.setCrewComingFrom(crewFltDetail.getCrewComingFrom());
                                propagationCrewDetail.setCurrentFlightCrewTypeCode(crewFltDetail.getCrewTypeCode());
                                propagationCrewDetail.setNextFlightCrewTypeCode(crewFltDetail.getNextFlightCrewTypeCode());

                                propagationCrewDetail.setNextFlightDepartureGate(flightRepository.getGatesById(crewFltDetail.getFlightKey()));
                                if(inboundArrivalStaion!= null)
                                {
                                    propagationCrewDetail.setCurrentFlightArrivalAirport(crewRepository.retrieveAirportDetails(inboundArrivalStaion));
                                }
                                propagationCrewDetail.setCurrentFlightArrivalGate(flightRepository.getGatesById(crewFltDetail.getPreviousFlightKey()));resourceReadyTimesMap.put(crewFltDetail.getDutyKey().toString(), propagationCrewDetail);
                                inboundArrivalStaion = null;
                                inboundTailNumber = null;inboundAirlineCode = null;
                            }
                        }
                    }
                }
            }

            if (!resourceReadyTimesMap.isEmpty()) {
                if(enableNewCrewTurnTimeLogic)
                {
                    String aa_Bases = null;
                    if(crewRepository != null )
                    {
                        aa_Bases =  new CrewPropertiesUtil(crewRepository).getCrewProperty("AA_BASES");
                    }
                    resourceReadyTimesMap = calculatorService.calculateCrewTurnTime(resourceReadyTimesMap,aa_Bases);
                }
                else
                {
                    resourceReadyTimesMap = calculatorService
                            .calculateCrewTurnTime(resourceReadyTimesMap);
                }
                propagationEngineEventPayload.setCrewTurnTimesMap(resourceReadyTimesMap);
            }
            if (outboundResourceFltKeys != null && !outboundResourceFltKeys.isEmpty()) {
                propagationEngineEventPayload.setNextLegFlightKeyList(outboundResourceFltKeys);
            }

            //FlightTimes query
            lkaFltKeysQueryArray = new LKAFlightKey[inboundResourceFltKeys.size()];
            lkaFltKeysQueryArray = inboundResourceFltKeys.toArray(lkaFltKeysQueryArray);
            List<FlightTimes> flightTimesList = flightRepository
                    .getFlightTimesByID_LKAFlightKeyList(lkaFltKeysQueryArray,
                            PROJECTIONCOLUMNS_FLIGHTTIMES);
            Map<LKAFlightKey, FlightTimes> fltTimesMap = new HashMap<LKAFlightKey, FlightTimes>();
            FlightTimes flightTimes_CurrentFlight = null;

            if (flightTimesList != null) {
                for (FlightTimes flightTimes : flightTimesList) {
                    if (flightTimes != null && !fltTimesMap
                            .containsKey(flightTimes.getFlightKey())) {
                        fltTimesMap.put(flightTimes.getFlightKey(), flightTimes);
                    }
                }

                if (fltTimesMap != null && !fltTimesMap.isEmpty()) {
                    flightTimes_CurrentFlight = fltTimesMap.get(currentFlightKey);
                    propagationEngineEventPayload.setFlightTimesMap(fltTimesMap);
                }

            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Inside routePropagationEngine(...): flightTimes_CurrentFlight: "
                        + flightTimes_CurrentFlight);
            }
            if (flightTimes_CurrentFlight != null) {

                propagationEngineEventPayload
                        .setCurrentFlightKey(currentFlightKey);
                propagationEngineEventPayload
                        .setCurrentFlightTimes(flightTimes_CurrentFlight);

                //Check for flight legStatus
                LKAFlightKey snapshotFlightKey = currentFlightKey.generateNonSnapshotFlightKey();
                snapshotFlightKey.setSnapshotId(requestID);
                Status status = getFlightLegStatusByFlightKey(snapshotFlightKey,
                        PROJECTIONCOLUMNS_LEGSTATUS);
                if (status != null) {
                    propagationEngineEventPayload.setFlightStatus(status);
                }
                else{
                    status = getFlightLegStatusByFlightKey(currentFlightKey,
                            PROJECTIONCOLUMNS_LEGSTATUS);
                    propagationEngineEventPayload.setFlightStatus(status);
                }
            }

            peFlightEvent.setEventPayload(propagationEngineEventPayload);

            if (LOG.isDebugEnabled()) {
                LOG.debug("Inside routePropagationEngine(...): peFlightEvent: " + peFlightEvent);
                LOG.debug("Inside routePropagationEngine(...): EventPayloadEvent: " + peFlightEvent
                        .getEventPayload());
            }
        } catch (Exception ex) {
            LOG.error("Inside routePropagationEngine(...): ", ex);
        }

        return peFlightEvent;
    }

    public Status getFlightLegStatusByFlightKey(LKAFlightKey flightKey,
                                                String... projectionValues) {
        try {

            return flightRepository.getStatusById(flightKey, projectionValues);
        } catch (Exception ex) {
            LOG.error("Unable to find leg status with details :" + CommonUtility
                    .getExceptionStackTrace(ex));
            return null;
        }
    }

    public PropagationTrackerRecord generateCompleteEvent(
            PropagationTrackerRecord propagationTrackerRecord) {
        if (propagationTrackerRecord != null) {
            Boolean status = true;
            if (propagationTrackerRecord.getInitialRootEvent() != null) {
                PE_EventFactory pe_EventFactory = new PE_EventFactory();
                PropagationEngineEvent peEvent = (PropagationEngineEvent) propagationTrackerRecord
                        .getInitialRootEvent();
                List<LKAFlightEvent> lkaEventList = new ArrayList<LKAFlightEvent>();
                LKAFlightEvent pe_completeEvent = pe_EventFactory
                        .createEvents(LKAEventType.PE_COMPLETE, null, peEvent.getFlightKey(), peEvent);
                if (propagationTrackerRecord.getFailed()) {
                    pe_completeEvent.getEventStatusDetails().setEventStatus(EventStatus.FAILED);
                }

                // Update processedFlagTrue for currentFlightKey in synchronized block
                lkaEventList.add(pe_completeEvent);
                status = calculatorService.publishEvents2Cache(lkaEventList, Boolean.TRUE);
            }
            if (!status) {
                propagationTrackerRecord.setFailed(Boolean.TRUE);
                LOG.info("PE_Complete event generate has issue for tracker record :"
                        + propagationTrackerRecord);
            }
            propagationTrackerRecord.setFinished(Boolean.TRUE);
        }
        return propagationTrackerRecord;
    }

    public void savePropagationDebug(final PropagationEngineCalculationValues values) {
        if (propagationEngineDAO != null && dynamicPropertyBean != null && dynamicPropertyBean.isPropagationEngineInsertEnabled()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (values.getPayload() != null) {
                            FlightResourceVO flightResourceVO = getMostRestrictiveResource(values.getResourceReadyTimes());
                            propagationEngineDAO.insertValues(values.getOriginFlightKey(), values.getPayload().getCurrentFlightTimes(), values.getEventType(), flightResourceVO);
                        }
                    } catch(Exception e) {
                        LOGDB.error("Error while inserting record to DB", e);
                    }
                }
            }).start();
        }
    }

    /**
     * Gets the most restrictive resource if any.
     *
     * @param flightResourceVOS - List of resources to loop thru and get the most restrictive based on resource ready time
     * @return {@code FlightResourceVO} object that is the most restrictive resource
     */
    private FlightResourceVO getMostRestrictiveResource(List<FlightResourceVO> flightResourceVOS) {
        FlightResourceVO flightResourceVO = null;
        if (flightResourceVOS != null && !flightResourceVOS.isEmpty()) {
            Collections.sort(flightResourceVOS, new Comparator<FlightResourceVO>() {
                @Override
                public int compare(FlightResourceVO o1, FlightResourceVO o2) {
                    long o1ReadyTime = o1.getResourceReadyTime();
                    long o2ReadyTime = o2.getResourceReadyTime();

                    int result = 0;

                    // sort in descending mode, most restrictive resource will be on the top of the list
                    if (o1ReadyTime < o2ReadyTime) {
                        result = 1;
                    } else if (o1ReadyTime > o2ReadyTime) {
                        result = -1;
                    }

                    return result;
                }
            });
            flightResourceVO = flightResourceVOS.get(0);
        }
        return flightResourceVO;
    }

    public DynamicPropertyBean getDynamicPropertyBean() {
        return dynamicPropertyBean;
    }

    public void setDynamicPropertyBean(DynamicPropertyBean dynamicPropertyBean) {
        this.dynamicPropertyBean = dynamicPropertyBean;
    }
    public Date calculateMntcArrivalTime(Date actualDepartureTime, Date actualArrivalTime, Date mntcDeparturetime) {
        if (mntcDeparturetime != null && actualDepartureTime != null && actualArrivalTime != null && mntcDeparturetime.after(actualDepartureTime)) {
            // Calculate the delay (difference) between mntcDeparturetime and actualDepartureTime
            long delayMillis = TimeUtils.getDiffInMilliSeconds(actualDepartureTime, mntcDeparturetime);

            // Adjust the actualArrivalTime by adding the delay
            return TimeUtils.plusMillisToDate(actualArrivalTime, (int) delayMillis);
        }
        return actualArrivalTime;
    }
    public Date calculateMntcDepartureTime(FlightTimes flightTimes, String requestId){
        Date actualDepartureTime = flightTimes.getProjectedDepartureTime(requestId);
        Date mntcDeparturetime = flightTimes.getProjectedMntcDepartureTime(requestId);
        return (mntcDeparturetime != null && actualDepartureTime != null && mntcDeparturetime.after(actualDepartureTime))
                ? mntcDeparturetime
                : actualDepartureTime;
    }

}
