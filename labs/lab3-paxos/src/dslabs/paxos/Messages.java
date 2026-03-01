package dslabs.paxos;

import dslabs.atmostonce.AMOCommand;
import dslabs.framework.Address;
import dslabs.framework.Message;
import java.io.Serializable;
import java.util.Map;
import lombok.Data;

// Ballot number used for ordering proposals
@Data
final class Ballot implements Comparable<Ballot>, Serializable {
  private final int seqNum;
  private final Address leader;

  @Override
  public int compareTo(Ballot other) {
    if (this.seqNum != other.seqNum) {
      return Integer.compare(this.seqNum, other.seqNum);
    }
    return this.leader.compareTo(other.leader);
  }
}

// Log slot entry storing accepted/chosen values
@Data
final class PaxosLogEntry implements Serializable {
  private final Ballot acceptedBallot;
  private final AMOCommand command;
  private final boolean chosen;

  // Create a copy with chosen flag set
  public PaxosLogEntry asChosen() {
    return new PaxosLogEntry(acceptedBallot, command, true);
  }
}

// Phase 1a: Prepare request
@Data
final class P1a implements Message {
  private final Address sender;
  private final Ballot ballot;
}

// Phase 1b: Prepare response
@Data
final class P1b implements Message {
  private final Address acceptor;
  private final Ballot ballot;
  private final boolean ok;
  // Slot -> (accepted ballot, command, chosen)
  private final Map<Integer, PaxosLogEntry> accepted;
}

// Phase 2a: Accept request
@Data
final class P2a implements Message {
  private final Address sender;
  private final Ballot ballot;
  private final int slotNum;
  private final AMOCommand command;
}

// Phase 2b: Accept response
@Data
final class P2b implements Message {
  private final Address acceptor;
  private final Ballot ballot;
  private final int slotNum;
  private final boolean ok;
}

// Leader heartbeat message
@Data
final class Heartbeat implements Message {
  private final Address leader;
  private final Ballot ballot;
  // The minimum slot that has been executed on all servers (for garbage collection)
  private final int slotCleared;
}

// Heartbeat reply from followers
@Data
final class HeartbeatReply implements Message {
  private final Address sender;
  private final Ballot ballot;
  // The last slot executed on this server
  private final int slotOut;
}

// Decision message: informs followers of chosen values
@Data
final class Decision implements Message {
  private final int slotNum;
  private final AMOCommand command;
}
