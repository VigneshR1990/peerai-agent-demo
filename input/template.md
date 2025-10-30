### User Story [N]: [Event Name] Event Processing

**Work Item ID**: PRISM-DELAY-US-00[N]  
**Title**: As a [role], I need [event] to [action] so that [business value]

**Priority**: [P0/P1/P2]  
**Story Points**: [Points]  
**Sprint**: Sprint [N]  
**Assigned To**: [TBD]  
**Area Path**: PRISM/Delay Processor  
**Iteration Path**: Sprint [N]  

---

#### Event Generation Requirements

**Module**: Flight Processor - [ProcessorName]

**Current State**: [Implemented/Partially Implemented/Not Implemented]

**Implementation Required**:
```java
// Code location and implementation details
```

**Legacy Reference**:
- **File**: `IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/service/impl/FlightEventPreProcessServiceImpl.java`
- **Look for**: [Event generation logic]
- **Event Classes**: [Legacy event class references]

**Logging Requirements**:
- [ ] Log when event detected
- [ ] Log event details (flight key, values)
- [ ] Log atomic event generation
- [ ] Log publishing to Event Hub

---

#### Event Consumption Requirements

**Module**: Delay Processor - DelayCalculations

**Current State**: [Implemented/Not Implemented]

**Implementation Required**:

**Step 1: Event Enum Update**
```java
// Add to FlightDelayEventEnum
```

**Step 2: Switch Case**
```java
// Add case statement
```

**Step 3: Processing Method**
```java
// Implement apply[EventName]() method
```

**Legacy Reference**:
- **File**: `IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/cache/propagationengine/Job/PropagationEngineDelayCalculatorJob.java`
- **Method**: `startPropagationEvent()` - Line 148 (entry point)
- **Method**: `calculatePropagatedFlightTimes()` - Line 262 (calculation logic)
- **Case Statement**: Look for `PE_[EVENT]` case around line [XXX]

**ResourceReadyTimes Calculation**: [REQUIRED/NOT REQUIRED]
- [ ] Calculate if event affects equipment/crew availability
- [ ] Implementation details

**Line of Flying Propagation**: [REQUIRED/NOT REQUIRED]
- [ ] Propagate through downstream flights
- [ ] Implementation approach

**Subsequent Event Publishing**: [REQUIRED/NOT REQUIRED]
- [ ] Publish crew delay events if needed
- [ ] Implementation details

**Logging Requirements**:
- [ ] Log event consumption: "Processing [EVENT] for flight: {}"
- [ ] Log calculation steps: "Calculating [value] from [inputs]"
- [ ] Log propagation: "Propagating to flight {}, LOF level: {}"
- [ ] Log results: "Updated [N] flights, delay propagated: {} minutes"
- [ ] Log MongoDB updates: "Persisted updates for flight: {}"

---

#### MongoDB Persistence Requirements

**Collection**: `peFlight`

**Fields to Update**:
```javascript
{
  "flightKey": "...",
  "field1": updated_value,
  "field2": updated_value,
  "updateDate": current_timestamp
}
```

**Logging for Persistence**:
```java
LOG.info("Updating MongoDB peFlight for flight: {}", flightKey);
LOG.debug("Fields to update: [list of fields]");
// ... perform update ...
LOG.info("MongoDB update successful for flight: {}", flightKey);
LOG.debug("Updated document: {}", peFlight);
```

---

#### Acceptance Criteria

**Event Generation**:
- [ ] Event generated in appropriate Flight Processor module
- [ ] Logic reviewed against FlightEventPreProcessServiceImpl
- [ ] Atomic event structure correct
- [ ] Published to Event Hub (atomicprismevents topic)
- [ ] Logging added and verified

**Event Consumption**:
- [ ] Event added to FlightDelayEventEnum
- [ ] Event passes through filter
- [ ] Case statement added to switch
- [ ] Processing method implemented
- [ ] Logic reviewed against PropagationEngineDelayCalculatorJob
- [ ] ResourceReadyTimes calculated if required
- [ ] Line of Flying propagation works
- [ ] Subsequent events published if needed
- [ ] Logging added at each step

**Data Persistence**:
- [ ] All updated fields sent to MongoDB
- [ ] peFlight document updated correctly
- [ ] Logging confirms persistence
- [ ] Data verified in MongoDB

**Testing**:
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Logging verified in tests
- [ ] Test data covers all scenarios

---