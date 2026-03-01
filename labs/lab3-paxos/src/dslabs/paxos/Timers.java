package dslabs.paxos;

import dslabs.framework.Timer;
import lombok.Data;

@Data
final class ClientTimer implements Timer {
  static final int CLIENT_RETRY_MILLIS = 100;
}

// Timer for checking if leader is still alive
@Data
final class HeartbeatCheckTimer implements Timer {
  static final int HEARTBEAT_CHECK_MILLIS = 100;
}

// Timer for leader to send periodic heartbeats
@Data
final class HeartbeatTimer implements Timer {
  static final int HEARTBEAT_MILLIS = 25;
}
