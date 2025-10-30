# Instructions for Generating User Stories for Missing Events

## Purpose

This document provides **step-by-step instructions** for creating Azure DevOps user stories for missing delay events by comparing legacy IPS LKA 2.0 code with modern PRISM code.

---

## Prerequisites

### Required Codebases

1. **Legacy Codebase**: `/Users/rvinayagam/Downloads/IPS_LKA2.0-Release/`
2. **PRISM Codebase**: `/Users/rvinayagam/Downloads/ot-opsco-prism-FlightProcessor-main/`

### Required Documentation

**Already Created**:
1. Event comparison documents (this repo)
2. PRISM implementation docs (this repo)
3. Azure DevOps user story templates (this repo)

---

## Step-by-Step Instructions

### Step 1: Identify the Missing Event

**Input**: Event name from legacy system (e.g., `PE_CANCEL`, `PE_ETR`, `PE_REINSTATE`)

**Check**:
1. Is event in legacy `LKAEventType` enum?
   - **File**: `IPS_LKA2.0-Release/DataModels/src/main/java/com/aa/lookahead/dataobjects/event/LKAEventType.java`
   - **Action**: Search for event name
   - **Confirm**: Event exists in legacy

2. Is event in PRISM `FlightDelayEventEnum`?
   - **File**: `ot-opsco-prism-DelayProcessor/src/main/java/com/aa/opsco/prism/flink/enums/FlightDelayEventEnum.java`
   - **Action**: Search for equivalent event name
   - **Result**: If NOT found → Event is missing

---

### Step 2: Analyze Legacy Implementation

#### 2.1: Find Event Class

**Location**: `IPS_LKA2.0-Release/DataModels/src/main/java/com/aa/lookahead/dataobjects/event/lookahead/`

**Find**:
- Event class file (e.g., `CancelEvent.java`, `ETREvent.java`, `ReinstateEvent.java`)
- Open and review event structure

**Extract**:
```java
// Note the fields:
- What data does event carry?
- What is the event value?
- When is event created?
```

**Example** (for PE_CANCEL):
```
File: CancelEvent.java
Fields: flightKey, cancelReason, cancelCode
Value: Cancellation code
When: Flight cancelled
```

---

#### 2.2: Find Event Generation Logic

**Location**: `IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/service/impl/FlightEventPreProcessServiceImpl.java`

**Search For**: Event generation logic

**Extract**:
```
When is event generated?
What triggers event generation?
What FlightHub event triggers it?
What fields are populated?
```

**Document**:
```markdown
## Event Generation (Legacy)

**File**: FlightEventPreProcessServiceImpl.java
**When**: [Trigger condition]
**FlightHub Source**: [CANCEL, RTD, REINSTATE, etc.]
**Fields Populated**: [List fields]
```

---

#### 2.3: Find Event Consumption Logic

**Location**: `IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/cache/propagationengine/Job/PropagationEngineDelayCalculatorJob.java`

**Actions**:
1. Search for event in switch statement (around line 469+)
2. If found as case: Document the case logic
3. If NOT found: Check if handled in default case
4. Note the processing method called

**Extract**:
```java
// Document:
- Line numbers where event is handled
- What method processes it
- What fields are read
- What fields are written
- What calculations are performed
- Does it propagate to next leg?
```

**Template**:
```markdown
## Event Consumption (Legacy)

**File**: PropagationEngineDelayCalculatorJob.java
**Line**: [Line number]
**Case**: [case PE_CANCEL: or default:]
**Method**: [calculatePropagatedFlightTimes, etc.]

**Processing Logic**:
1. [Step 1]
2. [Step 2]
3. [Step 3]

**Fields Read**: [List]
**Fields Written**: [List]
**Calculations**: [Formulas]
**Propagates**: [Yes/No]
```

---

#### 2.4: Identify Resource Ready Time Logic (if applicable)

**Location**: Same file, method `calculateAllResourceReadyTimes()`

**Check**:
- Does this event affect resource ready times?
- Which resources? (equipment, crew, other)
- How is RRT calculated?

**Document**:
```markdown
## Resource Ready Times (Legacy)

**Affected Resources**: [Equipment / Cabin Crew / Cockpit Crew / None]
**Calculation**: [Formula]
**Used In**: [PTD calculation]
```

---

### Step 3: Check PRISM Current State

#### 3.1: Check Flight Processor

**Location**: `ot-opsco-prism-FlightProcessor/src/main/java/com/aa/opsco/prism/flink/processors/`

**Files to Check**:
1. `TimesEventProcessor.java` - For timing events
2. `StatusEventProcessor.java` - For status events
3. `EquipmentEventProcessor.java` - For equipment events

**Search For**: 
- FlightHub event handling (e.g., `FlightHubEventEnum.CANCEL`)
- Atomic event generation (e.g., `createAtomicEvent(..., "CANCEL")`)

**Document**:
```markdown
## Current PRISM State (Flight Processor)

**Status**: [Implemented / Partially Implemented / Not Implemented]

If Implemented:
- **File**: [Processor name]
- **Method**: [determineAtomicEvents]
- **Generates**: [Atomic event name]

If Not Implemented:
- **Status**: Not generating atomic event
- **Needs**: Add event generation logic
```

---

#### 3.2: Check Delay Processor

**Location**: `ot-opsco-prism-DelayProcessor/src/main/java/com/aa/opsco/prism/flink/`

**Files to Check**:
1. `enums/FlightDelayEventEnum.java` - Check if event in enum
2. `functions/DelayCalculations.java` - Check switch statement (line 30-55)

**Document**:
```markdown
## Current PRISM State (Delay Processor)

**In Enum**: [Yes / No]
**In Switch**: [Yes / No]
**Method**: [applyXXX() method name if exists]

**Status**: ❌ Not Implemented
**Needs**: 
1. Add to FlightDelayEventEnum
2. Add case to switch
3. Implement processing method
```

---

### Step 4: Compare Implementations

**Create Comparison Table**:

| Aspect | Legacy | PRISM | Gap |
|--------|--------|-------|-----|
| **Event Name** | PE_CANCEL | - | Missing |
| **Event Generation** | FlightEventPreProcessServiceImpl | - | Need to add |
| **Event Enum** | LKAEventType.PE_CANCEL | - | Need to add to FlightDelayEventEnum |
| **Processing Method** | PropagationEngineDelayCalculatorJob (line XXX) | - | Need to implement |
| **Fields Affected** | [List from legacy] | - | Need to implement |
| **Calculations** | [Formulas from legacy] | - | Need to implement |

---

### Step 5: Extract Requirements for User Story

#### 5.1: Business Context

**From Legacy Code Analysis**:

```markdown
## Business Scenario

**What**: [What does event represent]
**When**: [When does it occur]
**Why**: [Why is it important]
**Impact**: [Business impact]

Example:
  Flight AA1234 [scenario description]
  Expected: [Expected behavior]
  Current (PRISM): [Current behavior - none if missing]
```

---

#### 5.2: Event Generation Requirements

**Template**:
```markdown
### Event Generation Requirements

**Module**: Flight Processor - [TimesEventProcessor / StatusEventProcessor / EquipmentEventProcessor]

**Current State**: ❌ NOT Implemented

**Implementation Required**:

**Step 1**: Add event detection in [Processor]
```java
// Location: [File path]
// Method: [Method name]

if ([Condition from legacy]) {
    LOG.info("[Event] detected for flight: {}", flightKey);
    
    atomicEvents.add(createAtomicEvent(
        newObject,
        [value],
        "[EVENT_TYPE]"
    ));
}
```

**Legacy Reference**:
- **File**: IPS_LKA2.0-Release/Service/.../FlightEventPreProcessServiceImpl.java
- **Look for**: [Event generation logic]
- **Event Class**: IPS_LKA2.0-Release/DataModels/.../[EventClass].java

**Fields to Include**: [List from legacy]
**Logging**: [Required log statements]
```

---

#### 5.3: Event Consumption Requirements

**Template**:
```markdown
### Event Consumption Requirements

**Module**: Delay Processor - DelayCalculations

**Current State**: ❌ NOT Implemented

**Implementation Required**:

**Step 1**: Add to FlightDelayEventEnum
```java
// File: FlightDelayEventEnum.java
[EVENT_NAME],  // Add this
```

**Step 2**: Add case to switch
```java
// File: DelayCalculations.java
// Line: ~30-55

case [EVENT_NAME]:
    LOG.info("Processing [EVENT] for flight: {}", lineOfFlying.get(i).get_id());
    isValidToPropagate = apply[EventName](lineOfFlying.get(i), isFirstFlight, i, flightDelayEvent);
    break;
```

**Step 3**: Implement processing method
```java
// File: DelayCalculations.java

private static boolean apply[EventName](LineOfFlying line, boolean isFirstFlight, int lofLevel, FlightDelayEvent event) {
    LOG.info("apply[EventName]() - Flight: {}", line.get_id());
    
    if (isFirstFlight) {
        // [Logic from legacy]
        // Based on PropagationEngineDelayCalculatorJob line [XXX]
        
        [Implementation based on legacy]
        
        return [true/false];
    } else {
        // Standard propagation
        [Implementation]
    }
}
```

**Legacy Reference**:
- **File**: PropagationEngineDelayCalculatorJob.java
- **Method**: calculatePropagatedFlightTimes()
- **Line**: [Line number from legacy]
- **Case**: [case PE_[EVENT]: or default case]

**Logic from Legacy**:
```java
// Copy actual legacy code here
```

**ResourceReadyTimes**: [REQUIRED / NOT REQUIRED]
- [Calculation if required]

**Line of Flying Propagation**: [REQUIRED / NOT REQUIRED]
- [Implementation approach]

**Subsequent Event Publishing**: [REQUIRED / NOT REQUIRED]
- [When to publish crew delay events]
```

---

#### 5.4: Field Impact Analysis

**From Legacy Code**:

**Template**:
```markdown
### MongoDB Fields to Update

**Collection**: peFlight

**Fields** (from legacy FlightTimes):
```javascript
{
  "flightKey": "...",
  "field1": value,  // From legacy: flightTimes.field1
  "field2": value,  // From legacy: flightTimes.field2
  "updateDate": timestamp
}
```

**Fields READ** (from legacy code):
- field1 (from line XXX)
- field2 (from line YYY)

**Fields WRITTEN** (from legacy code):
- field1 ← calculation
- field2 ← calculation

**Calculations** (from legacy):
```java
// Copy calculation logic from legacy
field1 = ...
field2 = ...
```
```

---

### Step 6: Create User Story

**Use Template**: `AZURE_DEVOPS_USER_STORIES_COMPLETE.md`

**Fill in Sections**:

1. **Work Item ID**: PRISM-DELAY-US-00[N]
2. **Title**: As a [role], I need [event functionality] so that [business value]
3. **Description**: From business scenario (Step 5.1)
4. **Event Generation Requirements**: From Step 5.2
5. **Event Consumption Requirements**: From Step 5.3
6. **MongoDB Fields**: From Step 5.4
7. **Legacy References**: File paths and line numbers collected
8. **Acceptance Criteria**: Based on requirements
9. **Tasks**: Break down into implementation tasks
10. **Story Points**: Estimate based on complexity

---

## Quick Reference: File Locations

### Legacy Source Files

**Primary Files**:
```
Event Enums:
├─ /IPS_LKA2.0-Release/DataModels/src/main/java/com/aa/lookahead/dataobjects/event/LKAEventType.java

Event Classes:
├─ /IPS_LKA2.0-Release/DataModels/src/main/java/com/aa/lookahead/dataobjects/event/lookahead/
│  ├─ CancelEvent.java
│  ├─ ETREvent.java
│  ├─ ReinstateEvent.java
│  ├─ GroundTimeChangeEvent.java
│  ├─ EquipmentPrevLegChangeEvent.java
│  └─ [Other event classes]

Event Generation:
├─ /IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/service/impl/FlightEventPreProcessServiceImpl.java

Event Processing:
├─ /IPS_LKA2.0-Release/Service/src/main/java/com/aa/lookahead/cache/propagationengine/Job/PropagationEngineDelayCalculatorJob.java
│  ├─ Method: startPropagationEvent() - Line ~148
│  ├─ Method: calculatePropagatedFlightTimes() - Line ~262
│  ├─ Switch statement - Line ~469
│  └─ Case statements - Lines 470-777

Resource Calculation:
├─ Method: calculateAllResourceReadyTimes() - Search in file
├─ Method: calculateFlightProjectedDepartures() - Search in file
└─ Method: calculateFlightProjectedArrivals() - Search in file
```

---

### PRISM Source Files

**Primary Files**:
```
Event Enums:
├─ /ot-opsco-prism-FlightProcessor/src/main/java/com/aa/opsco/prism/flink/enums/
│  └─ OPSEventType.java (atomic event types)
│
├─ /ot-opsco-prism-DelayProcessor/src/main/java/com/aa/opsco/prism/flink/enums/
│  └─ FlightDelayEventEnum.java (delay event types)

Flight Processor - Event Generation:
├─ /ot-opsco-prism-FlightProcessor/src/main/java/com/aa/opsco/prism/flink/processors/
│  ├─ TimesEventProcessor.java
│  │  └─ Method: determineTimesAtomicEvents()
│  ├─ StatusEventProcessor.java
│  │  └─ Method: determineStatusAtomicEvents()
│  └─ EquipmentEventProcessor.java
│     └─ Method: determineEquipmentAtomicEvents()

Delay Processor - Event Consumption:
├─ /ot-opsco-prism-DelayProcessor/src/main/java/com/aa/opsco/prism/flink/
│  ├─ streams/DelayStreams.java (event ingestion)
│  ├─ helpers/DelayHelper.java (event filtering)
│  ├─ processors/DelayEventProcessor.java (event routing)
│  └─ functions/
│     ├─ DelayCalculations.java (main logic)
│     │  ├─ Method: applyDelay() - Switch statement line 30-55
│     │  └─ Methods: apply[EventName]() - Event-specific logic
│     └─ DelayRichFlatMap.java (async processing, gRPC)
```

---

### Documentation Files

**Reference Documentation**:
```
Comparison Documents:
├─ /ot-opsco-prism-DelayProcessor/docs/delay_events_comparision.md
├─ /ot-opsco-prism-DelayProcessor/docs/legacy-vs-prism-comparison/
│  ├─ 01_DEP_DLY_EVENT_Comparison.md (template)
│  ├─ 02_ARR_DLY_EVENT_Comparison.md
│  └─ ALL_EVENTS_COMPARISON_SUMMARY.md

Implementation Docs:
├─ /ot-opsco-prism-DelayProcessor/docs/event-implementations/
│  ├─ 01_DEP_DLY_EVENT_Implementation.md (template for new events)
│  └─ EVENT_IMPLEMENTATIONS_INDEX.md (patterns)

User Story Templates:
├─ /ot-opsco-prism-FlightProcessor-main/docs/AZURE_DEVOPS_USER_STORIES_COMPLETE.md
└─ User Story 1-4 (complete examples)
```

---

## Example Walkthrough: PE_CANCEL Event

### Step 1: Identify Event

✅ **Found in Legacy**: `LKAEventType.PE_CANCEL`  
❌ **Not in PRISM**: Not in `FlightDelayEventEnum`  
**Conclusion**: Missing event

---

### Step 2: Analyze Legacy

#### Event Class

**File**: `IPS_LKA2.0-Release/DataModels/src/main/java/com/aa/lookahead/dataobjects/event/lookahead/CancelEvent.java`

**Extract**:
```java
public class CancelEvent extends LKAFlightEvent {
    private String cancelReason;
    private String cancelCode;
    private LegStatus legStatus;
    
    // Event carries cancellation details
}
```

**Fields**: flightKey, cancelReason, cancelCode, legStatus

---

#### Event Processing

**File**: `PropagationEngineDelayCalculatorJob.java`  
**Line**: ~470

**Code**:
```java
case PE_CANCEL:
    flightTimes = substituteFlightTimesWithScheduleTimes(flightTimes, requestID);
    operationFlightTimes = copyFlightTimesRequiredValue(flightTimes, operationFlightTimes, requestID);
    break;
```

**Extract**:
- **Method Called**: `substituteFlightTimesWithScheduleTimes()`
- **Action**: Reset all times to scheduled times
- **Propagation**: Stops (cancelled flights don't delay downstream)

---

### Step 3: Check PRISM State

**Flight Processor**: 
- ✅ StatusEventProcessor handles CANCEL FlightHub event
- ✅ Generates CANCEL atomic event (already implemented)
- ✅ Published to Event Hub

**Delay Processor**:
- ❌ CANCEL not in FlightDelayEventEnum
- ❌ No case in switch statement
- ❌ Event filtered out, never processed

**Gap**: Event generated but not consumed

---

### Step 4: Create Requirements

**Event Generation**: ✅ Already done (Flight Processor generates CANCEL)

**Event Consumption**: ❌ Need to implement

**Implementation**:
```java
// 1. Add to enum
CANCEL,

// 2. Add case
case CANCEL:
    isValidToPropagate = applyCancellation(lineOfFlying.get(i), isFirstFlight, i);
    break;

// 3. Implement method (based on legacy substituteFlightTimesWithScheduleTimes)
private static boolean applyCancellation(LineOfFlying line, boolean isFirstFlight, int lofLevel) {
    // Reset to scheduled times
    line.setProjectedDepartureTime(line.getScheduledDepartureTime());
    line.setProjectedArrivalTime(line.getScheduledArrivalTime());
    
    // Clear resource ready times
    line.setEquipmentResourceReadyTime(0);
    line.setCabinResourceReadyTime(0);
    line.setCockpitResourceReadyTime(0);
    
    // Stop propagation
    return false;
}
```

---

### Step 5: Create User Story

**Use Template**: Copy from `AZURE_DEVOPS_USER_STORIES_COMPLETE.md` → User Story 1

**Fill in**:
- Title: "As a dispatcher, I need cancelled flights to stop propagating delays..."
- Event Generation: Already implemented ✅
- Event Consumption: Implementation code from Step 4
- Legacy Reference: PropagationEngineDelayCalculatorJob.java line 470
- Acceptance Criteria: Based on requirements
- Tasks: Break down implementation

**Story Points**: 5 (based on complexity)

---

## Checklist for Each Missing Event

For each missing event (PE_CANCEL, PE_ETR, PE_REINSTATE, PE_CTA, PE_CTD):

### Analysis Phase

- [ ] Event found in legacy LKAEventType enum
- [ ] Event class located and reviewed
- [ ] Event generation logic found (FlightEventPreProcessServiceImpl)
- [ ] Event consumption logic found (PropagationEngineDelayCalculatorJob)
- [ ] Line numbers documented
- [ ] Fields affected documented
- [ ] Calculations/formulas extracted
- [ ] Resource ready time impact identified
- [ ] Propagation behavior documented

### PRISM Check Phase

- [ ] Checked Flight Processor (event generation)
- [ ] Checked Delay Processor (event consumption)
- [ ] Current status documented (implemented/partial/missing)
- [ ] Gap identified

### User Story Phase

- [ ] Business scenario written
- [ ] Event generation requirements specified
- [ ] Event consumption requirements specified
- [ ] Legacy code references added (file + line numbers)
- [ ] MongoDB fields identified
- [ ] Logging requirements specified
- [ ] Acceptance criteria defined
- [ ] Tasks broken down with estimates
- [ ] Story points assigned

---

## Tool: Event Analysis Template

**Use this template for each event**:

```markdown
# Event Analysis: [EVENT_NAME]

## Legacy Analysis

### Event Definition
- **Enum**: LKAEventType.[EVENT_NAME]
- **Event Class**: [ClassName].java
- **Location**: IPS_LKA2.0-Release/DataModels/.../[path]

### Event Generation
- **File**: FlightEventPreProcessServiceImpl.java
- **When**: [Trigger condition]
- **Code**: [Line numbers or snippet]

### Event Processing
- **File**: PropagationEngineDelayCalculatorJob.java
- **Line**: [Line number]
- **Case**: [case PE_[EVENT] or default]
- **Logic**: [Summary]
- **Code**: [Actual code snippet]

### Fields
- **Read**: [List]
- **Written**: [List]
- **Calculations**: [Formulas]

### Resource Ready Times
- **Affects**: [Yes/No]
- **Which**: [Equipment/Cabin/Cockpit]
- **Calculation**: [Formula]

### Propagation
- **Continues**: [Yes/No]
- **Next Leg**: [Yes/No]

## PRISM Current State

### Flight Processor
- **Status**: [Implemented/Not Implemented]
- **File**: [If implemented]
- **Method**: [If implemented]

### Delay Processor
- **In Enum**: [Yes/No]
- **In Switch**: [Yes/No]
- **Status**: [Implemented/Not Implemented]

## Gap Analysis

**Missing**:
- [ ] Event in FlightDelayEventEnum
- [ ] Case in switch statement
- [ ] Processing method
- [ ] [Other gaps]

## Implementation Requirements

### Flight Processor
[Requirements if needed]

### Delay Processor
[Requirements]

### MongoDB
[Fields to add/update]

## User Story

**ID**: PRISM-DELAY-US-[N]
**Title**: As a [role]...
**Points**: [Estimate]
**Priority**: [P0/P1/P2]

[Complete user story following template]
```

---

## Summary

### To Generate User Stories for Missing Events

**Follow These Steps**:

1. **Identify**: Check if event in legacy but not PRISM
2. **Analyze Legacy**: 
   - Find event class
   - Find generation logic (FlightEventPreProcessServiceImpl)
   - Find processing logic (PropagationEngineDelayCalculatorJob)
   - Extract: fields, calculations, propagation
3. **Check PRISM**: 
   - Flight Processor (generation)
   - Delay Processor (consumption)
   - Document current state
4. **Compare**: Create comparison table
5. **Extract Requirements**: Event generation, consumption, fields
6. **Create User Story**: Use template with all information

**Key Files to Reference**:
- **Legacy**: PropagationEngineDelayCalculatorJob.java (lines 469-777)
- **PRISM**: DelayCalculations.java (lines 30-55)
- **Template**: User Story 1 in AZURE_DEVOPS_USER_STORIES_COMPLETE.md

**Time per Event**: 2-4 hours (analysis + user story creation)

**Output**: Production-ready Azure DevOps user story with complete implementation guidance

---

## Quick Start Command Line

```bash
# For each missing event:

# 1. Find in legacy
grep -r "PE_CANCEL" /Users/rvinayagam/Downloads/IPS_LKA2.0-Release/Service/

# 2. Check PRISM
grep -r "CANCEL" /Users/rvinayagam/Downloads/ot-opsco-prism-FlightProcessor-main/ot-opsco-prism-DelayProcessor/

# 3. Compare
diff [legacy snippet] [prism snippet]

# 4. Document findings in template
# 5. Create user story
```

**This systematic approach ensures complete, accurate user stories based on actual code comparison!** 🎯
