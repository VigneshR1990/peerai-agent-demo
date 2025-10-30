# **PE_CANCEL EVENT - COMPREHENSIVE DOCUMENTATION**

## **📋 EVENT OVERVIEW**

**Event Type:** `PE_CANCEL`  
**Business Purpose:** Handle flight cancellation and reset all projected times to scheduled values  
**Processing Priority:** Critical - Immediate processing required  
**Code Location:** `PropagationEngineDelayCalculatorJob.java` Lines 470-475

---

## **🎯 BUSINESS LOGIC**

### **Primary Objective**
The PE_CANCEL event processes flight cancellations by resetting all propagation-calculated times back to the original scheduled times, effectively removing any delay propagation from the cancelled flight.

### **Business Rules**

#### **Rule 1: Complete Time Reset**
**Code Reference:** Lines 471-472
```java
flightTimes = substituteFlightTimesWithScheduleTimes(flightTimes, requestID);
operationFlightTimes = copyFlightTimesRequiredValue(flightTimes, operationFlightTimes, requestID);
```

**Business Logic:**
- All projected times (PTD, PTA, PCTD, PCTA) are reset to scheduled times
- Previous projected times are preserved for audit purposes
- Maintenance times are synchronized with scheduled times
- Latest maintenance times are reset

#### **Rule 2: Cancellation Validation**
**Code Reference:** Lines 468-782
```java
if (!isLegStatusCancelled) {
    switch (LKAEventType.valueOf(eventType)) {
        case PE_CANCEL:
            // ... cancellation logic
    }
} else {
    flightTimes = substituteFlightTimesWithScheduleTimes(flightTimes, requestID);
}
```

**Business Logic:**
- Checks `isLegStatusCancelled` flag before processing
- Three cancellation statuses recognized:
  - `CANCELLED_VIA_XL`
  - `CANCELLED`
  - `CANCELLED_VIA_PLAN`

---

## **⚙️ FUNCTIONALITY**

### **Input Data Requirements**

**Code Reference:** Lines 422-423
```java
FlightTimes flightTimes = propagationEngineEventPayload.getCurrentFlightTimes();
```

**Required Input Fields:**
1. **PropagationEngineEvent:**
   - `flightKey` - Flight identifier
   - `eventType` = "PE_CANCEL"
   - `requestID` - Snapshot or batch identifier
   - `eventPayload` - Contains flight times and status

2. **FlightTimes Object:**
   - `scheduledDepartureTime` - Original scheduled departure
   - `scheduledArrivalTime` - Original scheduled arrival
   - `projectedDepartureTime` - Current projected departure
   - `projectedArrivalTime` - Current projected arrival
   - `flightKey` - Flight identifier

3. **Status Information:**
   - `legStatus` - Must be CANCELLED variant

### **Processing Steps**

#### **Step 1: Event Reception**
**Code Reference:** Lines 469-470
```java
case PE_CANCEL:
    flightTimes = substituteFlightTimesWithScheduleTimes(flightTimes, requestID);
```

**Functionality:**
- Event enters switch statement based on event type
- Calls time substitution method immediately

#### **Step 2: Time Substitution**
**Code Reference:** Lines 2550-2582 (substituteFlightTimesWithScheduleTimes method)
```java
public FlightTimes substituteFlightTimesWithScheduleTimes(FlightTimes flightTimes, String requestID) {
    if (flightTimes != null) {
        // Preserve previous values
        flightTimes.setPreviousProjectedArrivalTime(flightTimes.getProjectedArrivalTime(requestID), requestID);
        flightTimes.setPreviousProjectedArrivalTime_controlled(flightTimes.getProjectedArrivalTime_Controlled(requestID), requestID);
        flightTimes.setPreviousProjectedDepartureTime(flightTimes.getProjectedDepartureTime(requestID), requestID);
        flightTimes.setPreviousProjectedDepartureTime_Controlled(flightTimes.getProjectedDepartureTime_Controlled(requestID), requestID);
        
        // Reset to scheduled times
        flightTimes.setProjectedArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedArrivalTime_Controlled(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
        flightTimes.setProjectedDepartureTime_Controlled(flightTimes.getScheduledDepartureTime(), requestID);
        
        // Reset maintenance times
        flightTimes.setPreviousProjectedLatestMntcDepartureTime(flightTimes.getProjectedLatestMntcDepartureTime(requestID), requestID);
        flightTimes.setPreviousProjectedLatestMntcArrivalTime(flightTimes.getProjectedLatestMntcArrivalTime(requestID), requestID);
        flightTimes.setProjectedLatestMntcDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
        flightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedMntcArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedMntcDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
    }
    return flightTimes;
}
```

**Functionality Details:**
- **Audit Trail:** Moves current projected times to "previous" fields
- **Time Reset:** Sets all projected times to scheduled times
- **Controlled Times:** Resets ATC-controlled times
- **Maintenance Times:** Resets all maintenance-related times
- **Snapshot Support:** Handles both live (requestID="0") and what-if scenarios

#### **Step 3: Operation Flight Times Copy**
**Code Reference:** Lines 473-474, 2584-2630 (copyFlightTimesRequiredValue method)
```java
operationFlightTimes = copyFlightTimesRequiredValue(flightTimes, operationFlightTimes, requestID);

// Implementation in copyFlightTimesRequiredValue
public FlightTimes copyFlightTimesRequiredValue(FlightTimes flightTimes, FlightTimes operationFlightTimes, String requestID) {
    if (operationFlightTimes == null) {
        operationFlightTimes = new FlightTimes(flightTimes.getFlightKey());
    }
    operationFlightTimes.setScheduledArrivalTime(flightTimes.getScheduledArrivalTime());
    operationFlightTimes.setScheduledDepartureTime(flightTimes.getScheduledDepartureTime());
    operationFlightTimes.setProjectedArrivalTime(flightTimes.getProjectedArrivalTime(requestID), requestID);
    operationFlightTimes.setProjectedDepartureTime(flightTimes.getProjectedDepartureTime(requestID), requestID);
    // ... copies all relevant time fields
}
```

**Functionality:**
- Creates operation object for database update
- Copies all scheduled and projected times
- Preserves snapshot data
- Includes GMT offset information

### **Output Data Generation**

#### **Change Detection**
**Code Reference:** Lines 2254-2412 (computeChangeSetFromFlightTimes method)
```java
private synchronized Map<String, Object> computeChangeSetFromFlightTimes(FlightTimes flightTimes, Boolean reseedForced, String requestID, RequestType requestType) {
    // Detects if projected times changed
    if (reseedForced || (flightTimes.getProjectedArrivalTime(requestID) != null && 
        !flightTimes.getProjectedArrivalTime(requestID).equals(flightTimes.getPreviousProjectedArrivalTime(requestID)))) {
        // Generate PTA event
        eventTypeList.add(LKAEventType.PTA);
    }
    // Similar for PTD, PTA_CONTROL, PTD_CONTROL
}
```

**Output Events Generated:**
1. **PTA (Projected Time Arrival)** - If arrival time changed
2. **PTD (Projected Time Departure)** - If departure time changed
3. **PTA_CONTROL** - If controlled arrival changed
4. **PTD_CONTROL** - If controlled departure changed

---

## **📊 DATA FLOW**

### **Flow Diagram**

```
┌─────────────────────────────────────────────────────────────────┐
│                     PE_CANCEL EVENT RECEIVED                     │
│                    (PropagationEngineEvent)                      │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              VALIDATE EVENT & FLIGHT KEY                         │
│         Lines 163-176: Request ID Processing                    │
│         Lines 422-423: Extract FlightTimes                       │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│         CHECK LEG STATUS FOR CANCELLATION                        │
│         Lines 456-466: LegStatus Validation                      │
│         - CANCELLED_VIA_XL                                       │
│         - CANCELLED                                              │
│         - CANCELLED_VIA_PLAN                                     │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│         SWITCH TO PE_CANCEL CASE HANDLER                         │
│         Lines 470-475: Case Statement                            │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│    SUBSTITUTE FLIGHT TIMES WITH SCHEDULED TIMES                  │
│    Lines 2550-2582: substituteFlightTimesWithScheduleTimes()     │
│                                                                   │
│    Operations Performed:                                         │
│    1. Save current projected times to previous fields            │
│    2. Set projectedDepartureTime = scheduledDepartureTime        │
│    3. Set projectedArrivalTime = scheduledArrivalTime            │
│    4. Set controlled times = scheduled times                     │
│    5. Reset maintenance times                                    │
│    6. Reset latest maintenance times                             │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│      COPY REQUIRED VALUES TO OPERATION FLIGHT TIMES              │
│      Lines 2584-2630: copyFlightTimesRequiredValue()             │
│                                                                   │
│      Fields Copied:                                              │
│      - scheduledDepartureTime                                    │
│      - scheduledArrivalTime                                      │
│      - projectedDepartureTime (now = scheduled)                  │
│      - projectedArrivalTime (now = scheduled)                    │
│      - All previous time fields                                  │
│      - Maintenance times                                         │
│      - Snapshot data                                             │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│         PROBLEM TYPE HANDLING (if enabled)                       │
│         Lines 785-833: handleProblemType()                       │
│                                                                   │
│         Creates/Updates Problem Types:                           │
│         - PROJECTED_DEP_DELAY → Inactive                         │
│         - PROJECTED_ARR_DELAY → Inactive                         │
│         - PROJECTED_CONTROLLED_DEP_DELAY → Inactive              │
│         - PROJECTED_CONTROLLED_ARR_DELAY → Inactive              │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│         COMPUTE CHANGE SET FROM FLIGHT TIMES                     │
│         Lines 857-866: computeChangeSetFromFlightTimes()         │
│                                                                   │
│         Detects Changes:                                         │
│         - projectedArrivalTime changed                           │
│         - projectedDepartureTime changed                         │
│         - Controlled times changed                               │
│         - Maintenance times changed                              │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│         PUBLISH FLIGHT TIMES TO CACHE                            │
│         Lines 876-902: publishFlightTime()                       │
│                                                                   │
│         Updates GigaSpaces with ChangeSet:                       │
│         - projectedDepartureTime                                 │
│         - projectedArrivalTime                                   │
│         - previousProjectedDepartureTime                         │
│         - previousProjectedArrivalTime                           │
│         - All controlled and maintenance times                   │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│         PUBLISH DOWNSTREAM EVENTS                                │
│         Lines 908-913: publishEvents()                           │
│         Lines 1222-1352: Event Publishing Logic                  │
│                                                                   │
│         Events Published:                                        │
│         - PTA (Projected Time Arrival)                           │
│         - PTD (Projected Time Departure)                         │
│         - PTA_CONTROL (if CTD exists)                            │
│         - PTD_CONTROL (if CTA exists)                            │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│    CHECK FOR PROPAGATION CONTINUATION                            │
│    Lines 913-917: checkPEContinuedforNextFlight()                │
│                                                                   │
│    Decision Logic:                                               │
│    - Compare scheduled vs projected times                        │
│    - If delay < 2 days AND flight date < 2 days from origin     │
│    - Result: Usually FALSE for cancellations                     │
│    - (Times are now equal to scheduled)                          │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│         PE_CALCULATED EVENT GENERATION                           │
│         Lines 1093-1107: Generate completion event               │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│    UPDATE PROPAGATION TRACKER RECORD                             │
│    Lines 1155-1167: Decrement pending count                      │
│    Mark flight as processed                                      │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              EVENT PROCESSING COMPLETE                           │
│         Status: EventStatus.DEBUG                                │
└─────────────────────────────────────────────────────────────────┘
```

---

## **🔄 DATA TRANSFORMATION**

### **Before PE_CANCEL Processing**

```java
FlightTimes (for AA123-01JAN):
├── scheduledDepartureTime: 2025-01-01 10:00:00
├── scheduledArrivalTime: 2025-01-01 12:00:00
├── projectedDepartureTime: 2025-01-01 10:45:00  // 45 min delay
├── projectedArrivalTime: 2025-01-01 12:45:00    // 45 min delay
├── previousProjectedDepartureTime: 2025-01-01 10:30:00
├── previousProjectedArrivalTime: 2025-01-01 12:30:00
├── projectedDepartureTime_Controlled: 2025-01-01 10:50:00
├── projectedArrivalTime_Controlled: 2025-01-01 12:50:00
├── projectedMntcDepartureTime: 2025-01-01 10:45:00
├── projectedMntcArrivalTime: 2025-01-01 12:45:00
├── projectedLatestMntcDepartureTime: 2025-01-01 10:45:00
└── projectedLatestMntcArrivalTime: 2025-01-01 12:45:00

Status:
├── legStatus: ACTIVE
├── departureStatus: null
└── arrivalStatus: null
```

### **After PE_CANCEL Processing**

```java
FlightTimes (for AA123-01JAN):
├── scheduledDepartureTime: 2025-01-01 10:00:00  // Unchanged
├── scheduledArrivalTime: 2025-01-01 12:00:00    // Unchanged
├── projectedDepartureTime: 2025-01-01 10:00:00  // ✓ Reset to scheduled
├── projectedArrivalTime: 2025-01-01 12:00:00    // ✓ Reset to scheduled
├── previousProjectedDepartureTime: 2025-01-01 10:45:00  // ✓ Preserved
├── previousProjectedArrivalTime: 2025-01-01 12:45:00    // ✓ Preserved
├── projectedDepartureTime_Controlled: 2025-01-01 10:00:00  // ✓ Reset
├── projectedArrivalTime_Controlled: 2025-01-01 12:00:00    // ✓ Reset
├── projectedMntcDepartureTime: 2025-01-01 10:00:00  // ✓ Reset
├── projectedMntcArrivalTime: 2025-01-01 12:00:00    // ✓ Reset
├── projectedLatestMntcDepartureTime: 2025-01-01 10:00:00  // ✓ Reset
└── projectedLatestMntcArrivalTime: 2025-01-01 12:00:00    // ✓ Reset

Status:
├── legStatus: CANCELLED  // ✓ Updated
├── departureStatus: null
└── arrivalStatus: null
```

---

## **📝 DOWNSTREAM EVENTS PUBLISHED**

### **Event 1: PTA (Projected Time Arrival)**
**Code Reference:** Lines 1245-1251
```java
case PTA:
    flightTimesChangeEventList.add(pe_EventFactory.createEvents(LKAEventType.PTA, flightTimes, propagationEngineEvent));
    break;
```

**Event Details:**
- **Event Type:** PTA
- **Flight Key:** AA123-01JAN
- **Previous PTA:** 12:45:00
- **Current PTA:** 12:00:00 (reset to scheduled)
- **Change Magnitude:** -45 minutes (improvement)

### **Event 2: PTD (Projected Time Departure)**
**Code Reference:** Lines 1253-1262
```java
case PTD:
    LKAFlightEvent ptdEvent = pe_EventFactory.createEvents(LKAEventType.PTD, flightTimes, propagationEngineEvent);
    ptdEvent.setPreviousFlightKey(previousEquipmentFlightKey);
    flightTimesChangeEventList.add(ptdEvent);
    break;
```

**Event Details:**
- **Event Type:** PTD
- **Flight Key:** AA123-01JAN
- **Previous PTD:** 10:45:00
- **Current PTD:** 10:00:00 (reset to scheduled)
- **Change Magnitude:** -45 minutes (improvement)
- **Previous Flight Key:** Equipment linkage preserved

### **Event 3: PTA_CONTROL (if applicable)**
Generated if controlled arrival time existed before cancellation.

### **Event 4: PTD_CONTROL (if applicable)**
Generated if controlled departure time existed before cancellation.

---

## **⚠️ SPECIAL CONSIDERATIONS**

### **1. Propagation Termination**
**Code Reference:** Lines 2429-2443
```java
if ((flightTimes.getProjectedArrivalTime(requestID) != null && 
     !flightTimes.getProjectedArrivalTime(requestID).equals(flightTimes.getPreviousProjectedArrivalTime(requestID)))) {
    // Check delay threshold
    long delayMilliSecond = TimeUtils.getDiffInMilliSeconds(/*...*/);
    if (delayMilliSecond < twoDaysInmillisecond) {
        returnPropagationContinue = Boolean.TRUE;
    }
}
```

**Business Impact:**
- After cancellation, projected times equal scheduled times
- No delay exists (0 delay)
- **Propagation stops** - No PE_CONTINUED events generated
- Downstream flights are NOT affected by this cancellation

### **2. Problem Type Deactivation**
**Code Reference:** Lines 2203-2248
```java
public void handleProblemType(FlightTimes flightTimes, boolean projected, boolean projectedControlled, boolean departures, Status flightStatus) throws Exception {
    boolean isInactive = problemTypeRepository.isProblemTypeInactive(flightTimes.getFlightKey(), departures, flightStatus);
    ProblemTypeDescription pt = new ProblemTypeDescription();
    pt.setActive(!isInactive);  // Sets to INACTIVE for cancelled flights
}
```

**Problem Types Affected:**
- `PROJECTED_DEP_DELAY` → Set to INACTIVE
- `PROJECTED_ARR_DELAY` → Set to INACTIVE
- `PROJECTED_CONTROLLED_DEP_DELAY` → Set to INACTIVE
- `PROJECTED_CONTROLLED_ARR_DELAY` → Set to INACTIVE

### **3. Snapshot Handling**
**Code Reference:** Lines 2260-2268
```java
if (flightTimes.getSnapshots().get(requestID) != null && !requestID.equalsIgnoreCase("0")) {
    changeSet.putInMap("snapshots", requestID, flightTimes.getSnapshots().get(requestID));
}
```

**Behavior:**
- **Live Mode (requestID="0"):** Updates master flight times
- **What-If Mode (requestID!="0"):** Updates only snapshot data
- Allows testing cancellation scenarios without affecting live operations

---

## **🔍 ERROR HANDLING**

### **Exception Scenarios**

**Code Reference:** Lines 1109-1122
```java
} catch (Exception ex) {
    if (LOG.isErrorEnabled()) {
        LOG.error("Propagation event handling error for event:" + propagationEngineEvent.toString() + " with details: ", ex);
    }
    processedError = true;
    propagationEngineEvent.getEventStatusDetails().setMessage(CommonUtility.getExceptionStackTrace(ex));
    CommonUtility.handleLKAException(propagationSpaceRepository.repoConn.getGigaSpace(), propagationEngineEvent, ex, "Error inside calculatePropagatedFlightTimes method");
}
```

**Error Handling:**
1. **Exception Logging:** Full stack trace logged
2. **Event Status:** Set to EventStatus.DEBUG or EXCEPTION
3. **Error Persistence:** Exception details stored in GigaSpaces
4. **Tracker Update:** PropagationTrackerRecord marked as failed
5. **Processing Continues:** Other flights not affected

---

## **🎓 BUSINESS EXAMPLES**

### **Example 1: Standard Cancellation**

**Scenario:**
Flight AA1234 from DFW to LAX scheduled at 10:00 AM is cancelled at 09:30 AM. The flight had a 30-minute delay (PTD = 10:30 AM).

**Input:**
```
Event: PE_CANCEL
Flight: AA1234-01JAN-DFW-LAX
scheduledDepartureTime: 10:00
projectedDepartureTime: 10:30 (30 min delay)
scheduledArrivalTime: 12:00
projectedArrivalTime: 12:30 (30 min delay)
```

**Processing:**
1. Save current projected times: PTD=10:30, PTA=12:30
2. Reset projected times: PTD=10:00, PTA=12:00
3. Generate events: PTD change, PTA change
4. Deactivate problem types
5. No propagation continuation (delay now = 0)

**Output:**
```
projectedDepartureTime: 10:00 (reset to scheduled)
projectedArrivalTime: 12:00 (reset to scheduled)
Events: PTA, PTD
Propagation: STOPPED
```

### **Example 2: Cancellation with Downstream Impact**

**Scenario:**
Flight AA1234 (DFW-LAX) is cancelled. Aircraft was scheduled to fly AA5678 (LAX-SFO) next.

**Impact:**
1. **AA1234:** Times reset to scheduled, no delay
2. **AA5678:** No longer receives delay propagation from AA1234
3. **Equipment Routing:** Next flight must find different aircraft
4. **Crew Impact:** Crew assignments cascade to other flights

**Note:** PE_CANCEL does NOT generate PE_CONTINUED events because there's no delay to propagate.

---

## **✅ VALIDATION CHECKLIST**

- ✓ **Flight Key exists and valid**
- ✓ **LegStatus is CANCELLED variant**
- ✓ **Scheduled times are available**
- ✓ **Request ID is valid**
- ✓ **Previous projected times preserved**
- ✓ **All time types reset (normal, controlled, maintenance)**
- ✓ **Downstream events generated**
- ✓ **Problem types deactivated**
- ✓ **PropagationTrackerRecord updated**
- ✓ **No PE_CONTINUED events generated**

---

## **🔗 RELATED EVENTS**

- **PE_REINSTATE:** Reverses cancellation effects
- **PE_OUT:** Cannot occur after cancellation
- **PE_IN:** Cannot occur after cancellation
- **PTA/PTD:** Generated as downstream events

---

## **📚 REFERENCES**

**Code Files:**
- `PropagationEngineDelayCalculatorJob.java` (Lines 470-475, 2550-2630)
- `PE_EventFactory.java` (Event creation)
- `PropagationSpaceRepository.java` (Data persistence)
