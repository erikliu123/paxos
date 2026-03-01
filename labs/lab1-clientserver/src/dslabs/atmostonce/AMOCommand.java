package dslabs.atmostonce;

import dslabs.framework.Address;
import dslabs.framework.Command;
import lombok.Data;

@Data
public final class AMOCommand implements Command {
  private final Command command;
  private final Address clientAddress;
  private final int sequenceNum;

  @Override
  public boolean readOnly() {
    return command.readOnly();
  }
}
