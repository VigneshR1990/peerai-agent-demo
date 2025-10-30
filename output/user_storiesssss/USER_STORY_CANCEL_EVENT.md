# User Story: CANCEL Event Implementation in PRISM Delay Processor

**Work Item ID:** PRISM-DELAY-US-003  
**Title:** Implement CANCEL Event Processing in PRISM Delay Processor  
**Priority:** P0 (Critical)  
**Story Points:** 8  
**Sprint:** Sprint [TBD]  
**Assigned To:** [TBD]  
**Area Path:** PRISM/Delay Processor  
**Iteration Path:** Sprint [TBD]  

---

## 📋 Business Value

### Overview
The CANCEL event is a **critical** event that handles flight cancellations in the delay propagation system. When a flight is cancelled, all projected times must be reset to scheduled times, and delay propagation must stop immediately to prevent cancelled flights from affecting downstream operations.

### Business Impact
- **Operational Accuracy**: Ensures cancelled flights do not propagate delays to subsequent flights
- **Resource Management**: Prevents incorrect resource allocation based on cancelled flight projections
- **Dispatcher Decision Support**: Provides accurate flight status for operational planning
- **System Integrity**: Maintains data consistency when flights are cancelled

### Current Gap
**Flight Processor**: ✅ Already generates CANCEL atomic events when leg status changes to CANCELLED  
**Delay Processor**: ❌ CANCEL event is **NOT** in `FlightDelayEventEnum` and is **NOT** processed  
**Result**: CANCEL events are generated but filtered out and never consumed, leaving cancelled flights with incorrect projected times

---

## 🎯 User Story

**As a** Flight Dispatcher  
**I need** cancelled flights to immediately reset their projected times to scheduled times and stop propagating delays  
**So that** downstream flights are not incorrectly delayed by cancelled flights and resources can be reallocated accurately

---

## 📥 Event Generation Requirements

### Current State: ✅ IMPLEMENTED

The Flight Processor **already generates** CANCEL atomic events in `StatusEventProcessor.java`.

**Implementation Location:**
- **File**: `StatusEventProcessor.java`
- **Method**: `determineStatusAtomicEvents()`
- **Lines**: 40-45

**Current Code:**
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
1. Leg status changes to `CANCELLED` or `CANCELLED_VIA_XL`
2. Departure status is NOT `CANCELLED` (to avoid duplicate events for deleted flights)
3. FlightHub event is `CANCEL`

**Event Structure:**
```java
AtomicEvent {
    eventType: "CANCEL",
    flightKey: "AA1234-01JAN-DFW-LAX",
    value: "CANCELLED" or "CANCELLED_VIA_XL",
    sourceTimestamp: <cancellation time>,
    effectiveTimestamp: <event processing time>
}
```

**No Changes Required** - Event generation is complete and functional.

---

## 📤 Event Consumption Requirements

### Current State: ❌ NOT IMPLEMENTED

The Delay Processor does **NOT** consume CANCEL events. Implementation is required.

### Step 1: Add CANCEL to FlightDelayEventEnum

**File**: `FlightDelayEventEnum.java`  
**Location**: `com.aa.opsco.prism.flink.enums`

**Required Change:**
```java
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
    CANCEL,  // ✅ ADD THIS LINE
}
```

**Logging:**
```java
LOG.info("Added CANCEL to FlightDelayEventEnum for event processing");
```

---

### Step 2: Add CANCEL Case to Switch Statement

**File**: `DelayCalculations.java`  
**Location**: `com.aa.opsco.prism.flink.functions`  
**Method**: `applyDelay()`  
**Current Lines**: 30-55

**Required Change:**
```java
public static List<LineOfFlying> applyDelay(FlightDelayEvent flightDelayEvent, 
                                            List<LineOfFlying> lineOfFlying, 
                                            PeFlight prevLegFlight) {
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
            case CANCEL:  // ✅ ADD THIS CASE
                LOG.info("Processing CANCEL event for flight: {}", lineOfFlying.get(i).get_id());
                isValidToPropagate = applyCancellation(lineOfFlying.get(i), isFirstFlight, i, flightDelayEvent);
                break;
            default:
                LOG.warn("Unhandled event type: {}", eventType);
                break;
        }
        
        isFirstFlight = false;
        
        if (isValidToPropagate) {
            updatedLineOfFlying.add(lineOfFlying.get(i));
        } else {
            LOG.info("Propagation stopped at flight: {} due to event: {}", 
                    lineOfFlying.get(i).get_id(), eventType);
            break;
        }
    }
    return updatedLineOfFlying;
}
```

---

### Step 3: Implement applyCancellation() Method

**File**: `DelayCalculations.java`  
**Location**: Add as new private static method

**Implementation Based on Legacy Logic:**

**Legacy Reference:**
- **File**: `PropagationEngineDelayCalculatorJob.java`
- **Lines**: 470-475 (case PE_CANCEL)
- **Lines**: 2550-2582 (substituteFlightTimesWithScheduleTimes method)

**Legacy Logic:**
```java
// From PropagationEngineDelayCalculatorJob.java lines 470-475
case PE_CANCEL:
    flightTimes = substituteFlightTimesWithScheduleTimes(flightTimes, requestID);
    operationFlightTimes = copyFlightTimesRequiredValue(flightTimes, operationFlightTimes, requestID);
    break;

// From lines 2550-2582
public FlightTimes substituteFlightTimesWithScheduleTimes(FlightTimes flightTimes, String requestID) {
    if (flightTimes != null) {
        // Preserve previous values
        flightTimes.setPreviousProjectedArrivalTime(flightTimes.getProjectedArrivalTime(requestID), requestID);
        flightTimes.setPreviousProjectedDepartureTime(flightTimes.getProjectedDepartureTime(requestID), requestID);
        
        // Reset to scheduled times
        flightTimes.setProjectedArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
        
        // Reset controlled times
        flightTimes.setProjectedArrivalTime_Controlled(flightTimes.getScheduledArrivalTime(), requestID);
        flightTimes.setProjectedDepartureTime_Controlled(flightTimes.getScheduledDepartureTime(), requestID);
        
        // Reset maintenance times
        flightTimes.setProjectedLatestMntcDepartureTime(flightTimes.getScheduledDepartureTime(), requestID);
        flightTimes.setProjectedLatestMntcArrivalTime(flightTimes.getScheduledArrivalTime(), requestID);
    }
    return flightTimes;
}
```

**PRISM Implementation:**
```java
/**
 * Applies cancellation logic to a flight in the line of flying.
 * Resets all projected times to scheduled times and stops propagation.
 * 
 * Based on legacy PropagationEngineDelayCalculatorJob.java lines 470-475, 2550-2582
 * 
 * @param line The current flight in the line of flying
 * @param isFirstFlight Whether this is the first flight in the chain
 * @param lofLevel The level in the line of flying (0-based index)
 * @param flightDelayEvent The cancellation event
 * @return false - Always returns false to stop propagation
 */
private static boolean applyCancellation(LineOfFlying line, 
                                        boolean isFirstFlight, 
                                        int lofLevel, 
                                        FlightDelayEvent flightDelayEvent) {
    LOG.info("applyCancellation() - Processing CANCEL event for flight: {}, LOF level: {}, isFirstFlight: {}", 
            line.get_id(), lofLevel, isFirstFlight);
    
    if (line == null) {
        LOG.warn("applyCancellation() - LineOfFlying is null, cannot process cancellation");
        return false;
    }
    
    try {
        // Store original values for logging
        long originalPTD = line.getProjectedDepartureTime();
        long originalPTA = line.getProjectedArrivalTime();
        
        // Reset projected departure time to scheduled departure time
        long scheduledDepartureTime = line.getScheduledDepartureTime();
        line.setProjectedDepartureTime(scheduledDepartureTime);
        LOG.debug("applyCancellation() - Reset PTD from {} to scheduled time: {}", 
                originalPTD, scheduledDepartureTime);
        
        // Reset projected arrival time to scheduled arrival time
        long scheduledArrivalTime = line.getScheduledArrivalTime();
        line.setProjectedArrivalTime(scheduledArrivalTime);
        LOG.debug("applyCancellation() - Reset PTA from {} to scheduled time: {}", 
                originalPTA, scheduledArrivalTime);
        
        // Clear all resource ready times (no resources needed for cancelled flight)
        line.setEquipmentResourceReadyTime(0);
        line.setCabinResourceReadyTime(0);
        line.setCockpitResourceReadyTime(0);
        LOG.debug("applyCancellation() - Cleared all resource ready times for cancelled flight: {}", 
                line.get_id());
        
        // Calculate time differences for logging
        long ptdDifference = scheduledDepartureTime - originalPTD;
        long ptaDifference = scheduledArrivalTime - originalPTA;
        
        LOG.info("applyCancellation() - Completed for flight: {}. PTD adjusted by {} ms, PTA adjusted by {} ms", 
                line.get_id(), ptdDifference, ptaDifference);
        
        // Always return false to stop propagation
        // Cancelled flights should not propagate delays to downstream flights
        LOG.info("applyCancellation() - Stopping propagation after cancelled flight: {}", 
                line.get_id());
        return false;
        
    } catch (Exception ex) {
        LOG.error("applyCancellation() - Error processing cancellation for flight: {}", 
                line.get_id(), ex);
        // Even on error, stop propagation for cancelled flights
        return false;
    }
}
```

**Key Implementation Details:**

1. **Time Reset Logic:**
   - `projectedDepartureTime` ← `scheduledDepartureTime`
   - `projectedArrivalTime` ← `scheduledArrivalTime`
   - This removes any delay that was previously calculated

2. **Resource Ready Times:**
   - All resource ready times set to 0
   - Equipment, cabin crew, and cockpit crew times cleared
   - Cancelled flights don't need resource allocation

3. **Propagation Control:**
   - **Always returns `false`**
   - This stops the propagation loop in `applyDelay()`
   - Downstream flights are not affected by cancelled flight

4. **Logging Strategy:**
   - INFO level: Entry, completion, propagation stop
   - DEBUG level: Individual field updates
   - ERROR level: Exception handling

---

## 💾 MongoDB Persistence Requirements

### Collection: `peFlight`

**Fields Updated by CANCEL Event:**

```javascript
{
  "_id": "AA1234-01JAN-DFW-LAX",
  
  // Time fields - Reset to scheduled
  "projectedDepartureTime": 1704110400000,  // Reset to scheduledDepartureTime
  "projectedArrivalTime": 1704117600000,    // Reset to scheduledArrivalTime
  "scheduledDepartureTime": 1704110400000,  // Unchanged
  "scheduledArrivalTime": 1704117600000,    // Unchanged
  
  // Resource ready times - Cleared
  "equipmentResourceReadyTime": 0,          // Cleared
  "cabinResourceReadyTime": 0,              // Cleared
  "cockpitResourceReadyTime": 0,            // Cleared
  
  // Metadata
  "lastUpdatedTime": <current_timestamp>,
  "lastEventType": "CANCEL",
  "legStatus": "CANCELLED"
}
```

**Fields Read:**
- `scheduledDepartureTime` - Source for resetting PTD
- `scheduledArrivalTime` - Source for resetting PTA
- `projectedDepartureTime` - Current value (to be reset)
- `projectedArrivalTime` - Current value (to be reset)

**Fields Written:**
- `projectedDepartureTime` ← `scheduledDepartureTime`
- `projectedArrivalTime` ← `scheduledArrivalTime`
- `equipmentResourceReadyTime` ← 0
- `cabinResourceReadyTime` ← 0
- `cockpitResourceReadyTime` ← 0

**Persistence Logging:**
```java
LOG.info("Persisting CANCEL event updates to MongoDB for flight: {}", flightKey);
LOG.debug("MongoDB update - PTD: {}, PTA: {}, Resources cleared", 
         projectedDepartureTime, projectedArrivalTime);
```

---

## ✅ Acceptance Criteria

### Event Generation (Already Complete)
- [x] CANCEL atomic event generated when leg status changes to CANCELLED
- [x] Event includes flight key and cancellation status
- [x] Event published to Event Hub topic
- [x] Logging confirms event generation

### Event Consumption (To Be Implemented)
- [ ] CANCEL added to `FlightDelayEventEnum`
- [ ] CANCEL case added to switch statement in `DelayCalculations.applyDelay()`
- [ ] `applyCancellation()` method implemented with complete logic
- [ ] Method resets projected times to scheduled times
- [ ] Method clears all resource ready times
- [ ] Method returns `false` to stop propagation
- [ ] Logging added at INFO, DEBUG, and ERROR levels

### Data Persistence
- [ ] Projected departure time reset to scheduled departure time
- [ ] Projected arrival time reset to scheduled arrival time
- [ ] Equipment resource ready time set to 0
- [ ] Cabin resource ready time set to 0
- [ ] Cockpit resource ready time set to 0
- [ ] MongoDB updates confirmed via logging

### Propagation Behavior
- [ ] Propagation stops after cancelled flight (no downstream updates)
- [ ] Cancelled flight included in `updatedLineOfFlying` list
- [ ] Downstream flights NOT included in `updatedLineOfFlying` list
- [ ] Logging confirms propagation stopped

### Testing
- [ ] Unit tests pass with >80% code coverage
- [ ] Integration tests cover end-to-end CANCEL event flow
- [ ] Test scenarios include:
  - Single flight cancellation
  - First flight in chain cancellation
  - Middle flight in chain cancellation
  - Cancellation with existing delays
  - Cancellation with resource constraints
- [ ] Edge cases tested:
  - Null line of flying
  - Missing scheduled times
  - Already cancelled flight
  - Concurrent cancellation events

### Documentation
- [ ] Code comments added to `applyCancellation()` method
- [ ] JavaDoc updated with method description
- [ ] Legacy code references documented
- [ ] User story marked as complete in Azure DevOps

---

## 📋 Implementation Tasks

### Task 1: Update FlightDelayEventEnum
**Estimate:** 0.5 hours  
**Description:** Add CANCEL to the enum  
**Files:** `FlightDelayEventEnum.java`  
**Acceptance:** Enum compiles without errors, CANCEL value accessible

### Task 2: Add CANCEL Case to Switch Statement
**Estimate:** 1 hour  
**Description:** Add case CANCEL to applyDelay() switch  
**Files:** `DelayCalculations.java`  
**Acceptance:** Switch statement includes CANCEL case, calls applyCancellation()

### Task 3: Implement applyCancellation() Method
**Estimate:** 3 hours  
**Description:** Implement complete cancellation logic based on legacy code  
**Files:** `DelayCalculations.java`  
**Acceptance:** Method resets times, clears resources, stops propagation, includes logging

### Task 4: Write Unit Tests
**Estimate:** 4 hours  
**Description:** Create comprehensive unit tests for applyCancellation()  
**Files:** `DelayCalculationsTest.java`  
**Test Cases:**
- Test time reset to scheduled times
- Test resource ready time clearing
- Test propagation stop (returns false)
- Test with null inputs
- Test with missing scheduled times
- Test logging output

### Task 5: Write Integration Tests
**Estimate:** 4 hours  
**Description:** Create end-to-end integration tests  
**Files:** `CancelEventIntegrationTest.java`  
**Test Cases:**
- CANCEL event from Event Hub to MongoDB
- Line of flying with cancelled first flight
- Line of flying with cancelled middle flight
- Verify downstream flights not updated
- Verify MongoDB persistence

### Task 6: Update Documentation
**Estimate:** 1 hour  
**Description:** Add JavaDoc and code comments  
**Files:** `DelayCalculations.java`, `README.md`  
**Acceptance:** All public methods documented, legacy references included

### Task 7: Code Review and Refinement
**Estimate:** 2 hours  
**Description:** Address code review feedback  
**Acceptance:** All review comments resolved, code approved

### Task 8: Deployment and Validation
**Estimate:** 1.5 hours  
**Description:** Deploy to test environment and validate  
**Acceptance:** CANCEL events processed correctly in test environment

**Total Estimate:** 17 hours (Story Points: 8)

---

## 📚 Legacy Code References

| Component | File Path | Line Numbers | Description |
|-----------|-----------|--------------|-------------|
| **Event Generation** | `IPS_LKA2.0-Release/DataModels/src/main/java/com/aa/lookahead/dataobjects/event/lookahead/CancelEvent.java` | 1-120 | Legacy CANCEL event class definition |
| **Event Enum** | `IPS_LKA2.0-Release/DataModels/src/main/java/com/aa/lookahead/dataobjects/event/LKAEventType.java` | [Enum definition] | PE_CANCEL enum value |
| **Event Processing - Switch Case** | `IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/cache/propagationengine/Job/PropagationEngineDelayCalculatorJob.java` | 470-475 | PE_CANCEL case in switch statement |
| **Time Substitution Method** | `IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/cache/propagationengine/Job/PropagationEngineDelayCalculatorJob.java` | 2550-2582 | substituteFlightTimesWithScheduleTimes() - Core cancellation logic |
| **Copy Flight Times Method** | `IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/cache/propagationengine/Job/PropagationEngineDelayCalculatorJob.java` | 2584-2630 | copyFlightTimesRequiredValue() - Prepares data for persistence |
| **Propagation Check** | `IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/cache/propagationengine/Job/PropagationEngineDelayCalculatorJob.java` | 2429-2443 | checkPEContinuedforNextFlight() - Determines if propagation continues |
| **Problem Type Handling** | `IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/cache/propagationengine/Job/PropagationEngineDelayCalculatorJob.java` | 2203-2248 | handleProblemType() - Deactivates problem types for cancelled flights |

---

## 🔧 Technical Notes

### Design Patterns
1. **Strategy Pattern**: Each event type has its own processing method
2. **Chain of Responsibility**: Line of flying processed sequentially until propagation stops
3. **Immutable State**: Original scheduled times never modified

### Dependencies
- **Flink Streaming**: Event processing framework
- **MongoDB**: Data persistence layer
- **Event Hub**: Event ingestion
- **Lombok**: Logging annotations

### Performance Considerations
- **O(1) Time Complexity**: Single flight update, no iteration
- **Minimal Memory**: Only current flight object modified
- **Fast Propagation Stop**: Immediate return false prevents unnecessary processing
- **Efficient Logging**: DEBUG level for detailed info, INFO for key events

### Error Handling
- **Null Safety**: Check for null line of flying
- **Exception Catching**: Catch and log all exceptions
- **Graceful Degradation**: Return false on error to stop propagation
- **Detailed Error Logging**: Include flight key and exception details

### Concurrency
- **Thread-Safe**: Static methods with local variables
- **No Shared State**: Each event processed independently
- **Idempotent**: Multiple CANCEL events produce same result

---

## 🧪 Testing Strategy

### Unit Test Scenarios

**Test Class:** `DelayCalculationsTest.java`

```java
@Test
public void testApplyCancellation_ResetsTimesToScheduled() {
    // Given: Flight with delays
    LineOfFlying flight = createFlightWithDelay(30); // 30 min delay
    FlightDelayEvent cancelEvent = createCancelEvent(flight);
    
    // When: Apply cancellation
    boolean shouldPropagate = applyCancellation(flight, true, 0, cancelEvent);
    
    // Then: Times reset to scheduled
    assertEquals(flight.getScheduledDepartureTime(), flight.getProjectedDepartureTime());
    assertEquals(flight.getScheduledArrivalTime(), flight.getProjectedArrivalTime());
    assertFalse(shouldPropagate); // Propagation stopped
}

@Test
public void testApplyCancellation_ClearsResourceReadyTimes() {
    // Given: Flight with resource constraints
    LineOfFlying flight = createFlightWithResourceDelays();
    FlightDelayEvent cancelEvent = createCancelEvent(flight);
    
    // When: Apply cancellation
    applyCancellation(flight, true, 0, cancelEvent);
    
    // Then: All resource times cleared
    assertEquals(0, flight.getEquipmentResourceReadyTime());
    assertEquals(0, flight.getCabinResourceReadyTime());
    assertEquals(0, flight.getCockpitResourceReadyTime());
}

@Test
public void testApplyCancellation_StopsPropagation() {
    // Given: Line of flying with 3 flights
    List<LineOfFlying> lof = createLineOfFlying(3);
    FlightDelayEvent cancelEvent = createCancelEvent(lof.get(0));
    
    // When: Apply delay with CANCEL event
    List<LineOfFlying> result = DelayCalculations.applyDelay(cancelEvent, lof, null);
    
    // Then: Only first flight in result
    assertEquals(1, result.size());
    assertEquals(lof.get(0).get_id(), result.get(0).get_id());
}

@Test
public void testApplyCancellation_HandlesNullLineOfFlying() {
    // Given: Null line of flying
    FlightDelayEvent cancelEvent = createCancelEvent(null);
    
    // When: Apply cancellation
    boolean shouldPropagate = applyCancellation(null, true, 0, cancelEvent);
    
    // Then: Returns false safely
    assertFalse(shouldPropagate);
}
```

### Integration Test Scenarios

**Test Class:** `CancelEventIntegrationTest.java`

```java
@Test
public void testCancelEvent_EndToEnd() {
    // Given: CANCEL event in Event Hub
    publishCancelEventToEventHub("AA1234-01JAN-DFW-LAX");
    
    // When: Event processed through pipeline
    waitForProcessing(5000);
    
    // Then: MongoDB updated correctly
    PeFlight flight = mongoTemplate.findById("AA1234-01JAN-DFW-LAX", PeFlight.class);
    assertEquals(flight.getScheduledDepartureTime(), flight.getProjectedDepartureTime());
    assertEquals(flight.getScheduledArrivalTime(), flight.getProjectedArrivalTime());
    assertEquals(0, flight.getEquipmentResourceReadyTime());
}

@Test
public void testCancelEvent_StopsLineOfFlyingPropagation() {
    // Given: Line of flying with 5 flights, cancel 2nd flight
    setupLineOfFlying(5);
    publishCancelEventToEventHub("AA1235-01JAN-LAX-ORD"); // 2nd flight
    
    // When: Event processed
    waitForProcessing(5000);
    
    // Then: Only first 2 flights updated
    assertFlightUpdated("AA1234-01JAN-DFW-LAX"); // 1st flight
    assertFlightUpdated("AA1235-01JAN-LAX-ORD"); // 2nd flight (cancelled)
    assertFlightNotUpdated("AA1236-01JAN-ORD-JFK"); // 3rd flight
    assertFlightNotUpdated("AA1237-01JAN-JFK-BOS"); // 4th flight
    assertFlightNotUpdated("AA1238-01JAN-BOS-DCA"); // 5th flight
}
```

### Edge Cases

1. **Concurrent Cancellation**: Multiple CANCEL events for same flight
2. **Already Cancelled**: CANCEL event for already cancelled flight
3. **Missing Scheduled Times**: Flight without scheduled times
4. **Partial Data**: Flight with some null fields
5. **Large Delay**: Cancelled flight with significant delay (>2 hours)

### Test Data Requirements

```java
// Sample test flight with delay
LineOfFlying createFlightWithDelay(int delayMinutes) {
    LineOfFlying flight = new LineOfFlying();
    flight.set_id("AA1234-01JAN-DFW-LAX");
    flight.setScheduledDepartureTime(1704110400000L); // 10:00 AM
    flight.setScheduledArrivalTime(1704117600000L);   // 12:00 PM
    flight.setProjectedDepartureTime(1704110400000L + (delayMinutes * 60000)); // Delayed
    flight.setProjectedArrivalTime(1704117600000L + (delayMinutes * 60000));   // Delayed
    flight.setEquipmentResourceReadyTime(1704110400000L + (delayMinutes * 60000));
    return flight;
}
```

---

## 📊 Business Scenarios

### Scenario 1: Standard Cancellation

**Situation:**
Flight AA1234 from DFW to LAX scheduled at 10:00 AM is cancelled at 09:30 AM. The flight had a 30-minute delay (PTD = 10:30 AM).

**Input:**
```
Event: CANCEL
Flight: AA1234-01JAN-DFW-LAX
scheduledDepartureTime: 10:00 (1704110400000)
projectedDepartureTime: 10:30 (1704112200000) - 30 min delay
scheduledArrivalTime: 12:00 (1704117600000)
projectedArrivalTime: 12:30 (1704119400000) - 30 min delay
equipmentResourceReadyTime: 10:30 (1704112200000)
```

**Processing:**
1. CANCEL event received from Event Hub
2. `applyCancellation()` called for AA1234
3. PTD reset: 10:30 → 10:00 (scheduled)
4. PTA reset: 12:30 → 12:00 (scheduled)
5. Equipment ready time cleared: 10:30 → 0
6. Method returns `false` to stop propagation

**Output:**
```
projectedDepartureTime: 10:00 (1704110400000) - Reset to scheduled
projectedArrivalTime: 12:00 (1704117600000) - Reset to scheduled
equipmentResourceReadyTime: 0 - Cleared
cabinResourceReadyTime: 0 - Cleared
cockpitResourceReadyTime: 0 - Cleared
Propagation: STOPPED
```

**Business Impact:**
- Cancelled flight no longer shows delay
- Downstream flights not affected by cancelled flight's delay
- Resources can be reallocated to other flights

---

### Scenario 2: Cancellation in Line of Flying

**Situation:**
Aircraft tail N123AA is flying a 4-leg sequence. The 2nd leg is cancelled mid-sequence.

**Line of Flying:**
1. AA1234 DFW-LAX (10:00-12:00) - On time
2. AA1235 LAX-ORD (13:00-19:00) - **CANCELLED**
3. AA1236 ORD-JFK (20:00-23:00) - Scheduled
4. AA1237 JFK-BOS (00:00-01:00) - Scheduled

**Processing:**
1. AA1234 completes normally, PTA = 12:00
2. CANCEL event received for AA1235
3. `applyCancellation()` processes AA1235:
   - PTD reset to 13:00 (scheduled)
   - PTA reset to 19:00 (scheduled)
   - Resources cleared
   - Returns `false`
4. Propagation loop stops at AA1235
5. AA1236 and AA1237 **NOT** updated

**Result:**
- AA1234: ✅ Updated (completed normally)
- AA1235: ✅ Updated (cancelled, times reset)
- AA1236: ❌ NOT updated (propagation stopped)
- AA1237: ❌ NOT updated (propagation stopped)

**Business Impact:**
- AA1236 and AA1237 require new aircraft assignment
- Crew reassignment needed for remaining legs
- Passengers rebooked on alternative flights

---

## 🔍 Validation Checklist

### Pre-Implementation
- [ ] Legacy code reviewed and understood
- [ ] PRISM architecture reviewed
- [ ] Event flow documented
- [ ] Test data prepared

### During Implementation
- [ ] Code follows PRISM coding standards
- [ ] Logging added at appropriate levels
- [ ] Error handling implemented
- [ ] Comments and JavaDoc added

### Post-Implementation
- [ ] Unit tests pass (>80% coverage)
- [ ] Integration tests pass
- [ ] Code review completed
- [ ] Documentation updated
- [ ] Deployed to test environment
- [ ] Validated with real CANCEL events

### Production Readiness
- [ ] Performance tested (no degradation)
- [ ] Monitoring alerts configured
- [ ] Rollback plan documented
- [ ] Operations team trained
- [ ] User story marked complete

---

## 🔗 Related Events

### Complementary Events
- **REINSTATE**: Reverses cancellation, restores projected times
- **DELETE**: Removes flight entirely from system
- **LEG_STATUS_CHANGE**: Updates leg status to CANCELLED

### Conflicting Events
- **OUT**: Cannot occur after cancellation
- **OFF**: Cannot occur after cancellation
- **IN**: Cannot occur after cancellation
- **ON**: Cannot occur after cancellation

### Dependent Events
- **PTA/PTD**: Generated when times change (not for CANCEL)
- **PE_CONTINUED**: NOT generated for cancelled flights

---

## 📝 Notes

### Key Differences from Legacy
1. **Simplified Logic**: PRISM uses simpler time reset (no controlled times yet)
2. **No Problem Types**: PRISM doesn't handle problem type deactivation (future enhancement)
3. **No Snapshots**: PRISM doesn't support what-if scenarios yet
4. **Streamlined Persistence**: Direct MongoDB updates vs. ChangeSet pattern

### Future Enhancements
1. Add controlled time handling (CTA/CTD)
2. Implement problem type deactivation
3. Add snapshot support for what-if scenarios
4. Enhance logging with metrics

### Known Limitations
1. Does not handle maintenance times (PMTD/PMTA)
2. Does not generate PTA/PTD events on cancellation
3. Does not support partial cancellations

---

## ✅ Definition of Done

- [ ] Code implemented and committed to feature branch
- [ ] Unit tests written and passing (>80% coverage)
- [ ] Integration tests written and passing
- [ ] Code review completed and approved
- [ ] Documentation updated (JavaDoc, README, this user story)
- [ ] Deployed to test environment
- [ ] Validated with test CANCEL events
- [ ] Performance tested (no degradation)
- [ ] Merged to main branch
- [ ] Deployed to production
- [ ] Validated in production with real events
- [ ] User story marked as complete in Azure DevOps
- [ ] Knowledge transfer completed with team

---

**Generated:** 2025-01-XX  
**Last Updated:** 2025-01-XX  
**Status:** Ready for Implementation  
**Reviewed By:** [TBD]  
**Approved By:** [TBD]
