package dslabs.paxos;

import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Node;
import dslabs.framework.Result;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import static dslabs.paxos.ClientTimer.CLIENT_RETRY_MILLIS;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public final class PaxosClient extends Node implements Client {
  private final Address[] servers;

  // Current sequence number for AMO
  private int sequenceNum = 0;
  // Current pending command
  private AMOCommand pendingCommand = null;
  // Result of the last completed command
  private AMOResult result = null;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public PaxosClient(Address address, Address[] servers) {
    super(address);
    this.servers = servers;
  }

  @Override
  public synchronized void init() {
    // No need to initialize
  }

  /* -----------------------------------------------------------------------------------------------
   *  Client Methods
   * ---------------------------------------------------------------------------------------------*/
  @Override
  public synchronized void sendCommand(Command operation) {
    sequenceNum++;
    pendingCommand = new AMOCommand(operation, address(), sequenceNum);
    result = null;

    // Broadcast request to all servers
    PaxosRequest request = new PaxosRequest(pendingCommand);
    broadcast(request, servers);

    // Set retry timer
    set(new ClientTimer(), CLIENT_RETRY_MILLIS);
  }

  @Override
  public synchronized boolean hasResult() {
    return result != null;
  }

  @Override
  public synchronized Result getResult() throws InterruptedException {
    while (result == null) {
      wait();
    }
    return result.result();
  }

  /* -----------------------------------------------------------------------------------------------
   * Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void handlePaxosReply(PaxosReply m, Address sender) {
    if (pendingCommand == null) {
      return;
    }

    AMOResult amoResult = m.result();
    if (amoResult != null && amoResult.sequenceNum() == pendingCommand.sequenceNum()) {
      result = amoResult;
      pendingCommand = null;
      notify();
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void onClientTimer(ClientTimer t) {
    if (pendingCommand != null) {
      // Resend request to all servers
      PaxosRequest request = new PaxosRequest(pendingCommand);
      broadcast(request, servers);

      // Reset retry timer
      set(new ClientTimer(), CLIENT_RETRY_MILLIS);
    }
  }
}
