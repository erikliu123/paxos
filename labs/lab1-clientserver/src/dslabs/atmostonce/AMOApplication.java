package dslabs.atmostonce;

import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;
import java.util.HashMap;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode
@ToString
@RequiredArgsConstructor
public final class AMOApplication<T extends Application> implements Application {
  @Getter @NonNull private final T application;

  // Map from client address to the last executed sequence number and result
  private final Map<Address, AMOResult> lastResults = new HashMap<>();

  @Override
  public AMOResult execute(Command command) {
    if (!(command instanceof AMOCommand)) {
      throw new IllegalArgumentException();
    }

    AMOCommand amoCommand = (AMOCommand) command;
    Address clientAddress = amoCommand.clientAddress();
    int sequenceNum = amoCommand.sequenceNum();

    // Check if we've already executed this command
    AMOResult lastResult = lastResults.get(clientAddress);
    if (lastResult != null && lastResult.sequenceNum() >= sequenceNum) {
      // Return cached result if it's the exact same sequence number
      if (lastResult.sequenceNum() == sequenceNum) {
        return lastResult;
      }
      // If sequence number is older, still return the last result (stale request)
      return lastResult;
    }

    // Execute the command
    Result result = application.execute(amoCommand.command());
    AMOResult amoResult = new AMOResult(result, sequenceNum);
    lastResults.put(clientAddress, amoResult);
    return amoResult;
  }

  public Result executeReadOnly(Command command) {
    if (!command.readOnly()) {
      throw new IllegalArgumentException();
    }

    if (command instanceof AMOCommand) {
      return execute(command);
    }

    return application.execute(command);
  }

  public boolean alreadyExecuted(AMOCommand amoCommand) {
    AMOResult lastResult = lastResults.get(amoCommand.clientAddress());
    return lastResult != null && lastResult.sequenceNum() >= amoCommand.sequenceNum();
  }
}
