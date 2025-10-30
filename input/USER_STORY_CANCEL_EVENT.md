# User Story: CANCEL Event Implementation in PRISM Delay Processor

**Work Item ID:** PRISM-DELAY-US-003  
**Title:** Implement CANCEL Event Processing in PRISM Delay Processor  
**Priority:** P0 - Critical  
**Story Points:** 8  
**Sprint:** Sprint [TBD]  
**Assigned To:** [TBD]  
**Area Path:** PRISM/Delay Processor  
**Iteration Path:** Sprint [TBD]  

---

## 📋 Business Value

### Overview
The CANCEL event is a critical flight status event that handles flight cancellations by resetting all projected times back to scheduled times, effectively removing any delay propagation from the cancelled flight. This ensures that cancelled flights do not continue to propagate delays through the line of flying and provides accurate operational data for dispatchers and operations teams.

### Business Impact
- **Operational Accuracy**: Ensures cancelled flights are immediately reflected in delay calculations
- **Delay Propagation Control**: Prevents cancelled flights from incorrectly propagating delays to downstream flights
- **Resource Management**: Allows proper reallocation of aircraft and crew resources when flights are cancelled
- **Dispatcher Visibility**: Provides real-time cancellation status to operations teams
- **System Integrity**: Maintains data consistency between flight status and delay calculations

### Current Gap
Currently, the PRISM system:
- ✅ **Generates** CANCEL atomic events in Flight Processor (StatusEventProcessor)
- ❌ **Does NOT consume** CANCEL events in Delay Processor
- ❌ **Does NOT reset** projected times to scheduled times on cancellation
- ❌ **Does NOT stop** delay propagation for cancelled flights

This creates a critical gap where cancelled flights continue to show delays and propagate those delays downstream, leading to inaccurate operational data.

---

## 🎯 Event Generation Requirements

### Current State: ✅ IMPLEMENTED

The CANCEL event generation is **already implemented** in the Flight Processor's `StatusEventProcessor.java`.

**Implementation Location:**
- **File**: `StatusEventProcessor.java`
- **Method**: `determineStatusAtomicEvents()`
- **Lines**: 46-51

**Current Implementation:**
```java
if (Objects.equals(newObject.getLegStatus(), LegStatus.CANCELLED) || 
    Objects.equals(newObject.getLegStatus(), LegStatus.CANCELLED_VIA_XL)) {
    // don't create cancel event if flight was deleted (dep status cancelled)
    if (!Objects.equals(newObject.getDepartureStatus(), DepartureStatus.CANCELLED)) {
        atomicEvents.add(createAtomicEvent(newObject, newObject.getLegStatus(), 
                         OPSEventType.CANCEL.getValue()));
    }
}
```

**Trigger Conditions:**
1. LegStatus changes to `CANCELLED` or `CANCELLED_VIA_XL`
2. DepartureStatus is NOT `CANCELLED` (to avoid duplicate events for deleted flights)
3. FlightHub event is `CANCEL`

**Event Structure:**
```java
AtomicEvent {
    eventType: "CANCEL",
    flightKey: "AA1234-01JAN-DFW-LAX",
    value: "CANCELLED" or "CANCELLED_VIA_XL",
    sourceTimestamp: <cancellation time>,
    effectiveTimestamp: <processing time>
}
```

**No Changes Required** - Event generation is complete and functional.

---

## 🔄 Event Consumption Requirements

### Current State: ❌ NOT IMPLEMENTED

The CANCEL event is **NOT** currently consumed by the Delay Processor.

### Required Implementation

#### Step 1: Add CANCEL to FlightDelayEventEnum

**File**: `FlightDelayEventEnum.java`  
**Location**: `com.aa.opsco.prism.flink.enums`

**Current Enum:**
```java
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
```

**Required Change:**
```java
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
    CANCEL,  // ← ADD THIS
}
```

---

#### Step 2: Add CANCEL Case to DelayCalculations Switch Statement

**File**: `DelayCalculations.java`  
**Location**: `com.aa.opsco.prism.flink.functions`  
**Method**: `applyDelay()`

**Current Switch Statement (Lines 30-55):**
```java
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
```

**Required Change:**
```java
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
    case CANCEL:  // ← ADD THIS CASE
        LOG.info("Processing CANCEL event for flight: {}", lineOfFlying.get(i).get_id());
        isValidToPropagate = applyCancellation(lineOfFlying.get(i), isFirstFlight, i);
        break;
    default:
        break;
}
```

---

#### Step 3: Implement applyCancellation() Method

**File**: `DelayCalculations.java`  
**Location**: Add new private static method

**Implementation Based on Legacy Logic:**

```java
/**
 * Applies cancellation logic to a flight by resetting all projected times to scheduled times.
 * This method implements the legacy PE_CANCEL event behavior from PropagationEngineDelayCalculatorJob.
 * 
 * Legacy Reference:
 * - File: PropagationEngineDelayCalculatorJob.java
 * - Method: substituteFlightTimesWithScheduleTimes()
 * - Lines: 2550-2582
 * - Case: PE_CANCEL (lines 470-475)
 * 
 * Business Logic:
 * 1. Reset projectedDepartureTime to scheduledDepartureTime
 * 2. Reset projectedArrivalTime to scheduledArrivalTime
 * 3. Clear all resource ready times (equipment, cabin crew, cockpit crew)
 * 4. Stop propagation (return false) - cancelled flights don't delay downstream
 * 
 * @param line The LineOfFlying object representing the cancelled flight
 * @param isFirstFlight Whether this is the first flight in the line of flying
 * @param lofLevel The level in the line of flying (0-based index)
 * @return false - Always returns false to stop propagation
 */
private static boolean applyCancellation(LineOfFlying line, boolean isFirstFlight, int lofLevel) {
    LOG.info("applyCancellation() - Processing cancellation for flight: {}, isFirstFlight: {}, lofLevel: {}", 
             line.get_id(), isFirstFlight, lofLevel);
    
    // Preserve previous projected times for audit trail
    long previousProjectedDepartureTime = line.getProjectedDepartureTime();
    long previousProjectedArrivalTime = line.getProjectedArrivalTime();
    
    LOG.debug("applyCancellation() - Before reset: PTD={}, PTA={}", 
              new Date(previousProjectedDepartureTime), 
              new Date(previousProjectedArrivalTime));
    
    // Reset projected times to scheduled times
    // This is the core cancellation logic from legacy system
    line.setProjectedDepartureTime(line.getScheduledDepartureTime());
    line.setProjectedArrivalTime(line.getScheduledArrivalTime());
    
    // Clear all resource ready times
    // Cancelled flights have no resource constraints
    line.setEquipmentResourceReadyTime(0L);
    line.setCabinResourceReadyTime(0L);
    line.setCockpitResourceReadyTime(0L);
    
    LOG.info("applyCancellation() - After reset: PTD={}, PTA={} (reset to scheduled)", 
             new Date(line.getProjectedDepartureTime()), 
             new Date(line.getProjectedArrivalTime()));
    
    LOG.info("applyCancellation() - Cleared resource ready times for flight: {}", line.get_id());
    
    // Calculate time difference for logging
    long departureTimeDiff = line.getScheduledDepartureTime() - previousProjectedDepartureTime;
    long arrivalTimeDiff = line.getScheduledArrivalTime() - previousProjectedArrivalTime;
    
    LOG.info("applyCancellation() - Time adjustments: Departure {} minutes, Arrival {} minutes", 
             departureTimeDiff / 60000, arrivalTimeDiff / 60000);
    
    // CRITICAL: Return false to stop propagation
    // Cancelled flights should NOT propagate delays to downstream flights
    // This matches legacy behavior where PE_CANCEL does not generate PE_CONTINUED events
    LOG.info("applyCancellation() - Stopping propagation for cancelled flight: {}", line.get_id());
    
    return false;
}
```

**Key Implementation Notes:**

1. **Time Reset Logic**: Directly sets projected times equal to scheduled times, removing any delay
2. **Resource Ready Times**: All set to 0 (or null) since cancelled flights have no resource constraints
3. **Propagation Control**: Returns `false` to stop the delay propagation chain
4. **Audit Trail**: Logs previous values before reset for troubleshooting
5. **Legacy Alignment**: Matches the behavior of `substituteFlightTimesWithScheduleTimes()` from legacy code

---

## 💾 MongoDB Persistence Requirements

### Collection: `peFlight`

**Fields Updated by CANCEL Event:**

| Field Name | Type | Value After CANCEL | Description |
|------------|------|-------------------|-------------|
| `projectedDepartureTime` | Long (epoch ms) | = `scheduledDepartureTime` | Reset to scheduled |
| `projectedArrivalTime` | Long (epoch ms) | = `scheduledArrivalTime` | Reset to scheduled |
| `equipmentResourceReadyTime` | Long (epoch ms) | 0 or null | Cleared |
| `cabinResourceReadyTime` | Long (epoch ms) | 0 or null | Cleared |
| `cockpitResourceReadyTime` | Long (epoch ms) | 0 or null | Cleared |
| `legStatus` | String | "CANCELLED" or "CANCELLED_VIA_XL" | From event |
| `updateDate` | Date | Current timestamp | Last update time |

**Example MongoDB Document (Before CANCEL):**
```javascript
{
  "_id": "AA1234-01JAN-DFW-LAX",
  "flightKey": "AA1234-01JAN-DFW-LAX",
  "scheduledDepartureTime": 1704117600000,  // 10:00 AM
  "scheduledArrivalTime": 1704124800000,    // 12:00 PM
  "projectedDepartureTime": 1704120300000,  // 10:45 AM (45 min delay)
  "projectedArrivalTime": 1704128100000,    // 12:45 PM (45 min delay)
  "equipmentResourceReadyTime": 1704120300000,
  "cabinResourceReadyTime": 1704119400000,
  "cockpitResourceReadyTime": 1704118500000,
  "legStatus": "ACTIVE",
  "updateDate": "2024-01-01T09:30:00Z"
}
```

**Example MongoDB Document (After CANCEL):**
```javascript
{
  "_id": "AA1234-01JAN-DFW-LAX",
  "flightKey": "AA1234-01JAN-DFW-LAX",
  "scheduledDepartureTime": 1704117600000,  // 10:00 AM (unchanged)
  "scheduledArrivalTime": 1704124800000,    // 12:00 PM (unchanged)
  "projectedDepartureTime": 1704117600000,  // 10:00 AM ← RESET TO SCHEDULED
  "projectedArrivalTime": 1704124800000,    // 12:00 PM ← RESET TO SCHEDULED
  "equipmentResourceReadyTime": 0,          // ← CLEARED
  "cabinResourceReadyTime": 0,              // ← CLEARED
  "cockpitResourceReadyTime": 0,            // ← CLEARED
  "legStatus": "CANCELLED",                 // ← UPDATED
  "updateDate": "2024-01-01T09:45:00Z"      // ← UPDATED
}
```

**Persistence Flow:**
1. DelayCalculations.applyCancellation() updates LineOfFlying object
2. DelayRichFlatMap sends updated LineOfFlying to gRPC service
3. gRPC service (FlightPathService) updates MongoDB peFlight collection
4. MongoDB update includes all modified fields with current timestamp

---

## ✅ Acceptance Criteria

### Event Generation (Already Complete)
- [x] CANCEL atomic event generated when LegStatus changes to CANCELLED or CANCELLED_VIA_XL
- [x] Event includes correct flight key and cancellation status
- [x] Event published to Event Hub (atomicprismevents topic)
- [x] Duplicate events prevented for deleted flights (DepartureStatus = CANCELLED)

### Event Consumption (To Be Implemented)
- [ ] CANCEL added to FlightDelayEventEnum
- [ ] CANCEL case added to DelayCalculations switch statement
- [ ] applyCancellation() method implemented with correct logic
- [ ] Method resets projectedDepartureTime to scheduledDepartureTime
- [ ] Method resets projectedArrivalTime to scheduledArrivalTime
- [ ] Method clears all resource ready times (equipment, cabin, cockpit)
- [ ] Method returns false to stop propagation
- [ ] Logging added at INFO level for cancellation processing
- [ ] Logging added at DEBUG level for time value changes

### Data Persistence
- [ ] Updated LineOfFlying sent to gRPC service
- [ ] MongoDB peFlight document updated with reset times
- [ ] MongoDB peFlight document updated with cleared resource times
- [ ] MongoDB peFlight document updated with CANCELLED leg status
- [ ] updateDate field set to current timestamp

### Testing
- [ ] Unit tests pass with >80% code coverage
- [ ] Unit test: applyCancellation() resets times correctly
- [ ] Unit test: applyCancellation() clears resource ready times
- [ ] Unit test: applyCancellation() returns false (stops propagation)
- [ ] Integration test: End-to-end CANCEL event flow
- [ ] Integration test: Cancelled flight does not propagate to next flight
- [ ] Integration test: MongoDB persistence verified
- [ ] Integration test: Multiple flights in LOF, only first cancelled

### Documentation
- [ ] Code comments added to applyCancellation() method
- [ ] JavaDoc added with legacy reference
- [ ] README updated with CANCEL event description
- [ ] Architecture diagram updated to show CANCEL flow

---

## 📝 Implementation Tasks

### Task 1: Update FlightDelayEventEnum
**Estimated Time:** 15 minutes  
**Description:** Add CANCEL to the enum  
**Files:**
- `FlightDelayEventEnum.java`

**Steps:**
1. Open `FlightDelayEventEnum.java`
2. Add `CANCEL,` to the enum list
3. Ensure proper comma placement
4. Compile and verify no syntax errors

---

### Task 2: Add CANCEL Case to Switch Statement
**Estimated Time:** 30 minutes  
**Description:** Add CANCEL case to DelayCalculations.applyDelay()  
**Files:**
- `DelayCalculations.java`

**Steps:**
1. Open `DelayCalculations.java`
2. Locate the `applyDelay()` method switch statement
3. Add new case before `default:`
4. Add logging statement
5. Call `applyCancellation()` method
6. Compile and verify

---

### Task 3: Implement applyCancellation() Method
**Estimated Time:** 2 hours  
**Description:** Implement the core cancellation logic  
**Files:**
- `DelayCalculations.java`

**Steps:**
1. Create new private static method `applyCancellation()`
2. Add method signature with parameters
3. Add JavaDoc with legacy reference
4. Implement time reset logic
5. Implement resource ready time clearing
6. Add comprehensive logging
7. Return false to stop propagation
8. Code review with team
9. Compile and verify

---

### Task 4: Write Unit Tests
**Estimated Time:** 3 hours  
**Description:** Create comprehensive unit tests  
**Files:**
- `DelayCalculationsTest.java` (new or existing)

**Test Cases:**
1. **testApplyCancellation_ResetsProjectedDepartureTime**
   - Verify PTD reset to scheduled
2. **testApplyCancellation_ResetsProjectedArrivalTime**
   - Verify PTA reset to scheduled
3. **testApplyCancellation_ClearsEquipmentResourceReadyTime**
   - Verify equipment time cleared
4. **testApplyCancellation_ClearsCabinResourceReadyTime**
   - Verify cabin crew time cleared
5. **testApplyCancellation_ClearsCockpitResourceReadyTime**
   - Verify cockpit crew time cleared
6. **testApplyCancellation_StopsPropagation**
   - Verify method returns false
7. **testApplyCancellation_FirstFlightInLOF**
   - Test with isFirstFlight = true
8. **testApplyCancellation_SubsequentFlightInLOF**
   - Test with isFirstFlight = false
9. **testApplyDelay_CancelEventType**
   - Test full applyDelay() flow with CANCEL event

---

### Task 5: Write Integration Tests
**Estimated Time:** 4 hours  
**Description:** Create end-to-end integration tests  
**Files:**
- `DelayProcessorIntegrationTest.java` (new or existing)

**Test Scenarios:**
1. **testCancelEvent_EndToEnd**
   - Send CANCEL event through full pipeline
   - Verify MongoDB update
2. **testCancelEvent_StopsPropagation**
   - Create LOF with 3 flights
   - Cancel first flight
   - Verify second flight not updated
3. **testCancelEvent_WithExistingDelay**
   - Flight has 45-minute delay
   - Send CANCEL event
   - Verify times reset to scheduled
4. **testCancelEvent_MultipleFlightsInLOF**
   - LOF with 5 flights
   - Cancel middle flight
   - Verify propagation stops at cancelled flight

---

### Task 6: Update Documentation
**Estimated Time:** 1 hour  
**Description:** Update all relevant documentation  
**Files:**
- `README.md`
- `ARCHITECTURE.md`
- `EVENT_PROCESSING.md`

**Updates:**
1. Add CANCEL to list of supported events
2. Document cancellation behavior
3. Update architecture diagram
4. Add troubleshooting section for CANCEL events

---

### Task 7: Code Review and Merge
**Estimated Time:** 2 hours  
**Description:** Team review and merge to main branch  

**Steps:**
1. Create pull request
2. Request reviews from 2+ team members
3. Address review comments
4. Run full test suite
5. Verify CI/CD pipeline passes
6. Merge to main branch

---

### Task 8: Deploy and Monitor
**Estimated Time:** 2 hours  
**Description:** Deploy to test environment and monitor  

**Steps:**
1. Deploy to DEV environment
2. Run smoke tests
3. Monitor logs for CANCEL events
4. Verify MongoDB updates
5. Deploy to QA environment
6. Coordinate with QA team for testing
7. Monitor production-like scenarios

---

## 📚 Legacy Code References

### Primary Reference: PropagationEngineDelayCalculatorJob.java

**Full Path:** `/Users/rvinayagam/Downloads/IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/cache/propagationengine/Job/PropagationEngineDelayCalculatorJob.java`

| Component | Method/Section | Line Numbers | Description |
|-----------|---------------|--------------|-------------|
| **Event Case Handler** | `calculatePropagatedFlightTimes()` | 470-475 | PE_CANCEL case in switch statement |
| **Time Reset Logic** | `substituteFlightTimesWithScheduleTimes()` | 2550-2582 | Core cancellation logic - resets all times |
| **Field Copy Logic** | `copyFlightTimesRequiredValue()` | 2584-2630 | Copies reset values to operation object |
| **Change Detection** | `computeChangeSetFromFlightTimes()` | 2254-2412 | Detects time changes and generates events |
| **Problem Type Handling** | `handleProblemType()` | 2203-2248 | Deactivates delay problem types |
| **Propagation Check** | `checkPEContinuedforNextFlight()` | 2429-2443 | Determines if propagation continues (returns false for CANCEL) |

**Key Code Snippet (Lines 470-475):**
```java
case PE_CANCEL:
    flightTimes = substituteFlightTimesWithScheduleTimes(flightTimes, requestID);
    operationFlightTimes = copyFlightTimesRequiredValue(flightTimes, operationFlightTimes, requestID);
    break;
```

**Key Code Snippet (Lines 2550-2582):**
```java
public FlightTimes substituteFlightTimesWithScheduleTimes(FlightTimes flightTimes, String requestID) {
    if (flightTimes != null) {
        // Preserve previous values
        flightTimes.setPreviousProjectedArrivalTime(flightTimes.getProjectedArrivalTime(requestID), requestID);
        flightTimes.setPreviousProjectedArrivalTime_controlled(
            flightTimes.getProjectedArrivalTime_Controlled(requestID), requestID);
        flightTimes.setPreviousProjectedDepartureTime(flightTimes.getProjectedDepartureTime(requestID), requestID);
        flightTimes.setPreviousProjectedDepartureTime_Controlled(
            flightTimes.getProjectedDepartureTime_Controlled(requestID), requestID);
        
        // Reset to scheduled times
        flightTimes.setProjectedArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedArrivalTime_Controlled(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
        flightTimes.setProjectedDepartureTime_Controlled(flightTimes.getScheduledDepartureTime(), requestID);
        
        // Reset maintenance times
        flightTimes.setPreviousProjectedLatestMntcDepartureTime(
            flightTimes.getProjectedLatestMntcDepartureTime(requestID), requestID);
        flightTimes.setPreviousProjectedLatestMntcArrivalTime(
            flightTimes.getProjectedLatestMntcArrivalTime(requestID), requestID);
        flightTimes.setProjectedLatestMntcDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
        flightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedMntcArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedMntcDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
    }
    return flightTimes;
}
```

---

### Secondary Reference: CancelEvent.java

**Full Path:** `/Users/rvinayagam/Downloads/IPS_LKA2.0-Release/DataModels/src/main/java/com/aa/lookahead/dataobjects/event/lookahead/CancelEvent.java`

| Component | Line Numbers | Description |
|-----------|--------------|-------------|
| **Event Class Definition** | 1-120 | Complete CancelEvent class |
| **Constructor** | 20-24 | Creates event from Status object |
| **Fields** | 15-16 | legStatus, previousLegStatus |
| **Event Type** | 22 | LKAEventType.CANCEL |

**Key Code Snippet:**
```java
public CancelEvent(Status status) {
    super(LKAEventType.CANCEL, Calendar.getInstance().getTime(), status.getFlightKey());
    this.legStatus = status.getLegStatus();
    this.previousLegStatus = status.getPreviousLegStatus();
}
```

---

### Modernized Reference: StatusEventProcessor.java

**Full Path:** `./input/StatusEventProcessor.java`

| Component | Method | Line Numbers | Description |
|-----------|--------|--------------|-------------|
| **Event Generation** | `determineStatusAtomicEvents()` | 46-51 | Generates CANCEL atomic event |
| **Cancellation Check** | `determineStatusAtomicEvents()` | 46-51 | Checks for CANCELLED or CANCELLED_VIA_XL |
| **Duplicate Prevention** | `determineStatusAtomicEvents()` | 48 | Prevents duplicate for deleted flights |

**Key Code Snippet (Lines 46-51):**
```java
if (Objects.equals(newObject.getLegStatus(), LegStatus.CANCELLED) || 
    Objects.equals(newObject.getLegStatus(), LegStatus.CANCELLED_VIA_XL)) {
    // don't create cancel event if flight was deleted (dep status cancelled)
    if (!Objects.equals(newObject.getDepartureStatus(), DepartureStatus.CANCELLED)) {
        atomicEvents.add(createAtomicEvent(newObject, newObject.getLegStatus(), 
                         OPSEventType.CANCEL.getValue()));
    }
}
```

---

## 🔧 Technical Notes

### Design Patterns
1. **Strategy Pattern**: Each event type has its own processing method (applyCancellation, applyDepartureDelay, etc.)
2. **Chain of Responsibility**: Events flow through the delay calculation chain until propagation stops
3. **Immutable Data**: LineOfFlying objects are updated but not replaced, maintaining reference integrity

### Dependencies
- **Flink Streaming**: Event processing framework
- **gRPC**: Communication with FlightPathService for MongoDB updates
- **MongoDB**: Persistent storage for flight delay data
- **Event Hub**: Message broker for atomic events

### Performance Considerations
1. **Propagation Stop**: CANCEL events stop propagation immediately, reducing downstream processing
2. **Minimal Calculations**: Only time resets, no complex calculations required
3. **Resource Clearing**: Setting to 0 is faster than null checks
4. **Logging Level**: Use INFO for key events, DEBUG for detailed values

### Error Handling
1. **Null Safety**: Check for null LineOfFlying before processing
2. **Exception Handling**: Wrap in try-catch to prevent pipeline failure
3. **Logging**: Log all errors with full context (flight key, event type, timestamp)
4. **Fallback**: If cancellation fails, log error but don't crash pipeline

### Edge Cases
1. **Already Cancelled**: If flight already cancelled, skip processing
2. **No Scheduled Times**: If scheduled times missing, log error and skip
3. **Multiple Cancellations**: Handle duplicate CANCEL events gracefully
4. **Partial LOF**: If LOF has only one flight, still process correctly

---

## 🧪 Testing Strategy

### Unit Tests (Target: >80% Coverage)

**Test Class:** `DelayCalculationsTest.java`

**Test Methods:**
```java
@Test
public void testApplyCancellation_ResetsProjectedDepartureTime() {
    // Given: Flight with 45-minute delay
    LineOfFlying flight = createFlightWithDelay(45);
    
    // When: Apply cancellation
    boolean result = DelayCalculations.applyCancellation(flight, true, 0);
    
    // Then: PTD reset to scheduled
    assertEquals(flight.getScheduledDepartureTime(), flight.getProjectedDepartureTime());
    assertFalse(result); // Propagation stopped
}

@Test
public void testApplyCancellation_ResetsProjectedArrivalTime() {
    // Given: Flight with 45-minute delay
    LineOfFlying flight = createFlightWithDelay(45);
    
    // When: Apply cancellation
    boolean result = DelayCalculations.applyCancellation(flight, true, 0);
    
    // Then: PTA reset to scheduled
    assertEquals(flight.getScheduledArrivalTime(), flight.getProjectedArrivalTime());
    assertFalse(result);
}

@Test
public void testApplyCancellation_ClearsAllResourceReadyTimes() {
    // Given: Flight with resource ready times set
    LineOfFlying flight = createFlightWithResources();
    
    // When: Apply cancellation
    DelayCalculations.applyCancellation(flight, true, 0);
    
    // Then: All resource times cleared
    assertEquals(0L, flight.getEquipmentResourceReadyTime());
    assertEquals(0L, flight.getCabinResourceReadyTime());
    assertEquals(0L, flight.getCockpitResourceReadyTime());
}

@Test
public void testApplyCancellation_StopsPropagation() {
    // Given: Flight in LOF
    LineOfFlying flight = createFlight();
    
    // When: Apply cancellation
    boolean result = DelayCalculations.applyCancellation(flight, true, 0);
    
    // Then: Returns false to stop propagation
    assertFalse(result);
}

@Test
public void testApplyDelay_CancelEventType() {
    // Given: CANCEL event and LOF with 3 flights
    FlightDelayEvent cancelEvent = createCancelEvent();
    List<LineOfFlying> lof = createLineOfFlying(3);
    
    // When: Apply delay
    List<LineOfFlying> result = DelayCalculations.applyDelay(cancelEvent, lof, null);
    
    // Then: Only first flight processed, propagation stopped
    assertEquals(1, result.size());
    assertEquals(lof.get(0).getScheduledDepartureTime(), 
                 result.get(0).getProjectedDepartureTime());
}
```

---

### Integration Tests

**Test Class:** `DelayProcessorIntegrationTest.java`

**Test Scenarios:**

```java
@Test
public void testCancelEvent_EndToEnd() {
    // Given: Flight with delay in MongoDB
    String flightKey = "AA1234-01JAN-DFW-LAX";
    createFlightInMongoDB(flightKey, 45); // 45 min delay
    
    // When: Send CANCEL atomic event
    sendAtomicEvent(createCancelEvent(flightKey));
    
    // Wait for processing
    Thread.sleep(5000);
    
    // Then: MongoDB updated with reset times
    PeFlight flight = getFlightFromMongoDB(flightKey);
    assertEquals(flight.getScheduledDepartureTime(), flight.getProjectedDepartureTime());
    assertEquals(flight.getScheduledArrivalTime(), flight.getProjectedArrivalTime());
    assertEquals("CANCELLED", flight.getLegStatus());
}

@Test
public void testCancelEvent_StopsPropagation() {
    // Given: LOF with 3 flights, all with delays
    String flight1 = "AA1234-01JAN-DFW-LAX";
    String flight2 = "AA1234-01JAN-LAX-SFO";
    String flight3 = "AA1234-01JAN-SFO-SEA";
    createLineOfFlyingInMongoDB(flight1, flight2, flight3);
    
    // When: Cancel first flight
    sendAtomicEvent(createCancelEvent(flight1));
    
    // Wait for processing
    Thread.sleep(5000);
    
    // Then: First flight reset, others unchanged
    PeFlight f1 = getFlightFromMongoDB(flight1);
    PeFlight f2 = getFlightFromMongoDB(flight2);
    PeFlight f3 = getFlightFromMongoDB(flight3);
    
    assertEquals(f1.getScheduledDepartureTime(), f1.getProjectedDepartureTime());
    assertNotEquals(f2.getScheduledDepartureTime(), f2.getProjectedDepartureTime()); // Still delayed
    assertNotEquals(f3.getScheduledDepartureTime(), f3.getProjectedDepartureTime()); // Still delayed
}

@Test
public void testCancelEvent_WithMultipleResourceDelays() {
    // Given: Flight delayed by equipment, cabin crew, and cockpit crew
    String flightKey = "AA1234-01JAN-DFW-LAX";
    createFlightWithMultipleResourceDelays(flightKey);
    
    // When: Cancel flight
    sendAtomicEvent(createCancelEvent(flightKey));
    
    // Wait for processing
    Thread.sleep(5000);
    
    // Then: All resource times cleared
    PeFlight flight = getFlightFromMongoDB(flightKey);
    assertEquals(0L, flight.getEquipmentResourceReadyTime());
    assertEquals(0L, flight.getCabinResourceReadyTime());
    assertEquals(0L, flight.getCockpitResourceReadyTime());
}
```

---

### Manual Testing Checklist

- [ ] **Scenario 1**: Cancel flight with no delay
  - Expected: Times remain at scheduled, no changes
- [ ] **Scenario 2**: Cancel flight with 30-minute delay
  - Expected: Times reset to scheduled, 30-minute improvement
- [ ] **Scenario 3**: Cancel first flight in LOF of 5 flights
  - Expected: Only first flight updated, others unchanged
- [ ] **Scenario 4**: Cancel middle flight in LOF
  - Expected: Flights before cancelled flight updated, flights after unchanged
- [ ] **Scenario 5**: Cancel flight with equipment delay only
  - Expected: Equipment resource time cleared
- [ ] **Scenario 6**: Cancel flight with crew delays
  - Expected: All crew resource times cleared
- [ ] **Scenario 7**: Receive duplicate CANCEL events
  - Expected: Handled gracefully, no errors
- [ ] **Scenario 8**: Cancel already cancelled flight
  - Expected: No changes, logged as duplicate

---

## 📊 Success Metrics

### Functional Metrics
- **Event Processing Rate**: 100% of CANCEL events processed successfully
- **Time Reset Accuracy**: 100% of cancelled flights have PTD = STD and PTA = STA
- **Propagation Stop Rate**: 100% of cancelled flights stop propagation
- **Resource Clearing Rate**: 100% of resource ready times cleared

### Performance Metrics
- **Processing Latency**: < 500ms from event receipt to MongoDB update
- **Throughput**: Handle 1000+ CANCEL events per minute
- **Error Rate**: < 0.1% processing errors

### Quality Metrics
- **Code Coverage**: > 80% for new code
- **Test Pass Rate**: 100% of unit and integration tests pass
- **Code Review**: Approved by 2+ senior engineers
- **Documentation**: Complete JavaDoc and README updates

---

## 🚀 Deployment Plan

### Phase 1: Development (Week 1)
- Day 1-2: Implement enum and switch case
- Day 3-4: Implement applyCancellation() method
- Day 5: Code review and refinement

### Phase 2: Testing (Week 2)
- Day 1-2: Write and run unit tests
- Day 3-4: Write and run integration tests
- Day 5: Performance testing and optimization

### Phase 3: Deployment (Week 3)
- Day 1: Deploy to DEV environment
- Day 2-3: QA testing in DEV
- Day 4: Deploy to QA environment
- Day 5: User acceptance testing

### Phase 4: Production (Week 4)
- Day 1: Deploy to PROD during maintenance window
- Day 2-5: Monitor production, address any issues

---

## 📞 Support and Contacts

### Development Team
- **Lead Developer**: [Name]
- **Backend Engineer**: [Name]
- **QA Engineer**: [Name]

### Stakeholders
- **Product Owner**: [Name]
- **Operations Manager**: [Name]
- **Dispatcher Representative**: [Name]

### Related Documentation
- [PRISM Architecture Overview](link)
- [Delay Processor Design](link)
- [Event Processing Guide](link)
- [MongoDB Schema](link)

---

## 📝 Notes and Assumptions

### Assumptions
1. CANCEL events are always generated for cancelled flights
2. Scheduled times are always available in the database
3. MongoDB is the source of truth for flight delay data
4. gRPC service is available and responsive

### Known Limitations
1. Does not handle reinstatement (separate REINSTATE event)
2. Does not generate downstream events (PTA, PTD) for cancellations
3. Does not update problem types (future enhancement)

### Future Enhancements
1. Add support for partial cancellations (specific legs)
2. Implement cancellation reason tracking
3. Add metrics dashboard for cancellation analytics
4. Implement automatic notification to dispatchers

---

**End of User Story**

---

**Generated:** 2024-01-15  
**Last Updated:** 2024-01-15  
**Version:** 1.0  
**Status:** Ready for Implementation
