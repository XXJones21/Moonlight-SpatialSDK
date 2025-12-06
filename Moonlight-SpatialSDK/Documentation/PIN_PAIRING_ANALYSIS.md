# PIN Pairing Process Analysis

## Moonlight-Android Pairing Flow (Reference Implementation)

### Step-by-Step Process

1. **Check Pairing Status**
   - Location: `PcView.java:419`
   - Method: `httpConn.getPairState()`
   - If already `PAIRED`: Skip pairing, proceed to connection
   - If `NOT_PAIRED`: Proceed to pairing flow

2. **Generate PIN (Client-Side)**
   - Location: `PcView.java:425`
   - Method: `PairingManager.generatePinString()`
   - Generates: 4-digit random PIN (e.g., "1234")
   - **Key Point**: Client generates the PIN, not the server

3. **Display PIN to User**
   - Location: `PcView.java:428-430`
   - Shows dialog with generated PIN
   - Instructions: User must enter this PIN on the server (Sunshine/GFE)
   - **User enters PIN on server, not on client**

4. **Initiate Pairing Handshake**
   - Location: `PcView.java:432-434`
   - Get `PairingManager` from `NvHTTP`
   - Call: `pm.pair(httpConn.getServerInfo(true), pinStr)`
   - Uses the **client-generated PIN**

5. **Pairing Handshake (PairingManager.pair())**
   - Location: `PairingManager.java:185-294`
   - **Step 5a**: Generate salt (16 random bytes)
   - **Step 5b**: Create AES key from salt + PIN
   - **Step 5c**: Send `phrase=getservercert&salt=<hex>&clientcert=<hex>` to server
     - Server waits for user to enter PIN before responding
     - No read timeout (user must enter PIN on server)
   - **Step 5d**: Receive server certificate
   - **Step 5e**: Generate random challenge, encrypt with AES key
   - **Step 5f**: Send `clientchallenge=<encrypted>` to server
   - **Step 5g**: Receive server challenge response
   - **Step 5h**: Verify server challenge (validates PIN was correct)
   - **Step 5i**: Send client secret with signature
   - **Step 5j**: Receive server secret with signature
   - **Step 5k**: Verify server signature
   - **Step 5l**: Execute pairing challenge (`phrase=pairchallenge`)
   - **Result**: Returns `PairState.PAIRED` if successful

6. **Save Server Certificate**
   - Location: `PcView.java:455`
   - Save: `pm.getPairedCert()` to computer record
   - Used for future HTTPS connections

### Key Differences from Our Implementation

**Moonlight-Android (Correct)**:
- ✅ Client generates PIN
- ✅ Client displays PIN to user
- ✅ User enters PIN on server
- ✅ Client uses generated PIN for pairing

**Our Current Implementation (Incorrect)**:
- ❌ Client asks user to enter PIN from server
- ❌ User enters PIN on client
- ❌ Client uses server-provided PIN (backwards!)

### Pairing Command Flow

The pairing uses HTTP commands to `/pair` endpoint:

1. **Get Server Cert**: `phrase=getservercert&salt=<hex>&clientcert=<hex>`
   - Server waits for user to enter PIN (no timeout)
   - Returns server certificate

2. **Client Challenge**: `clientchallenge=<encrypted_challenge>`
   - Encrypted with AES key derived from salt + PIN

3. **Server Challenge Response**: Server responds with encrypted challenge response

4. **Client Secret**: `clientpairingsecret=<hex>`
   - Contains client secret + signature

5. **Final Challenge**: `phrase=pairchallenge`
   - Completes pairing process

### Device Name

- Moonlight-Android uses: `devicename=roth` (hardcoded)
- This is sent in all pairing commands: `devicename=roth&updateState=1&...`
- Server may display this in the pairing UI

---

## Our Current Implementation Issues

### MoonlightConnectionManager.kt

**Current Flow**:
1. `checkPairing()` - Checks if server is paired
2. `pairWithServer()` - Takes PIN from user input
3. Uses user-provided PIN for pairing

**Problem**: We're asking the user to enter the PIN that the server displays, but the server expects the client to generate the PIN and the user enters it on the server.

### PancakeActivity.kt

**Current UI**:
- Shows PIN input field when `needsPairing = true`
- User enters PIN from server
- Calls `pairWithServer()` with user-entered PIN

**Problem**: This is backwards. The client should generate the PIN and display it to the user.

---

## Required Changes

### 1. Update Pairing Flow

**MoonlightConnectionManager.kt**:
- Add method to generate PIN: `PairingManager.generatePinString()`
- Modify `pairWithServer()` to accept client-generated PIN (already does, but UI needs to change)

### 2. Update UI Flow

**PancakeActivity.kt**:
- When pairing needed:
  1. Generate PIN client-side
  2. Display PIN prominently to user
  3. Show instructions: "Enter this PIN on your server"
  4. Start pairing process with generated PIN
  5. Show pairing status (waiting for user to enter PIN on server)

### 3. Pairing Status Updates

- Show "Waiting for PIN entry on server..." during pairing
- The `pair()` method blocks until user enters PIN on server (no timeout on first command)
- Update status when pairing completes

---

## Implementation Plan

1. **Generate PIN in UI** when pairing is needed
2. **Display PIN** with clear instructions
3. **Start pairing** automatically with generated PIN
4. **Show status** during pairing process
5. **Handle pairing results** (success, wrong PIN, failed, etc.)

The key insight: The PIN is generated by the client, displayed to the user, and the user enters it on the server. The server then validates the PIN during the pairing handshake.

