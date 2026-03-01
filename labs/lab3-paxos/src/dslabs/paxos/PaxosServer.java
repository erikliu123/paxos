package dslabs.paxos;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Node;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import static dslabs.paxos.HeartbeatCheckTimer.HEARTBEAT_CHECK_MILLIS;
import static dslabs.paxos.HeartbeatTimer.HEARTBEAT_MILLIS;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class PaxosServer extends Node {
  /** All servers in the Paxos group, including this one. */
  private final Address[] servers;

  // Application wrapped with AMO semantics
  private final AMOApplication<Application> app;

  // ==================== Acceptor State ====================
  // Highest ballot we've seen
  private Ballot acceptedBallot;

  // Log of accepted/chosen values: slot -> PaxosLogEntry
  private final Map<Integer, PaxosLogEntry> log = new HashMap<>();

  // ==================== Leader State ====================
  // Our current ballot when we're the leader
  private Ballot myBallot;

  // Whether we are currently the active leader
  private boolean isLeader = false;

  // P1b responses received during Phase 1
  private final Set<Address> phase1Responses = new HashSet<>();

  // P2b responses for each slot: slot -> set of acceptors that accepted
  private final Map<Integer, Set<Address>> phase2Responses = new HashMap<>();

  // Proposals waiting to be proposed: client address -> command
  private final Map<Address, AMOCommand> pendingProposals = new HashMap<>();

  // Next slot to propose into
  private int nextSlotToPropose = 1;

  // ==================== Replica State ====================
  // Next slot to execute
  private int slotOut = 1;

  // First non-cleared slot (for garbage collection)
  private int slotCleared = 0;

  // ==================== Stable Leader State ====================
  // Current leader we believe exists (based on highest ballot seen)
  private Address currentLeader = null;

  // Counter for missed heartbeats
  private int missedHeartbeats = 0;

  // ==================== Garbage Collection State ====================
  // Map from server address to their last executed slot
  private final Map<Address, Integer> serverSlotOuts = new HashMap<>();

  // Minimum slot executed across all servers
  private int globalSlotCleared = 0;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public PaxosServer(Address address, Address[] servers, Application app) {
    super(address);
    this.servers = servers;
    this.app = new AMOApplication<>(app);

    // Initialize with lowest possible ballot
    this.acceptedBallot = new Ballot(0, address);
    this.myBallot = new Ballot(0, address);
  }

  @Override
  public void init() {
    // Start heartbeat check timer
    set(new HeartbeatCheckTimer(), HEARTBEAT_CHECK_MILLIS);

    // Initially try to become leader
    startPhase1();
  }

  /* -----------------------------------------------------------------------------------------------
   *  Interface Methods
   *
   *  Be sure to implement the following methods correctly. The test code uses them to check
   *  correctness more efficiently.
   * ---------------------------------------------------------------------------------------------*/

  /**
   * Return the status of a given slot in the server's local log.
   */
  public PaxosLogSlotStatus status(int logSlotNum) {
    if (logSlotNum <= slotCleared) {
      return PaxosLogSlotStatus.CLEARED;
    }
    PaxosLogEntry entry = log.get(logSlotNum);
    if (entry == null) {
      return PaxosLogSlotStatus.EMPTY;
    }
    if (entry.chosen()) {
      return PaxosLogSlotStatus.CHOSEN;
    }
    return PaxosLogSlotStatus.ACCEPTED;
  }

  /**
   * Return the command associated with a given slot in the server's local log.
   */
  public Command command(int logSlotNum) {
    if (logSlotNum <= slotCleared) {
      return null;
    }
    PaxosLogEntry entry = log.get(logSlotNum);
    if (entry == null) {
      return null;
    }
    AMOCommand amoCmd = entry.command();
    return amoCmd != null ? amoCmd.command() : null;
  }

  /**
   * Return the index of the first non-cleared slot in the server's local log.
   */
  public int firstNonCleared() {
    return slotCleared + 1;
  }

  /**
   * Return the index of the last non-empty slot in the server's local log.
   */
  public int lastNonEmpty() {
    int maxSlot = slotCleared; // Cleared slots are non-empty
    for (int slot : log.keySet()) {
      if (slot > maxSlot) {
        maxSlot = slot;
      }
    }
    return maxSlot;
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void handlePaxosRequest(PaxosRequest m, Address sender) {
    AMOCommand command = m.command();

    // If already executed, return cached result
    if (app.alreadyExecuted(command)) {
      AMOResult result = app.execute(command);
      send(new PaxosReply(result), sender);
      return;
    }

    // If we're the leader, propose the command
    if (isLeader) {
      proposeCommand(command);
    } else {
      // Store pending proposal for when we become leader
      pendingProposals.put(command.clientAddress(), command);
    }
  }

  // Phase 1a handler (Acceptor role)
  private void handleP1a(P1a m, Address sender) {
    Ballot ballot = m.ballot();

    // Update accepted ballot if higher
    if (ballot.compareTo(acceptedBallot) > 0) {
      acceptedBallot = ballot;
      currentLeader = ballot.leader();
      missedHeartbeats = 0;

      // If we were leader and got preempted, step down
      if (isLeader && !ballot.leader().equals(address())) {
        becomeFollower();
      }
    }

    // Always respond with our log (for slots > slotCleared)
    Map<Integer, PaxosLogEntry> accepted = new HashMap<>();
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      if (entry.getKey() > slotCleared) {
        accepted.put(entry.getKey(), entry.getValue());
      }
    }

    boolean ok = ballot.compareTo(acceptedBallot) >= 0;
    send(new P1b(address(), acceptedBallot, ok, accepted), sender);
  }

  // Phase 1b handler (Leader role)
  private void handleP1b(P1b m, Address sender) {
    if (!m.ballot().equals(myBallot)) {
      // Response for old ballot, ignore
      return;
    }

    if (!m.ok()) {
      // We were preempted, step down
      if (m.ballot().compareTo(myBallot) > 0) {
        acceptedBallot = m.ballot();
        currentLeader = m.ballot().leader();
        becomeFollower();
      }
      return;
    }

    // Record response
    phase1Responses.add(m.acceptor());

    // Merge accepted values from acceptor
    for (Map.Entry<Integer, PaxosLogEntry> entry : m.accepted().entrySet()) {
      int slot = entry.getKey();
      PaxosLogEntry their = entry.getValue();

      PaxosLogEntry ours = log.get(slot);

      // If they have a chosen value, adopt it
      if (their.chosen()) {
        log.put(slot, their);
      } else if (ours == null || !ours.chosen()) {
        // Adopt if their ballot is higher
        if (ours == null || 
            (their.acceptedBallot() != null && 
             (ours.acceptedBallot() == null || 
              their.acceptedBallot().compareTo(ours.acceptedBallot()) > 0))) {
          log.put(slot, their);
        }
      }

      // Update nextSlotToPropose
      if (slot >= nextSlotToPropose) {
        nextSlotToPropose = slot + 1;
      }
    }

    // Check if we have majority
    if (phase1Responses.size() >= majority()) {
      becomeLeader();
    }
  }

  // Phase 2a handler (Acceptor role)
  private void handleP2a(P2a m, Address sender) {
    Ballot ballot = m.ballot();
    int slot = m.slotNum();

    // Update accepted ballot if higher
    if (ballot.compareTo(acceptedBallot) > 0) {
      acceptedBallot = ballot;
      currentLeader = ballot.leader();
      missedHeartbeats = 0;
    }

    boolean ok = ballot.compareTo(acceptedBallot) >= 0;
    
    if (ok && slot > slotCleared) {
      // Accept the value (but don't mark as chosen yet)
      PaxosLogEntry existing = log.get(slot);
      if (existing == null || !existing.chosen()) {
        log.put(slot, new PaxosLogEntry(ballot, m.command(), false));
      }
    }

    send(new P2b(address(), acceptedBallot, slot, ok), sender);
  }

  // Phase 2b handler (Leader role)
  private void handleP2b(P2b m, Address sender) {
    if (!m.ballot().equals(myBallot)) {
      // Response for old ballot
      if (m.ballot().compareTo(myBallot) > 0) {
        acceptedBallot = m.ballot();
        currentLeader = m.ballot().leader();
        becomeFollower();
      }
      return;
    }

    if (!m.ok()) {
      return;
    }

    int slot = m.slotNum();
    Set<Address> responses = phase2Responses.computeIfAbsent(slot, k -> new HashSet<>());
    responses.add(m.acceptor());

    // Check if we have majority for this slot
    if (responses.size() >= majority()) {
      // Mark as chosen
      PaxosLogEntry entry = log.get(slot);
      if (entry != null && !entry.chosen()) {
        log.put(slot, entry.asChosen());
        // Broadcast decision to all
        broadcastDecision(slot, entry.command());
      }
      // Execute any ready commands
      executeCommands();
    }
  }

  // Decision handler
  private void handleDecision(Decision m, Address sender) {
    int slot = m.slotNum();
    if (slot <= slotCleared) {
      return;
    }

    PaxosLogEntry existing = log.get(slot);
    if (existing == null || !existing.chosen()) {
      log.put(slot, new PaxosLogEntry(null, m.command(), true));
    }

    // Update nextSlotToPropose if needed
    if (slot >= nextSlotToPropose) {
      nextSlotToPropose = slot + 1;
    }

    executeCommands();
  }

  // Heartbeat handler (Follower role)
  private void handleHeartbeat(Heartbeat m, Address sender) {
    Ballot ballot = m.ballot();

    // Update if higher ballot
    if (ballot.compareTo(acceptedBallot) >= 0) {
      acceptedBallot = ballot;
      currentLeader = m.leader();
      missedHeartbeats = 0;

      // If we were leader and got preempted, step down
      if (isLeader && !m.leader().equals(address())) {
        becomeFollower();
      }
    }

    // Update garbage collection info
    if (m.slotCleared() > slotCleared) {
      // Clear old slots
      for (int i = slotCleared + 1; i <= m.slotCleared(); i++) {
        log.remove(i);
      }
      slotCleared = m.slotCleared();
    }

    // Reply with our slotOut
    send(new HeartbeatReply(address(), acceptedBallot, slotOut), sender);
  }

  // Heartbeat reply handler (Leader role)
  private void handleHeartbeatReply(HeartbeatReply m, Address sender) {
    if (!isLeader) {
      return;
    }

    // Record this server's slotOut
    serverSlotOuts.put(m.sender(), m.slotOut());

    // Calculate minimum slotOut across all servers
    updateGlobalSlotCleared();

    // Send missing decisions to lagging servers
    for (int slot = m.slotOut(); slot < slotOut; slot++) {
      PaxosLogEntry entry = log.get(slot);
      if (entry != null && entry.chosen()) {
        send(new Decision(slot, entry.command()), sender);
      }
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void onHeartbeatCheckTimer(HeartbeatCheckTimer t) {
    if (!isLeader) {
      missedHeartbeats++;
      
      // If we've missed two heartbeats, try to become leader
      if (missedHeartbeats >= 2) {
        startPhase1();
      } else if (phase1Responses.size() > 0 && phase1Responses.size() < majority()) {
        // We're in the middle of Phase 1 but haven't gotten majority yet
        // Resend P1a to servers that haven't responded
        resendPhase1();
      }
    }

    // Reset timer
    set(new HeartbeatCheckTimer(), HEARTBEAT_CHECK_MILLIS);
  }

  private void onHeartbeatTimer(HeartbeatTimer t) {
    if (isLeader) {
      // Send heartbeat to all other servers
      Heartbeat hb = new Heartbeat(address(), myBallot, globalSlotCleared);
      for (Address server : servers) {
        if (!server.equals(address())) {
          send(hb, server);
        }
      }

      // Resend P2a for any slots that are not yet chosen (in case of message loss)
      resendPendingPhase2();

      // Reset timer
      set(new HeartbeatTimer(), HEARTBEAT_MILLIS);
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  private int majority() {
    return servers.length / 2 + 1;
  }

  private void startPhase1() {
    // Increment ballot number
    int newSeqNum = acceptedBallot.seqNum() + 1;
    myBallot = new Ballot(newSeqNum, address());
    acceptedBallot = myBallot;
    currentLeader = address();
    missedHeartbeats = 0;
    isLeader = false;

    // Clear phase 1 responses
    phase1Responses.clear();
    phase2Responses.clear();

    // Build our own P1b response
    Map<Integer, PaxosLogEntry> accepted = new HashMap<>();
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      if (entry.getKey() > slotCleared) {
        accepted.put(entry.getKey(), entry.getValue());
      }
    }
    
    // Handle our own P1b response locally (don't send via network)
    P1b selfP1b = new P1b(address(), myBallot, true, accepted);
    handleP1b(selfP1b, address());

    // Send P1a to other servers
    P1a p1a = new P1a(address(), myBallot);
    for (Address server : servers) {
      if (!server.equals(address())) {
        send(p1a, server);
      }
    }
  }

  private void resendPhase1() {
    // Resend P1a to servers that haven't responded
    P1a p1a = new P1a(address(), myBallot);
    for (Address server : servers) {
      if (!server.equals(address()) && !phase1Responses.contains(server)) {
        send(p1a, server);
      }
    }
  }

  private void becomeLeader() {
    if (isLeader) {
      return; // Already leader
    }
    isLeader = true;

    // Re-propose any accepted but not chosen values from Phase 1
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      int slot = entry.getKey();
      PaxosLogEntry logEntry = entry.getValue();
      if (slot > slotCleared && !logEntry.chosen() && logEntry.command() != null) {
        startPhase2(slot, logEntry.command());
      }
    }

    // Propose any pending requests
    for (AMOCommand command : pendingProposals.values()) {
      if (!app.alreadyExecuted(command)) {
        proposeCommand(command);
      }
    }
    pendingProposals.clear();

    // Start heartbeat timer
    set(new HeartbeatTimer(), HEARTBEAT_MILLIS);

    // Initialize our own slotOut in serverSlotOuts
    serverSlotOuts.put(address(), slotOut);
  }

  private void becomeFollower() {
    isLeader = false;
    phase1Responses.clear();
    phase2Responses.clear();
    missedHeartbeats = 0;
  }

  private void proposeCommand(AMOCommand command) {
    // Skip if already executed
    if (app.alreadyExecuted(command)) {
      AMOResult result = app.execute(command);
      send(new PaxosReply(result), command.clientAddress());
      return;
    }

    // Check if this command is already in the log
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      PaxosLogEntry logEntry = entry.getValue();
      if (logEntry.command() != null && logEntry.command().equals(command)) {
        // Already proposed, don't duplicate
        return;
      }
    }

    // Find next empty slot
    while (log.containsKey(nextSlotToPropose)) {
      nextSlotToPropose++;
    }

    int slot = nextSlotToPropose++;
    startPhase2(slot, command);
  }

  private void startPhase2(int slot, AMOCommand command) {
    // Record our own acceptance
    log.put(slot, new PaxosLogEntry(myBallot, command, false));
    phase2Responses.computeIfAbsent(slot, k -> new HashSet<>()).add(address());

    // Send P2a to all servers
    P2a p2a = new P2a(address(), myBallot, slot, command);
    for (Address server : servers) {
      if (!server.equals(address())) {
        send(p2a, server);
      }
    }

    // Check if we already have majority (single server case)
    if (phase2Responses.get(slot).size() >= majority()) {
      log.put(slot, log.get(slot).asChosen());
      broadcastDecision(slot, command);
      executeCommands();
    }
  }

  private void resendPendingPhase2() {
    // Resend P2a for any slots that are not yet chosen
    for (Map.Entry<Integer, PaxosLogEntry> entry : log.entrySet()) {
      int slot = entry.getKey();
      PaxosLogEntry logEntry = entry.getValue();
      
      // Skip cleared, chosen, or empty entries
      if (slot <= slotCleared || logEntry.chosen() || logEntry.command() == null) {
        continue;
      }

      // Resend P2a to servers that haven't responded
      Set<Address> responded = phase2Responses.getOrDefault(slot, new HashSet<>());
      P2a p2a = new P2a(address(), myBallot, slot, logEntry.command());
      for (Address server : servers) {
        if (!server.equals(address()) && !responded.contains(server)) {
          send(p2a, server);
        }
      }
    }
  }

  private void broadcastDecision(int slot, AMOCommand command) {
    Decision decision = new Decision(slot, command);
    for (Address server : servers) {
      if (!server.equals(address())) {
        send(decision, server);
      }
    }
  }

  private void executeCommands() {
    while (true) {
      PaxosLogEntry entry = log.get(slotOut);
      if (entry == null || !entry.chosen()) {
        break;
      }

      AMOCommand command = entry.command();
      if (command != null && !app.alreadyExecuted(command)) {
        AMOResult result = app.execute(command);
        // Send reply to client
        send(new PaxosReply(result), command.clientAddress());
      }

      slotOut++;
    }

    // Update our slotOut in serverSlotOuts
    if (isLeader) {
      serverSlotOuts.put(address(), slotOut);
      updateGlobalSlotCleared();
    }
  }

  private void updateGlobalSlotCleared() {
    if (serverSlotOuts.size() < servers.length) {
      return;
    }

    int minSlotOut = Integer.MAX_VALUE;
    for (int s : serverSlotOuts.values()) {
      if (s < minSlotOut) {
        minSlotOut = s;
      }
    }

    // We can clear all slots before minSlotOut
    int newGlobalCleared = minSlotOut - 1;
    if (newGlobalCleared > globalSlotCleared) {
      globalSlotCleared = newGlobalCleared;

      // Clear old entries from our own log
      for (int i = slotCleared + 1; i <= globalSlotCleared; i++) {
        log.remove(i);
      }
      slotCleared = globalSlotCleared;
    }
  }
}
